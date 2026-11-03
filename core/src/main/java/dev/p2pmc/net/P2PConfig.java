package dev.p2pmc.net;

import java.util.List;

/**
 * @param stunServers        public STUN servers ("host:port") used to learn the public UDP endpoint
 * @param hostPunchMillis    how long the host keeps trying after accepting an answer
 * @param joinerWaitMillis   how long a joiner keeps probing while waiting for the host to accept its answer
 * @param handshakeMillis    time allowed for the encrypted handshake once the NAT hole is open
 * @param stunTimeoutMillis  time allowed for STUN discovery
 */
public record P2PConfig(List<String> stunServers, int hostPunchMillis, int joinerWaitMillis, int handshakeMillis,
                        int stunTimeoutMillis) {

    public static P2PConfig defaults() {
        return new P2PConfig(
                List.of("stun.l.google.com:19302", "stun1.l.google.com:19302", "stun.cloudflare.com:3478"),
                30_000, 180_000, 8_000, 2_500);
    }

    public P2PConfig withStunServers(List<String> servers) {
        return new P2PConfig(List.copyOf(servers), hostPunchMillis, joinerWaitMillis, handshakeMillis, stunTimeoutMillis);
    }
}
