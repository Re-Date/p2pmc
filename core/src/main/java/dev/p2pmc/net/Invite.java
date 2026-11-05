package dev.p2pmc.net;

import java.io.ByteArrayOutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Short copy/paste codes that replace a signalling server.
 * <ul>
 *   <li><b>Offer</b> (host -> friend): secret token + the host's UDP endpoints. Same code for every friend.</li>
 *   <li><b>Answer</b> (friend -> host): random session id + the friend's UDP endpoints, authenticated with the
 *       token so the host can reject codes made for a different invite.</li>
 * </ul>
 * Codes are Crockford base32 (no 0/O/1/I/L confusion), shown in groups of five characters.
 */
public final class Invite {
    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final int KIND_OFFER = 1;
    private static final int KIND_ANSWER = 2;
    private static final int VERSION = 1;
    private static final int MAX_CANDIDATES = 4;

    public static final int TOKEN_LEN = 16;
    public static final int SESSION_ID_LEN = 8;

    public record Offer(byte[] token, List<InetSocketAddress> candidates) {
    }

    public record Answer(byte[] sessionId, List<InetSocketAddress> candidates) {
    }

    private Invite() {
    }

    // ------------------------------------------------------------------ offer

    public static String encodeOffer(byte[] token, List<InetSocketAddress> candidates) {
        if (token.length != TOKEN_LEN) {
            throw new IllegalArgumentException("token must be " + TOKEN_LEN + " bytes");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(header(KIND_OFFER));
        out.writeBytes(token);
        writeCandidates(out, candidates);
        out.writeBytes(Arrays.copyOf(sha256(out.toByteArray()), 2));
        return group(base32(out.toByteArray()));
    }

    public static Offer decodeOffer(String text) throws InviteException {
        byte[] raw = decode(text, KIND_OFFER, "invite");
        if (raw.length < 1 + TOKEN_LEN + 1 + 2) {
            throw new InviteException(Messages.t("inv.tooShort"));
        }
        byte[] body = Arrays.copyOf(raw, raw.length - 2);
        byte[] crc = Arrays.copyOfRange(raw, raw.length - 2, raw.length);
        if (!MessageDigest.isEqual(crc, Arrays.copyOf(sha256(body), 2))) {
            throw new InviteException(Messages.t("inv.checksum"));
        }
        byte[] token = Arrays.copyOfRange(body, 1, 1 + TOKEN_LEN);
        return new Offer(token, readCandidates(body, 1 + TOKEN_LEN));
    }

    // ----------------------------------------------------------------- answer

    public static String encodeAnswer(byte[] token, byte[] sessionId, List<InetSocketAddress> candidates) {
        if (sessionId.length != SESSION_ID_LEN) {
            throw new IllegalArgumentException("sessionId must be " + SESSION_ID_LEN + " bytes");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(header(KIND_ANSWER));
        out.writeBytes(sessionId);
        writeCandidates(out, candidates);
        out.writeBytes(Keys.answerMac(token, out.toByteArray()));
        return group(base32(out.toByteArray()));
    }

    public static Answer decodeAnswer(byte[] token, String text) throws InviteException {
        byte[] raw = decode(text, KIND_ANSWER, "answer");
        if (raw.length < 1 + SESSION_ID_LEN + 1 + 8) {
            throw new InviteException(Messages.t("ans.tooShort"));
        }
        byte[] body = Arrays.copyOf(raw, raw.length - 8);
        byte[] mac = Arrays.copyOfRange(raw, raw.length - 8, raw.length);
        if (!MessageDigest.isEqual(mac, Keys.answerMac(token, body))) {
            throw new InviteException(Messages.t("ans.mismatch"));
        }
        byte[] sessionId = Arrays.copyOfRange(body, 1, 1 + SESSION_ID_LEN);
        return new Answer(sessionId, readCandidates(body, 1 + SESSION_ID_LEN));
    }

    // ---------------------------------------------------------------- helpers

    private static byte[] decode(String text, int expectedKind, String what) throws InviteException {
        byte[] raw = unbase32(text);
        if (raw.length == 0) {
            throw new InviteException(Messages.t("invite".equals(what) ? "code.pasteInvite" : "code.pasteAnswer"));
        }
        int kind = (raw[0] >> 4) & 0xF;
        int version = raw[0] & 0xF;
        if (kind != KIND_OFFER && kind != KIND_ANSWER) {
            throw new InviteException(Messages.t("code.notP2P"));
        }
        if (kind != expectedKind) {
            throw new InviteException(kind == KIND_ANSWER
                    ? Messages.t("code.isAnswer")
                    : Messages.t("code.isInvite"));
        }
        if (version != VERSION) {
            throw new InviteException(Messages.t("code.incompatible"));
        }
        return raw;
    }

    private static byte header(int kind) {
        return (byte) ((kind << 4) | VERSION);
    }

    private static void writeCandidates(ByteArrayOutputStream out, List<InetSocketAddress> candidates) {
        List<InetSocketAddress> usable = new ArrayList<>();
        for (InetSocketAddress a : candidates) {
            if (a.getAddress() instanceof Inet4Address && usable.size() < MAX_CANDIDATES && !usable.contains(a)) {
                usable.add(a);
            }
        }
        out.write(usable.size());
        for (InetSocketAddress a : usable) {
            out.writeBytes(a.getAddress().getAddress());
            out.write(a.getPort() >>> 8);
            out.write(a.getPort());
        }
    }

    private static List<InetSocketAddress> readCandidates(byte[] body, int pos) throws InviteException {
        int n = body[pos++] & 0xFF;
        if (n > MAX_CANDIDATES || body.length != pos + n * 6) {
            throw new InviteException(Messages.t("code.damaged"));
        }
        List<InetSocketAddress> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            try {
                byte[] ip = Arrays.copyOfRange(body, pos, pos + 4);
                int port = ((body[pos + 4] & 0xFF) << 8) | (body[pos + 5] & 0xFF);
                if (port == 0) {
                    throw new InviteException(Messages.t("code.damaged"));
                }
                list.add(new InetSocketAddress(InetAddress.getByAddress(ip), port));
            } catch (UnknownHostException e) {
                throw new InviteException(Messages.t("code.damaged"));
            }
            pos += 6;
        }
        return list;
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------- Crockford base32

    static String base32(byte[] data) {
        StringBuilder sb = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                sb.append(ALPHABET.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
            buffer &= (1 << bits) - 1;
        }
        if (bits > 0) {
            sb.append(ALPHABET.charAt((buffer << (5 - bits)) & 31));
        }
        return sb.toString();
    }

    /** Tolerant decoder: ignores whitespace, dashes and case; maps O->0 and I/L->1 like Crockford specifies. */
    static byte[] unbase32(String text) throws InviteException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char raw : text.toUpperCase(Locale.ROOT).toCharArray()) {
            if (Character.isWhitespace(raw) || raw == '-' || raw == '_') {
                continue;
            }
            char c = raw == 'O' ? '0' : (raw == 'I' || raw == 'L') ? '1' : raw;
            int v = ALPHABET.indexOf(c);
            if (v < 0) {
                throw new InviteException(Messages.t("code.badChar", raw));
            }
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xFF);
                bits -= 8;
                buffer &= (1 << bits) - 1;
            }
        }
        return out.toByteArray();
    }

    private static String group(String s) {
        StringBuilder sb = new StringBuilder(s.length() + s.length() / 5);
        for (int i = 0; i < s.length(); i++) {
            if (i > 0 && i % 5 == 0) {
                sb.append('-');
            }
            sb.append(s.charAt(i));
        }
        return sb.toString();
    }
}
