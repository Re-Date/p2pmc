package dev.p2pmc.net;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Discovers this socket's public UDP endpoint through public STUN servers. The queries go out of the very same
 * socket that later carries the tunnel, otherwise the NAT would show a different mapping.
 * <p>
 * Asking two servers also reveals an endpoint-dependent ("symmetric") NAT: it hands out a new public port per
 * destination, so the port the peer is told about is wrong and hole punching will usually fail.
 */
public final class StunResolver {
    private static final System.Logger LOG = System.getLogger("p2pmc");
    private static final SecureRandom RANDOM = new SecureRandom();

    /** @param mapped distinct public endpoints seen (empty if no STUN server answered) */
    public record Discovery(List<InetSocketAddress> mapped, boolean symmetric) {
    }

    private record Query(String name, InetSocketAddress server, byte[] txid, CompletableFuture<InetSocketAddress> result) {
    }

    private final UdpEndpoint endpoint;
    private final ConcurrentHashMap<String, CompletableFuture<InetSocketAddress>> pending = new ConcurrentHashMap<>();

    StunResolver(UdpEndpoint endpoint) {
        this.endpoint = endpoint;
    }

    void onDatagram(byte[] buf, int off, int len) {
        Stun.Response response = Stun.parse(buf, off, len);
        if (response != null) {
            CompletableFuture<InetSocketAddress> f = pending.get(HexFormat.of().formatHex(response.txid()));
            if (f != null) {
                f.complete(response.mapped());
            }
        }
    }

    /**
     * Blocking; call from a background thread. Queries every server in parallel, retransmitting twice.
     *
     * @param servers "host:port" strings
     */
    public Discovery discover(List<String> servers, long timeoutMillis) {
        List<Query> queries = new ArrayList<>();
        for (String s : servers) {
            InetSocketAddress addr = resolve(s);
            if (addr != null) {
                byte[] txid = new byte[12];
                RANDOM.nextBytes(txid);
                CompletableFuture<InetSocketAddress> f = new CompletableFuture<>();
                pending.put(HexFormat.of().formatHex(txid), f);
                queries.add(new Query(s, addr, txid, f));
            }
        }
        try {
            long end = System.nanoTime() + timeoutMillis * 1_000_000L;
            long[] waits = {400, 800, Long.MAX_VALUE};
            for (int attempt = 0; attempt < waits.length && !allDone(queries); attempt++) {
                for (Query q : queries) {
                    if (!q.result().isDone()) {
                        endpoint.sendQuiet(q.server(), Stun.bindingRequest(q.txid()));
                    }
                }
                long until = waits[attempt] == Long.MAX_VALUE ? end : Math.min(end, System.nanoTime() + waits[attempt] * 1_000_000L);
                while (System.nanoTime() < until && !allDone(queries)) {
                    Thread.sleep(15);
                }
                if (System.nanoTime() >= end) {
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            for (Query q : queries) {
                pending.remove(HexFormat.of().formatHex(q.txid()));
            }
        }

        Set<InetSocketAddress> distinct = new LinkedHashSet<>();
        Set<Integer> ports = new LinkedHashSet<>();
        int answered = 0;
        for (Query q : queries) {
            if (q.result().isDone() && !q.result().isCompletedExceptionally()) {
                InetSocketAddress mapped = q.result().join();
                distinct.add(mapped);
                ports.add(mapped.getPort());
                answered++;
            }
        }
        boolean symmetric = answered >= 2 && ports.size() > 1;
        if (answered == 0) {
            LOG.log(System.Logger.Level.WARNING, "No STUN server answered ({0} tried)", queries.size());
        }
        return new Discovery(List.copyOf(distinct), symmetric);
    }

    private static boolean allDone(List<Query> queries) {
        for (Query q : queries) {
            if (!q.result().isDone()) {
                return false;
            }
        }
        return true;
    }

    private static InetSocketAddress resolve(String hostPort) {
        try {
            int colon = hostPort.lastIndexOf(':');
            String host = colon < 0 ? hostPort : hostPort.substring(0, colon);
            int port = colon < 0 ? 3478 : Integer.parseInt(hostPort.substring(colon + 1).trim());
            for (InetAddress a : InetAddress.getAllByName(host.trim())) {
                if (a instanceof Inet4Address) {
                    return new InetSocketAddress(a, port);
                }
            }
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.DEBUG, "STUN server {0} not usable: {1}", hostPort, e.toString());
        }
        return null;
    }
}
