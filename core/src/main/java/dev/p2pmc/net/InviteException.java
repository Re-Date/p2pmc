package dev.p2pmc.net;

/** A pasted invite/answer code is malformed or does not belong to this session. The message is user-facing. */
public final class InviteException extends Exception {
    private static final long serialVersionUID = 1L;

    public InviteException(String message) {
        super(message);
    }
}
