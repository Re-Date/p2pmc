package dev.p2pmc.crypto;

import java.security.KeyPair;
import java.util.Arrays;

/**
 * Client side of the handshake as a pure byte[] state machine (no I/O, no Netty).
 * Call order: {@link #start()}, {@link #onServerHello(byte[])}, {@link #onServerFinish(byte[])}.
 */
public final class ClientHandshake {
    private enum State {NEW, AWAIT_SERVER_HELLO, AWAIT_SERVER_FINISH, DONE}

    private final byte[] pskKey;
    private State state = State.NEW;
    private KeyPair keyPair;
    private byte[] clientHello;
    private byte[] clientProof;
    private KeySchedule keys;

    public ClientHandshake(String token) {
        if (token == null || token.length() < TunnelProtocol.MIN_TOKEN_LEN) {
            throw new IllegalArgumentException("token must be at least " + TunnelProtocol.MIN_TOKEN_LEN + " characters");
        }
        this.pskKey = TunnelCrypto.pskKey(token);
    }

    /** @return the ClientHello bytes to send. */
    public byte[] start() {
        require(State.NEW);
        keyPair = TunnelCrypto.generateKeyPair();
        byte[] publicKey = keyPair.getPublic().getEncoded();
        if (publicKey.length != TunnelProtocol.PUBLIC_KEY_LEN) {
            throw new IllegalStateException("unexpected public key encoding length " + publicKey.length);
        }
        clientHello = TunnelCrypto.concat(
                TunnelProtocol.MAGIC,
                new byte[]{(byte) TunnelProtocol.VERSION},
                publicKey,
                TunnelCrypto.randomBytes(TunnelProtocol.NONCE_LEN));
        state = State.AWAIT_SERVER_HELLO;
        return clientHello;
    }

    /** @return the ClientFinish bytes (the client's proof of token knowledge) to send. */
    public byte[] onServerHello(byte[] serverHello) {
        require(State.AWAIT_SERVER_HELLO);
        checkHello(serverHello, TunnelProtocol.SERVER_HELLO_LEN);
        byte[] serverPublic = Arrays.copyOfRange(serverHello, TunnelProtocol.HEADER_LEN,
                TunnelProtocol.HEADER_LEN + TunnelProtocol.PUBLIC_KEY_LEN);
        byte[] shared = TunnelCrypto.agree(keyPair.getPrivate(), serverPublic);
        keys = KeySchedule.derive(pskKey, shared, clientHello, serverHello);
        clientProof = keys.clientProof();
        keyPair = null;
        state = State.AWAIT_SERVER_FINISH;
        return clientProof;
    }

    /** Verifies the server's proof; on success the tunnel is established. */
    public TunnelSession onServerFinish(byte[] serverFinish) {
        require(State.AWAIT_SERVER_FINISH);
        if (serverFinish.length != TunnelProtocol.SERVER_FINISH_LEN
                || !TunnelCrypto.equal(serverFinish, keys.serverProof(clientProof))) {
            throw new HandshakeException("server authentication failed");
        }
        state = State.DONE;
        return new TunnelSession(TunnelSession.Role.CLIENT, keys);
    }

    private void require(State expected) {
        if (state != expected) {
            throw new IllegalStateException("handshake in state " + state + ", expected " + expected);
        }
    }

    private static void checkHello(byte[] hello, int expectedLength) {
        if (hello.length != expectedLength || !TunnelProtocol.hasMagic(hello)) {
            throw new HandshakeException("not a tunnel server");
        }
        if ((hello[TunnelProtocol.MAGIC_LEN] & 0xFF) != TunnelProtocol.VERSION) {
            throw new HandshakeException("unsupported tunnel protocol version " + (hello[TunnelProtocol.MAGIC_LEN] & 0xFF));
        }
    }
}
