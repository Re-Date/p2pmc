package dev.p2pmc.mod.mixin;

import dev.p2pmc.mod.P2PState;
import dev.p2pmc.mod.ui.JoinScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds "P2P: Join a friend" to the title screen and shuts down hosting once no world is open any more. */
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {

    protected TitleScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void p2pmc$addJoinButton(CallbackInfo ci) {
        P2PState.onTitleScreen(this.minecraft);
        this.addRenderableWidget(Button.builder(Component.literal("P2P: Join a friend"),
                        b -> this.minecraft.setScreen(new JoinScreen(this)))
                .bounds(6, 6, 130, 20).build());
    }
}
