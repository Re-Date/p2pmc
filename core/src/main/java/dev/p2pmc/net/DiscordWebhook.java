package dev.p2pmc.net;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Posts "the server is online, here is the invite code" (and optionally "offline") to a Discord webhook.
 * <p>
 * Pure java.base (HttpURLConnection: works inside modular mod loaders too), never blocks the caller (unless asked to), never throws and never logs the webhook URL: it is a secret.
 * Only https URLs on discord.com / discordapp.com are accepted, so the invite code cannot be sent to another host by a
 * typo in the config.
 */
public final class DiscordWebhook {
    private static final System.Logger LOG = System.getLogger("p2pmc");
    private static final int MAX_CONTENT = 2000;

    /**
     * @param webhookUrl   {@code https://discord.com/api/webhooks/<id>/<token>}
     * @param username     name shown as the sender (blank: the webhook's own name)
     * @param serverName   fills {@code {name}} in the messages
     * @param mention      text put in front of the start message, e.g. {@code @everyone} or {@code <@&ROLE_ID>}; blank: no ping
     * @param startMessage template with {@code {name}} and {@code {code}}
     * @param stopMessage  template with {@code {name}}
     */
    public record Settings(boolean enabled, String webhookUrl, String username, String serverName, String mention,
                           String startMessage, boolean notifyStop, String stopMessage) {
        public static final String DEFAULT_START =
                "{name} is online! Open Minecraft, click \"P2P: Join\" on the title screen and paste this invite code:\n```{code}```";
        public static final String DEFAULT_STOP = "{name} is now offline.";

        public static Settings disabled() {
            return new Settings(false, "", "", "", "", DEFAULT_START, false, DEFAULT_STOP);
        }
    }

    private DiscordWebhook() {
    }

    /** True for an https webhook URL on Discord's own domains. */
    public static boolean isDiscordUrl(String url) {
        try {
            URI u = new URI(url == null ? "" : url.trim());
            String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
            boolean hostOk = host.equals("discord.com") || host.equals("discordapp.com")
                    || host.endsWith(".discord.com") || host.endsWith(".discordapp.com");
            return "https".equalsIgnoreCase(u.getScheme()) && hostOk && u.getUserInfo() == null
                    && u.getPath() != null && u.getPath().startsWith("/api/webhooks/") && u.getPath().length() > 16;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /** Announces that hosting started and shares the invite code. Asynchronous. */
    public static void announceStart(Settings s, String code) {
        if (s == null || !s.enabled()) {
            return;
        }
        String text = render(s.startMessage(), s, code);
        String mention = s.mention() == null ? "" : s.mention().trim();
        send(s, mention.isEmpty() ? text : mention + " " + text, !mention.isEmpty(), false);
    }

    /** Announces that hosting stopped (only if enabled in the settings). Pass {@code blocking} on shutdown paths. */
    public static void announceStop(Settings s, boolean blocking) {
        if (s == null || !s.enabled() || !s.notifyStop()) {
            return;
        }
        send(s, render(s.stopMessage(), s, ""), false, blocking);
    }

    // ------------------------------------------------------------------ internals (package-private for tests)

    private static void send(Settings s, String text, boolean allowPings, boolean blocking) {
        if (!isDiscordUrl(s.webhookUrl())) {
            LOG.log(System.Logger.Level.WARNING,
                    "Discord webhook is enabled but its URL is empty or not a https://discord.com/api/webhooks/... URL");
            return;
        }
        URI uri = URI.create(s.webhookUrl().trim());
        String json = payload(s.username(), text, allowPings);
        if (blocking) {
            report(post(uri, json));
        } else {
            Thread.ofVirtual().name("p2pmc-discord").start(() -> report(post(uri, json)));
        }
    }

    private static void report(int status) {
        if (status < 0) {
            LOG.log(System.Logger.Level.WARNING, "Discord webhook: could not reach Discord");
        } else if (status / 100 != 2) {
            LOG.log(System.Logger.Level.WARNING, "Discord webhook: Discord answered HTTP {0} (check the webhook URL)", status);
        }
    }

    static String render(String template, Settings s, String code) {
        String t = template == null || template.isBlank() ? Settings.DEFAULT_START : template;
        String name = s.serverName() == null || s.serverName().isBlank() ? "Minecraft server" : s.serverName();
        return t.replace("{name}", name).replace("{code}", code == null ? "" : code);
    }

    static String payload(String username, String text, boolean allowPings) {
        String content = text.length() > MAX_CONTENT ? text.substring(0, MAX_CONTENT - 3) + "..." : text;
        StringBuilder sb = new StringBuilder("{");
        if (username != null && !username.isBlank()) {
            String u = username.trim();
            sb.append("\"username\":").append(jsonString(u.length() > 80 ? u.substring(0, 80) : u)).append(',');
        }
        sb.append("\"content\":").append(jsonString(content));
        sb.append(",\"allowed_mentions\":{\"parse\":").append(allowPings ? "[\"everyone\",\"roles\",\"users\"]" : "[]").append('}');
        return sb.append('}').toString();
    }

    /** @return the HTTP status, or -1 if Discord could not be reached. Retries once on HTTP 429. */
    static int post(URI uri, String json) {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        try {
            for (int attempt = 0; ; attempt++) {
                HttpURLConnection c = (HttpURLConnection) uri.toURL().openConnection();
                try {
                    c.setRequestMethod("POST");
                    c.setConnectTimeout(5000);
                    c.setReadTimeout(8000);
                    c.setInstanceFollowRedirects(false);
                    c.setDoOutput(true);
                    c.setFixedLengthStreamingMode(body.length);
                    c.setRequestProperty("Content-Type", "application/json");
                    c.setRequestProperty("User-Agent", "p2pmc-webhook/1.0");
                    try (OutputStream out = c.getOutputStream()) {
                        out.write(body);
                    }
                    int status = c.getResponseCode();
                    if (status == 429 && attempt == 0) {
                        long wait = 1;
                        try {
                            wait = (long) Math.ceil(Double.parseDouble(String.valueOf(c.getHeaderField("Retry-After")).trim()));
                        } catch (NumberFormatException ignored) {
                            // keep the 1 second default
                        }
                        Thread.sleep(Math.min(Math.max(wait, 1), 5) * 1000L);
                        continue;
                    }
                    return status;
                } finally {
                    c.disconnect();
                }
            }
        } catch (IOException | RuntimeException e) {
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    static String jsonString(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
