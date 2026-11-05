package dev.p2pmc.mod.ui;

import dev.p2pmc.net.Messages;
import dev.p2pmc.mod.ModConfig;
import dev.p2pmc.mod.P2PState;
import dev.p2pmc.net.InviteException;
import dev.p2pmc.net.P2PHost;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Host dashboard (opened from the pause menu of a singleplayer world):
 * 1. copy the invite code and send it to a friend, 2. paste the friend's answer code and press Accept.
 */
public final class HostScreen extends Screen {
    private static final int WIDTH = 340;
    private static final int PEER_LINES = 5;
    private static final int REPORT_LINES = 2;

    private final Screen parent;
    private EditBox offerBox;
    private EditBox answerBox;
    private EditBox feedback;
    private EditBox portNote;
    private final EditBox[] reportLines = new EditBox[REPORT_LINES];
    private final EditBox[] peerLines = new EditBox[PEER_LINES];
    private String feedbackText = "";

    public HostScreen(Screen parent) {
        super(Component.literal(Messages.t("ui.hostTitle")));
        this.parent = parent;
    }

    @Override
    protected void init() {
        P2PState.refreshLanguage(this.minecraft);
        P2PState.startHostingIfNeeded(this.minecraft);

        int x = (this.width - WIDTH) / 2;
        int y = 16;
        addRenderableWidget(Widgets.label(this.font, x, y, WIDTH, Messages.t("ui.hostHeader")));
        y += 20;
        addRenderableWidget(Widgets.label(this.font, x, y, WIDTH, Messages.t("ui.step1")));
        y += 14;
        offerBox = new EditBox(this.font, x, y, WIDTH - 70, 20, Component.literal(Messages.t("ui.inviteCode")));
        offerBox.setEditable(false);
        offerBox.setMaxLength(400);
        addRenderableWidget(offerBox);
        addRenderableWidget(Button.builder(Component.literal(Messages.t("ui.copy")), b -> {
            P2PHost h = P2PState.host();
            if (h != null && !h.offerCode().isEmpty()) {
                this.minecraft.keyboardHandler.setClipboard(h.offerCode());
                setFeedback(Messages.t("ui.copied"));
            }
        }).bounds(x + WIDTH - 66, y, 66, 20).build());
        y += 24;
        for (int i = 0; i < REPORT_LINES; i++) {
            reportLines[i] = Widgets.label(this.font, x, y, WIDTH, "");
            addRenderableWidget(reportLines[i]);
            y += 11;
        }
        y += 6;
        addRenderableWidget(Widgets.label(this.font, x, y, WIDTH, Messages.t("ui.step2")));
        y += 14;
        answerBox = new EditBox(this.font, x, y, WIDTH - 70, 20, Component.literal(Messages.t("ui.answerCode")));
        answerBox.setMaxLength(400);
        addRenderableWidget(answerBox);
        addRenderableWidget(Button.builder(Component.literal(Messages.t("ui.accept")), b -> acceptAnswer())
                .bounds(x + WIDTH - 66, y, 66, 20).build());
        y += 24;
        feedback = Widgets.label(this.font, x, y, WIDTH, feedbackText);
        addRenderableWidget(feedback);
        y += 18;
        addRenderableWidget(Widgets.label(this.font, x, y, WIDTH, Messages.t("ui.friends")));
        y += 12;
        for (int i = 0; i < PEER_LINES; i++) {
            peerLines[i] = Widgets.label(this.font, x, y, WIDTH, "");
            addRenderableWidget(peerLines[i]);
            y += 11;
        }
        y += 6;
        portNote = Widgets.label(this.font, x, y, WIDTH, "");
        addRenderableWidget(portNote);

        int bottom = this.height - 28;
        addRenderableWidget(Button.builder(Component.literal(Messages.t("ui.stop")), b -> {
            P2PState.stopHosting();
            dev.p2pmc.mod.Compat.show(this.minecraft, parent);
        }).bounds(x, bottom, 110, 20).build());
        addRenderableWidget(Button.builder(licenceLabel(), this::toggleLicence)
                .tooltip(Tooltip.create(Component.literal(Messages.t("ui.licenceTip"))))
                .bounds(x + 114, bottom, 118, 20).build());
        addRenderableWidget(Button.builder(Component.literal(Messages.t("ui.done")), b -> onClose())
                .bounds(x + 236, bottom, 104, 20).build());
        setInitialFocus(answerBox);
    }

    private static Component licenceLabel() {
        return Component.literal(ModConfig.current().onlineMode ? Messages.t("ui.licenceOn") : Messages.t("ui.licenceOff"));
    }

    /** Switches the licence (online-mode) check for joining friends, immediately and for next time. */
    private void toggleLicence(Button button) {
        boolean on = !ModConfig.current().onlineMode;
        ModConfig.current().onlineMode = on;
        ModConfig.saveCurrent();
        var server = this.minecraft.getSingleplayerServer();
        if (server != null) {
            server.setUsesAuthentication(on);
        }
        button.setMessage(licenceLabel());
        setFeedback(on ? Messages.t("ui.licenceNowOn") : Messages.t("ui.licenceNowOff"));
    }

    private void acceptAnswer() {
        P2PHost h = P2PState.host();
        if (h == null) {
            setFeedback(Messages.t("ui.notReady"));
            return;
        }
        try {
            P2PHost.Peer peer = h.acceptAnswer(answerBox.getValue());
            answerBox.setValue("");
            setFeedback(Messages.t("ui.accepted", peer.id()));
        } catch (InviteException e) {
            setFeedback(e.getMessage());
        }
    }

    private void setFeedback(String text) {
        feedbackText = text;
        if (feedback != null) {
            Widgets.setIfChanged(feedback, text);
        }
    }

    @Override
    public void tick() {
        super.tick();
        P2PHost h = P2PState.host();
        if (h == null) {
            Widgets.setIfChanged(offerBox, "");
            fillLines(reportLines, Widgets.wrap(P2PState.hostMessage().isEmpty() ? Messages.t("ui.starting") : P2PState.hostMessage(), 60));
            fillLines(peerLines, List.of());
            Widgets.setIfChanged(portNote, "");
            return;
        }
        Widgets.setIfChanged(offerBox, h.offerCode());
        fillLines(reportLines, Widgets.wrap(h.networkReport(), 60));

        List<P2PHost.Peer> peers = h.peers();
        String[] lines = new String[PEER_LINES];
        int first = Math.max(0, peers.size() - PEER_LINES);
        for (int i = 0; i < PEER_LINES; i++) {
            if (first + i < peers.size()) {
                P2PHost.Peer p = peers.get(first + i);
                long ping = p.pingMillis();
                lines[i] = "- " + p.id() + ": " + p.detail() + (ping >= 0 && p.state() == P2PHost.PeerState.CONNECTED ? " (ping " + ping + " ms)" : "");
            } else {
                lines[i] = "";
            }
        }
        fillLines(peerLines, List.of(lines));
        Widgets.setIfChanged(portNote, Messages.t("ui.worldPort", h.minecraftPort()));
    }

    private static void fillLines(EditBox[] boxes, List<String> lines) {
        for (int i = 0; i < boxes.length; i++) {
            Widgets.setIfChanged(boxes[i], i < lines.size() ? lines.get(i) : "");
        }
    }

    @Override
    public void onClose() {
        dev.p2pmc.mod.Compat.show(this.minecraft, parent);
    }
}
