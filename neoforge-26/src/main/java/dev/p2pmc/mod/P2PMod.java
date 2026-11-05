package dev.p2pmc.mod;

import dev.p2pmc.mod.ui.HostScreen;
import dev.p2pmc.mod.ui.JoinScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** NeoForge client entrypoint: loads the config and adds the two buttons (no mixins needed). */
@Mod(value = "p2pmc", dist = Dist.CLIENT)
public final class P2PMod {
    public static final Logger LOGGER = LoggerFactory.getLogger("p2pmc");

    public P2PMod() {
        ModConfig.load(FMLPaths.CONFIGDIR.get().resolve("p2pmc.json"));
        NeoForge.EVENT_BUS.addListener(P2PMod::onScreenInit);
    }

    private static void onScreenInit(ScreenEvent.Init.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (event.getScreen() instanceof TitleScreen) {
            P2PState.onTitleScreen(mc);
        } else if (event.getScreen() instanceof JoinMultiplayerScreen multiplayer) {
            event.addListener(Button.builder(Component.literal("P2P: Join"),
                    b -> Compat.show(mc, new JoinScreen(multiplayer))).bounds(6, 6, 70, 20).build());
        } else if (event.getScreen() instanceof PauseScreen pause && mc.getSingleplayerServer() != null) {
            event.addListener(Button.builder(Component.literal("P2P: Invite friends"),
                    b -> Compat.show(mc, new HostScreen(pause))).bounds(6, 6, 130, 20).build());
        }
    }
}
