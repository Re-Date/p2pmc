package dev.p2pmc.mod;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Client entrypoint. The UI is injected by the mixins; the engine lives in {@code dev.p2pmc.net}. */
public final class P2PMod implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("p2pmc");

    @Override
    public void onInitializeClient() {
        ModConfig.load(FabricLoader.getInstance().getConfigDir().resolve("p2pmc.json"));
        LOGGER.info("P2P Minecraft ready: use 'P2P: Join' in the Multiplayer menu, or 'P2P: Invite friends' in a singleplayer world.");
    }
}
