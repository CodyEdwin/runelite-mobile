#!/bin/sh
# Publishes the custom plugin jars to the "custom-plugins" release, where the launcher's
# "Update custom plugins" button downloads them. plugins.txt lists every jar, so new plugins
# reach the app without an APK update. Run after scripts/update_custom_runelite.sh.
set -e
cd "$(dirname "$0")/.."
TAG=custom-plugins
DIR=runelite/src/main/assets/runelite/custom-plugins
VERSION=$(cat runelite/src/main/assets/components/runelite/version 2>/dev/null || echo unknown)
if ! gh release view "$TAG" >/dev/null 2>&1; then
    gh release create "$TAG" --latest=false --title "Custom plugins (latest)" \
        --notes "Plugin jars for the custom RuneLite. The app's \"Update custom plugins\" button downloads these."
fi
INDEX=$(mktemp -d)/plugins.txt
(cd "$DIR" && ls *.jar) > "$INDEX"
gh release upload "$TAG" "$DIR"/*.jar "$INDEX" --clobber
gh release edit "$TAG" --notes "Plugin jars for the custom RuneLite ($VERSION). The app's \"Update custom plugins\" button downloads these into /sdcard/RuneLite/custom-plugins."
echo "Published $(ls "$DIR"/*.jar | wc -l) jar(s) to $TAG ($VERSION)"
