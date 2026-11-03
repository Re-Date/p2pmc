package dev.p2pmc.crypto;

/** Thrown when the tunnel handshake fails (malformed message, wrong token, protocol mismatch). */
public final class HandshakeException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public HandshakeException(String message) {
        super(message);
    }

    public HandshakeException(String message, Throwable cause) {
        super(message, cause);
    }
}
