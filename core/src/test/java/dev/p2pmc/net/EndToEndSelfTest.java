package dev.p2pmc.net;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * Whole stack on real UDP sockets (loopback): STUN -> invite -> answer -> punch -> reliable channel -> encrypted
 * handshake -> bridge to a fake Minecraft server (an echo server). Run: java -cp out dev.p2pmc.net.EndToEndSelfTest
 */
public final class EndToEndSelfTest {
    private static final byte[] LOCALHOST = {127, 0, 0, 1};
    private static int checks;

    public static void main(String[] args) throws Exception {
        try (FakeStun stun = new FakeStun()) {
            P2PConfig cfg = new P2PConfig(List.of(stun.address()), 8_000, 10_000, 5_000, 1_500);
            singleFriend(cfg);
            twoFriendsAtOnce(cfg);
            wrongInviteRejected(cfg);
            strangersCannotCreateSessions(cfg);
            hostNeverAccepts(new P2PConfig(List.of(stun.address()), 8_000, 2_000, 5_000, 1_500));
            disconnectsPropagate(cfg);
        }
        System.out.println("END-TO-END OK (" + checks + " checks)");
        System.exit(0);
    }

    // ================================================================ scenarios

    private static void singleFriend(P2PConfig cfg) throws Exception {
        try (EchoServer mc = new EchoServer(); P2PHost host = new P2PHost(mc.port(), cfg)) {
            host.start();
            check(!host.offerCode().isEmpty(), "host produced an invite code");
            check(host.networkReport().contains("friendly"), "network report: " + host.networkReport());

            P2PJoin join = new P2PJoin(host.offerCode(), cfg);
            join.start();
            waitFor(() -> join.state() == P2PJoin.State.WAITING_FOR_HOST, 5000, "joiner prepared its answer");
            check(!join.answerCode().isEmpty(), "joiner produced an answer code");

            P2PHost.Peer peer = host.acceptAnswer(join.answerCode());
            waitFor(() -> join.state() == P2PJoin.State.READY, 10_000, "tunnel up (joiner state " + join.state() + ": " + join.detail() + ")");

            try (Socket game = new Socket(InetAddress.getByAddress(LOCALHOST), join.localPort())) {
                game.setTcpNoDelay(true);
                byte[] data = new byte[4 * 1024 * 1024];
                new Random(77).nextBytes(data);
                long t0 = System.nanoTime();
                byte[] echoed = roundTrip(game, data);
                double secs = (System.nanoTime() - t0) / 1e9;
                check(Arrays.equals(sha(data), sha(echoed)), "4 MB echoed through the full encrypted P2P stack intact");
                System.out.printf("  e2e: 4 MB there and back in %.2fs (%.1f MB/s each way)%n", secs, 4 / secs);

                waitFor(() -> peer.state() == P2PHost.PeerState.CONNECTED, 3000, "host sees the friend as connected");
                waitFor(() -> peer.pingMillis() >= 0, 3000, "host measured a ping");
                check(join.state() == P2PJoin.State.CONNECTED, "joiner state CONNECTED");

                // tiny request/response ping-pong, like Minecraft keepalives
                OutputStream out = game.getOutputStream();
                InputStream in = game.getInputStream();
                long worst = 0;
                for (int i = 0; i < 200; i++) {
                    long s = System.nanoTime();
                    out.write(new byte[]{(byte) i});
                    int r = in.read();
                    check(r == (i & 0xFF), "ping-pong byte " + i);
                    worst = Math.max(worst, System.nanoTime() - s);
                }
                System.out.printf("  e2e: worst small-packet round trip %.1f ms%n", worst / 1e6);
            }
            join.close();
        }
    }

    private static void twoFriendsAtOnce(P2PConfig cfg) throws Exception {
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        try (EchoServer mc = new EchoServer(); P2PHost host = new P2PHost(mc.port(), cfg)) {
            host.start();
            P2PJoin a = new P2PJoin(host.offerCode(), cfg);
            P2PJoin b = new P2PJoin(host.offerCode(), cfg);
            a.start();
            b.start();
            waitFor(() -> a.state() == P2PJoin.State.WAITING_FOR_HOST && b.state() == P2PJoin.State.WAITING_FOR_HOST, 5000, "both joiners ready");
            check(!a.answerCode().equals(b.answerCode()), "each friend gets a distinct answer");
            host.acceptAnswer(a.answerCode());
            host.acceptAnswer(b.answerCode());
            waitFor(() -> a.state() == P2PJoin.State.READY && b.state() == P2PJoin.State.READY, 10_000, "both tunnels up");

            Future<byte[]> fa = pool.submit(() -> echo(a.localPort(), 1_500_000, 1));
            Future<byte[]> fb = pool.submit(() -> echo(b.localPort(), 1_500_000, 2));
            byte[] ra = fa.get(60, TimeUnit.SECONDS);
            byte[] rb = fb.get(60, TimeUnit.SECONDS);
            check(Arrays.equals(sha(randomBytes(1_500_000, 1)), sha(ra)), "friend A data intact");
            check(Arrays.equals(sha(randomBytes(1_500_000, 2)), sha(rb)), "friend B data intact (concurrent)");
            check(host.peers().size() == 2, "host tracks two friends");
            a.close();
            b.close();
        } finally {
            pool.shutdownNow();
        }
    }

