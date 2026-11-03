package dev.p2pmc.net;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Dependency-free tests for invite codes, STUN and the reliable UDP channel, including a hostile network simulator
 * (loss, duplication, reordering, latency jitter).
 * Run: java -cp out dev.p2pmc.net.TransportSelfTest
 */
public final class TransportSelfTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        inviteCodes();
        stunCodec();
        stunDiscovery();
        channelCleanNetwork();
        channelHostileNetwork();
        channelBothDirectionsAtOnce();
        channelSlowReaderBackpressure();
        channelGracefulClose();
        channelDeadPeer();
        System.out.println("TRANSPORT OK (" + checks + " checks)");
        System.exit(0);
    }

    // ================================================================ invite codes

    private static void inviteCodes() throws Exception {
        byte[] token = new byte[16];
        new Random(1).nextBytes(token);
        List<InetSocketAddress> cands = List.of(
                new InetSocketAddress(InetAddress.getByAddress(new byte[]{85, 12, 34, 56}), 51234),
                new InetSocketAddress(InetAddress.getByAddress(new byte[]{(byte) 192, (byte) 168, 1, 20}), 51234));

        String offer = Invite.encodeOffer(token, cands);
        System.out.println("offer  (" + offer.replace("-", "").length() + " chars): " + offer);
        Invite.Offer decoded = Invite.decodeOffer(offer);
        check(Arrays.equals(decoded.token(), token), "offer token round trip");
        check(decoded.candidates().equals(cands), "offer candidates round trip");

        // forgiving paste: lower case, spaces, line breaks, ambiguous characters
        String messy = " " + offer.toLowerCase().replace("-", " \n") + " ";
        check(Arrays.equals(Invite.decodeOffer(messy).token(), token), "messy paste accepted");
        String ambiguous = offer.replace('0', 'O').replace('1', 'l');
        check(Arrays.equals(Invite.decodeOffer(ambiguous).token(), token), "O/0 and l/1 confusion tolerated");

        // a single wrong character must be caught by the checksum
        char[] chars = offer.toCharArray();
        int idx = offer.indexOf('-') + 2;
        chars[idx] = chars[idx] == 'A' ? 'B' : 'A';
        expectInvite(() -> Invite.decodeOffer(new String(chars)), "corrupted offer rejected");
        expectInvite(() -> Invite.decodeOffer(offer.substring(0, offer.length() - 6)), "truncated offer rejected");
        expectInvite(() -> Invite.decodeOffer("hello world"), "garbage rejected");
        expectInvite(() -> Invite.decodeOffer(""), "empty rejected");

        byte[] sessionId = {1, 2, 3, 4, 5, 6, 7, 8};
        String answer = Invite.encodeAnswer(token, sessionId, cands);
        System.out.println("answer (" + answer.replace("-", "").length() + " chars): " + answer);
        Invite.Answer a = Invite.decodeAnswer(token, answer);
        check(Arrays.equals(a.sessionId(), sessionId) && a.candidates().equals(cands), "answer round trip");

        byte[] otherToken = token.clone();
        otherToken[0] ^= 1;
        expectInvite(() -> Invite.decodeAnswer(otherToken, answer), "answer for a different invite rejected");
        expectInvite(() -> Invite.decodeAnswer(token, offer), "offer pasted as answer gives a helpful error");
        expectInvite(() -> Invite.decodeOffer(answer), "answer pasted as offer gives a helpful error");
        String tamperedAnswer = answer.substring(0, 12) + (answer.charAt(12) == 'A' ? 'B' : 'A') + answer.substring(13);
        expectInvite(() -> Invite.decodeAnswer(token, tamperedAnswer), "tampered answer rejected");
    }

    // ============================================================ STUN codec + discovery

    private static void stunCodec() throws Exception {
        byte[] txid = new byte[12];
        new Random(2).nextBytes(txid);
        InetSocketAddress mapped = new InetSocketAddress(InetAddress.getByAddress(new byte[]{(byte) 203, 0, 113, 9}), 40001);
        byte[] req = Stun.bindingRequest(txid);
        check(Stun.looksLikeStun(req, 0, req.length), "request recognised as STUN");
        check(Stun.parse(req, 0, req.length) == null, "request is not a response");
        byte[] resp = Stun.bindingSuccess(txid, mapped);
        Stun.Response parsed = Stun.parse(resp, 0, resp.length);
        check(parsed != null && parsed.mapped().equals(mapped) && Arrays.equals(parsed.txid(), txid), "XOR-MAPPED-ADDRESS round trip");

        // A real (RFC 5769 style) response with extra attributes before the mapped address.
        byte[] withNoise = new byte[resp.length + 8];
        System.arraycopy(resp, 0, withNoise, 0, 20);
        withNoise[3] = (byte) (resp[3] + 8);
        withNoise[20] = (byte) 0x80; // SOFTWARE
        withNoise[21] = (byte) 0x22;
        withNoise[23] = 4;
        System.arraycopy(resp, 20, withNoise, 28, resp.length - 20);
        Stun.Response noisy = Stun.parse(withNoise, 0, withNoise.length);
        check(noisy != null && noisy.mapped().equals(mapped), "unknown attributes are skipped");

        // tunnel packets must never be mistaken for STUN
        byte[] data = new byte[100];
        data[0] = ReliableChannel.T_DATA;
        check(!Stun.looksLikeStun(data, 0, data.length), "data packet is not STUN");
        check(Stun.parse(new byte[19], 0, 19) == null, "short garbage ignored");
    }

    /** Tiny STUN server on loopback; {@code portShift} simulates a symmetric NAT. */
    private static final class FakeStunServer implements AutoCloseable {
        final java.net.DatagramSocket socket;
        final int portShift;
        volatile boolean running = true;

        FakeStunServer(int portShift) throws IOException {
            this.socket = new java.net.DatagramSocket(0, InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));
            this.portShift = portShift;
            Thread t = new Thread(() -> {
                byte[] buf = new byte[512];
                while (running) {
                    try {
                        java.net.DatagramPacket p = new java.net.DatagramPacket(buf, buf.length);
                        socket.receive(p);
                        byte[] txid = Arrays.copyOfRange(buf, 8, 20);
                        byte[] reply = Stun.bindingSuccess(txid, new InetSocketAddress(p.getAddress(), p.getPort() + portShift));
                        socket.send(new java.net.DatagramPacket(reply, reply.length, p.getSocketAddress()));
                    } catch (IOException e) {
                        return;
                    }
                }
            }, "fake-stun");
            t.setDaemon(true);
            t.start();
        }

        String address() {
            return "127.0.0.1:" + socket.getLocalPort();
        }

        @Override
        public void close() {
            running = false;
            socket.close();
        }
    }

    private static void stunDiscovery() throws Exception {
        try (FakeStunServer a = new FakeStunServer(0); FakeStunServer b = new FakeStunServer(0);
             UdpEndpoint ep = new UdpEndpoint(0)) {
            StunResolver.Discovery d = ep.stun().discover(List.of(a.address(), b.address()), 2000);
            check(d.mapped().size() == 1 && d.mapped().get(0).getPort() == ep.localPort(), "STUN reports the socket's own port");
            check(!d.symmetric(), "same port from two servers = cone NAT");
        }
        try (FakeStunServer a = new FakeStunServer(0); FakeStunServer b = new FakeStunServer(7);
             UdpEndpoint ep = new UdpEndpoint(0)) {
            StunResolver.Discovery d = ep.stun().discover(List.of(a.address(), b.address()), 2000);
            check(d.symmetric() && d.mapped().size() == 2, "different ports from two servers = symmetric NAT detected");
        }
        try (UdpEndpoint ep = new UdpEndpoint(0)) {
            long t0 = System.nanoTime();
            StunResolver.Discovery d = ep.stun().discover(List.of("127.0.0.1:9"), 1000);
            check(d.mapped().isEmpty() && !d.symmetric(), "unreachable STUN yields an empty discovery");
            check((System.nanoTime() - t0) / 1_000_000 < 1600, "unreachable STUN respects the timeout");
        }
    }

    // ==================================================== reliable channel simulator

    /** A pair of channels joined by a lossy, laggy, reordering "internet". */
    private static final class Net implements AutoCloseable {
        final ScheduledThreadPoolExecutor delivery = new ScheduledThreadPoolExecutor(2);
        final Random rnd;
        final double loss;
        final double dup;
        final int baseMs;
        final int jitterMs;
        final AtomicReference<ReliableChannel> a = new AtomicReference<>();
        final AtomicReference<ReliableChannel> b = new AtomicReference<>();
        volatile boolean blackhole;
        long sentPackets;
        long droppedPackets;

        Net(long seed, double loss, double dup, int baseMs, int jitterMs, ReliableChannel.Config cfg) {
            this.rnd = new Random(seed);
            this.loss = loss;
            this.dup = dup;
            this.baseMs = baseMs;
            this.jitterMs = jitterMs;
            byte[] tag = {9, 8, 7, 6};
            a.set(new ReliableChannel(link(b), tag, cfg, () -> {
            }));
            b.set(new ReliableChannel(link(a), tag, cfg, () -> {
            }));
        }

        private ReliableChannel.Sink link(AtomicReference<ReliableChannel> dest) {
            return (buf, off, len) -> {
                int copies;
                long delay;
                synchronized (rnd) {
                    sentPackets++;
                    if (blackhole || rnd.nextDouble() < loss) {
                        droppedPackets++;
                        return;
                    }
                    copies = rnd.nextDouble() < dup ? 2 : 1;
                }
                byte[] copy = Arrays.copyOfRange(buf, off, off + len);
                for (int i = 0; i < copies; i++) {
                    synchronized (rnd) {
                        delay = baseMs + (jitterMs > 0 ? rnd.nextInt(jitterMs + 1) : 0);
                    }
                    delivery.schedule(() -> dest.get().onPacket(copy, 0, copy.length), delay, TimeUnit.MILLISECONDS);
                }
            };
        }

        @Override
        public void close() {
            a.get().close();
            b.get().close();
            delivery.shutdownNow();
        }
    }

    private static ReliableChannel.Config fastConfig() {
        ReliableChannel.Config c = new ReliableChannel.Config();
        c.minRtoMillis = 40;
        c.peerTimeoutMillis = 30_000;
        return c;
    }

    private static byte[] randomBytes(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }

    private static byte[] sha(byte[] b) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(b);
    }

    /** Writes {@code data} on {@code from} in odd-sized chunks while reading it back on {@code to}. */
    private static byte[] transfer(ExecutorService pool, ReliableChannel from, ReliableChannel to, byte[] data,
                                   long readDelayMillis) throws Exception {
        Future<?> writer = pool.submit(() -> {
            Random r = new Random(5);
            int pos = 0;
            while (pos < data.length) {
                int n = Math.min(data.length - pos, 1 + r.nextInt(6000));
                from.write(data, pos, n);
                pos += n;
            }
            return null;
        });
        byte[] got = new byte[data.length];
        int pos = 0;
        byte[] buf = new byte[4096];
        while (pos < data.length) {
            int n = to.read(buf, 0, Math.min(buf.length, data.length - pos));
            if (n < 0) {
                throw new IOException("unexpected EOF at " + pos);
            }
            System.arraycopy(buf, 0, got, pos, n);
            pos += n;
            if (readDelayMillis > 0) {
                Thread.sleep(readDelayMillis);
            }
        }
        writer.get(60, TimeUnit.SECONDS);
        return got;
    }

    private static void channelCleanNetwork() throws Exception {
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        try (Net net = new Net(1, 0, 0, 2, 0, fastConfig())) {
            byte[] data = randomBytes(30 * 1024 * 1024, 11);
            long t0 = System.nanoTime();
            byte[] got = transfer(pool, net.a.get(), net.b.get(), data, 0);
            double secs = (System.nanoTime() - t0) / 1e9;
            check(Arrays.equals(sha(data), sha(got)), "30 MB intact over a clean 4 ms RTT link");
            System.out.printf("  clean link: %.1f MB/s, %d packets, rtt=%d ms%n", 30 / secs, net.sentPackets, net.a.get().rttMillis());
        } finally {
            pool.shutdownNow();
        }
    }

    private static void channelHostileNetwork() throws Exception {
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        // 3% loss, 2% duplicates, 20 ms +-15 ms delay (so heavy reordering)
        try (Net net = new Net(7, 0.03, 0.02, 20, 15, fastConfig())) {
            byte[] data = randomBytes(3 * 1024 * 1024, 22);
            long t0 = System.nanoTime();
            byte[] got = transfer(pool, net.a.get(), net.b.get(), data, 0);
            double secs = (System.nanoTime() - t0) / 1e9;
            check(Arrays.equals(sha(data), sha(got)), "3 MB intact with 3% loss, 2% duplicates, reordering");
            System.out.printf("  hostile link: %.2f MB/s, dropped %d of %d packets%n", 3 / secs, net.droppedPackets, net.sentPackets);
        } finally {
            pool.shutdownNow();
        }
        // very bad: 12% loss
        pool = Executors.newVirtualThreadPerTaskExecutor();
        try (Net net = new Net(8, 0.12, 0.0, 15, 10, fastConfig())) {
            byte[] data = randomBytes(300 * 1024, 23);
            byte[] got = transfer(pool, net.a.get(), net.b.get(), data, 0);
            check(Arrays.equals(sha(data), sha(got)), "300 KB intact with 12% loss");
        } finally {
            pool.shutdownNow();
        }
    }

    private static void channelBothDirectionsAtOnce() throws Exception {
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        try (Net net = new Net(3, 0.02, 0.01, 10, 8, fastConfig())) {
            byte[] d1 = randomBytes(2 * 1024 * 1024, 31);
            byte[] d2 = randomBytes(2 * 1024 * 1024, 32);
            Future<byte[]> f1 = pool.submit(() -> transfer(pool, net.a.get(), net.b.get(), d1, 0));
            Future<byte[]> f2 = pool.submit(() -> transfer(pool, net.b.get(), net.a.get(), d2, 0));
            check(Arrays.equals(sha(d1), sha(f1.get(90, TimeUnit.SECONDS))), "A->B intact during bidirectional transfer");
            check(Arrays.equals(sha(d2), sha(f2.get(90, TimeUnit.SECONDS))), "B->A intact during bidirectional transfer");
        } finally {
            pool.shutdownNow();
        }
    }

    private static void channelSlowReaderBackpressure() throws Exception {
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        try (Net net = new Net(4, 0, 0, 2, 0, fastConfig())) {
            byte[] data = randomBytes(2 * 1024 * 1024, 41);
            long t0 = System.nanoTime();
            // reader consumes 4 KiB every 2 ms = ~2 MB/s max, far slower than the link can deliver
            byte[] got = transfer(pool, net.a.get(), net.b.get(), data, 2);
            check(Arrays.equals(sha(data), sha(got)), "data intact with a slow reader (flow control)");
            check((System.nanoTime() - t0) / 1_000_000 > 500, "slow reader really throttled the transfer");
        } finally {
            pool.shutdownNow();
        }
    }

    private static void channelGracefulClose() throws Exception {
        try (Net net = new Net(5, 0.05, 0, 10, 5, fastConfig())) {
            ReliableChannel a = net.a.get();
            ReliableChannel b = net.b.get();
            byte[] data = randomBytes(200_000, 51);
            a.write(data, 0, data.length);
            a.close(); // must flush everything before the FIN
            byte[] got = new byte[data.length];
            int pos = 0;
            while (pos < got.length) {
                int n = b.read(got, pos, got.length - pos);
                check(n > 0, "data available before EOF");
                pos += n;
            }
            check(Arrays.equals(data, got), "all data delivered before close");
            check(b.read(new byte[10], 0, 10) == -1, "reader sees EOF after the peer closed");
            expectIo(() -> a.write(new byte[1], 0, 1), "write after close fails");
            b.close();
        }
    }

    private static void channelDeadPeer() throws Exception {
        ReliableChannel.Config cfg = fastConfig();
        cfg.peerTimeoutMillis = 1500;
        cfg.keepaliveMillis = 200;
        try (Net net = new Net(6, 0, 0, 2, 0, cfg)) {
            ReliableChannel a = net.a.get();
            byte[] ping = {1, 2, 3};
            a.write(ping, 0, ping.length);
            byte[] buf = new byte[3];
            check(net.b.get().read(buf, 0, 3) == 3, "traffic flows before the outage");
            net.blackhole = true; // pull the cable
            long t0 = System.nanoTime();
            ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
            Future<Object> reader = pool.submit(() -> {
                try {
                    a.read(new byte[1], 0, 1);
                    return "read returned";
                } catch (IOException e) {
                    return e.getMessage();
                }
            });
            Object result = reader.get(10, TimeUnit.SECONDS);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            check(String.valueOf(result).contains("timed out"), "dead peer reported as timeout: " + result);
            check(ms > 1000 && ms < 4000, "dead peer detected in " + ms + " ms");
            pool.shutdownNow();
        }
        // read timeout: no data, no failure
        try (Net net = new Net(9, 0, 0, 1, 0, fastConfig())) {
            net.a.get().setReadTimeout(200);
            long t0 = System.nanoTime();
            try {
                net.a.get().read(new byte[1], 0, 1);
                throw new AssertionError("FAIL: read should have timed out");
            } catch (java.net.SocketTimeoutException expected) {
                check((System.nanoTime() - t0) / 1_000_000 >= 190, "read timeout honoured");
            }
        }
        // garbage with the wrong tag is ignored
        try (Net net = new Net(10, 0, 0, 1, 0, fastConfig())) {
            byte[] junk = new byte[40];
            new Random(1).nextBytes(junk);
            junk[0] = ReliableChannel.T_DATA;
            net.b.get().onPacket(junk, 0, junk.length);
            byte[] ok = {42};
            net.a.get().write(ok, 0, 1);
            byte[] r = new byte[1];
            check(net.b.get().read(r, 0, 1) == 1 && r[0] == 42, "junk packet with wrong tag ignored");
        }
    }

    // ================================================================ helpers

    private interface Thrower {
        void run() throws Exception;
    }

    private static void expectInvite(Thrower t, String what) throws Exception {
        checks++;
        try {
            t.run();
        } catch (InviteException expected) {
            return;
        }
        throw new AssertionError("FAIL: " + what);
    }

    private static void expectIo(Thrower t, String what) throws Exception {
        checks++;
        try {
            t.run();
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("FAIL: " + what);
    }

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            throw new AssertionError("FAIL: " + what);
        }
    }

    @SuppressWarnings("unused")
    private static List<String> unused() {
        return new ArrayList<>();
    }
}
