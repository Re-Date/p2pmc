package dev.p2pmc.net;

import com.sun.net.httpserver.HttpServer;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Tests UPnP against a fake router on loopback (SSDP over UDP + description + SOAP over HTTP). Run: java -cp out dev.p2pmc.net.UpnpSelfTest */
public final class UpnpSelfTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        InetAddress lo = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        List<String> calls = new CopyOnWriteArrayList<>();
        String[] mode = {"ok"}; // ok | permanent-only | conflict-first

        HttpServer http = HttpServer.create(new InetSocketAddress(lo, 0), 0);
        int port = http.getAddress().getPort();
        http.createContext("/desc.xml", ex -> {
            String xml = "<root><device><serviceList><service><serviceType>urn:schemas-upnp-org:service:Layer3Forwarding:1</serviceType><controlURL>/l3</controlURL></service>"
                    + "<service><serviceType>urn:schemas-upnp-org:service:WANIPConnection:1</serviceType><controlURL>/ctl</controlURL></service></serviceList></device></root>";
            reply(ex, 200, xml);
        });
        http.createContext("/ctl", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String action = ex.getRequestHeaders().getFirst("SOAPAction");
            calls.add(action + " " + body);
            if (action.endsWith("#GetExternalIPAddress\"")) {
                reply(ex, 200, "<NewExternalIPAddress>203.0.113.9</NewExternalIPAddress>");
            } else if (action.endsWith("#AddPortMapping\"")) {
                boolean lease0 = body.contains("<NewLeaseDuration>0<");
                if (mode[0].equals("permanent-only") && !lease0) {
                    reply(ex, 500, "<errorCode>725</errorCode>");
                } else if (mode[0].equals("conflict-first") && body.contains("<NewExternalPort>4000<")) {
                    reply(ex, 500, "<errorCode>718</errorCode>");
                } else {
                    reply(ex, 200, "<ok/>");
                }
            } else {
                reply(ex, 200, "<ok/>");
            }
        });
        http.start();

        // fake SSDP responder
        DatagramSocket ssdp = new DatagramSocket(new InetSocketAddress(lo, 0));
        Thread t = Thread.ofVirtual().start(() -> {
            try {
                byte[] b = new byte[1024];
                DatagramPacket p = new DatagramPacket(b, b.length);
                ssdp.receive(p);
                String r = "HTTP/1.1 200 OK\r\nST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\nLOCATION: http://127.0.0.1:" + port + "/desc.xml\r\n\r\n";
                byte[] rb = r.getBytes(StandardCharsets.US_ASCII);
                ssdp.send(new DatagramPacket(rb, rb.length, p.getSocketAddress()));
            } catch (Exception ignored) {
                // test ends
            }
        });

        Upnp up = Upnp.discover(new InetSocketAddress(lo, ssdp.getLocalPort()), 3000, false);
        check(up != null, "router found via SSDP + description, WANIPConnection chosen over Layer3Forwarding");
        check("203.0.113.9".equals(up.externalIp()), "external IP read");
        check(up.mapUdp(4000, 4000, "192.168.1.5", "P2P Minecraft", 3600) == 4000, "port mapped");
        String add = calls.stream().filter(c -> c.contains("#AddPortMapping")).findFirst().orElse("");
        check(add.contains("<NewProtocol>UDP<") && add.contains("<NewInternalClient>192.168.1.5<") && add.contains("<NewLeaseDuration>3600<"), "AddPortMapping arguments: " + add);

        mode[0] = "permanent-only";
        check(up.mapUdp(4001, 4001, "192.168.1.5", "x", 3600) == 4001, "router that only allows permanent leases: retried with lease 0");
        mode[0] = "conflict-first";
        int other = up.mapUdp(4000, 4000, "192.168.1.5", "x", 3600);
        check(other > 0 && other != 4000, "port conflict (718): another external port used: " + other);
        up.unmapUdp(4000);
        check(calls.stream().anyMatch(c -> c.contains("#DeletePortMapping") && c.contains("<NewExternalPort>4000<")), "mapping removed");

        check(Upnp.fromDescription(URI.create("http://127.0.0.1:" + port + "/desc.xml"), InetAddress.getByName("10.9.9.9")) == null,
                "description from a host other than the one that answered is rejected");
        check(Upnp.fromDescription(URI.create("http://8.8.8.8/desc.xml"), InetAddress.getByName("8.8.8.8")) == null,
                "a public address is never contacted");
        check(Upnp.fromDescription(URI.create("https://127.0.0.1:" + port + "/desc.xml"), lo) == null, "https/other schemes rejected");
        check(Upnp.isPublicIpv4("203.0.113.9") && !Upnp.isPublicIpv4("192.168.0.1") && !Upnp.isPublicIpv4("100.64.1.1")
                && !Upnp.isPublicIpv4("10.1.1.1") && !Upnp.isPublicIpv4("garbage") && !Upnp.isPublicIpv4(null), "public address test (CGNAT 100.64/10 is not public)");

        ssdp.close();
        check(Upnp.discover(new InetSocketAddress(lo, 9), 300, false) == null, "no router: null, no exception");
        http.stop(0);
        System.out.println("UPNP OK (" + checks + " checks)");
        System.exit(0);
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws java.io.IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            throw new AssertionError("FAIL: " + what);
        }
    }
}
