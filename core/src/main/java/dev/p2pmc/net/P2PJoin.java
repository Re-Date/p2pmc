package dev.p2pmc.net;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * Guest side. Turns the host's invite code into an answer code, punches towards the host while the user passes
 * the answer on, then exposes the host's Minecraft server as a normal local port ({@link #localPort()}) that the
 * game connects to like to any LAN server.
 * <p>
 * Progress is exposed as polled state ({@link #state()}, {@link #detail()}) so UI code never needs callbacks.
 */
public final class P2PJoin implements Closeable {
    private static final System.Logger LOG = System.getLogger("p2pmc");

    public enum State {
        /** Not started yet. */
        NEW,
        /** Reading the invite and checking the network. */
        PREPARING,
        /** The answer code is ready; waiting for the host to accept it. */
        WAITING_FOR_HOST,
        /** The path is open, securing the connection. */
        HANDSHAKING,
        /** {@link #localPort()} is listening; connect the game to 127.0.0.1:localPort. */
        READY,
        /** The game connected and traffic is flowing. */
        CONNECTED,
        FAILED,
        CLOSED
    }

    private final String offerCode;
    private final P2PConfig config;
    private final ReliableChannel.Config channelConfig = new ReliableChannel.Config();

    private volatile State state = State.NEW;
    private volatile String detail = "";
    private volatile String answerCode = "";
    private volatile String networkReport = "";
    private volatile int localPort = -1;

    private volatile boolean closed;
    private UdpEndpoint endpoint;
    private PunchSession punch;
    private volatile ServerSocket listener;
    private volatile ReliableChannel channel;

    public P2PJoin(String offerCode, P2PConfig config) {
        this.offerCode = offerCode;
        this.config = config;
    }

    public State state() {
        return state;
    }

    public String detail() {
        return detail;
    }

    /** The code to send back to the host; empty until {@link State#WAITING_FOR_HOST}. */
    public String answerCode() {
        return answerCode;
    }

    public String networkReport() {
        return networkReport;
    }

    /** Valid from {@link State#READY}. */
    public int localPort() {
        return localPort;
    }

    public long pingMillis() {
        ReliableChannel c = channel;
        return c == null ? -1 : c.rttMillis();
    }

    /** Starts in the background and returns immediately. */
    public void start() {
        Thread.ofVirtual().name("p2p-join").start(this::run);
    }

    private void run() {
        try {
            set(State.PREPARING, "Reading the invite...");
            Invite.Offer offer = Invite.decodeOffer(offerCode);
            endpoint = new UdpEndpoint(0);

            set(State.PREPARING, "Checking your network...");
            StunResolver.Discovery d = endpoint.stun().discover(config.stunServers(), config.stunTimeoutMillis());
            List<InetSocketAddress> mine = new ArrayList<>(d.mapped());
            mine.addAll(endpoint.lanCandidates());
            if (mine.isEmpty()) {
                throw new IOException("No network connection found.");
            }
            networkReport = d.mapped().isEmpty()
                    ? "No public address found (STUN unreachable) - only a host on your own network can be reached."
                    : d.symmetric()
                    ? "Your router uses a strict NAT (symmetric / CGNAT): connecting may fail. Use the host's tunnel (playit.gg) address instead."
                    : "Network looks friendly.";

            byte[] sessionId = new byte[Invite.SESSION_ID_LEN];
            new SecureRandom().nextBytes(sessionId);
            answerCode = Invite.encodeAnswer(offer.token(), sessionId, mine);

            PunchSession session = new PunchSession(endpoint, offer.token(), sessionId, PunchSession.ROLE_JOINER,
                    offer.candidates(), channelConfig,
                    link -> Thread.ofVirtual().name("p2p-join-link").start(() -> onLink(link, Keys.psk(offer.token(), sessionId))),
                    () -> fail("The host did not accept the answer in time, or the direct path is blocked. "
                            + "Ask the host to paste your answer code, or use the host's tunnel address."));
            punch = session;
            endpoint.setUnknownHandler((from, buf, off, len) -> {
                if (len > 0 && buf[off] == ReliableChannel.T_PUNCH) {
                    session.offer(from, buf, off, len);
                }
            });
            set(State.WAITING_FOR_HOST, "Connecting to the host... if nothing happens in a few seconds, send your answer code to the host.");
            session.start(config.joinerWaitMillis());
        } catch (InviteException e) {
            fail(e.getMessage());
        } catch (IOException | RuntimeException e) {
            fail("Could not start: " + e.getMessage());
        }
    }

    private void onLink(Link link, String psk) {
        channel = link.channel();
        if (closed) {
            link.channel().close();
            return;
        }
        set(State.HANDSHAKING, "Path open, securing the connection...");
        try {
            SecureStream stream = SecureStream.connect(link.channel(), psk, config.handshakeMillis());
            ServerSocket server = new ServerSocket(0, 1, InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));
            listener = server;
            localPort = server.getLocalPort();
            set(State.READY, "Connected to the host - joining the game...");
            server.setSoTimeout(60_000);
            try (stream; Socket game = server.accept()) {
                game.setTcpNoDelay(true);
                server.close();
                set(State.CONNECTED, "Connected");
                Bridge.run(game, stream);
            }
            if (state != State.FAILED) {
                set(State.CLOSED, "Disconnected");
            }
        } catch (SocketTimeoutException e) {
            fail("The game did not connect to the tunnel in time.");
        } catch (IOException e) {
            fail(closed ? "Closed" : "Connection failed: " + e.getMessage());
        }
    }

    private void set(State s, String d) {
        if (state == State.FAILED || state == State.CLOSED) {
            return;
        }
        this.detail = d;
        this.state = s;
    }

    private void fail(String message) {
        if (state == State.FAILED || state == State.CLOSED) {
            return;
        }
        LOG.log(System.Logger.Level.INFO, "P2P join failed: {0}", message);
        this.detail = message;
        this.state = State.FAILED;
        releaseResources();
    }

    @Override
    public void close() {
        closed = true;
        if (state != State.FAILED) {
            state = State.CLOSED;
        }
        releaseResources();
    }

    private void releaseResources() {
        if (punch != null) {
            punch.cancel();
        }
        ServerSocket l = listener;
        if (l != null) {
            try {
                l.close();
            } catch (IOException ignored) {
                // closing anyway
            }
        }
        ReliableChannel c = channel;
        if (c != null) {
            c.close();
        }
        if (endpoint != null) {
            endpoint.close();
        }
    }
}
