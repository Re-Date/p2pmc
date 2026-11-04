package dev.p2pmc.mod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.lang.reflect.Method;

/** Opens a screen on every 26.x version: 26.3 renamed {@code setScreen} to {@code setScreenAndShow}. */
public final class Compat {
    private static final Method SHOW = find();

    private Compat() {
    }

    private static Method find() {
        for (String name : new String[]{"setScreenAndShow", "setScreen"}) {
            try {
                return Minecraft.class.getMethod(name, Screen.class);
            } catch (NoSuchMethodException ignored) {
                // try the next name
            }
        }
        throw new IllegalStateException("No Minecraft method to open a screen was found");
    }

    public static void show(Minecraft mc, Screen screen) {
        try {
            SHOW.invoke(mc, screen);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
