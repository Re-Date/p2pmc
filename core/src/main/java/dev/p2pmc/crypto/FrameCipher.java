package dev.p2pmc.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;

/**
 * One direction of the encrypted tunnel: ChaCha20-Poly1305 with a per-frame counter nonce.
 * Frame layout on the wire: {@code len(2, big endian) | ciphertext+tag}. The length is bound as AAD,
 * so truncation, reordering, replay and length tampering all fail authentication.
 * <p>
 * Not thread-safe: use from a single Netty event loop.
 */
public final class FrameCipher {
    private final SecretKeySpec key;
    private final Cipher cipher;
    private long counter;

    FrameCipher(byte[] keyBytes) {
        this.key = new SecretKeySpec(keyBytes, "ChaCha20");
        try {
            this.cipher = Cipher.getInstance("ChaCha20-Poly1305");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("ChaCha20-Poly1305 unavailable", e);
        }
    }

    /** Bytes written to the output for a plaintext of {@code plainLen} bytes. */
    public static int frameLength(int plainLen) {
        return TunnelProtocol.FRAME_HEADER_LEN + plainLen + TunnelProtocol.TAG_LEN;
    }

    /**
     * Encrypts {@code plain} (at most {@link TunnelProtocol#MAX_PLAINTEXT} bytes) into one frame.
     * Consumes {@code plain}; {@code out} needs {@link #frameLength(int)} bytes of room.
     */
    public void seal(ByteBuffer plain, ByteBuffer out) throws GeneralSecurityException {
        int plainLen = plain.remaining();
        if (plainLen > TunnelProtocol.MAX_PLAINTEXT) {
            throw new IllegalArgumentException("plaintext too large for one frame: " + plainLen);
        }
        if (out.remaining() < frameLength(plainLen)) {
            throw new IllegalArgumentException("output buffer too small");
        }
        int cipherLen = plainLen + TunnelProtocol.TAG_LEN;
        byte[] header = {(byte) (cipherLen >>> 8), (byte) cipherLen};
        out.put(header);
        cipher.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(nextNonce()));
        cipher.updateAAD(header);
        cipher.doFinal(plain, out);
    }

    /**
     * Decrypts one frame body (the bytes after the 2 byte length header).
     *
     * @param cipherLen the value of the length header
     * @return plaintext bytes written to {@code plainOut}
     * @throws GeneralSecurityException if authentication fails
     */
    public int open(int cipherLen, ByteBuffer cipherText, ByteBuffer plainOut) throws GeneralSecurityException {
        if (cipherLen < TunnelProtocol.TAG_LEN || cipherLen - TunnelProtocol.TAG_LEN > TunnelProtocol.MAX_PLAINTEXT) {
            throw new IllegalArgumentException("illegal frame length: " + cipherLen);
        }
        if (cipherText.remaining() != cipherLen) {
            throw new IllegalArgumentException("ciphertext size does not match frame length");
        }
        cipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(nextNonce()));
        cipher.updateAAD(new byte[]{(byte) (cipherLen >>> 8), (byte) cipherLen});
        return cipher.doFinal(cipherText, plainOut);
    }

    private byte[] nextNonce() {
        if (counter < 0) {
            throw new IllegalStateException("nonce counter exhausted");
        }
        byte[] nonce = new byte[12];
        long c = counter++;
        for (int i = 11; i >= 4; i--) {
            nonce[i] = (byte) c;
            c >>>= 8;
        }
        return nonce;
    }
}
