package dev.p2pmc.mod.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Tiny helpers. Screens are built only from {@code Button} and {@code EditBox}: a borderless read-only EditBox is
 * used as a text label, which keeps the mod independent from the more volatile text-widget and drawing APIs.
 */
final class Widgets {
    private Widgets() {
    }

    static EditBox label(Font font, int x, int y, int width, String text) {
        EditBox box = new EditBox(font, x, y, width, 12, Component.empty());
        box.setBordered(false);
        box.setEditable(false);
        box.setMaxLength(400);
        box.setValue(text);
        box.moveCursorToStart(false);
        return box;
    }

    /** Sets the text only when it changed, so the cursor and selection of the user are not reset every tick. */
    static void setIfChanged(EditBox box, String text) {
        if (!box.getValue().equals(text)) {
            box.setValue(text);
            box.moveCursorToStart(false);
        }
    }

    /** Word-wraps {@code text} to roughly {@code maxChars} characters per line. */
    static List<String> wrap(String text, int maxChars) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (line.length() > 0 && line.length() + 1 + word.length() > maxChars) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) {
                line.append(' ');
            }
            line.append(word);
        }
        if (line.length() > 0) {
            lines.add(line.toString());
        }
        return lines;
    }
}
