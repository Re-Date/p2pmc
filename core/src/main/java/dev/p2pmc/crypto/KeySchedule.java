package dev.p2pmc.crypto;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Key derivation shared by both handshake roles. */
final class KeySchedule {
    private static final byte[] LABEL = "mctunnel/v1".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ROLE_CLIENT = {'C'};
    private static final byte[] ROLE_SERVER = {'S'};

    final byte[] clientToServerKey;
    final byte[] serverToClientKey;
    private final byte[] confirmKey;
    private final byte[] transcriptHash;

    private KeySchedule(byte[] okm, byte[] transcriptHash) {
        this.clientToServerKey = Arrays.copyOfRange(okm, 0, 32);
        this.serverToClientKey = Arrays.copyOfRange(okm, 32, 64);
        this.confirmKey = Arrays.copyOfRange(okm, 64, 96);
        this.transcriptHash = transcriptHash;
    }

    static KeySchedule derive(byte[] pskKey, byte[] sharedSecret, byte[] clientHello, byte[] serverHello) {
        byte[] transcriptHash = TunnelCrypto.sha256(clientHello, serverHello);
        byte[] prk = TunnelCrypto.hmac(pskKey, sharedSecret);
        byte[] okm = TunnelCrypto.hkdfExpand(prk, TunnelCrypto.concat(LABEL, transcriptHash), 96);
        return new KeySchedule(okm, transcriptHash);
    }

    byte[] clientProof() {
        return TunnelCrypto.hmac(confirmKey, ROLE_CLIENT, transcriptHash);
    }

    byte[] serverProof(byte[] clientProof) {
        return TunnelCrypto.hmac(confirmKey, ROLE_SERVER, transcriptHash, clientProof);
    }
}
