package dev.p2pmc.net;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** Dependency-free tests for the Discord webhook (local HTTP server, no network). Run: java -cp out dev.p2pmc.net.DiscordSelfTest */
public final class DiscordSelfTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        urlValidation();
        rendering();
        jsonEscaping();
        delivery();
        System.out.println("DISCORD OK (" + checks + " checks)");
        System.exit(0);
    }

    private static void urlValidation() {
        check(DiscordWebhook.isDiscordUrl("https://discord.com/api/webhooks/123456789/abcDEF_-token"), "discord.com webhook accepted");
        check(DiscordWebhook.isDiscordUrl("https://canary.discord.com/api/webhooks/1/x1234567"), "canary subdomain accepted");
        check(DiscordWebhook.isDiscordUrl("https://discordapp.com/api/webhooks/1/x1234567"), "discordapp.com accepted");
        check(!DiscordWebhook.isDiscordUrl("http://discord.com/api/webhooks/123456789/abc"), "plain http rejected");
        check(!DiscordWebhook.isDiscordUrl("https://evil.example/api/webhooks/123456789/abc"), "other host rejected");
        check(!DiscordWebhook.isDiscordUrl("https://discord.com.evil.example/api/webhooks/123456789/abc"), "look-alike host rejected");
        check(!DiscordWebhook.isDiscordUrl("https://user:pw@discord.com/api/webhooks/123456789/abc"), "credentials in url rejected");
        check(!DiscordWebhook.isDiscordUrl("https://discord.com/channels/1/2"), "non-webhook path rejected");
        check(!DiscordWebhook.isDiscordUrl(""), "empty rejected");
        check(!DiscordWebhook.isDiscordUrl(null), "null rejected");
        check(!DiscordWebhook.isDiscordUrl("not a url at all"), "garbage rejected");
    }

    private static void rendering() {
        DiscordWebhook.Settings s = new DiscordWebhook.Settings(true, "", "Bot", "Dima's world", "", "{name}: {code} / {name}", false, "{name} off");
        check(DiscordWebhook.render(s.startMessage(), s, "ABC-123").equals("Dima's world: ABC-123 / Dima's world"), "placeholders replaced everywhere");
        String def = DiscordWebhook.render(null, s, "CODE");
        check(def.contains("CODE") && def.contains("Dima's world") && def.contains("P2P: Join"), "default message used for a blank template");
        check(DiscordWebhook.render("{name}", DiscordWebhook.Settings.disabled(), "x").equals("Minecraft server"), "blank server name has a fallback");
        check(DiscordWebhook.render("a{code}b", s, null).equals("ab"), "null code is empty");
    }

    private static void jsonEscaping() {
        String nasty = "q\" b\\ n\n r\r t\t u\u0001 eé cя emoji😀";
        String json = DiscordWebhook.jsonString(nasty);
        check(unescape(json).equals(nasty), "JSON string round-trips quotes, backslashes, control chars and unicode: " + json);
        check(!json.contains("\n") && !json.contains("\r") && !json.contains("\t"), "no raw control characters in JSON");

        String withPing = DiscordWebhook.payload("Bot", "@everyone hi", true);
        check(withPing.contains("\"parse\":[\"everyone\",\"roles\",\"users\"]"), "pings allowed only when a mention is configured");
        String noPing = DiscordWebhook.payload("", "```CODE```", false);
        check(noPing.contains("\"parse\":[]") && !noPing.contains("username"), "no pings and no username by default");
        String longText = DiscordWebhook.payload("Bot", "x".repeat(5000), false);
        check(longText.length() < 2200 && longText.contains("...\""), "content is cut to Discord's 2000 character limit");
    }

    private static void delivery() throws Exception {
        List<String> bodies = new CopyOnWriteArrayList<>();
        List<String> contentTypes = new CopyOnWriteArrayList<>();
        AtomicInteger hits = new AtomicInteger();
        HttpServer ok = server(bodies, contentTypes, hits, 204, 0);
        try {
            URI uri = URI.create("http://127.0.0.1:" + ok.getAddress().getPort() + "/api/webhooks/1/abc");
            String json = DiscordWebhook.payload("Bot", "hello ```CODE```", false);
            check(DiscordWebhook.post(uri, json) == 204, "HTTP 204 returned");
            check(bodies.size() == 1 && bodies.get(0).equals(json), "exact JSON body delivered");
            check(contentTypes.get(0).startsWith("application/json"), "content type is JSON: " + contentTypes.get(0));
        } finally {
            ok.stop(0);
        }

        // rate limited once, then fine: one retry
        List<String> b2 = new CopyOnWriteArrayList<>();
        AtomicInteger hits2 = new AtomicInteger();
        HttpServer limited = server(b2, new CopyOnWriteArrayList<>(), hits2, 204, 1);
        try {
            URI uri = URI.create("http://127.0.0.1:" + limited.getAddress().getPort() + "/api/webhooks/1/abc");
            check(DiscordWebhook.post(uri, "{}") == 204, "HTTP 429 is retried once and then succeeds");
            check(hits2.get() == 2, "exactly two requests were made: " + hits2.get());
        } finally {
            limited.stop(0);
        }

        // a failing server and an unreachable one never throw
        HttpServer broken = server(new CopyOnWriteArrayList<>(), new CopyOnWriteArrayList<>(), new AtomicInteger(), 500, 0);
        int brokenPort = broken.getAddress().getPort();
        try {
            check(DiscordWebhook.post(URI.create("http://127.0.0.1:" + brokenPort + "/x"), "{}") == 500, "HTTP 500 is reported, not thrown");
        } finally {
            broken.stop(0);
        }
        check(DiscordWebhook.post(URI.create("http://127.0.0.1:" + brokenPort + "/x"), "{}") == -1, "unreachable server returns -1, not an exception");

        // the public API never throws on bad input and never sends to a non-discord URL
        DiscordWebhook.announceStart(null, "CODE");
        DiscordWebhook.announceStart(DiscordWebhook.Settings.disabled(), "CODE");
        DiscordWebhook.announceStart(new DiscordWebhook.Settings(true, "http://127.0.0.1:" + brokenPort + "/api/webhooks/1/abc",
                "", "", "", "", true, ""), "CODE");
        DiscordWebhook.announceStop(new DiscordWebhook.Settings(true, "", "", "", "", "", true, ""), true);
        check(true, "announce* tolerate null, disabled, blank and non-discord settings");
    }

    // ------------------------------------------------------------------ helpers

    private static HttpServer server(List<String> bodies, List<String> types, AtomicInteger hits, int status, int rateLimitFirst) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0), 0);
        s.createContext("/", ex -> {
            int n = hits.incrementAndGet();
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            types.add(ex.getRequestHeaders().getFirst("Content-Type"));
            if (n <= rateLimitFirst) {
                ex.getResponseHeaders().add("Retry-After", "1");
                ex.sendResponseHeaders(429, -1);
            } else {
                ex.sendResponseHeaders(status, -1);
            }
            ex.close();
        });
        s.start();
        return s;
    }

    /** Minimal decoder for one JSON string literal, enough to prove the escaping is reversible. */
    private static String unescape(String lit) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < lit.length() - 1; i++) {
            char c = lit.charAt(i);
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            char n = lit.charAt(++i);
            switch (n) {
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    sb.append((char) Integer.parseInt(lit.substring(i + 1, i + 5), 16));
                    i += 4;
                }
                default -> sb.append(n);
            }
        }
        return sb.toString();
    }

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            throw new AssertionError("FAIL: " + what);
        }
    }
}
