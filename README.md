<p align="center"><img src="docs/runelite_mobile_icon.png" width="128" alt="RuneLite Mobile icon"></p>

# RuneLite Mobile

A standalone Android app that runs the [RuneLite](https://runelite.net) Old School RuneScape client
on phones and tablets. Package: `com.codyedwin.runelitemobile` (debug builds add `.debug`).

Not affiliated with Jagex or the RuneLite project. RuneScape is a trademark of Jagex Ltd.

## How it works
- A Java 17 runtime (downloaded on first start) runs the desktop RuneLite client.
- Caciocavallo renders AWT/Swing into an off-screen buffer that a native blitter copies into an
  Android `TextureView`; touch, mouse, keyboard and gamepad-less input is bridged back into AWT.
- `runelite_android_shim/` (put in front of RuneLite on the classpath) fits the window to the
  screen, fixes repaints/flicker, focus and popups, runs the Jagex login in an Android
  browser tab and keeps the Jagex session across restarts.

## Start screen
- RuneLite profile picker and **PLAY**
- **Performance mode** (off by default): renders the game at a lower resolution through
  RuneLite's Stretched Mode for this session only (higher FPS, softer picture)
- **Load sideloaded plugins** (on by default): the bundled plugin jars plus any jars in
  `files/runelite/sideloaded-plugins` are loaded (RuneLite runs in developer mode)
- Import plugin (.jar), Open game directory, RuneLite version & updates, Settings (memory)

## Building
```
./gradlew :app_pojavlauncher:assembleDebug
```
The shim jar is rebuilt with `runelite_android_shim/build.sh`, the patched cacio classes with
`caciocavallo17_patches/build.sh`.

Based on PojavLauncher / Amethyst / XDAetherium (LGPL-3.0); see `LICENSE`. The original
Minecraft launcher README is in `docs/README_XDAetherium_legacy.md`.
