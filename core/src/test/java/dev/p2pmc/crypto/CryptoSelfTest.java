package dev.p2pmc.crypto;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Dependency-free self test for the protocol core (no JUnit, no Netty).
 * Run: javac -d out $(find tunnel-core/src/main tunnel-core/src/test -name '*.java') && java -cp out dev.p2pmc.crypto.CryptoSelfTest
 */
public final class CryptoSelfTest {
    private static final String TOKEN = "correct-horse-battery-staple-1234";
    private static final String OTHER = "wrong-horse-battery-staple-99999";
    private static final SecureRandom RNG = new SecureRandom();
    private static int checks;

    public static void main(String[] args) throws Exception {
        happyPath();
        wrongTokenIsRejectedByServer();
        tamperedServerFinishIsRejectedByClient();
        framingRoundTripAllSizes();
        tamperReplayReorderAndReflection();
        malformedInputOnlyEverThrowsHandshakeException();
        shortTokensRejected();
        throughput();
        System.out.println("ALL OK (" + checks + " checks)");
    }

    // ---------------------------------------------------------------- scenarios

    private static void happyPath() throws Exception {
        TunnelSession[] s = handshake(TOKEN, TOKEN);
        byte[] msg = "hello minecraft".getBytes();
        check(Arrays.equals(msg, open(s[1], seal(s[0], msg))), "client->server message");
        check(Arrays.equals(msg, open(s[0], seal(s[1], msg))), "server->client message");
    }

    private static void wrongTokenIsRejectedByServer() {
        ClientHandshake c = new ClientHandshake(TOKEN);
        ServerHandshake sv = new ServerHandshake(OTHER);
        byte[] finish = c.onServerHello(sv.onClientHello(c.start()));
        expect(HandshakeException.class, () -> sv.onClientFinish(finish), "server rejects wrong token");
    }

    private static void tamperedServerFinishIsRejectedByClient() {
        ClientHandshake c = new ClientHandshake(TOKEN);
        ServerHandshake sv = new ServerHandshake(TOKEN);
        byte[] serverFinish = sv.onClientFinish(c.onServerHello(sv.onClientHello(c.start())));
        serverFinish[5] ^= 1;
        expect(HandshakeException.class, () -> c.onServerFinish(serverFinish), "client rejects bad server proof");

        // a fake server that does not know the token cannot produce an acceptable proof
        ClientHandshake c2 = new ClientHandshake(TOKEN);
        ServerHandshake fake = new ServerHandshake(OTHER);
        byte[] cf = c2.onServerHello(fake.onClientHello(c2.start()));
        expect(HandshakeException.class, () -> fake.onClientFinish(cf), "fake server cannot complete");
        expect(HandshakeException.class, () -> c2.onServerFinish(new byte[32]), "client rejects forged finish");
    }

    private static void framingRoundTripAllSizes() throws Exception {
        TunnelSession[] s = handshake(TOKEN, TOKEN);
        int[] sizes = {0, 1, 2, 15, 16, 17, 255, 256, 1000, 4096, 16383, 16384};
        for (int size : sizes) {
            byte[] msg = new byte[size];
            RNG.nextBytes(msg);
            check(Arrays.equals(msg, open(s[1], seal(s[0], msg))), "round trip size " + size);
        }
        // direct buffers too
        byte[] msg = new byte[5000];
        RNG.nextBytes(msg);
        ByteBuffer direct = ByteBuffer.allocateDirect(FrameCipher.frameLength(msg.length));
        s[0].sendCipher().seal(ByteBuffer.wrap(msg), direct);
        direct.flip();
        int len = direct.getShort() & 0xFFFF;
        ByteBuffer plain = ByteBuffer.allocateDirect(len - 16);
        int n = s[1].receiveCipher().open(len, direct.slice(), plain);
        byte[] got = new byte[n];
        plain.flip();
        plain.get(got);
        check(Arrays.equals(msg, got), "direct buffer round trip");
        expect(IllegalArgumentException.class,
                () -> s[0].sendCipher().seal(ByteBuffer.wrap(new byte[TunnelProtocol.MAX_PLAINTEXT + 1]),
                        ByteBuffer.allocate(TunnelProtocol.MAX_PLAINTEXT + 64)),
                "oversized plaintext refused");
    }

