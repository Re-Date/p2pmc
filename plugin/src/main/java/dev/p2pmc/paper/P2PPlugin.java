package dev.p2pmc.paper;

import dev.p2pmc.net.InviteException;
import dev.p2pmc.net.P2PConfig;
import dev.p2pmc.net.P2PHost;
import org.bukkit.command.Command;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * "P2P: Invite friends" for a Paper server: friends join through a direct encrypted peer-to-peer tunnel, so the
 * server needs neither a public IP nor a VPS. Players reach the server through 127.0.0.1 on its normal port.
 * <p>
 * Commands (permission {@code p2p.admin}, default: op):
 * {@code /p2p invite}, {@code /p2p accept <answer code>}, {@code /p2p status}, {@code /p2p stop}.
 */
public final class P2PPlugin extends JavaPlugin {
    private final Object lock = new Object();
    private P2PHost host;
    private boolean starting;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        String ip = getServer().getIp();
        if (ip != null && !ip.isBlank() && !ip.equals("0.0.0.0") && !ip.startsWith("127.")) {
            getLogger().warning("server-ip is set to '" + ip + "'. The tunnel connects to 127.0.0.1, so friends can only "
                    + "join if you clear server-ip in server.properties.");
        }
        if (getConfig().getBoolean("auto-start", false)) {
            startHost(message -> getLogger().info(message));
        }
    }

    @Override
    public void onDisable() {
        stopHost();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "invite" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "invite" -> startHost(sender::sendMessage);
            case "accept" -> accept(sender, args);
            case "status" -> sender.sendMessage(status());
            case "stop" -> {
                stopHost();
                sender.sendMessage("P2P hosting stopped. Friends are disconnected.");
            }
            default -> sender.sendMessage("Usage: /p2p invite | accept <answer code> | status | stop");
        }
        return true;
    }

    // ------------------------------------------------------------------ actions

    /** Starts hosting if needed (network discovery takes a moment, so off the main thread) and reports the invite. */
    private void startHost(Consumer<String> reply) {
        synchronized (lock) {
            if (host != null) {
                reply.accept(describeInvite(host));
                return;
            }
            if (starting) {
                reply.accept("Still checking the network - run /p2p invite again in a few seconds.");
                return;
            }
            starting = true;
        }
        reply.accept("Checking the network...");
        P2PConfig config = P2PConfig.defaults();
        List<String> stun = getConfig().getStringList("stun-servers");
        if (!stun.isEmpty()) {
            config = config.withStunServers(stun);
        }
        final P2PConfig finalConfig = config;
        final int port = getServer().getPort();
        final boolean autoAccept = getConfig().getBoolean("auto-accept", true);
        Thread.ofVirtual().name("p2pmc-host-start").start(() -> {
            P2PHost h = new P2PHost(port, finalConfig);
            h.setAutoAccept(autoAccept);
            try {
                h.start();
                synchronized (lock) {
                    host = h;
                    starting = false;
                }
                reply.accept(describeInvite(h));
            } catch (Exception e) {
                h.close();
                synchronized (lock) {
                    starting = false;
                }
                getLogger().warning("Could not start P2P hosting: " + e);
                reply.accept("Could not start P2P hosting: " + e.getMessage());
            }
        });
    }

    private void accept(CommandSender sender, String[] args) {
        P2PHost h;
        synchronized (lock) {
            h = host;
        }
        if (h == null) {
            sender.sendMessage("Hosting is not running. Use /p2p invite first.");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("Usage: /p2p accept <answer code from your friend>");
            return;
        }
        String code = String.join("", java.util.Arrays.copyOfRange(args, 1, args.length));
        try {
            P2PHost.Peer peer = h.acceptAnswer(code);
            sender.sendMessage("Answer accepted - connecting to friend " + peer.id() + ". Check /p2p status.");
        } catch (InviteException e) {
            sender.sendMessage(e.getMessage());
        }
    }

    private void stopHost() {
        P2PHost h;
        synchronized (lock) {
            h = host;
            host = null;
            starting = false;
        }
        if (h != null) {
            h.close();
        }
    }

    private String status() {
        P2PHost h;
        boolean busy;
        synchronized (lock) {
            h = host;
            busy = starting;
        }
        if (h == null) {
            return busy ? "Checking the network..." : "P2P hosting is not running. Use /p2p invite.";
        }
        StringBuilder sb = new StringBuilder("P2P hosting is running for port " + h.minecraftPort() + ".\n");
        sb.append(h.networkReport());
        List<P2PHost.Peer> peers = h.peers();
        sb.append("\nFriends: ").append(peers.isEmpty() ? "none yet" : "");
        for (P2PHost.Peer p : peers) {
            long ping = p.pingMillis();
            sb.append("\n- ").append(p.id()).append(": ").append(p.detail());
            if (p.state() == P2PHost.PeerState.CONNECTED && ping >= 0) {
                sb.append(" (ping ").append(ping).append(" ms)");
            }
        }
        return sb.toString();
    }

    private static String describeInvite(P2PHost h) {
        return "Invite code - send it to your friend:\n" + h.offerCode()
                + "\nFriends paste it in the mod (P2P: Join) and connect by themselves. Only if one gets stuck, he sends you an answer code: /p2p accept <answer code>\n"
                + h.networkReport();
    }
}
