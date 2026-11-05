package dev.p2pmc.net;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;

/**
 * Host side. Owns the UDP socket, publishes an invite code and, for every answer code the host pastes, punches a
 * hole to that friend, authenticates them and bridges their stream to the local Minecraft server port.
 * <p>
 * Minecraft itself is untouched: it only ever sees ordinary local TCP connections from 127.0.0.1.
 */
public final class P2PHost implements Closeable {
    private static final System.Logger LOG = System.getLogger("p2pmc");
    private static final int MAX_PENDING = 8;

    public enum PeerState {PUNCHING, HANDSHAKING, CONNECTED, FAILED, CLOSED}

    /** Live view of one friend; getters are safe from any thread. */
    public static final class Peer {
        private final String id;
        private volatile PeerState state = PeerState.PUNCHING;
        private volatile String detail = Messages.t("host.opening");
        private volatile ReliableChannel channel;

        Peer(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public PeerState state() {
            return state;
        }

        public String detail() {
            return detail;
        }

        /** Round-trip time to this friend in ms, or -1 if unknown. */
        public long pingMillis() {
            ReliableChannel c = channel;
            return c == null ? -1 : c.rttMillis();
        }

        private void set(PeerState s, String d) {
            this.detail = d;
            this.state = s;
        }
    }

    private final int minecraftPort;
    private final P2PConfig config;
    private final ReliableChannel.Config channelConfig = new ReliableChannel.Config();
    private final SecureRandom random = new SecureRandom();
    private final CopyOnWriteArrayList<PunchSession> pending = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Peer> peers = new CopyOnWriteArrayList<>();
    private final java.util.Map<String, Peer> bySession = new java.util.HashMap<>();
    private volatile boolean autoAccept;
    private long helloWindowStart;
    private int helloCount;

    private UdpEndpoint endpoint;
    private byte[] token;
    private volatile String offerCode = "";
    private volatile String networkReport = Messages.t("net.checking");
    private volatile boolean closed;
    private ScheduledFuture<?> refresher;

    private volatile boolean upnpEnabled = true;
    private Upnp upnp;
    private int upnpPort = -1;
    private String upnpIp;
    private long upnpNextAttempt;

    /** @param minecraftPort the local (LAN-published) integrated server port */
    public P2PHost(int minecraftPort, P2PConfig config) {
        this.minecraftPort = minecraftPort;
        this.config = config;
    }

    /**
     * With auto-accept on, a friend who holds the invite code connects by himself: no answer code has to be pasted.
     * Works whenever the friend's first packets can reach this PC (open or port-preserving NAT, forwarded port);
     * otherwise the manual answer code still works. Anyone holding the invite code can join, so treat it like a password.
     */
    public void setAutoAccept(boolean enabled) {
        this.autoAccept = enabled;
    }

    /** Ask the router (UPnP) to forward our UDP port, which lets friends connect even behind a strict NAT. On by default. */
    public void setUpnpEnabled(boolean enabled) {
        this.upnpEnabled = enabled;
    }

    /** Opens/renews the UPnP mapping when due. Best effort, never throws. */
    private synchronized void ensureUpnp() {
        long now = System.nanoTime();
        if (!upnpEnabled || closed || now - upnpNextAttempt < 0) {
            return;
        }
        if (upnp == null) {
            upnp = Upnp.discover(2000);
        }
        if (upnp == null) {
            upnpNextAttempt = now + 120_000_000_000L;
            return;
        }
        int local = endpoint.localPort();
        String lan = UdpEndpoint.lanAddress() == null ? null : UdpEndpoint.lanAddress().getHostAddress();
        int mapped = lan == null ? -1 : upnp.mapUdp(local, local, lan, "P2P Minecraft", 3600);
        if (mapped > 0) {
            upnpPort = mapped;
            upnpIp = upnp.externalIp();
            upnpNextAttempt = now + 1_200_000_000_000L; // renew well before the 1 hour lease ends
        } else {
            upnp = null;
            upnpPort = -1;
            upnpIp = null;
            upnpNextAttempt = now + 120_000_000_000L;
        }
    }

    /** Blocking (up to the STUN timeout): binds the socket and builds the first invite. Call off the render thread. */
    public void start() throws IOException {
        endpoint = new UdpEndpoint(0);
        token = new byte[Invite.TOKEN_LEN];
        random.nextBytes(token);
        endpoint.setUnknownHandler(this::onUnknownPacket);
        refreshEndpoints();
        // Re-check periodically: keeps the NAT mapping alive and updates the invite if the public port changes.
        refresher = Timers.every(20_000, () -> Thread.ofVirtual().name("p2p-stun-refresh").start(this::refreshEndpoints));
    }

    /** The code to send to friends. Changes if the public endpoint changes, so re-read it before sharing. */
    public String offerCode() {
        return offerCode;
    }

    /** Human readable verdict on whether direct connections are likely to work. */
    public String networkReport() {
        return networkReport;
    }

    public List<Peer> peers() {
        return List.copyOf(peers);
    }

    public int minecraftPort() {
        return minecraftPort;
    }

    /**
     * Accepts a friend's answer code and starts connecting to them.
     *
     * @throws InviteException if the code is malformed, belongs to a different invite, or was already used
     */
    public Peer acceptAnswer(String answerCode) throws InviteException {
        if (closed || endpoint == null) {
            throw new InviteException(Messages.t("host.notRunning"));
        }
        Invite.Answer answer = Invite.decodeAnswer(token, answerCode);
        return acceptSession(answer.sessionId(), answer.candidates());
    }

