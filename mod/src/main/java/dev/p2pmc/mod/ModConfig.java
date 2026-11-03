package dev.p2pmc.mod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import dev.p2pmc.net.P2PConfig;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code config/p2pmc.json}. Only needed for advanced tweaks: everything works with the defaults. A broken file is
 * left untouched and the defaults are used for that session.
 */
public final class ModConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static volatile ModConfig current = new ModConfig();

    /** Public STUN servers used to discover your public address (they only see a UDP packet, never your game traffic). */
    public List<String> stunServers = new ArrayList<>(P2PConfig.defaults().stunServers());
    /** How long the host keeps trying to open a path after accepting a friend's answer. */
    public int hostPunchSeconds = 30;
    /** How long a joining player waits for the host to accept their answer code. */
    public int joinWaitSeconds = 180;

    public static ModConfig current() {
        return current;
    }

    public P2PConfig toP2PConfig() {
        P2PConfig d = P2PConfig.defaults();
        List<String> servers = stunServers == null || stunServers.isEmpty() ? d.stunServers() : List.copyOf(stunServers);
        return new P2PConfig(servers, clamp(hostPunchSeconds, 5, 300) * 1000, clamp(joinWaitSeconds, 10, 1800) * 1000,
                d.handshakeMillis(), d.stunTimeoutMillis());
    }

    public static void load(Path file) {
        ModConfig loaded = null;
        boolean write = false;
        if (Files.isRegularFile(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                loaded = GSON.fromJson(r, ModConfig.class);
            } catch (IOException | JsonParseException e) {
                P2PMod.LOGGER.error("Could not read {} - using defaults this session (file left untouched): {}", file, e.toString());
            }
        } else {
            write = true;
        }
        current = loaded != null ? loaded : new ModConfig();
        if (write) {
            save(file, current);
        }
    }

    private static void save(Path file, ModConfig config) {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(config, w);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            P2PMod.LOGGER.error("Could not write {}: {}", file, e.toString());
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
