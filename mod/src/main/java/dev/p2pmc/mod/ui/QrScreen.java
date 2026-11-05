package dev.p2pmc.mod.ui;

import dev.p2pmc.mod.P2PState;
import dev.p2pmc.net.Messages;
import dev.p2pmc.net.QrCode;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Shows the invite code as a QR code: scan it with a phone camera to get the code as text. */
public final class QrScreen extends Screen {
    private static final int QUIET = 2;
    private final Screen parent;
    private final boolean[][] modules;

    public QrScreen(Screen parent, String code) {
        super(Component.literal(Messages.t("ui.showQr")));
        this.parent = parent;
        this.modules = QrCode.encode(code);
    }

    @Override
    protected void init() {
        P2PState.refreshLanguage(this.minecraft);
        addRenderableWidget(Widgets.label(this.font, this.width / 2 - 170, 12, 340, Messages.t("ui.qrTitle")));
        addRenderableWidget(Button.builder(Component.literal(Messages.t("ui.qrBack")), b -> onClose())
                .bounds(this.width / 2 - 50, this.height - 28, 100, 20).build());
    }

    @Override
    public void onClose() {
        dev.p2pmc.mod.Compat.show(this.minecraft, parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        drawCode(g);
    }

    private void drawCode(GuiGraphicsExtractor g) {
        int total = modules.length + 2 * QUIET;
        int scale = Math.max(1, Math.min(8, Math.min((this.height - 80) / total, (this.width - 20) / total)));
        int x0 = (this.width - total * scale) / 2;
        int y0 = 34;
        g.fill(x0, y0, x0 + total * scale, y0 + total * scale, 0xFFFFFFFF);
        for (int row = 0; row < modules.length; row++) {
            int col = 0;
            while (col < modules.length) {
                if (!modules[row][col]) {
                    col++;
                    continue;
                }
                int start = col;
                while (col < modules.length && modules[row][col]) {
                    col++;
                }
                g.fill(x0 + (QUIET + start) * scale, y0 + (QUIET + row) * scale,
                        x0 + (QUIET + col) * scale, y0 + (QUIET + row + 1) * scale, 0xFF000000);
            }
        }
    }
}
