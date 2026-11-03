package dev.p2pmc.net;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Copies bytes both ways between a local TCP socket (Minecraft, on loopback) and the encrypted P2P stream.
 * Blocks until either side ends, then closes both. Backpressure works end to end: when the peer is slow the
 * channel's send queue fills and {@code SecureStream.write} blocks, which stops reading from the TCP socket.
 */
final class Bridge {
    private static final int BUFFER = 16 * 1024;

    private Bridge() {
    }

    static void run(Socket tcp, SecureStream tunnel) {
        AtomicBoolean closed = new AtomicBoolean();
        Runnable closeAll = () -> {
            if (closed.compareAndSet(false, true)) {
                try {
                    tcp.close();
                } catch (IOException ignored) {
                    // already gone
                }
                tunnel.close();
            }
        };

        Thread up = Thread.ofVirtual().name("p2p-up").start(() -> {
            byte[] buf = new byte[BUFFER];
            try {
                InputStream in = tcp.getInputStream();
                int n;
                while ((n = in.read(buf)) >= 0) {
                    tunnel.write(buf, 0, n);
                }
            } catch (IOException ignored) {
                // either side ended: fall through to cleanup
            } finally {
                closeAll.run();
            }
        });

        byte[] buf = new byte[BUFFER];
        try {
            OutputStream out = tcp.getOutputStream();
            int n;
            while ((n = tunnel.read(buf, 0, buf.length)) >= 0) {
                out.write(buf, 0, n);
            }
        } catch (IOException ignored) {
            // either side ended: fall through to cleanup
        } finally {
            closeAll.run();
        }
        try {
            up.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
