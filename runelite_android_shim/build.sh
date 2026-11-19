#!/bin/sh
# Build runelite-android.jar (Android entry point + JagexAuthService override) against the
# bundled runelite.jar. Requires a JDK 17 on PATH.
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
RL="$HERE/../runelite/src/main/assets/components/runelite"
OUT="$(mktemp -d)"
javac --release 11 -nowarn -cp "$RL/runelite.jar" -d "$OUT" $(find "$HERE/src/main/java" -name '*.java')
[ -d "$HERE/src/main/resources" ] && cp -r "$HERE/src/main/resources/." "$OUT/"
rm -f "$RL/runelite-android.jar"
(cd "$OUT" && jar cf "$RL/runelite-android.jar" .)
rm -rf "$OUT"
echo "Built $RL/runelite-android.jar"
