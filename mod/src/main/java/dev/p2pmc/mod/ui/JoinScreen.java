package dev.p2pmc.mod.ui;

import dev.p2pmc.net.Messages;
import dev.p2pmc.mod.P2PState;
import dev.p2pmc.net.P2PJoin;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Guest flow: paste the host's invite code, press Connect, send the generated answer code back to the host.
 * As soon as the tunnel is up the game is pointed at the local end of it, like any LAN server.
 */
public final class JoinScreen extends Screen {
    private static final int WIDTH = 340;
    private static final int STATUS_LINES = 4;

    private final Screen parent;
    private EditBox inviteBox;
    private EditBox answerBox;
    private Button connectButton;
    private Button copyButton;
    private final EditBox[] statusLines = new EditBox[STATUS_LINES];
    private boolean joining;
    private boolean gameConnectStarted;

    public JoinScreen(Screen parent) {
        super(Component.literal(Messages.t("ui.joinTitle")));
        this.parent = parent;
    }

    @Override
    protected void init() {
        P2PState.refreshLanguage(this.minecraft);
        int x = (this.width - WIDTH) / 2;
        int y = 24;
        addRenderableWidget(Widgets.label(this.font, x, y, WIDTH, Messages.t("ui.joinHeader")));
        y += 24;
        addRenderableWidget(Widgets.label(this.font, x, y, WIDTH, "1) Paste the invite code from the host:"));
        y += 14;
        inviteBox = new EditBox(this.font, x, y, WIDTH - 70, 20, Component.literal(Messages.t("ui.inviteCode")));
        inviteBox.setMaxLength(400);
        addRenderableWidget(inviteBox);
        addRenderableWidget(Button.builder(Component.literal(Messages.t("ui.paste")), b -> {
            inviteBox.setValue(this.minecraft.keyboardHandler.getClipboard().trim());
        }).bounds(x + WIDTH - 66, y, 66, 20).build());
        y += 26;
        connectButton = Button.builder(Component.literal(Messages.t("ui.connect")), b -> connect())
                .bounds(x, y, WIDTH, 20).build();
        addRenderableWidget(connectButton);
        y += 30;
        addRenderableWidget(Widgets.label(this.font, x, y, WIDTH, "2) Only if it does not connect: send this to the host:"));
        y += 14;
        answerBox = new EditBox(this.font, x, y, WIDTH - 70, 20, Component.literal(Messages.t("ui.answerCode")));
        answerBox.setEditable(false);
        answerBox.setMaxLength(400);
        addRenderableWidget(answerBox);
        copyButton = Button.builder(Component.literal(Messages.t("ui.copy")), b -> {
            P2PJoin j = P2PState.join();
            if (j != null && !j.answerCode().isEmpty()) {
                this.minecraft.keyboardHandler.setClipboard(j.answerCode());
            }
        }).bounds(x + WIDTH - 66, y, 66, 20).build();
        copyButton.active = false;
        addRenderableWidget(copyButton);
        y += 30;
        for (int i = 0; i < STATUS_LINES; i++) {
            statusLines[i] = Widgets.label(this.font, x, y, WIDTH, "");
            addRenderableWidget(statusLines[i]);
            y += 11;
        }
        addRenderableWidget(Button.builder(Component.literal(Messages.t("ui.cancel")), b -> onClose())
                .bounds(this.width / 2 - 75, this.height - 28, 150, 20).build());
        setInitialFocus(inviteBox);
    }

    private void connect() {
        String code = inviteBox.getValue().trim();
        if (code.isEmpty()) {
            fill(List.of(Messages.t("ui.pasteFirst")));
            return;
        }
        joining = true;
        gameConnectStarted = false;
        connectButton.active = false;
        inviteBox.setEditable(false);
        P2PState.startJoin(code);
    }

    @Override
    public void tick() {
        super.tick();
        P2PJoin j = P2PState.join();
        if (!joining || j == null) {
            return;
        }
        Widgets.setIfChanged(answerBox, j.answerCode());
        copyButton.active = !j.answerCode().isEmpty();

        switch (j.state()) {
            case FAILED -> {
                fill(Widgets.wrap(j.detail(), 60));
                connectButton.active = true; // let the user fix the code and retry
                inviteBox.setEditable(true);
                joining = false;
            }
            case READY -> {
                fill(List.of(j.detail()));
                if (!gameConnectStarted) {
                    gameConnectStarted = true;
                    joinGame(j.localPort());
                }
            }
            default -> {
                List<String> lines = new java.util.ArrayList<>(Widgets.wrap(j.detail(), 60));
                if (!j.networkReport().isEmpty()) {
                    lines.addAll(Widgets.wrap(j.networkReport(), 60));
                }
                fill(lines);
            }
        }
    }

    /**
     * Points the game at the local end of the tunnel. NOTE: the ConnectScreen API is the one call in this mod that
     * has changed between Minecraft versions; adjust the arguments here if the compiler complains.
     */
    private void joinGame(int port) {
        String address = "127.0.0.1:" + port;
        ConnectScreen.startConnecting(parent, this.minecraft, ServerAddress.parseString(address),
                new ServerData("P2P world", address, ServerData.Type.OTHER), false, null);
    }

    private void fill(List<String> lines) {
        for (int i = 0; i < statusLines.length; i++) {
            Widgets.setIfChanged(statusLines[i], i < lines.size() ? lines.get(i) : "");
        }
    }

    @Override
    public void onClose() {
        if (!gameConnectStarted) {
            P2PState.stopJoin();
        }
        dev.p2pmc.mod.Compat.show(this.minecraft, parent);
    }
}
