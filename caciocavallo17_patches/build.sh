#!/bin/sh
# Recompile the patched caciocavallo classes and update the bundled cacio-tta jar in place.
# Requires a JDK 17 on PATH. Bump assets/components/caciocavallo17/version afterwards so
# existing installs re-extract the jar.
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
CACIO="$HERE/../app_pojavlauncher/src/main/assets/components/caciocavallo17"
OUT="$(mktemp -d)"
javac -source 17 -target 17 -nowarn \
    --add-exports java.desktop/java.awt.peer=ALL-UNNAMED \
    -cp "$CACIO/cacio-shared-1.18-SNAPSHOT.jar:$CACIO/cacio-tta-1.18-SNAPSHOT.jar" \
    -d "$OUT" $(find "$HERE/src/main/java" -name '*.java')
(cd "$OUT" && jar uf "$CACIO/cacio-tta-1.18-SNAPSHOT.jar" $(find . -name '*.class'))
rm -rf "$OUT"
echo "Updated $CACIO/cacio-tta-1.18-SNAPSHOT.jar"
