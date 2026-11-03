package dev.p2pmc.net;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** Minimal RFC 5389 STUN codec: Binding request and the (XOR-)MAPPED-ADDRESS of the response, IPv4 only. */
public final class Stun {
    public static final int MAGIC = 0x2112A442;
    private static final int BINDING_REQUEST = 0x0001;
    private static final int BINDING_SUCCESS = 0x0101;
    private static final int ATTR_MAPPED = 0x0001;
    private static final int ATTR_XOR_MAPPED = 0x0020;

    public record Response(byte[] txid, InetSocketAddress mapped) {
    }

    private Stun() {
    }

    public static byte[] bindingRequest(byte[] txid) {
        if (txid.length != 12) {
            throw new IllegalArgumentException("txid must be 12 bytes");
        }
        return ByteBuffer.allocate(20).putShort((short) BINDING_REQUEST).putShort((short) 0).putInt(MAGIC).put(txid).array();
    }

    /** Builds a Binding success response (used by the local test STUN server and nothing else). */
    public static byte[] bindingSuccess(byte[] txid, InetSocketAddress mapped) {
        byte[] ip = mapped.getAddress().getAddress();
        ByteBuffer b = ByteBuffer.allocate(20 + 12);
        b.putShort((short) BINDING_SUCCESS).putShort((short) 12).putInt(MAGIC).put(txid);
        b.putShort((short) ATTR_XOR_MAPPED).putShort((short) 8);
        b.put((byte) 0).put((byte) 1).putShort((short) (mapped.getPort() ^ (MAGIC >>> 16)));
        b.putInt(ByteBuffer.wrap(ip).getInt() ^ MAGIC);
        return b.array();
    }

    /** Cheap check used by the socket loop; the magic cookie keeps it from matching tunnel packets. */
    public static boolean looksLikeStun(byte[] b, int off, int len) {
        return len >= 20 && (b[off] & 0xC0) == 0 && ByteBuffer.wrap(b, off + 4, 4).getInt() == MAGIC;
    }

    /** @return the parsed response, or null if this is not a successful Binding response with an IPv4 address */
    public static Response parse(byte[] b, int off, int len) {
        if (!looksLikeStun(b, off, len)) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(b, off, len);
        int type = buf.getShort(off) & 0xFFFF;
        int msgLen = buf.getShort(off + 2) & 0xFFFF;
        if (type != BINDING_SUCCESS || 20 + msgLen > len) {
            return null;
        }
        byte[] txid = Arrays.copyOfRange(b, off + 8, off + 20);
        InetSocketAddress plain = null;
        int pos = off + 20;
        int end = off + 20 + msgLen;
        while (pos + 4 <= end) {
            int attrType = buf.getShort(pos) & 0xFFFF;
            int attrLen = buf.getShort(pos + 2) & 0xFFFF;
            int value = pos + 4;
            if (value + attrLen > end) {
                break;
            }
            if ((attrType == ATTR_XOR_MAPPED || attrType == ATTR_MAPPED) && attrLen >= 8 && b[value + 1] == 1) {
                int port = buf.getShort(value + 2) & 0xFFFF;
                int addr = buf.getInt(value + 4);
                if (attrType == ATTR_XOR_MAPPED) {
                    port ^= MAGIC >>> 16;
                    addr ^= MAGIC;
                }
                InetSocketAddress parsed = socketAddress(addr, port);
                if (parsed == null) {
                    return null;
                }
                if (attrType == ATTR_XOR_MAPPED) {
                    return new Response(txid, parsed);
                }
                plain = parsed;
            }
            pos = value + ((attrLen + 3) & ~3);
        }
        return plain == null ? null : new Response(txid, plain);
    }

    private static InetSocketAddress socketAddress(int addr, int port) {
        try {
            InetAddress ip = InetAddress.getByAddress(ByteBuffer.allocate(4).putInt(addr).array());
            return ip instanceof Inet4Address && port != 0 ? new InetSocketAddress(ip, port) : null;
        } catch (UnknownHostException e) {
            return null;
        }
    }
}