    /** Starts connecting to one friend. A session that is already known (e.g. auto-accepted) just returns its peer. */
    private Peer acceptSession(byte[] sessionId, List<InetSocketAddress> candidates) throws InviteException {
        String sessionKey = HexFormat.of().formatHex(sessionId);
        Peer peer;
        synchronized (this) {
            Peer existing = bySession.get(sessionKey);
            if (existing != null) {
                return existing;
            }
            pending.removeIf(PunchSession::isDone);
            if (pending.size() >= MAX_PENDING) {
                throw new InviteException(Messages.t("host.tooMany"));
            }
            peer = new Peer(sessionKey.substring(0, 6));
            bySession.put(sessionKey, peer);
            peers.add(peer);
        }
        final Peer p = peer;
        PunchSession punch = new PunchSession(endpoint, token, sessionId, PunchSession.ROLE_HOST,
                candidates, channelConfig,
                link -> Thread.ofVirtual().name("p2p-host-" + p.id).start(() -> serve(p, sessionId, link)),
                () -> p.set(PeerState.FAILED, Messages.t("host.punchFailed")));
        pending.add(punch);
        punch.start(config.hostPunchMillis());
        return peer;
    }

    private void onUnknownPacket(InetSocketAddress from, byte[] buf, int off, int len) {
        if (len <= 0 || buf[off] != ReliableChannel.T_PUNCH) {
            return;
        }
        for (PunchSession s : pending) {
            if (s.offer(from, buf, off, len)) {
                return;
            }
        }
        if (autoAccept && len == PunchSession.HELLO_LEN) {
            autoAcceptHello(from, buf, off, len);
        }
    }

    /** A friend announced himself with a MAC made from the invite token: accept without an answer code. */
    private void autoAcceptHello(InetSocketAddress from, byte[] buf, int off, int len) {
        byte[] sessionId = PunchSession.helloSessionId(buf, off, len);
        if (sessionId == null) {
            return;
        }
        synchronized (this) {
            if (bySession.containsKey(HexFormat.of().formatHex(sessionId))) {
                return; // the friend repeats his hello 4 times a second until connected
            }
            long now = System.nanoTime();
            if (now - helloWindowStart > 1_000_000_000L) {
                helloWindowStart = now;
                helloCount = 0;
            }
            if (++helloCount > 50) {
                return; // flood guard: at most 50 new hellos are verified per second
            }
        }
        if (!PunchSession.validHello(token, sessionId, buf, off)) {
            return;
        }
        try {
            Peer peer = acceptSession(sessionId, List.of(from));
            LOG.log(System.Logger.Level.INFO, "P2P friend {0} auto-accepted from {1}", peer.id, from);
        } catch (InviteException ignored) {
            // too many connections in progress: the friend keeps trying
        }
    }

    private void serve(Peer peer, byte[] sessionId, Link link) {
        peer.channel = link.channel();
        peer.set(PeerState.HANDSHAKING, Messages.t("peer.securing"));
        try (SecureStream stream = SecureStream.accept(link.channel(), Keys.psk(token, sessionId), config.handshakeMillis())) {
            Socket minecraft = new Socket();
            minecraft.connect(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), minecraftPort), 3000);
            minecraft.setTcpNoDelay(true);
            peer.set(PeerState.CONNECTED, Messages.t("peer.connected"));
            LOG.log(System.Logger.Level.INFO, "P2P peer {0} connected", peer.id);
            Bridge.run(minecraft, stream);
            peer.set(PeerState.CLOSED, Messages.t("peer.disconnected"));
        } catch (IOException e) {
            if (peer.state != PeerState.CLOSED) {
                peer.set(PeerState.FAILED, Messages.t("peer.failed", e.getMessage()));
            }
            LOG.log(System.Logger.Level.INFO, "P2P peer {0} failed: {1}", peer.id, e.toString());
        }
    }

    private void refreshEndpoints() {
        if (closed) {
            return;
        }
        StunResolver.Discovery d = endpoint.stun().discover(config.stunServers(), config.stunTimeoutMillis());
        ensureUpnp();
        java.util.LinkedHashSet<InetSocketAddress> candidates = new java.util.LinkedHashSet<>();
        boolean viaUpnp = upnpPort > 0 && Upnp.isPublicIpv4(upnpIp);
        if (viaUpnp) {
            candidates.add(new InetSocketAddress(upnpIp, upnpPort));
        }
        candidates.addAll(d.mapped());
        candidates.addAll(endpoint.lanCandidates());
        if (!candidates.isEmpty()) {
            offerCode = Invite.encodeOffer(token, new ArrayList<>(candidates));
        }
        boolean good = viaUpnp || (!d.mapped().isEmpty() && !d.symmetric());
        String verdict;
        if (viaUpnp || (!d.mapped().isEmpty() && !d.symmetric())) {
            verdict = Messages.t("net.friendly.host");
        } else if (d.mapped().isEmpty()) {
            verdict = Messages.t("net.noPublic.host");
        } else {
            verdict = Messages.t("net.strict.host");
        }
        if (viaUpnp) {
            verdict += " " + Messages.t("net.upnp.ok", upnpPort);
        } else if (!good && upnpEnabled) {
            verdict += " " + Messages.t("net.upnp.none");
        }
        networkReport = verdict;
    }

    @Override
    public void close() {
        closed = true;
        final Upnp u = upnp;
        final int mappedPort = upnpPort;
        if (u != null && mappedPort > 0) {
            Thread.ofVirtual().name("p2p-upnp-unmap").start(() -> u.unmapUdp(mappedPort));
        }
        if (refresher != null) {
            refresher.cancel(false);
        }
        for (PunchSession s : pending) {
            s.cancel();
        }
        for (Peer p : peers) {
            ReliableChannel c = p.channel;
            if (c != null) {
                c.close();
            }
        }
        if (endpoint != null) {
            endpoint.close();
        }
    }
}
