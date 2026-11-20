#!/bin/sh
# Rebuild the custom RuneLite client and its plugins from runelite-automation and copy them into
# the APK assets (the "Use custom RuneLite" launcher toggle runs these).
#   scripts/update_custom_runelite.sh [path/to/runelite-automation]
set -e
HERE="$(cd "$(dirname "$0")/.." && pwd)"
RLA="$(cd "${1:-$HERE/../runelite-automation}" && pwd)"
PLUGINS="rsbuddy xplogger fullscreen currenthit mouseeffects"
ASSETS="$HERE/runelite/src/main/assets"

TASKS=":client:shadowJar"
for p in $PLUGINS; do TASKS="$TASKS :$p:jar"; done
(cd "$RLA" && ./gradlew $TASKS -x test -x checkstyleMain)

VERSION="$(sed -n 's/^project.build.version=//p' "$RLA/gradle.properties")"
COMMIT="$(git -C "$RLA" rev-parse --short=9 HEAD)"
cp "$RLA/runelite-client/build/libs/client-$VERSION-shaded.jar" "$ASSETS/components/runelite/runelite.jar"
printf '%s-%s' "$COMMIT" "$VERSION" > "$ASSETS/components/runelite/version"
mkdir -p "$ASSETS/runelite/custom-plugins"
for p in $PLUGINS; do cp "$RLA/$p/build/libs/$p.jar" "$ASSETS/runelite/custom-plugins/$p.jar"; done

# The Android shim is compiled against the bundled client.
sh "$HERE/runelite_android_shim/build.sh"
echo "Custom RuneLite $COMMIT-$VERSION copied into $ASSETS"
