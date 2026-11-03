package dev.p2pmc.crypto;

import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;

/** JDK-only crypto primitives used by the handshake. */
final class TunnelCrypto {
    private static final SecureRandom RANDOM = new SecureRandom();

    private TunnelCrypto() {
    }

    static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }

    static byte[] sha256(byte[]... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (byte[] p : parts) {
                md.update(p);
            }
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] hmac(byte[] key, byte[]... parts) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            for (byte[] p : parts) {
                mac.update(p);
            }
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** HKDF-Expand (RFC 5869) with HMAC-SHA256. */
    static byte[] hkdfExpand(byte[] prk, byte[] info, int length) {
        if (length <= 0 || length > 255 * 32) {
            throw new IllegalArgumentException("bad HKDF length");
        }
        byte[] out = new byte[length];
        byte[] t = new byte[0];
        int pos = 0;
        for (int counter = 1; pos < length; counter++) {
            t = hmac(prk, t, info, new byte[]{(byte) counter});
            int n = Math.min(t.length, length - pos);
            System.arraycopy(t, 0, out, pos, n);
            pos += n;
        }
        return out;
    }

    /** Normalises a token of arbitrary length into a 32 byte key. */
    static byte[] pskKey(String token) {
        return hmac("mctunnel/v1/psk".getBytes(StandardCharsets.US_ASCII), token.getBytes(StandardCharsets.UTF_8));
    }

    static KeyPair generateKeyPair() {
        try {
            return KeyPairGenerator.getInstance("X25519").generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("X25519 unavailable", e);
        }
    }

    /** X25519 agreement. Rejects malformed public keys and the all-zero (low-order point) result. */
    static byte[] agree(PrivateKey privateKey, byte[] peerPublicEncoded) {
        try {
            PublicKey peer = KeyFactory.getInstance("X25519").generatePublic(new X509EncodedKeySpec(peerPublicEncoded));
            KeyAgreement ka = KeyAgreement.getInstance("X25519");
            ka.init(privateKey);
            ka.doPhase(peer, true);
            byte[] secret = ka.generateSecret();
            int acc = 0;
            for (byte b : secret) {
                acc |= b;
            }
            if (acc == 0) {
                throw new HandshakeException("degenerate key agreement result");
            }
            return secret;
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new HandshakeException("invalid peer public key", e);
        }
    }

    static boolean equal(byte[] a, byte[] b) {
        return MessageDigest.isEqual(a, b);
    }

    static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] p : parts) {
            total += p.length;
        }
        byte[] out = new byte[total];
        int pos = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return out;
    }
}
