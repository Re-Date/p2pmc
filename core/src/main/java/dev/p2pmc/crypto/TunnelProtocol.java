package dev.p2pmc.crypto;

/**
 * Wire constants for MC Tunnel protocol v1.
 *
 * <pre>
 * C -> S  ClientHello  = "MCTN" | ver(1) | clientPub(44, X.509 X25519) | clientNonce(16)      (65 bytes)
 * S -> C  ServerHello  = "MCTN" | ver(1) | serverPub(44)               | serverNonce(16)      (65 bytes)
 * C -> S  ClientFinish = HMAC(confirmKey, 'C' | transcriptHash)                                (32 bytes)
 * S -> C  ServerFinish = HMAC(confirmKey, 'S' | transcriptHash | clientProof)                  (32 bytes)
 * then both directions: frames  = len(2, big endian) | ChaCha20-Poly1305(ciphertext + 16 byte tag)
 * </pre>
 *
 * Keys: HKDF-Expand(HMAC(pskKey, X25519 secret), "mctunnel/v1" | SHA256(ClientHello | ServerHello)).
 * The client proves knowledge of the token first, so an unauthenticated peer never receives
 * anything derived from the token.
 */
public final class TunnelProtocol {
    private TunnelProtocol() {
    }

    static final byte[] MAGIC = {'M', 'C', 'T', 'N'};
    public static final int VERSION = 1;

    public static final int MAGIC_LEN = 4;
    public static final int HEADER_LEN = MAGIC_LEN + 1;
    /** X.509 encoding of an X25519 public key. */
    public static final int PUBLIC_KEY_LEN = 44;
    public static final int NONCE_LEN = 16;
    public static final int PROOF_LEN = 32;

    public static final int CLIENT_HELLO_LEN = HEADER_LEN + PUBLIC_KEY_LEN + NONCE_LEN;
    public static final int SERVER_HELLO_LEN = CLIENT_HELLO_LEN;
    public static final int CLIENT_FINISH_LEN = PROOF_LEN;
    public static final int SERVER_FINISH_LEN = PROOF_LEN;

    public static final int FRAME_HEADER_LEN = 2;
    public static final int TAG_LEN = 16;
    /** Largest plaintext carried by one frame. Larger writes are split. */
    public static final int MAX_PLAINTEXT = 16 * 1024;

    /** Minimum token length; tokens are the only secret, so they must be high entropy. */
    public static final int MIN_TOKEN_LEN = 16;

    /** True if the first {@link #MAGIC_LEN} bytes of {@code head} are the protocol magic. */
    public static boolean hasMagic(byte[] head) {
        if (head.length < MAGIC_LEN) {
            return false;
        }
        for (int i = 0; i < MAGIC_LEN; i++) {
            if (head[i] != MAGIC[i]) {
                return false;
            }
        }
        return true;
    }
}
