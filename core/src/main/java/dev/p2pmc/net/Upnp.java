package dev.p2pmc.net;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal UPnP IGD client (pure JDK): asks the home router to forward a UDP port to this PC, so friends can reach the
 * host directly even behind a strict NAT. Everything is best effort: no router, UPnP disabled or an error simply means
 * "no mapping"; nothing here throws to the caller. Only devices on the local network are ever contacted.
 */
public final class Upnp {
    static final InetSocketAddress SSDP = new InetSocketAddress("239.255.255.250", 1900);
    private static final String ST = "urn:schemas-upnp-org:device:InternetGatewayDevice:1";
    private static final Pattern SERVICE = Pattern.compile("<service>(.*?)</service>", Pattern.DOTALL);
    private static final System.Logger LOG = System.getLogger("p2pmc");

    private final URI control;
    private final String serviceType;

    private Upnp(URI control, String serviceType) {
        this.control = control;
        this.serviceType = serviceType;
    }

    /** Looks for a router for up to {@code timeoutMillis}; null if none answered. Blocking. */
    public static Upnp discover(int timeoutMillis) {
        return discover(SSDP, timeoutMillis, true);
    }

    static Upnp discover(InetSocketAddress target, int timeoutMillis, boolean allInterfaces) {
        try (MulticastSocket socket = new MulticastSocket(null)) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(0));
            socket.setTimeToLive(2);
            byte[] request = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\nST: " + ST + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII);
            if (allInterfaces) {
                for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    try {
                        if (!ni.isUp() || ni.isLoopback() || !ni.supportsMulticast() || !hasIpv4(ni)) {
                            continue;
                        }
                        socket.setNetworkInterface(ni);
                        socket.send(new DatagramPacket(request, request.length, target));
                    } catch (IOException ignored) {
                        // this adapter cannot multicast; try the others
                    }
                }
            } else {
                socket.send(new DatagramPacket(request, request.length, target));
            }
            long end = System.nanoTime() + timeoutMillis * 1_000_000L;
            byte[] buf = new byte[2048];
            while (true) {
                long left = (end - System.nanoTime()) / 1_000_000L;
                if (left <= 0) {
                    return null;
                }
                socket.setSoTimeout((int) left);
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(p);
                } catch (IOException timeout) {
                    return null;
                }
                String text = new String(p.getData(), 0, p.getLength(), StandardCharsets.US_ASCII);
                String location = header(text, "LOCATION");
                if (location == null) {
                    continue;
                }
                Upnp found = fromDescription(URI.create(location.trim()), p.getAddress());
                if (found != null) {
                    return found;
                }
            }
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.DEBUG, "UPnP discovery failed: {0}", e.toString());
            return null;
        }
    }

    private static boolean hasIpv4(NetworkInterface ni) {
        for (InetAddress a : Collections.list(ni.getInetAddresses())) {
            if (a instanceof Inet4Address) {
                return true;
            }
        }
        return false;
    }

    static String header(String response, String name) {
        for (String line : response.split("\r?\n")) {
            int c = line.indexOf(':');
            if (c > 0 && line.substring(0, c).trim().equalsIgnoreCase(name)) {
                return line.substring(c + 1).trim();
            }
        }
        return null;
    }

    /** Reads the router's description and finds its WAN connection service. {@code responder} must match the URL's host. */
    static Upnp fromDescription(URI location, InetAddress responder) {
        try {
            if (!isLocalHttp(location) || !InetAddress.getByName(location.getHost()).equals(responder)) {
                return null; // a device must only describe itself, and only on the local network
            }
            String xml = http(location, "GET", null, null, null).body();
            Matcher m = SERVICE.matcher(xml);
            while (m.find()) {
                String service = m.group(1);
                String type = tag(service, "serviceType");
                String path = tag(service, "controlURL");
                if (type != null && path != null && (type.contains(":WANIPConnection:") || type.contains(":WANPPPConnection:"))) {
                    URI control = location.resolve(path.trim());
                    if (isLocalHttp(control) && control.getHost().equalsIgnoreCase(location.getHost())) {
                        return new Upnp(control, type.trim());
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.DEBUG, "UPnP description failed: {0}", e.toString());
        }
        return null;
    }

    private static boolean isLocalHttp(URI u) throws IOException {
        if (u.getHost() == null || !"http".equalsIgnoreCase(u.getScheme())) {
            return false;
        }
        InetAddress a = InetAddress.getByName(u.getHost());
        return a.isSiteLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress()
                || (a.getAddress().length == 4 && (a.getAddress()[0] & 0xff) == 100 && (a.getAddress()[1] & 0xc0) == 64);
    }

    /** The router's public IPv4 address, or null. */
    public String externalIp() {
        try {
            Reply r = soap("GetExternalIPAddress", "");
            return r.status() / 100 == 2 ? tag(r.body(), "NewExternalIPAddress") : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Forwards {@code externalPort} (UDP) on the router to {@code client:internalPort}. Retries with a permanent lease
     * if the router refuses leases, and with other external ports if the port is taken.
     *
     * @return the external port that was mapped, or -1
     */
    public int mapUdp(int externalPort, int internalPort, String client, String description, int leaseSeconds) {
        int[] tries = {externalPort, 0, 0, 0};
        java.util.Random rnd = new java.util.Random();
        for (int i = 1; i < tries.length; i++) {
            tries[i] = 20000 + rnd.nextInt(40000);
        }
        for (int port : tries) {
            for (int lease : new int[]{leaseSeconds, 0}) {
                try {
                    Reply r = soap("AddPortMapping", "<NewRemoteHost></NewRemoteHost><NewExternalPort>" + port + "</NewExternalPort>"
                            + "<NewProtocol>UDP</NewProtocol><NewInternalPort>" + internalPort + "</NewInternalPort>"
                            + "<NewInternalClient>" + client + "</NewInternalClient><NewEnabled>1</NewEnabled>"
                            + "<NewPortMappingDescription>" + description + "</NewPortMappingDescription>"
                            + "<NewLeaseDuration>" + lease + "</NewLeaseDuration>");
                    if (r.status() / 100 == 2) {
                        return port;
                    }
                    String code = tag(r.body(), "errorCode");
                    if (!"725".equals(code)) { // 725: only permanent leases supported, retry with 0
                        break; // e.g. 718 conflict: next port
                    }
                } catch (IOException | RuntimeException e) {
                    return -1;
                }
            }
        }
        return -1;
    }

    public void unmapUdp(int externalPort) {
        try {
            soap("DeletePortMapping", "<NewRemoteHost></NewRemoteHost><NewExternalPort>" + externalPort
                    + "</NewExternalPort><NewProtocol>UDP</NewProtocol>");
        } catch (IOException | RuntimeException ignored) {
            // the lease expires on its own
        }
    }

    // ------------------------------------------------------------------ plumbing

    private record Reply(int status, String body) {
    }

    private Reply soap(String action, String args) throws IOException {
        String body = "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:" + action + " xmlns:u=\"" + serviceType + "\">"
                + args + "</u:" + action + "></s:Body></s:Envelope>";
        return http(control, "POST", body, "text/xml; charset=\"utf-8\"", "\"" + serviceType + "#" + action + "\"");
    }

    private static Reply http(URI uri, String method, String body, String contentType, String soapAction) throws IOException {
        HttpURLConnection c = (HttpURLConnection) uri.toURL().openConnection(java.net.Proxy.NO_PROXY);
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(2000);
            c.setReadTimeout(3000);
            c.setInstanceFollowRedirects(false);
            if (body != null) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(bytes.length);
                c.setRequestProperty("Content-Type", contentType);
                c.setRequestProperty("SOAPAction", soapAction);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(bytes);
                }
            }
            int status = c.getResponseCode();
            InputStream in = status / 100 == 2 ? c.getInputStream() : c.getErrorStream();
            String text = in == null ? "" : new String(in.readNBytes(256 * 1024), StandardCharsets.UTF_8);
            return new Reply(status, text);
        } finally {
            c.disconnect();
        }
    }

    static String tag(String xml, String name) {
        Matcher m = Pattern.compile("<" + Pattern.quote(name) + "[^>]*>(.*?)</" + Pattern.quote(name) + ">", Pattern.DOTALL | Pattern.CASE_INSENSITIVE).matcher(xml);
        return m.find() ? m.group(1).trim() : null;
    }

    /** True for an IPv4 text that is a public (internet) address, i.e. not private, loopback, link-local or CGNAT. */
    public static boolean isPublicIpv4(String ip) {
        if (ip == null || !ip.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            return false;
        }
        try {
            InetAddress a = InetAddress.getByName(ip);
            byte[] b = a.getAddress();
            return !(a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
                    || a.isMulticastAddress() || ((b[0] & 0xff) == 100 && (b[1] & 0xc0) == 64));
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public String toString() {
        return "Upnp[" + control.getHost().toLowerCase(Locale.ROOT) + "]";
    }
}