    private static void wrongInviteRejected(P2PConfig cfg) throws Exception {
        try (EchoServer mc = new EchoServer(); P2PHost hostA = new P2PHost(mc.port(), cfg); P2PHost hostB = new P2PHost(mc.port(), cfg)) {
            hostA.start();
            hostB.start();
            P2PJoin join = new P2PJoin(hostA.offerCode(), cfg);
            join.start();
            waitFor(() -> join.state() == P2PJoin.State.WAITING_FOR_HOST, 5000, "joiner ready");
            expectInvite(() -> hostB.acceptAnswer(join.answerCode()), "answer made for host A is rejected by host B");
            expectInvite(() -> hostA.acceptAnswer("not a code"), "garbage answer rejected");
            expectInvite(() -> hostA.acceptAnswer(hostA.offerCode()), "invite pasted as answer rejected");
            hostA.acceptAnswer(join.answerCode());
            expectInvite(() -> hostA.acceptAnswer(join.answerCode()), "the same answer cannot be accepted twice");

            P2PJoin bogus = new P2PJoin("AAAAA-BBBBB", cfg);
            bogus.start();
            waitFor(() -> bogus.state() == P2PJoin.State.FAILED, 3000, "bogus invite fails the joiner");
            check(!bogus.detail().isEmpty(), "bogus invite gives a message: " + bogus.detail());
            join.close();
        }
    }

    private static void strangersCannotCreateSessions(P2PConfig cfg) throws Exception {
        try (EchoServer mc = new EchoServer(); P2PHost host = new P2PHost(mc.port(), cfg);
             DatagramSocket attacker = new DatagramSocket()) {
            host.start();
            Invite.Offer offer = Invite.decodeOffer(host.offerCode());
            InetSocketAddress target = offer.candidates().get(0);
            Random rnd = new Random(9);
            for (int i = 0; i < 300; i++) {
                byte[] junk = new byte[rnd.nextInt(60)];
                rnd.nextBytes(junk);
                if (i % 3 == 0 && junk.length > 0) {
                    junk[0] = ReliableChannel.T_PUNCH;
                }
                if (i % 3 == 1 && junk.length >= 15) { // right shape, wrong MAC
                    junk = new byte[15];
                    junk[0] = ReliableChannel.T_PUNCH;
                    junk[5] = PunchSession.ROLE_JOINER;
                    rnd.nextBytes(Arrays.copyOfRange(junk, 7, 15));
                }
                attacker.send(new DatagramPacket(junk, junk.length, target));
            }
            Thread.sleep(500);
            check(host.peers().isEmpty(), "300 junk/forged UDP packets created no session");

            // the host is still fully functional afterwards
            P2PJoin join = new P2PJoin(host.offerCode(), cfg);
            join.start();
            waitFor(() -> join.state() == P2PJoin.State.WAITING_FOR_HOST, 5000, "joiner ready after attack");
            host.acceptAnswer(join.answerCode());
            waitFor(() -> join.state() == P2PJoin.State.READY, 10_000, "legitimate friend still connects after the attack");
            join.close();
        }
    }

    private static void hostNeverAccepts(P2PConfig cfg) throws Exception {
        try (EchoServer mc = new EchoServer(); P2PHost host = new P2PHost(mc.port(), cfg)) {
            host.start();
            P2PJoin join = new P2PJoin(host.offerCode(), cfg);
            join.start();
            waitFor(() -> join.state() == P2PJoin.State.FAILED, 6000, "joiner gives up when the host never accepts");
            check(join.detail().contains("host"), "timeout message mentions the host: " + join.detail());
        }
    }

