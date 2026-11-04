#!/usr/bin/env bash
# Dumps the real Minecraft API surface we depend on, to help fix compile errors. Output: /tmp/logs/probe.txt
mkdir -p /tmp/logs
OUT=/tmp/logs/probe.txt
: > "$OUT"
JAR=$(find "$HOME/.gradle" mod -name '*.jar' -size +10M 2>/dev/null | grep -iE 'minecraft' | grep -viE 'sources|javadoc' | head -1)
echo "jar: $JAR" >> "$OUT"
[ -z "$JAR" ] && { find "$HOME/.gradle" mod -name '*.jar' -size +10M 2>/dev/null | head -20 >> "$OUT"; exit 0; }
show() { echo "=== $1 ($2)" >> "$OUT"; javap -p -cp "$JAR" "$1" 2>&1 | grep -iE "$2" >> "$OUT"; }
show net.minecraft.client.Minecraft 'Screen|singleplayer|IntegratedServer|keyboard|Clipboard'
show net.minecraft.client.gui.screens.Screen 'minecraft|init|addRenderableWidget|font|width|height|onClose|tick|setInitialFocus'
show net.minecraft.client.gui.screens.PauseScreen 'init|createPauseMenu'
show net.minecraft.client.gui.screens.TitleScreen 'init|createNormalMenuOptions'
show net.minecraft.client.gui.screens.ConnectScreen 'startConnecting'
show net.minecraft.client.server.IntegratedServer 'publishServer|isPublished|getPort'
for c in $(jar tf "$JAR" | grep -E "MultiplayerScope\.class$" | sed "s/.class$//;s#/#.#g"); do echo "=== $c" >> "$OUT"; javap -p -constants -cp "$JAR" "$c" >> "$OUT" 2>&1; done
show net.minecraft.util.HttpUtil 'getAvailablePort'
show net.minecraft.client.KeyboardHandler 'lipboard'
echo "=== classes mentioning Gui" >> "$OUT"
jar tf "$JAR" | grep -E '^net/minecraft/client/gui/Gui(\$|\.)' | head -5 >> "$OUT"
# which classes declare a method taking a Screen and named like set*Screen*
for c in net.minecraft.client.gui.Gui net.minecraft.client.Minecraft; do
  javap -p -cp "$JAR" "$c" 2>/dev/null | grep -E "\(net\.minecraft\.client\.gui\.screens\.Screen\)" | sed "s/^/$c: /" >> "$OUT"
done
# 26.2 changed publishServer: list the scope enum it takes
J262="$HOME/.gradle/caches/fabric-loom/26.2/minecraft-client.jar"
if [ -f "$J262" ]; then
  echo "=== 26.2 scope enum" >> "$OUT"
  for c in $(jar tf "$J262" | grep -E "MultiplayerScope\.class$" | sed "s/.class$//;s#/#.#g"); do javap -p -constants -cp "$J262" "$c" >> "$OUT" 2>&1; done
  javap -p -cp "$J262" net.minecraft.client.server.IntegratedServer | grep publishServer >> "$OUT"
fi
