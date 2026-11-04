package dev.p2pmc.net;

import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Consumer;

/**
 * UDP hole punching between exactly two parties that both know each other's candidate endpoints.
 * <p>
 * Both sides fire authenticated probe packets at every candidate of the peer, 4 times a second. Each outgoing probe
 * opens (or keeps open) a pinhole in the sender's own NAT for the peer's address, so after a moment the probes
 * start getting through in both directions. The first valid probe received fixes the peer's real source address
 * (which may differ from the advertised one) and turns the path into a {@link ReliableChannel}.
 * <p>
 * Probe = {@code 5 | tag(4) | role(1) | ack(1) | mac(8)}; the MAC (keyed with the invite token) means scanners and
 * strangers cannot make the host spin up channels.
 */
final class PunchSession {
    static final int ROLE_HOST = 0;
    static final int ROLE_JOINER = 1;
    private static final int PROBE_LEN = 1 + 4 + 1 + 1 + 8;
    /** A joiner's hello is a normal probe with its session id appended, so a host can accept it without an answer code. */
    static final int HELLO_LEN = PROBE_LEN + Invite.SESSION_ID_LEN;

    private final UdpEndpoint endpoint;
    private final byte[] token;
    private final byte[] sessionId;
    private final byte[] tag;
    private final byte[] hello;
    private final int myRole;
    private final List<InetSocketAddress> targets;
    private final ReliableChannel.Config channelConfig;
    private final Consumer<Link> onLink;
    private final Runnable onTimeout;

    private volatile boolean done;
    private volatile ScheduledFuture<?> ticker;
    private long deadlineNanos;

    PunchSession(UdpEndpoint endpoint, byte[] token, byte[] sessionId, int myRole, List<InetSocketAddress> targets,
                 ReliableChannel.Config channelConfig, Consumer<Link> onLink, Runnable onTimeout) {
        this.endpoint = endpoint;
        this.token = token;
        this.sessionId = sessionId;
        this.tag = Keys.tag(token, sessionId);
        this.hello = myRole == ROLE_JOINER ? hello(token, sessionId) : null;
        this.myRole = myRole;
        this.targets = List.copyOf(targets);
        this.channelConfig = channelConfig;
        this.onLink = onLink;
        this.onTimeout = onTimeout;
    }

    boolean isDone() {
        return done;
    }

    void start(long timeoutMillis) {
        deadlineNanos = System.nanoTime() + timeoutMillis * 1_000_000L;
        ticker = Timers.everyNow(250, this::tick);
    }

    void cancel() {
        done = true;
        ScheduledFuture<?> t = ticker;
        if (t != null) {
            t.cancel(false);
        }
    }

    private void tick() {
        if (done) {
            return;
        }
        if (System.nanoTime() > deadlineNanos) {
            cancel();
            onTimeout.run();
            return;
        }
        byte[] probe = probe(myRole, false);
        for (InetSocketAddress target : targets) {
            endpoint.sendQuiet(target, probe);
            if (hello != null) {
                endpoint.sendQuiet(target, hello); // lets a host with auto-accept on create the session by itself
            }
        }
    }

    /** Joiner hello: {@code 5 | tag | role | 0 | mac | sessionId}. Hosts that do not know hellos ignore it (wrong length). */
    static byte[] hello(byte[] token, byte[] sessionId) {
        byte[] p = new byte[HELLO_LEN];
        p[0] = ReliableChannel.T_PUNCH;
        System.arraycopy(Keys.tag(token, sessionId), 0, p, 1, 4);
        p[5] = (byte) ROLE_JOINER;
        p[6] = 0;
        System.arraycopy(Keys.punchMac(token, sessionId, ROLE_JOINER, false), 0, p, 7, 8);
        System.arraycopy(sessionId, 0, p, PROBE_LEN, Invite.SESSION_ID_LEN);
        return p;
    }

    /** The session id inside a hello-shaped packet, or null if the packet has another shape. Not yet authenticated. */
    static byte[] helloSessionId(byte[] b, int off, int len) {
        if (len != HELLO_LEN || b[off] != ReliableChannel.T_PUNCH || b[off + 5] != ROLE_JOINER || b[off + 6] != 0) {
            return null;
        }
        return Arrays.copyOfRange(b, off + PROBE_LEN, off + HELLO_LEN);
    }

    /** Checks the hello's MAC with the invite token: only holders of the invite code can make a host accept them. */
    static boolean validHello(byte[] token, byte[] sessionId, byte[] b, int off) {
        byte[] mac = Arrays.copyOfRange(b, off + 7, off + 15);
        return MessageDigest.isEqual(mac, Keys.punchMac(token, sessionId, ROLE_JOINER, false));
    }

    /**
     * Offers a packet from an address that has no channel yet.
     *
     * @return true if it was a valid probe from our peer (and the link is now being established)
     */
    boolean offer(InetSocketAddress from, byte[] buf, int off, int len) {
        if (done || !validProbe(1 - myRole, buf, off, len)) {
            return false;
        }
        // The host fixes the path with the first probe it sees and acknowledges it on that very pair of addresses.
        // The joiner therefore only trusts an acknowledgement: that guarantees both ends use the same pair even when
        // several paths (LAN, public, loopback) work at once. Plain probes from the host just keep its pinhole open.
        if (myRole == ROLE_JOINER && buf[off + 6] != 1) {
            return false;
        }
        synchronized (this) {
            if (done) {
                return true;
            }
            done = true;
        }
        cancel();

        ReliableChannel channel = new ReliableChannel(
                (b, o, l) -> endpoint.send(from, b, o, l), tag, channelConfig, () -> endpoint.unregister(from));
        endpoint.register(from, (src, b, o, l) -> {
            if (b[o] == ReliableChannel.T_PUNCH) {
                // The peer keeps probing until it has seen one of ours: answer so it can finish too.
                if (myRole == ROLE_HOST && validProbe(ROLE_JOINER, b, o, l)) {
                    endpoint.sendQuiet(from, probe(ROLE_HOST, true));
                }
            } else {
                channel.onPacket(b, o, l);
            }
        });
        endpoint.sendQuiet(from, probe(myRole, true));
        onLink.accept(new Link(channel, from));
        return true;
    }

    private byte[] probe(int role, boolean ack) {
        byte[] p = new byte[PROBE_LEN];
        p[0] = ReliableChannel.T_PUNCH;
        System.arraycopy(tag, 0, p, 1, 4);
        p[5] = (byte) role;
        p[6] = (byte) (ack ? 1 : 0);
        System.arraycopy(Keys.punchMac(token, sessionId, role, ack), 0, p, 7, 8);
        return p;
    }

    private boolean validProbe(int expectedRole, byte[] b, int off, int len) {
        if (len != PROBE_LEN || b[off] != ReliableChannel.T_PUNCH || b[off + 5] != expectedRole) {
            return false;
        }
        boolean ack = b[off + 6] == 1;
        byte[] mac = Arrays.copyOfRange(b, off + 7, off + 15);
        return MessageDigest.isEqual(mac, Keys.punchMac(token, sessionId, expectedRole, ack));
    }
}