    private static void disconnectsPropagate(P2PConfig cfg) throws Exception {
        // game quits -> host side peer closes and the "Minecraft server" sees EOF
        try (EchoServer mc = new EchoServer(); P2PHost host = new P2PHost(mc.port(), cfg)) {
            host.start();
            P2PJoin join = new P2PJoin(host.offerCode(), cfg);
            join.start();
            waitFor(() -> join.state() == P2PJoin.State.WAITING_FOR_HOST, 5000, "joiner ready");
            P2PHost.Peer peer = host.acceptAnswer(join.answerCode());
            waitFor(() -> join.state() == P2PJoin.State.READY, 10_000, "tunnel up");
            Socket game = new Socket(InetAddress.getByAddress(LOCALHOST), join.localPort());
            game.getOutputStream().write(1);
            check(game.getInputStream().read() == 1, "echo works");
            waitFor(() -> mc.connections.get() == 1, 3000, "fake server saw one connection");
            game.close();
            waitFor(() -> peer.state() == P2PHost.PeerState.CLOSED, 5000, "host peer closed after the game quit");
            waitFor(() -> mc.closedConnections.get() == 1, 5000, "Minecraft server saw the connection close");
            join.close();
        }
        // server stops -> game socket sees EOF
        try (EchoServer mc = new EchoServer(); P2PHost host = new P2PHost(mc.port(), cfg)) {
            host.start();
            P2PJoin join = new P2PJoin(host.offerCode(), cfg);
            join.start();
            waitFor(() -> join.state() == P2PJoin.State.WAITING_FOR_HOST, 5000, "joiner ready");
            host.acceptAnswer(join.answerCode());
            waitFor(() -> join.state() == P2PJoin.State.READY, 10_000, "tunnel up");
            try (Socket game = new Socket(InetAddress.getByAddress(LOCALHOST), join.localPort())) {
                game.getOutputStream().write(1);
                check(game.getInputStream().read() == 1, "echo works");
                mc.dropAll();
                game.setSoTimeout(5000);
                check(game.getInputStream().read() == -1, "game sees EOF when the server side closes");
            }
            join.close();
        }
    }

    // ================================================================ fakes and helpers

    /** Echoes every byte back, like a trivially simple Minecraft server. */
    private static final class EchoServer implements AutoCloseable {
        final ServerSocket server;
        final AtomicInteger connections = new AtomicInteger();
        final AtomicInteger closedConnections = new AtomicInteger();
        final java.util.concurrent.CopyOnWriteArrayList<Socket> sockets = new java.util.concurrent.CopyOnWriteArrayList<>();

        EchoServer() throws IOException {
            server = new ServerSocket(0, 50, InetAddress.getByAddress(LOCALHOST));
            Thread.ofVirtual().start(() -> {
                try {
                    while (true) {
                        Socket s = server.accept();
                        sockets.add(s);
                        connections.incrementAndGet();
                        Thread.ofVirtual().start(() -> {
                            byte[] buf = new byte[8192];
                            try (s) {
                                int n;
                                while ((n = s.getInputStream().read(buf)) >= 0) {
                                    s.getOutputStream().write(buf, 0, n);
                                }
                            } catch (IOException ignored) {
                                // closed
                            } finally {
                                closedConnections.incrementAndGet();
                            }
                        });
                    }
                } catch (IOException ignored) {
                    // server closed
                }
            });
        }

        int port() {
            return server.getLocalPort();
        }

        void dropAll() {
            for (Socket s : sockets) {
                try {
                    s.close();
                } catch (IOException ignored) {
                    // closing anyway
                }
            }
        }

        @Override
        public void close() throws IOException {
            server.close();
            dropAll();
        }
    }

    private static final class FakeStun implements AutoCloseable {
        final DatagramSocket socket;

        FakeStun() throws IOException {
            socket = new DatagramSocket(0, InetAddress.getByAddress(LOCALHOST));
            Thread t = new Thread(() -> {
                byte[] buf = new byte[512];
                while (true) {
                    try {
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        socket.receive(p);
                        byte[] reply = Stun.bindingSuccess(Arrays.copyOfRange(buf, 8, 20), new InetSocketAddress(p.getAddress(), p.getPort()));
                        socket.send(new DatagramPacket(reply, reply.length, p.getSocketAddress()));
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
            socket.close();
        }
    }

    private static byte[] roundTrip(Socket s, byte[] data) throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> writer = pool.submit(() -> {
                s.getOutputStream().write(data);
                s.getOutputStream().flush();
                return null;
            });
            byte[] got = new byte[data.length];
            s.setSoTimeout(60_000);
            int pos = 0;
            while (pos < got.length) {
                int n = s.getInputStream().read(got, pos, got.length - pos);
                if (n < 0) {
                    throw new IOException("EOF at " + pos);
                }
                pos += n;
            }
            writer.get(10, TimeUnit.SECONDS);
            return got;
        } finally {
            pool.shutdownNow();
        }
    }

    private static byte[] echo(int port, int size, long seed) throws Exception {
        try (Socket s = new Socket(InetAddress.getByAddress(LOCALHOST), port)) {
            return roundTrip(s, randomBytes(size, seed));
        }
    }

    private static byte[] randomBytes(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }

    private static byte[] sha(byte[] b) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(b);
    }

    private static void waitFor(BooleanSupplier cond, long timeoutMillis, String what) throws InterruptedException {
        long end = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (System.nanoTime() < end) {
            if (cond.getAsBoolean()) {
                checks++;
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("FAIL (timeout): " + what);
    }

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

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            throw new AssertionError("FAIL: " + what);
        }
    }
}
