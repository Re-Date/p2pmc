#!/usr/bin/env bash
# Dumps the Minecraft 1.21.1 / NeoForge API we depend on. Output: /tmp/logs/probe-neoforge.txt
mkdir -p /tmp/logs
OUT=/tmp/logs/probe-neoforge.txt
: > "$OUT"
find "$HOME/.gradle" neoforge -name '*.jar' -size +5M 2>/dev/null | grep -iE 'neoforge|minecraft|client' | head -10 >> "$OUT"
for CLS in net.neoforged.neoforge.client.event.ScreenEvent\$Init\$Post net.neoforged.neoforge.client.event.ScreenEvent\$Init net.minecraft.client.gui.screens.ConnectScreen net.minecraft.client.Minecraft; do
  JAR=$(for j in $(find "$HOME/.gradle" -name '*.jar' -size +1M 2>/dev/null); do unzip -l "$j" 2>/dev/null | grep -qF "$(echo ${CLS//./\/} | sed 's/\$.*//').class" && echo "$j" && break; done)
  echo "=== $CLS in $JAR" >> "$OUT"
  [ -n "$JAR" ] && javap -p -cp "$JAR" "$CLS" 2>&1 | grep -iE 'addListener|startConnecting|setScreen|getScreen|singleplayer' >> "$OUT"
done
