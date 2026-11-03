package dev.p2pmc.net;

import java.io.Closeable;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A reliable, ordered byte stream over unreliable datagrams (a small TCP-like ARQ).
 * <ul>
 *   <li>sliding window with selective acks (64 packet bitmap) and fast retransmit</li>
 *   <li>RFC 6298 retransmission timer with exponential backoff</li>
 *   <li>slow start / congestion avoidance so a home uplink is never flooded</li>
 *   <li>receiver-advertised window: a slow reader throttles the sender instead of growing memory</li>
 *   <li>keepalives (also keep the NAT mapping open) and dead-peer detection</li>
 * </ul>
 * Packets are not encrypted here; the stream above is ({@code SecureStream}), so tampering can only break the
 * connection, never inject data.
 * <p>
 * Wire format (big endian): {@code DATA|FIN: type(1) tag(4) seq(4) payload}; {@code ACK: type(1) tag(4) cum(4)
 * sackBitmap(8) window(2)}. {@code cum} is the next sequence number the receiver expects; bit {@code i} of the bitmap
 * says that {@code cum + 1 + i} has arrived.
 */
public final class ReliableChannel implements Closeable {
    private static final System.Logger LOG = System.getLogger("p2pmc");

    /** Where packets go (usually {@code UdpEndpoint.send} bound to one remote address). */
    @FunctionalInterface
    public interface Sink {
        void send(byte[] buf, int off, int len) throws IOException;
    }

    /** Timing knobs; the defaults suit the internet, tests shrink them. */
    public static final class Config {
        public int minRtoMillis = 200;
        public int peerTimeoutMillis = 15_000;
        public int keepaliveMillis = 2_000;
        public int closeDrainMillis = 3_000;
    }

    public static final byte T_DATA = 1;
    public static final byte T_ACK = 2;
    public static final byte T_FIN = 3;
    /** Reserved for {@link PunchSession}; never handled by a channel. */
    public static final byte T_PUNCH = 5;

    /** Payload bytes per datagram: with headers stays well below the 1280 byte IPv6 minimum MTU. */
    public static final int MSS = 1150;
    static final int WINDOW = 256;
    private static final int SEND_LIMIT_BYTES = 512 * 1024;
    private static final int DATA_HEADER = 9;
    private static final int ACK_LEN = 1 + 4 + 4 + 8 + 2;
    private static final long MS = 1_000_000L;
    private static final byte[] EMPTY = new byte[0];

    private static final class Seg {
        final long seq;
        final byte[] data;
        final boolean fin;
        long firstSent;
        long lastSent;
        int transmissions;
        boolean sacked;

        Seg(long seq, byte[] data, boolean fin) {
            this.seq = seq;
            this.data = data;
            this.fin = fin;
        }
    }

    private record Recv(byte[] data, boolean fin) {
    }

    private final Sink sink;
    private final byte[] tag;
    private final Config cfg;
    private final Runnable onClosed;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition writable = lock.newCondition();
    private final Condition readable = lock.newCondition();
    private final Condition drained = lock.newCondition();

    // ---- send side (guarded by lock)
    private final ArrayDeque<byte[]> pending = new ArrayDeque<>();
    private int pendingBytes;
    private boolean finQueued;
    private boolean finSent;
    private final ArrayDeque<Seg> inflight = new ArrayDeque<>();
    private long nextSeq;
    private int peerWindow = WINDOW;
    private double cwnd = 10;
    private double ssthresh = WINDOW;
    private boolean inRecovery;
    private long recoverySeq;
    private long srtt = -1;
    private long rttvar;
    private long rto = 1_000 * MS;
    private long lastProbe;
    private long retransmitCount;
    private long timeoutCount;

    // ---- receive side (guarded by lock)
    private long rcvNext;
    private final TreeMap<Long, Recv> outOfOrder = new TreeMap<>();
    private final ArrayDeque<byte[]> inbox = new ArrayDeque<>();
    private byte[] current;
    private int currentOffset;
    private boolean eof;
    private int lastAdvertised = WINDOW;

    // ---- state (guarded by lock)
    private boolean closed;
    private String error;
    private long lastRecv;
    private long lastSend;
    private int readTimeoutMillis;
    private volatile long srttNanos = -1;
    private final ScheduledFuture<?> ticker;

    public ReliableChannel(Sink sink, byte[] tag, Config cfg, Runnable onClosed) {
        if (tag.length != 4) {
            throw new IllegalArgumentException("tag must be 4 bytes");
        }
        this.sink = sink;
        this.tag = tag.clone();
        this.cfg = cfg;
        this.onClosed = onClosed;
        this.lastRecv = System.nanoTime();
        this.lastSend = this.lastRecv;
        this.ticker = Timers.every(20, this::tick);
    }

