package dev.p2pmc.crypto;

import java.security.KeyPair;
import java.util.Arrays;

/**
 * Server side of the handshake as a pure byte[] state machine (no I/O, no Netty).
 * Call order: {@link #onClientHello(byte[])}, {@link #onClientFinish(byte[])}, {@link #session()}.
 * Every failure is a {@link HandshakeException}; the caller should close the connection silently.
 */
public final class ServerHandshake {
    private enum State {AWAIT_CLIENT_HELLO, AWAIT_CLIENT_FINISH, DONE}

    private final byte[] pskKey;
    private State state = State.AWAIT_CLIENT_HELLO;
    private KeySchedule keys;
    private TunnelSession session;

    public ServerHandshake(String token) {
        if (token == null || token.length() < TunnelProtocol.MIN_TOKEN_LEN) {
            throw new IllegalArgumentException("token must be at least " + TunnelProtocol.MIN_TOKEN_LEN + " characters");
        }
        this.pskKey = TunnelCrypto.pskKey(token);
    }

    /** @return the ServerHello bytes to send. */
    public byte[] onClientHello(byte[] clientHello) {
        require(State.AWAIT_CLIENT_HELLO);
        if (clientHello.length != TunnelProtocol.CLIENT_HELLO_LEN || !TunnelProtocol.hasMagic(clientHello)) {
            throw new HandshakeException("bad hello");
        }
        if ((clientHello[TunnelProtocol.MAGIC_LEN] & 0xFF) != TunnelProtocol.VERSION) {
            throw new HandshakeException("unsupported protocol version");
        }
        byte[] clientPublic = Arrays.copyOfRange(clientHello, TunnelProtocol.HEADER_LEN,
                TunnelProtocol.HEADER_LEN + TunnelProtocol.PUBLIC_KEY_LEN);

        KeyPair keyPair = TunnelCrypto.generateKeyPair();
        byte[] serverPublic = keyPair.getPublic().getEncoded();
        if (serverPublic.length != TunnelProtocol.PUBLIC_KEY_LEN) {
            throw new IllegalStateException("unexpected public key encoding length " + serverPublic.length);
        }
        byte[] serverHello = TunnelCrypto.concat(
                TunnelProtocol.MAGIC,
                new byte[]{(byte) TunnelProtocol.VERSION},
                serverPublic,
                TunnelCrypto.randomBytes(TunnelProtocol.NONCE_LEN));

        byte[] shared = TunnelCrypto.agree(keyPair.getPrivate(), clientPublic);
        keys = KeySchedule.derive(pskKey, shared, clientHello, serverHello);
        state = State.AWAIT_CLIENT_FINISH;
        return serverHello;
    }

    /**
     * Verifies the client's proof of token knowledge.
     *
     * @return the ServerFinish bytes to send (in plaintext) before switching to encrypted frames
     */
    public byte[] onClientFinish(byte[] clientFinish) {
        require(State.AWAIT_CLIENT_FINISH);
        if (clientFinish.length != TunnelProtocol.CLIENT_FINISH_LEN
                || !TunnelCrypto.equal(clientFinish, keys.clientProof())) {
            throw new HandshakeException("client authentication failed");
        }
        session = new TunnelSession(TunnelSession.Role.SERVER, keys);
        state = State.DONE;
        return keys.serverProof(clientFinish);
    }

    public TunnelSession session() {
        if (state != State.DONE) {
            throw new IllegalStateException("handshake not complete");
        }
        return session;
    }

    private void require(State expected) {
        if (state != expected) {
            throw new IllegalStateException("handshake in state " + state + ", expected " + expected);
        }
    }
}
