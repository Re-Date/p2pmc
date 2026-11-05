package dev.p2pmc.mod.mixin;

import dev.p2pmc.net.Messages;
import dev.p2pmc.mod.ui.JoinScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds "P2P: Join" to the Multiplayer menu. */
@Mixin(JoinMultiplayerScreen.class)
public abstract class JoinMultiplayerScreenMixin extends Screen {

    protected JoinMultiplayerScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void p2pmc$addJoinButton(CallbackInfo ci) {
        dev.p2pmc.mod.P2PState.refreshLanguage(this.minecraft);
        this.addRenderableWidget(Button.builder(Component.literal(Messages.t("ui.joinButton")),
                        b -> dev.p2pmc.mod.Compat.show(this.minecraft, new JoinScreen(this)))
                .bounds(6, 6, 70, 20).build());
    }
}
