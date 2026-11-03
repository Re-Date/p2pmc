package dev.p2pmc.net;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One UDP socket shared by STUN, NAT punching and every peer channel. A single socket is essential: the NAT
 * mapping that STUN reports and that the peer punches towards belongs to this exact local port.
 */
public final class UdpEndpoint implements Closeable {
    private static final System.Logger LOG = System.getLogger("p2pmc");

    @FunctionalInterface
    public interface Handler {
        /** Called on the receive thread. {@code buf} is reused afterwards: copy anything you keep. */
        void onPacket(InetSocketAddress from, byte[] buf, int off, int len);
    }

    private final DatagramSocket socket;
    private final ConcurrentHashMap<InetSocketAddress, Handler> peers = new ConcurrentHashMap<>();
    private final StunResolver stun = new StunResolver(this);
    private volatile Handler unknown = (from, buf, off, len) -> {
    };
    private volatile boolean closed;

    /** @param port local UDP port, 0 for any */
    public UdpEndpoint(int port) throws IOException {
        socket = new DatagramSocket(null);
        try {
            socket.setReceiveBufferSize(1 << 20);
            socket.setSendBufferSize(1 << 20);
        } catch (SocketException ignored) {
            // best effort: defaults are fine for Minecraft traffic
        }
        socket.bind(new InetSocketAddress(InetAddress.getByAddress(new byte[4]), port));
        Thread rx = new Thread(this::receiveLoop, "p2pmc-udp-rx");
        rx.setDaemon(true);
        rx.start();
    }

    public int localPort() {
        return socket.getLocalPort();
    }

    public StunResolver stun() {
        return stun;
    }

    /** Receives packets from addresses without a registered peer handler (incoming NAT punches). */
    public void setUnknownHandler(Handler handler) {
        this.unknown = handler;
    }

    void register(InetSocketAddress peer, Handler handler) {
        peers.put(peer, handler);
    }

    void unregister(InetSocketAddress peer) {
        peers.remove(peer);
    }

    public void send(InetSocketAddress to, byte[] buf, int off, int len) throws IOException {
        socket.send(new DatagramPacket(buf, off, len, to));
    }

    void sendQuiet(InetSocketAddress to, byte[] data) {
        try {
            send(to, data, 0, data.length);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "udp send to {0} failed: {1}", to, e.toString());
        }
    }

    /** Local LAN address of the default route (no packet is sent), or null. Lets friends on the same network connect. */
    public static Inet4Address lanAddress() {
        try (DatagramSocket probe = new DatagramSocket()) {
            probe.connect(InetAddress.getByAddress(new byte[]{(byte) 192, 0, 2, 1}), 9); // TEST-NET-1: routing lookup only
            if (probe.getLocalAddress() instanceof Inet4Address v4 && !v4.isAnyLocalAddress() && !v4.isLoopbackAddress()) {
                return v4;
            }
        } catch (IOException | RuntimeException ignored) {
            // no route: no LAN candidate
        }
        return null;
    }

    /** The LAN candidate for this endpoint, as a list of zero or one addresses. */
    public List<InetSocketAddress> lanCandidates() {
        List<InetSocketAddress> list = new ArrayList<>(1);
        Inet4Address lan = lanAddress();
        if (lan != null) {
            list.add(new InetSocketAddress(lan, localPort()));
        }
        return list;
    }

    private void receiveLoop() {
        byte[] buf = new byte[2048];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        while (!closed) {
            try {
                packet.setLength(buf.length);
                socket.receive(packet);
            } catch (IOException e) {
                if (closed) {
                    return;
                }
                // Windows reports ICMP "port unreachable" as an exception on unconnected UDP sockets: keep going.
                continue;
            }
            try {
                InetSocketAddress from = (InetSocketAddress) packet.getSocketAddress();
                int len = packet.getLength();
                if (Stun.looksLikeStun(buf, 0, len)) {
                    stun.onDatagram(buf, 0, len);
                    continue;
                }
                Handler handler = peers.get(from);
                (handler != null ? handler : unknown).onPacket(from, buf, 0, len);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "packet handler failed", e);
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        socket.close();
    }
}
