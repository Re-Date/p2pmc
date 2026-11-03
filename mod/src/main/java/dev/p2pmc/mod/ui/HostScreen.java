package dev.p2pmc.mod.ui;

import dev.p2pmc.mod.P2PState;
import dev.p2pmc.net.InviteException;
import dev.p2pmc.net.P2PHost;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
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
        super(Component.literal("P2P host"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        P2PState.startHostingIfNeeded(this.minecraft);

        int x = (this.width - WIDTH) / 2;
        int y = 16;
        addRenderableWidget(Widgets.label(this.font, x, y, WIDTH, "Invite friends (direct P2P - no VPN, no public IP)"));
        y += 20;
        addRenderableWidget(Widgets.label(this.font, x, y, WIDTH, "1) Send this invite code to your friend:"));
        y += 14;
        offerBox = new EditBox(this.font, x, y, WIDTH - 70, 20, Component.literal("Invite code"));
        offerBox.setEditable(false);
        offerBox.setMaxLength(400);
        addRenderableWidget(offerBox);
        addRenderableWidget(Button.builder(Component.literal("Copy"), b -> {
            P2PHost h = P2PState.host();
            if (h != null && !h.offerCode().isEmpty()) {
                this.minecraft.keyboardHandler.setClipboard(h.offerCode());
                setFeedback("Invite code copied.");
            }
        }).bounds(x + WIDTH - 66, y, 66, 20).build());
        y += 24;
        for (int i = 0; i < REPORT_LINES; i++) {
            reportLines[i] = Widgets.label(this.font, x, y, WIDTH, "");
            addRenderableWidget(reportLines[i]);
            y += 11;
        }
        y += 6;
        addRenderableWidget(Widgets.label(this.font, x, y, WIDTH, "2) Paste the answer code your friend sends back:"));
        y += 14;
        answerBox = new EditBox(this.font, x, y, WIDTH - 70, 20, Component.literal("Answer code"));
        answerBox.setMaxLength(400);
        addRenderableWidget(answerBox);
        addRenderableWidget(Button.builder(Component.literal("Accept"), b -> acceptAnswer())
                .bounds(x + WIDTH - 66, y, 66, 20).build());
        y += 24;
        feedback = Widgets.label(this.font, x, y, WIDTH, feedbackText);
        addRenderableWidget(feedback);
        y += 18;
        addRenderableWidget(Widgets.label(this.font, x, y, WIDTH, "Friends:"));
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
        addRenderableWidget(Button.builder(Component.literal("Stop hosting"), b -> {
            P2PState.stopHosting();
            this.minecraft.setScreen(parent);
        }).bounds(this.width / 2 - 154, bottom, 150, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(this.width / 2 + 4, bottom, 150, 20).build());
        setInitialFocus(answerBox);
    }

    private void acceptAnswer() {
        P2PHost h = P2PState.host();
        if (h == null) {
            setFeedback("Hosting is not ready yet.");
            return;
        }
        try {
            P2PHost.Peer peer = h.acceptAnswer(answerBox.getValue());
            answerBox.setValue("");
            setFeedback("Answer accepted - connecting to friend " + peer.id() + "...");
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
            fillLines(reportLines, Widgets.wrap(P2PState.hostMessage().isEmpty() ? "Starting..." : P2PState.hostMessage(), 60));
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
        Widgets.setIfChanged(portNote, "World port on this PC: " + h.minecraftPort() + " (also usable as the target of a tunnel service).");
    }

    private static void fillLines(EditBox[] boxes, List<String> lines) {
        for (int i = 0; i < boxes.length; i++) {
            Widgets.setIfChanged(boxes[i], i < lines.size() ? lines.get(i) : "");
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }
}
