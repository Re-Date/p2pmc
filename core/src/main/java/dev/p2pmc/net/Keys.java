package dev.p2pmc.net;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;

/** All values derived from the invite token. Each use has its own label so outputs are never interchangeable. */
final class Keys {
    private Keys() {
    }

    static byte[] hmac(byte[] key, String label, byte[]... parts) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update(label.getBytes(StandardCharsets.US_ASCII));
            for (byte[] p : parts) {
                mac.update(p);
            }
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 4 byte filter tag stamped on every data packet: drops stray traffic cheaply (not a security boundary). */
    static byte[] tag(byte[] token, byte[] sessionId) {
        return Arrays.copyOf(hmac(token, "mcp2p/tag", sessionId), 4);
    }

    static byte[] punchMac(byte[] token, byte[] sessionId, int role, boolean ack) {
        return Arrays.copyOf(hmac(token, "mcp2p/punch", sessionId, new byte[]{(byte) role, (byte) (ack ? 1 : 0)}), 8);
    }

    static byte[] answerMac(byte[] token, byte[] body) {
        return Arrays.copyOf(hmac(token, "mcp2p/answer", body), 8);
    }

    /** Pre-shared key for the encrypted stream handshake; unique per joiner session. */
    static String psk(byte[] token, byte[] sessionId) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(token, "mcp2p/psk", sessionId));
    }
}
