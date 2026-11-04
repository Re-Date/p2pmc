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

    /**
     * Opens the world to the network on the given port. 26.1/26.3 take (GameType, cheats, port); 26.2 takes (MultiplayerScope, port).
     */
    public static boolean publish(net.minecraft.client.server.IntegratedServer server, int port) {
        try {
            for (Method m : server.getClass().getMethods()) {
                if (!m.getName().equals("publishServer")) {
                    continue;
                }
                Class<?>[] t = m.getParameterTypes();
                if (t.length == 3 && t[1] == boolean.class && t[2] == int.class) {
                    return (Boolean) m.invoke(server, null, false, port);
                }
                if (t.length == 2 && t[0].isEnum() && t[1] == int.class) {
                    return (Boolean) m.invoke(server, lanScope(t[0]), port);
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        throw new IllegalStateException("No supported IntegratedServer.publishServer method found");
    }

    /** The "open to LAN" constant of the 26.2 scope enum (not an online/realms one). */
    private static Object lanScope(Class<?> type) {
        Object[] all = type.getEnumConstants();
        for (String wanted : new String[]{"LAN", "LOCAL"}) {
            for (Object o : all) {
                if (((Enum<?>) o).name().equals(wanted)) {
                    return o;
                }
            }
        }
        for (Object o : all) {
            if (((Enum<?>) o).name().contains("LAN")) {
                return o;
            }
        }
        throw new IllegalStateException("No LAN scope in " + java.util.Arrays.toString(all));
    }
}
