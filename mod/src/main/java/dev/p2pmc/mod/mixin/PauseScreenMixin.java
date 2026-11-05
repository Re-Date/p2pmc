package dev.p2pmc.mod.mixin;

import dev.p2pmc.net.Messages;
import dev.p2pmc.mod.ui.HostScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds "P2P: Invite friends" to the pause menu of a singleplayer world. */
@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin extends Screen {

    protected PauseScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void p2pmc$addHostButton(CallbackInfo ci) {
        if (this.minecraft.getSingleplayerServer() == null) {
            return; // only worlds hosted by this client can be shared
        }
        dev.p2pmc.mod.P2PState.refreshLanguage(this.minecraft);
        this.addRenderableWidget(Button.builder(Component.literal(Messages.t("ui.inviteButton")),
                        b -> dev.p2pmc.mod.Compat.show(this.minecraft, new HostScreen(this)))
                .bounds(6, 6, 130, 20).build());
    }
}
