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
        private volatile String detail = "Opening a direct path to your friend...";
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
    private final List<String> acceptedSessions = new ArrayList<>();

    private UdpEndpoint endpoint;
    private byte[] token;
    private volatile String offerCode = "";
    private volatile String networkReport = "Checking your network...";
    private volatile boolean closed;
    private ScheduledFuture<?> refresher;

    /** @param minecraftPort the local (LAN-published) integrated server port */
    public P2PHost(int minecraftPort, P2PConfig config) {
        this.minecraftPort = minecraftPort;
        this.config = config;
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
            throw new InviteException("Hosting is not running.");
        }
        Invite.Answer answer = Invite.decodeAnswer(token, answerCode);
        String sessionKey = HexFormat.of().formatHex(answer.sessionId());
        Peer peer;
        synchronized (this) {
            pending.removeIf(PunchSession::isDone);
            if (acceptedSessions.contains(sessionKey)) {
                throw new InviteException("This answer was already accepted.");
            }
            if (pending.size() >= MAX_PENDING) {
                throw new InviteException("Too many connections in progress - wait a moment.");
            }
            acceptedSessions.add(sessionKey);
            peer = new Peer(sessionKey.substring(0, 6));
            peers.add(peer);
        }
        final Peer p = peer;
        PunchSession punch = new PunchSession(endpoint, token, answer.sessionId(), PunchSession.ROLE_HOST,
                answer.candidates(), channelConfig,
                link -> Thread.ofVirtual().name("p2p-host-" + p.id).start(() -> serve(p, answer.sessionId(), link)),
                () -> p.set(PeerState.FAILED, "Could not open a direct path (NAT too strict?). "
                        + "Use your tunnel service (playit.gg) for this friend."));
        pending.add(punch);
        punch.start(config.hostPunchMillis());
        return peer;
    }

    private void onUnknownPacket(InetSocketAddress from, byte[] buf, int off, int len) {
        if (len > 0 && buf[off] == ReliableChannel.T_PUNCH) {
            for (PunchSession s : pending) {
                if (s.offer(from, buf, off, len)) {
                    return;
                }
            }
        }
    }

    private void serve(Peer peer, byte[] sessionId, Link link) {
        peer.channel = link.channel();
        peer.set(PeerState.HANDSHAKING, "Path open, securing the connection...");
        try (SecureStream stream = SecureStream.accept(link.channel(), Keys.psk(token, sessionId), config.handshakeMillis())) {
            Socket minecraft = new Socket();
            minecraft.connect(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), minecraftPort), 3000);
            minecraft.setTcpNoDelay(true);
            peer.set(PeerState.CONNECTED, "Connected");
            LOG.log(System.Logger.Level.INFO, "P2P peer {0} connected", peer.id);
            Bridge.run(minecraft, stream);
            peer.set(PeerState.CLOSED, "Disconnected");
        } catch (IOException e) {
            if (peer.state != PeerState.CLOSED) {
                peer.set(PeerState.FAILED, "Connection failed: " + e.getMessage());
            }
            LOG.log(System.Logger.Level.INFO, "P2P peer {0} failed: {1}", peer.id, e.toString());
        }
    }

    private void refreshEndpoints() {
        if (closed) {
            return;
        }
        StunResolver.Discovery d = endpoint.stun().discover(config.stunServers(), config.stunTimeoutMillis());
        List<InetSocketAddress> candidates = new ArrayList<>(d.mapped());
        candidates.addAll(endpoint.lanCandidates());
        if (!candidates.isEmpty()) {
            offerCode = Invite.encodeOffer(token, candidates);
        }
        if (d.mapped().isEmpty()) {
            networkReport = "No public address found (STUN unreachable). Only friends on your own network can connect directly.";
        } else if (d.symmetric()) {
            networkReport = "Your router uses a strict NAT (symmetric / CGNAT). Direct connections will often fail - "
                    + "use your tunnel service (playit.gg) as a fallback.";
        } else {
            networkReport = "Network looks friendly: direct connections should work.";
        }
    }

    @Override
    public void close() {
        closed = true;
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