    private static void tamperReplayReorderAndReflection() throws Exception {
        // tamper
        TunnelSession[] s = handshake(TOKEN, TOKEN);
        byte[] frame = seal(s[0], "payload".getBytes());
        frame[frame.length - 1] ^= 1;
        TunnelSession tamperRecv = s[1];
        expectGse(() -> open(tamperRecv, frame), "tampered ciphertext detected");

        // tampered length header (AAD) detected
        s = handshake(TOKEN, TOKEN);
        byte[] f2 = seal(s[0], new byte[100]);
        TunnelSession recv = s[1];
        f2[1] ^= 1; // flips the low length byte -> length mismatch / AAD mismatch
        expect(Exception.class, () -> open(recv, f2), "tampered length header rejected");

        // replay: same frame twice
        s = handshake(TOKEN, TOKEN);
        byte[] once = seal(s[0], "once".getBytes());
        open(s[1], once);
        TunnelSession recv2 = s[1];
        expectGse(() -> open(recv2, once), "replayed frame rejected");

        // reorder: frame 2 before frame 1
        s = handshake(TOKEN, TOKEN);
        byte[] first = seal(s[0], "first".getBytes());
        byte[] second = seal(s[0], "second".getBytes());
        TunnelSession recv3 = s[1];
        expectGse(() -> open(recv3, second), "reordered frame rejected");
        check(first.length > 0, "sanity");

        // reflection: a frame we sent must not open with our own receive cipher
        s = handshake(TOKEN, TOKEN);
        byte[] mine = seal(s[0], "reflect".getBytes());
        TunnelSession self = s[0];
        expectGse(() -> open(self, mine), "reflected frame rejected (directional keys)");

        // two handshakes never share keys
        TunnelSession[] a = handshake(TOKEN, TOKEN);
        TunnelSession[] b = handshake(TOKEN, TOKEN);
        byte[] fa = seal(a[0], "x".getBytes());
        TunnelSession bRecv = b[1];
        expectGse(() -> open(bRecv, fa), "sessions are independent");
    }

    private static void malformedInputOnlyEverThrowsHandshakeException() {
        ServerHandshake dummy = new ServerHandshake(TOKEN);
        int rejected = 0;
        for (int i = 0; i < 3000; i++) {
            byte[] junk = new byte[RNG.nextInt(3) == 0 ? TunnelProtocol.CLIENT_HELLO_LEN : RNG.nextInt(200)];
            RNG.nextBytes(junk);
            if (junk.length >= 5 && RNG.nextBoolean()) { // valid magic + version, random key
                System.arraycopy(TunnelProtocol.MAGIC, 0, junk, 0, 4);
                junk[4] = (byte) TunnelProtocol.VERSION;
            }
            try {
                new ServerHandshake(TOKEN).onClientHello(junk);
                // only acceptable if junk happened to be a valid X25519 key encoding (it will not)
                throw new AssertionError("junk accepted");
            } catch (HandshakeException expected) {
                rejected++;
            }
        }
        check(rejected == 3000, "3000 junk ClientHellos rejected cleanly");
        check(dummy != null, "sanity");

        ClientHandshake c = new ClientHandshake(TOKEN);
        c.start();
        byte[] junk = new byte[TunnelProtocol.SERVER_HELLO_LEN];
        RNG.nextBytes(junk);
        expect(HandshakeException.class, () -> c.onServerHello(junk), "junk ServerHello rejected");

        // a degenerate (all-zero) X25519 key must not yield a session
        ServerHandshake sv = new ServerHandshake(TOKEN);
        byte[] hello = new ClientHandshake(TOKEN).start();
        Arrays.fill(hello, TunnelProtocol.HEADER_LEN + 12, TunnelProtocol.HEADER_LEN + 44, (byte) 0); // u = 0 (low order)
        expect(HandshakeException.class, () -> sv.onClientHello(hello), "low-order public key rejected");

        // wrong version
        byte[] v2 = new ClientHandshake(TOKEN).start();
        v2[4] = 2;
        expect(HandshakeException.class, () -> new ServerHandshake(TOKEN).onClientHello(v2), "wrong version rejected");

        // out-of-order use is a programming error, not a handshake failure
        expect(IllegalStateException.class, () -> new ServerHandshake(TOKEN).onClientFinish(new byte[32]), "state machine enforced");
    }

