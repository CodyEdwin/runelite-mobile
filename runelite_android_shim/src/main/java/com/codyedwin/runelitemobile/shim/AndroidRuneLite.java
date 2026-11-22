package com.codyedwin.runelitemobile.shim;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Entry point used by the Android launcher instead of {@code net.runelite.client.RuneLite}.
 *
 * <p>Caciocavallo only provides a single fixed-size virtual screen and has no window manager:
 * Frame#setExtendedState(MAXIMIZED_BOTH) is ignored (Toolkit#isFrameStateSupported only accepts
 * NORMAL) and every window keeps whatever bounds the application gives it. RuneLite therefore
 * opens a packed, desktop-sized frame in the top-left corner of the virtual screen with its own
 * title bar and border. {@link WindowFitter} acts as a minimal window manager so the client
 * always fills the screen that is blitted onto the Android surface.</p>
 */
public final class AndroidRuneLite {
    public static final String ANDROID_PROPERTY = "runelite.android";

    private AndroidRuneLite() {
    }

    public static boolean isAndroid() {
        return Boolean.getBoolean(ANDROID_PROPERTY)
                || System.getProperty("os.version", "").startsWith("Android");
    }

    public static void main(String[] args) throws Throwable {
        System.setProperty(ANDROID_PROPERTY, "true");
        fixPluginHubVersion();
        // Swing's per-window BufferStrategy painting assumes the window system keeps window contents
        // and only repaints damaged areas; on the cacio screen that lost sidebar/panel contents.
        if (System.getProperty("swing.bufferPerWindow") == null) System.setProperty("swing.bufferPerWindow", "false");
        javax.swing.SwingUtilities.invokeAndWait(LockingRepaintManager::install);
        PaintRedirect.install();
        WindowFitter.install();
        SidebarRefresher.install();
        RepaintPump.install();
        FpsLog.install();
        StallProfiler.install();
        HotspotProfiler.install();
        InputFocus.install();
        TouchRouter.install();
        DevPluginReloader.install();
        javax.swing.SwingUtilities.invokeLater(PerformanceMode::install);
        javax.swing.SwingUtilities.invokeLater(GpuMode::install);

        net.runelite.client.rs.auth.AndroidSessionKeyMigration.migrate(new java.io.File(System.getProperty("user.home")));
        args = JagexSessionMemory.addSavedCharacter(args);
        LaunchConfirm.start();
        Method main = Class.forName("net.runelite.client.RuneLite").getMethod("main", String[].class);
        try {
            main.invoke(null, (Object) args);
        } catch (Throwable e) {
            Throwable cause = e instanceof InvocationTargetException && e.getCause() != null ? e.getCause() : e;
            System.err.println("AndroidRuneLite: RuneLite crashed during startup");
            cause.printStackTrace();
            startupFailed();
        }
        StartupWatch.start();
        JagexSessionMemory.autoSelectSingleCharacter();
    }

    /**
     * Exit code the launcher maps to "RuneLite failed to start" (ExitActivity) instead of
     * leaving the loading screen up forever.
     */
    static final int EXIT_STARTUP_FAILED = 3;

    static void startupFailed() {
        System.err.println("AndroidRuneLite: RuneLite failed to start, exiting with code " + EXIT_STARTUP_FAILED + " (see the log above)");
        System.out.flush();
        System.err.flush();
        try {
            Thread.sleep(1500); // let the log reach latestlog.txt
        } catch (InterruptedException ignored) {
        }
        System.exit(EXIT_STARTUP_FAILED);
    }

    /**
     * The client we run is a -SNAPSHOT build and RuneLite asks the Plugin Hub for the manifest of
     * its own version (repo.runelite.net/plugins/manifest/1.13.7-SNAPSHOT_full.js), which doesn't
     * exist (404), so the hub list was empty and 117 HD etc. could not be found. Point the hub at
     * the current release (from RuneLite's bootstrap, else the previous patch version).
     */
    private static void fixPluginHubVersion() {
        final String key = "runelite.pluginhub.version";
        if (System.getProperty(key) != null) return;
        try {
            java.util.Properties props = new java.util.Properties();
            try (java.io.InputStream in = AndroidRuneLite.class.getClassLoader().getResourceAsStream("net/runelite/client/runelite.properties")) {
                if (in == null) return;
                props.load(in);
            }
            String v = props.getProperty(key);
            if (v == null || !v.endsWith("-SNAPSHOT")) return;
            String rel = null;
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL("https://static.runelite.net/bootstrap.json").openConnection();
                c.setConnectTimeout(4000);
                c.setReadTimeout(4000);
                try (java.io.InputStream in = c.getInputStream()) {
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"name\"\\s*:\\s*\"client-([0-9.]+)\\.jar\"")
                            .matcher(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                    if (m.find()) rel = m.group(1);
                }
            } catch (Exception e) {
                System.err.println("PluginHub: bootstrap lookup failed: " + e);
            }
            if (rel == null) {
                String base = v.substring(0, v.length() - "-SNAPSHOT".length());
                int dot = base.lastIndexOf('.');
                int patch = Integer.parseInt(base.substring(dot + 1));
                rel = patch > 0 ? base.substring(0, dot + 1) + (patch - 1) : base;
            }
            System.setProperty(key, rel);
            System.out.println("PluginHub: client is " + v + ", using the " + rel + " Plugin Hub manifest");
        } catch (Exception e) {
            System.err.println("PluginHub: " + e);
        }
    }
}