    // ================================================================= public API

    /** One-line congestion/timer state for diagnostics and tests. */
    public String debugState() {
        lock.lock();
        try {
            return String.format("cwnd=%.1f ssthresh=%.1f rto=%dms srtt=%dms inflight=%d pending=%dB peerWnd=%d retransmits=%d timeouts=%d",
                    cwnd, ssthresh, rto / MS, srtt < 0 ? -1 : srtt / MS, inflight.size(), pendingBytes, peerWindow,
                    retransmitCount, timeoutCount);
        } finally {
            lock.unlock();
        }
    }

    /** Smoothed round-trip time in milliseconds, or -1 before the first sample. */
    public long rttMillis() {
        long v = srttNanos;
        return v < 0 ? -1 : v / MS;
    }

    public void setReadTimeout(int millis) {
        lock.lock();
        try {
            this.readTimeoutMillis = millis;
        } finally {
            lock.unlock();
        }
    }

    /** Queues bytes for sending; blocks while more than ~512 KiB are waiting for the network. */
    public void write(byte[] b, int off, int len) throws IOException {
        lock.lock();
        try {
            while (len > 0) {
                checkWritable();
                while (pendingBytes >= SEND_LIMIT_BYTES) {
                    writable.awaitUninterruptibly();
                    checkWritable();
                }
                int n = Math.min(len, MSS);
                pending.addLast(Arrays.copyOfRange(b, off, off + n));
                pendingBytes += n;
                off += n;
                len -= n;
                fill();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Blocks until data is available.
     *
     * @return bytes read, or -1 once the peer closed its side and everything was consumed
     * @throws SocketTimeoutException if a read timeout is set and expires
     * @throws IOException            if the connection failed or was closed locally
     */
    public int read(byte[] b, int off, int len) throws IOException {
        if (len == 0) {
            return 0;
        }
        lock.lock();
        try {
            long deadline = readTimeoutMillis > 0 ? System.nanoTime() + readTimeoutMillis * MS : 0;
            while (current == null && inbox.isEmpty()) {
                if (eof) {
                    return -1;
                }
                if (error != null) {
                    throw new IOException(error);
                }
                if (closed) {
                    throw new IOException("channel closed");
                }
                try {
                    if (deadline == 0) {
                        readable.await();
                    } else {
                        long left = deadline - System.nanoTime();
                        if (left <= 0) {
                            throw new SocketTimeoutException("read timed out");
                        }
                        readable.awaitNanos(left);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", e);
                }
            }
            if (current == null) {
                current = inbox.pollFirst();
                currentOffset = 0;
            }
            int n = Math.min(len, current.length - currentOffset);
            System.arraycopy(current, currentOffset, b, off, n);
            currentOffset += n;
            if (currentOffset == current.length) {
                current = null;
                if (lastAdvertised <= WINDOW / 2) {
                    sendAck(); // window update: the sender may be waiting for room
                }
            }
            return n;
        } finally {
            lock.unlock();
        }
    }

    /** Graceful close: flushes queued data, sends FIN and waits (bounded) for it to be acknowledged. */
    @Override
    public void close() {
        lock.lock();
        try {
            if (closed) {
                return;
            }
            if (!finQueued && error == null) {
                finQueued = true;
                fill();
                long deadline = System.nanoTime() + cfg.closeDrainMillis * MS;
                while (!closed && error == null && !(inflight.isEmpty() && pending.isEmpty())) {
                    long left = deadline - System.nanoTime();
                    if (left <= 0) {
                        break;
                    }
                    try {
                        drained.awaitNanos(left);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            shutdown(null);
        } finally {
            lock.unlock();
        }
    }

    // ================================================================ packet input

    /** Feed every datagram that belongs to this channel. The buffer may be reused by the caller afterwards. */
    public void onPacket(byte[] buf, int off, int len) {
        if (len < 5 || buf[off + 1] != tag[0] || buf[off + 2] != tag[1] || buf[off + 3] != tag[2] || buf[off + 4] != tag[3]) {
            return;
        }
        lock.lock();
        try {
            if (closed) {
                return;
            }
            lastRecv = System.nanoTime();
            switch (buf[off]) {
                case T_DATA, T_FIN -> onData(buf, off, len);
                case T_ACK -> onAck(buf, off, len);
                default -> {
                    // unknown type: ignore
                }
            }
        } finally {
            lock.unlock();
        }
    }

    private void onData(byte[] buf, int off, int len) {
        if (len < DATA_HEADER) {
            return;
        }
        boolean fin = buf[off] == T_FIN;
        long seq = getInt(buf, off + 5) & 0xFFFFFFFFL;
        if (seq >= rcvNext && seq < rcvNext + WINDOW && inbox.size() < 2 * WINDOW && !outOfOrder.containsKey(seq)) {
            byte[] data = fin ? EMPTY : Arrays.copyOfRange(buf, off + DATA_HEADER, off + len);
            outOfOrder.put(seq, new Recv(data, fin));
            Recv r;
            while ((r = outOfOrder.remove(rcvNext)) != null) {
                rcvNext++;
                if (r.fin()) {
                    eof = true;
                } else if (r.data().length > 0) {
                    inbox.addLast(r.data());
                }
            }
            if (!inbox.isEmpty() || eof) {
                readable.signalAll();
            }
        }
        sendAck();
    }

    private void onAck(byte[] buf, int off, int len) {
        if (len < ACK_LEN) {
            return;
        }
        long cum = getInt(buf, off + 5) & 0xFFFFFFFFL;
        long sack = getLong(buf, off + 9);
        int window = ((buf[off + 17] & 0xFF) << 8) | (buf[off + 18] & 0xFF);
        if (cum > nextSeq) {
            return; // acknowledges something we never sent
        }
        long now = System.nanoTime();
        peerWindow = Math.min(window, WINDOW);

        int newlyAcked = 0;
        long sample = -1;
        while (!inflight.isEmpty() && inflight.peekFirst().seq < cum) {
            Seg s = inflight.pollFirst();
            if (!s.sacked) {
                newlyAcked++;
            }
            if (s.transmissions == 1) {
                sample = now - s.firstSent;
            }
        }
        long highestSacked = -1;
        for (Seg s : inflight) {
            long bit = s.seq - cum - 1;
            if (bit >= 0 && bit < 64 && ((sack >>> bit) & 1L) != 0) {
                if (!s.sacked) {
                    s.sacked = true;
                    newlyAcked++;
                    if (s.transmissions == 1) {
                        sample = now - s.firstSent;
                    }
                }
                highestSacked = s.seq;
            }
        }
        if (sample > 0) {
            updateRtt(sample);
        }

        if (inRecovery && cum > recoverySeq) {
            inRecovery = false;
        }
        if (!inRecovery) {
            for (int i = 0; i < newlyAcked; i++) {
                cwnd += cwnd < ssthresh ? 1.0 : 1.0 / cwnd;
            }
            cwnd = Math.min(cwnd, WINDOW);
        }

        if (highestSacked >= 0) { // fast retransmit: three packets above a hole are already acknowledged
            // Time-based guard (RACK-like): a hole is only "lost" once it is older than one RTT plus a reordering
            // allowance, so mild packet reordering does not trigger spurious retransmits or window cuts.
            long minAge = srtt > 0 ? srtt + Math.max(srtt / 4, 3 * MS) : 100 * MS;
            int sackedAbove = 0;
            boolean lossEvent = false;
            Iterator<Seg> it = inflight.descendingIterator();
            while (it.hasNext()) {
                Seg s = it.next();
                if (s.sacked) {
                    sackedAbove++;
                } else if (sackedAbove >= 3 && s.seq < highestSacked && now - s.lastSent > minAge) {
                    retransmit(s, now);
                    lossEvent = true;
                }
            }
            if (lossEvent && !inRecovery) {
                enterRecovery(Math.max(pipe() / 2.0, 2));
            }
        }

        fill();
        if (inflight.isEmpty() && pending.isEmpty()) {
            drained.signalAll();
        }
    }

    // ================================================================ send machinery

    private void fill() {
        long now = System.nanoTime();
        long base = inflight.isEmpty() ? nextSeq : inflight.peekFirst().seq;
        while (true) {
            boolean haveData = !pending.isEmpty();
            boolean wantFin = finQueued && !finSent && pending.isEmpty();
            if (!haveData && !wantFin) {
                break;
            }
            boolean allowed = pipe() < (int) cwnd && nextSeq - base < peerWindow;
            if (!allowed) {
                boolean probe = inflight.isEmpty() && peerWindow == 0 && now - lastProbe > 500 * MS;
                if (!probe) {
                    break;
                }
                lastProbe = now; // zero window: poke the receiver so a lost window update cannot stall us forever
            }
            Seg s = new Seg(nextSeq++, haveData ? pending.pollFirst() : EMPTY, !haveData);
            if (haveData) {
                pendingBytes -= s.data.length;
            } else {
                finSent = true;
            }
            s.firstSent = now;
            s.lastSent = now;
            s.transmissions = 1;
            inflight.addLast(s);
            transmit(s);
        }
        if (pendingBytes < SEND_LIMIT_BYTES) {
            writable.signalAll();
        }
    }

    private int pipe() {
        int n = 0;
        for (Seg s : inflight) {
            if (!s.sacked) {
                n++;
            }
        }
        return n;
    }

    private void retransmit(Seg s, long now) {
        retransmitCount++;
        s.transmissions++;
        s.lastSent = now;
        transmit(s);
    }

    private void enterRecovery(double newSsthresh) {
        ssthresh = newSsthresh;
        cwnd = newSsthresh;
        inRecovery = true;
        recoverySeq = nextSeq - 1;
    }

    private void transmit(Seg s) {
        byte[] p = new byte[DATA_HEADER + s.data.length];
        p[0] = s.fin ? T_FIN : T_DATA;
        System.arraycopy(tag, 0, p, 1, 4);
        putInt(p, 5, (int) s.seq);
        System.arraycopy(s.data, 0, p, DATA_HEADER, s.data.length);
        emit(p);
    }

    private void sendAck() {
        byte[] p = new byte[ACK_LEN];
        p[0] = T_ACK;
        System.arraycopy(tag, 0, p, 1, 4);
        putInt(p, 5, (int) rcvNext);
        long sack = 0;
        for (Map.Entry<Long, Recv> e : outOfOrder.entrySet()) {
            long bit = e.getKey() - rcvNext - 1;
            if (bit >= 64) {
                break;
            }
            if (bit >= 0) {
                sack |= 1L << bit;
            }
        }
        putLong(p, 9, sack);
        int window = Math.max(0, WINDOW - inbox.size());
        p[17] = (byte) (window >>> 8);
        p[18] = (byte) window;
        lastAdvertised = window;
        emit(p);
    }

    private void emit(byte[] p) {
        lastSend = System.nanoTime();
        try {
            sink.send(p, 0, p.length);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "datagram send failed: {0}", e.toString());
        }
    }

    private void updateRtt(long r) {
        if (srtt < 0) {
            srtt = r;
            rttvar = r / 2;
        } else {
            rttvar = (3 * rttvar + Math.abs(srtt - r)) / 4;
            srtt = (7 * srtt + r) / 8;
        }
        rto = Math.max(cfg.minRtoMillis * MS, Math.min(4_000 * MS, srtt + Math.max(10 * MS, 4 * rttvar)));
        srttNanos = srtt;
    }

    // ================================================================ timer

    private void tick() {
        lock.lock();
        try {
            if (closed) {
                return;
            }
            long now = System.nanoTime();
            Seg head = null;
            for (Seg s : inflight) {
                if (!s.sacked) {
                    head = s;
                    break;
                }
            }
            if (head != null && now - head.lastSent > rto) {
                timeoutCount++;
                retransmit(head, now);
                rto = Math.min(rto * 2, 5_000 * MS);
                ssthresh = Math.max(pipe() / 2.0, 2);
                cwnd = 2;
                inRecovery = true;
                recoverySeq = nextSeq - 1;
            }
            fill();
            if (now - lastSend > cfg.keepaliveMillis * MS) {
                sendAck();
            }
            if (now - lastRecv > cfg.peerTimeoutMillis * MS) {
                shutdown("Connection timed out - the other side stopped responding");
            }
        } finally {
            lock.unlock();
        }
    }

    // ================================================================ lifecycle

    private void checkWritable() throws IOException {
        if (error != null) {
            throw new IOException(error);
        }
        if (closed || finQueued) {
            throw new IOException("channel closed");
        }
    }

    /** Caller holds the lock. */
    private void shutdown(String failure) {
        if (closed) {
            return;
        }
        closed = true;
        error = failure;
        ticker.cancel(false);
        readable.signalAll();
        writable.signalAll();
        drained.signalAll();
        try {
            onClosed.run();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "close callback failed", e);
        }
    }

    // ================================================================ byte helpers

    private static int getInt(byte[] b, int o) {
        return ((b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16) | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
    }

    private static long getLong(byte[] b, int o) {
        return ((long) getInt(b, o) << 32) | (getInt(b, o + 4) & 0xFFFFFFFFL);
    }

    private static void putInt(byte[] b, int o, int v) {
        b[o] = (byte) (v >>> 24);
        b[o + 1] = (byte) (v >>> 16);
        b[o + 2] = (byte) (v >>> 8);
        b[o + 3] = (byte) v;
    }

    private static void putLong(byte[] b, int o, long v) {
        putInt(b, o, (int) (v >>> 32));
        putInt(b, o + 4, (int) v);
    }
}