    private static void shortTokensRejected() {
        expect(IllegalArgumentException.class, () -> new ClientHandshake("short"), "short client token refused");
        expect(IllegalArgumentException.class, () -> new ServerHandshake(""), "empty server token refused");
    }

    private static void throughput() throws Exception {
        TunnelSession[] s = handshake(TOKEN, TOKEN);
        byte[] chunk = new byte[TunnelProtocol.MAX_PLAINTEXT];
        RNG.nextBytes(chunk);
        ByteBuffer out = ByteBuffer.allocateDirect(FrameCipher.frameLength(chunk.length));
        ByteBuffer plain = ByteBuffer.allocateDirect(chunk.length);
        int frames = 20_000; // ~320 MB
        for (int warm = 0; warm < 2000; warm++) {
            roundTrip(s, chunk, out, plain);
        }
        long t0 = System.nanoTime();
        for (int i = 0; i < frames; i++) {
            roundTrip(s, chunk, out, plain);
        }
        double secs = (System.nanoTime() - t0) / 1e9;
        double mb = frames * (double) chunk.length / (1024 * 1024);
        System.out.printf("throughput: %.0f MB/s seal+open (16 KiB frames, 1 core)%n", mb / secs);
    }

    // ---------------------------------------------------------------- helpers

    private static void roundTrip(TunnelSession[] s, byte[] chunk, ByteBuffer out, ByteBuffer plain) throws Exception {
        out.clear();
        s[0].sendCipher().seal(ByteBuffer.wrap(chunk), out);
        out.flip();
        int len = out.getShort() & 0xFFFF;
        plain.clear();
        s[1].receiveCipher().open(len, out.slice(), plain);
    }

    /** @return {client session, server session} */
    private static TunnelSession[] handshake(String clientToken, String serverToken) {
        ClientHandshake c = new ClientHandshake(clientToken);
        ServerHandshake sv = new ServerHandshake(serverToken);
        byte[] clientHello = c.start();
        check(clientHello.length == TunnelProtocol.CLIENT_HELLO_LEN, "ClientHello length");
        byte[] serverHello = sv.onClientHello(clientHello);
        check(serverHello.length == TunnelProtocol.SERVER_HELLO_LEN, "ServerHello length");
        byte[] clientFinish = c.onServerHello(serverHello);
        byte[] serverFinish = sv.onClientFinish(clientFinish);
        TunnelSession client = c.onServerFinish(serverFinish);
        return new TunnelSession[]{client, sv.session()};
    }

    private static byte[] seal(TunnelSession s, byte[] plain) throws GeneralSecurityException {
        ByteBuffer out = ByteBuffer.allocate(FrameCipher.frameLength(plain.length));
        s.sendCipher().seal(ByteBuffer.wrap(plain), out);
        return out.array();
    }

    private static byte[] open(TunnelSession s, byte[] frame) throws GeneralSecurityException {
        int len = ((frame[0] & 0xFF) << 8) | (frame[1] & 0xFF);
        ByteBuffer body = ByteBuffer.wrap(frame, 2, frame.length - 2).slice();
        ByteBuffer out = ByteBuffer.allocate(Math.max(0, len - 16));
        int n = s.receiveCipher().open(len, body, out);
        return Arrays.copyOf(out.array(), n);
    }

    private interface Thrower {
        void run() throws Exception;
    }

    private static void expect(Class<? extends Throwable> type, Thrower t, String what) {
        checks++;
        try {
            t.run();
        } catch (Throwable e) {
            if (type.isInstance(e)) {
                return;
            }
            throw new AssertionError("FAIL: " + what + " (threw " + e + ")", e);
        }
        throw new AssertionError("FAIL: " + what + " (nothing thrown)");
    }

    private static void expectGse(Thrower t, String what) {
        expect(GeneralSecurityException.class, t, what);
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
