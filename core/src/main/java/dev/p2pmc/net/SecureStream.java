package dev.p2pmc.net;

import dev.p2pmc.crypto.ClientHandshake;
import dev.p2pmc.crypto.FrameCipher;
import dev.p2pmc.crypto.HandshakeException;
import dev.p2pmc.crypto.ServerHandshake;
import dev.p2pmc.crypto.TunnelProtocol;
import dev.p2pmc.crypto.TunnelSession;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Authenticated encryption on top of a {@link ReliableChannel}: an X25519 + pre-shared-key handshake, then
 * ChaCha20-Poly1305 frames. One thread may write while another reads; neither side may be used by two threads.
 * <p>
 * The tunnel peer that opened the connection ("joiner") acts as handshake client, the host as server. Each failed
 * check throws and the caller closes the channel - nothing from an unauthenticated peer is ever processed.
 */
final class SecureStream implements Closeable {
    private final ReliableChannel channel;
    private final FrameCipher sendCipher;
    private final FrameCipher receiveCipher;

    private final ReentrantLock writeLock = new ReentrantLock();
    private final byte[] sealBuffer = new byte[FrameCipher.frameLength(TunnelProtocol.MAX_PLAINTEXT)];
    private final byte[] header = new byte[TunnelProtocol.FRAME_HEADER_LEN];
    private final byte[] frame = new byte[TunnelProtocol.MAX_PLAINTEXT + TunnelProtocol.TAG_LEN];
    private final byte[] plain = new byte[TunnelProtocol.MAX_PLAINTEXT];
    private int plainPos;
    private int plainLen;

    private SecureStream(ReliableChannel channel, TunnelSession session) {
        this.channel = channel;
        this.sendCipher = session.sendCipher();
        this.receiveCipher = session.receiveCipher();
    }

    static SecureStream connect(ReliableChannel channel, String psk, int timeoutMillis) throws IOException {
        channel.setReadTimeout(timeoutMillis);
        try {
            ClientHandshake handshake = new ClientHandshake(psk);
            write(channel, handshake.start());
            byte[] serverHello = new byte[TunnelProtocol.SERVER_HELLO_LEN];
            readFully(channel, serverHello);
            write(channel, handshake.onServerHello(serverHello));
            byte[] serverFinish = new byte[TunnelProtocol.SERVER_FINISH_LEN];
            readFully(channel, serverFinish);
            TunnelSession session = handshake.onServerFinish(serverFinish);
            return new SecureStream(channel, session);
        } catch (HandshakeException e) {
            throw new IOException("Handshake failed: " + e.getMessage(), e);
        } finally {
            channel.setReadTimeout(0);
        }
    }

    static SecureStream accept(ReliableChannel channel, String psk, int timeoutMillis) throws IOException {
        channel.setReadTimeout(timeoutMillis);
        try {
            ServerHandshake handshake = new ServerHandshake(psk);
            byte[] clientHello = new byte[TunnelProtocol.CLIENT_HELLO_LEN];
            readFully(channel, clientHello);
            write(channel, handshake.onClientHello(clientHello));
            byte[] clientFinish = new byte[TunnelProtocol.CLIENT_FINISH_LEN];
            readFully(channel, clientFinish);
            write(channel, handshake.onClientFinish(clientFinish));
            return new SecureStream(channel, handshake.session());
        } catch (HandshakeException e) {
            throw new IOException("Handshake failed: " + e.getMessage(), e);
        } finally {
            channel.setReadTimeout(0);
        }
    }

    /** Encrypts and sends {@code len} bytes, splitting them into frames of at most 16 KiB. */
    void write(byte[] b, int off, int len) throws IOException {
        writeLock.lock();
        try {
            while (len > 0) {
                int n = Math.min(len, TunnelProtocol.MAX_PLAINTEXT);
                ByteBuffer out = ByteBuffer.wrap(sealBuffer);
                sendCipher.seal(ByteBuffer.wrap(b, off, n), out);
                channel.write(sealBuffer, 0, FrameCipher.frameLength(n));
                off += n;
                len -= n;
            }
        } catch (GeneralSecurityException e) {
            throw new IOException("encryption failed", e);
        } finally {
            writeLock.unlock();
        }
    }

    /** @return bytes read (at least 1), or -1 if the peer closed cleanly between frames */
    int read(byte[] b, int off, int len) throws IOException {
        while (plainPos == plainLen) {
            if (!readFrame()) {
                return -1;
            }
        }
        int n = Math.min(len, plainLen - plainPos);
        System.arraycopy(plain, plainPos, b, off, n);
        plainPos += n;
        return n;
    }

    private boolean readFrame() throws IOException {
        int first = readHeaderByte();
        if (first < 0) {
            return false;
        }
        header[0] = (byte) first;
        readFully(channel, header, 1, 1);
        int cipherLen = ((header[0] & 0xFF) << 8) | (header[1] & 0xFF);
        if (cipherLen < TunnelProtocol.TAG_LEN || cipherLen > frame.length) {
            throw new IOException("corrupt frame length " + cipherLen);
        }
        readFully(channel, frame, 0, cipherLen);
        try {
            plainLen = receiveCipher.open(cipherLen, ByteBuffer.wrap(frame, 0, cipherLen), ByteBuffer.wrap(plain));
            plainPos = 0;
        } catch (GeneralSecurityException e) {
            throw new IOException("tunnel authentication failed (corrupted or forged data)", e);
        }
        return true;
    }

    private int readHeaderByte() throws IOException {
        byte[] one = new byte[1];
        int n = channel.read(one, 0, 1);
        return n < 0 ? -1 : one[0] & 0xFF;
    }

    long rttMillis() {
        return channel.rttMillis();
    }

    @Override
    public void close() {
        channel.close();
    }

    private static void write(ReliableChannel channel, byte[] data) throws IOException {
        channel.write(data, 0, data.length);
    }

    private static void readFully(ReliableChannel channel, byte[] dst) throws IOException {
        readFully(channel, dst, 0, dst.length);
    }

    private static void readFully(ReliableChannel channel, byte[] dst, int off, int len) throws IOException {
        int done = 0;
        while (done < len) {
            int n = channel.read(dst, off + done, len - done);
            if (n < 0) {
                throw new EOFException("connection closed by peer");
            }
            done += n;
        }
    }
}
