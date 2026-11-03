package dev.p2pmc.crypto;

/** Established tunnel: one cipher per direction, already oriented for the local role. */
public final class TunnelSession {
    public enum Role {CLIENT, SERVER}

    private final FrameCipher sendCipher;
    private final FrameCipher receiveCipher;

    TunnelSession(Role role, KeySchedule keys) {
        if (role == Role.CLIENT) {
            this.sendCipher = new FrameCipher(keys.clientToServerKey);
            this.receiveCipher = new FrameCipher(keys.serverToClientKey);
        } else {
            this.sendCipher = new FrameCipher(keys.serverToClientKey);
            this.receiveCipher = new FrameCipher(keys.clientToServerKey);
        }
    }

    public FrameCipher sendCipher() {
        return sendCipher;
    }

    public FrameCipher receiveCipher() {
        return receiveCipher;
    }
}
