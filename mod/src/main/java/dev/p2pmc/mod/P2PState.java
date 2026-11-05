package dev.p2pmc.mod;

import dev.p2pmc.net.Messages;
import dev.p2pmc.net.DiscordWebhook;
import dev.p2pmc.net.P2PConfig;
import dev.p2pmc.net.P2PHost;
import dev.p2pmc.net.P2PJoin;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.util.HttpUtil;

import java.io.IOException;

/**
 * Process-wide P2P session state, so hosting keeps running while the screens come and go.
 * Hosting follows the singleplayer world: when the world is closed the host is shut down.
 */
public final class P2PState {
    private static volatile P2PHost host;
    private static volatile String hostMessage = "";
    private static volatile boolean hostStarting;
    private static volatile P2PJoin join;

    private P2PState() {
    }

    // ------------------------------------------------------------------ hosting

    /** Takes the language from the game's language setting (ru_ru: Russian, anything else: English). */
    public static void refreshLanguage(Minecraft mc) {
        try {
            Messages.setLanguage(mc.getLanguageManager().getSelected());
        } catch (RuntimeException e) {
            // keep the previous language
        }
    }

    public static P2PHost host() {
        return host;
    }

    /** Status text while there is no running host (starting, or why it could not start). */
    public static String hostMessage() {
        return hostMessage;
    }

    /** Call on the game thread (screen init): publishes the world to the loopback/LAN port if needed, then starts the engine. */
    public static void startHostingIfNeeded(Minecraft mc) {
        refreshLanguage(mc);
        if (host != null || hostStarting) {
            return;
        }
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null) {
            hostMessage = Messages.t("ui.openWorldFirst");
            return;
        }
        final int port;
        try {
            if (server.isPublished()) {
                port = server.getPort();
            } else {
                int free = HttpUtil.getAvailablePort();
                if (!Compat.publish(server, free)) {
                    hostMessage = Messages.t("ui.publishFailed");
                    return;
                }
                port = free;
            }
        } catch (RuntimeException e) {
            P2PMod.LOGGER.error("Could not publish the world", e);
            hostMessage = Messages.t("ui.publishError", e.getMessage());
            return;
        }

        try {
            // licence check for joining players (vanilla default: true)
            server.setUsesAuthentication(ModConfig.current().onlineMode);
        } catch (RuntimeException e) {
            P2PMod.LOGGER.warn("Could not change online-mode: {}", e.toString());
        }

        hostStarting = true;
        hostMessage = Messages.t("net.checking");
        P2PConfig config = ModConfig.current().toP2PConfig();
        Thread.ofVirtual().name("p2pmc-host-start").start(() -> {
            P2PHost h = new P2PHost(port, config);
            h.setAutoAccept(ModConfig.current().autoAccept);
            h.setUpnpEnabled(ModConfig.current().upnp);
            try {
                h.start();
                host = h;
                hostMessage = "";
                DiscordWebhook.announceStart(ModConfig.current().discordSettings(), h.offerCode());
            } catch (IOException | RuntimeException e) {
                P2PMod.LOGGER.error("Could not start P2P hosting", e);
                h.close();
                hostMessage = Messages.t("ui.startFailed", e.getMessage());
            } finally {
                hostStarting = false;
            }
        });
    }

    public static void stopHosting() {
        P2PHost h = host;
        host = null;
        hostMessage = "";
        if (h != null) {
            h.close();
            DiscordWebhook.announceStop(ModConfig.current().discordSettings(), false);
        }
    }

    // ------------------------------------------------------------------ joining

    public static P2PJoin join() {
        return join;
    }

    public static P2PJoin startJoin(String inviteCode) {
        stopJoin();
        P2PJoin j = new P2PJoin(inviteCode, ModConfig.current().toP2PConfig());
        join = j;
        j.start();
        return j;
    }

    public static void stopJoin() {
        P2PJoin j = join;
        join = null;
        if (j != null) {
            j.close();
        }
    }

    // ------------------------------------------------------------------ lifecycle

    /** Called whenever the title screen appears: that means no world is open, so nothing may stay hosted. */
    public static void onTitleScreen(Minecraft mc) {
        if (mc.getSingleplayerServer() == null) {
            stopHosting();
        }
    }
}
