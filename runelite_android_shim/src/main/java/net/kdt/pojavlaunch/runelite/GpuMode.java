package net.kdt.pojavlaunch.runelite;

import javax.swing.Timer;
import net.runelite.client.RuneLite;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;

/**
 * Opt-in "Performance mode" from the launcher (-Drunelite.android.performanceMode=true).
 *
 * The game's software renderer (the JVM "Client" thread) is the FPS limit and its cost scales with
 * the number of 3D viewport pixels. Performance mode starts RuneLite's own GPU plugin plugin for
 * this session only (nothing is written to the profile, so turning the toggle off restores the
 * normal full-resolution picture): the scene is rendered at a lower resolution and stretched up,
 * using the user's GPU plugin settings (scaling factor, "increased performance" filtering).
 * When the toggle is off this class does nothing.
 */
/** GPU mode (experimental): starts RuneLite's GPU plugin for this session only (config untouched). */
final class GpuMode {
    static final boolean ENABLED = Boolean.getBoolean("runelite.android.gpu");
    private static final String STRETCHED = "net.runelite.client.plugins.gpu.GpuPlugin";

    private GpuMode() {
    }

    private static final String HD = "rs117.hd.HdPlugin";
    private static final long HD_LOAD_WAIT_MS = 120_000; // Plugin Hub plugins load after the core ones
    private static long t0;
    private static boolean stockStarted, hdWasActive;

    /**
     * Watchdog (every 2 s, cheap): runs exactly one GPU renderer. If 117 HD (Plugin Hub) is installed
     * and enabled it is given the GPU; the stock GPU plugin is started for the session only when 117 HD
     * isn't used, and again as a fallback when 117 HD stops (it disables itself on startup errors or
     * an unsupported GPU), so a failed 117 HD leaves the user on stock GPU instead of software.
     */
    static void install() {
        if (!ENABLED) return;
        patchShaders();
        t0 = System.currentTimeMillis();
        Timer timer = new Timer(2000, e -> tick());
        timer.start();
    }

    private static void tick() {
        try {
            if (RuneLite.getInjector() == null) return;
            PluginManager pm = RuneLite.getInjector().getInstance(PluginManager.class);
            boolean started = false;
            Plugin gpu = null, hd = null;
            for (Plugin p : pm.getPlugins()) {
                started |= pm.isPluginActive(p);
                String n = p.getClass().getName();
                if (STRETCHED.equals(n)) gpu = p; else if (HD.equals(n)) hd = p;
            }
            if (!started || gpu == null) return; // wait for RuneLite's own startPlugins()
            if (hd == null) autoInstallHd();
            boolean hdActive = hd != null && pm.isPluginActive(hd);
            if (hdActive) {
                if (!hdWasActive) System.out.println("GpuMode: 117 HD is active, stock GPU plugin stays off");
                hdWasActive = true;
                if (pm.isPluginActive(gpu)) {
                    System.out.println("GpuMode: stopping stock GPU plugin (117 HD has the GPU)");
                    pm.stopPlugin(gpu);
                }
                return;
            }
            boolean fallback = hdWasActive;
            hdWasActive = false;
            if (hd == null && hdExpected() && System.currentTimeMillis() - t0 < HD_LOAD_WAIT_MS) return;
            if (pm.isPluginActive(gpu) || (stockStarted && !fallback)) return;
            stockStarted = true;
            if (fallback) System.out.println("GpuMode: 117 HD stopped, falling back to the stock GPU plugin");
            if (pm.isPluginEnabled(gpu)) {
                pm.startPlugin(gpu); // enabled by the user: normal start
            } else {
                startForSession(pm, gpu);
                System.out.println("GpuMode: started GPU plugin for this session");
            }
        } catch (Throwable t) {
            stockStarted = true;
            System.err.println("GpuMode: " + t);
        }
    }

    private static boolean autoChecked;

    /**
     * GPU mode installs 117 HD once through RuneLite's own Plugin Hub install (signature- and
     * hash-checked download into ~/.runelite/plugins, same as pressing Install), because the hub
     * list didn't offer it on the phone. Remembered in the profile, so uninstalling it sticks.
     */
    private static void autoInstallHd() {
        if (autoChecked) return;
        autoChecked = true;
        net.runelite.client.config.ConfigManager cm = RuneLite.getInjector().getInstance(net.runelite.client.config.ConfigManager.class);
        if ("true".equals(cm.getConfiguration("runelitemobile", "hdAutoInstalled"))) return;
        cm.setConfiguration("runelitemobile", "hdAutoInstalled", "true");
        String hub = cm.getConfiguration("runelite", "externalPlugins");
        if (hub != null && java.util.Arrays.asList(hub.split(",")).contains("117hd")) return;
        System.out.println("GpuMode: installing 117 HD from the Plugin Hub");
        RuneLite.getInjector().getInstance(net.runelite.client.externalplugins.ExternalPluginManager.class).install("117hd");
        t0 = System.currentTimeMillis(); // give the ~10 MB download time before falling back
    }

    /** 117 HD is installed from the Plugin Hub and not disabled in the profile (hub jars load late). */
    private static boolean hdExpected() {
        net.runelite.client.config.ConfigManager cm = RuneLite.getInjector().getInstance(net.runelite.client.config.ConfigManager.class);
        String hub = cm.getConfiguration("runelite", "externalPlugins");
        if (hub == null || !java.util.Arrays.asList(hub.split(",")).contains("117hd")) return false;
        return !"false".equals(cm.getConfiguration("runelite", "hdplugin"));
    }

    /**
     * Same steps as PluginManager.startPlugin, minus its "is enabled in the profile" check, so the
     * plugin runs for this session without writing runelite.stretchedmodeplugin=true to the profile.
     */
    /**
     * Mali's GLES has no noperspective interpolation, and MobileGlues passes the qualifier through
     * (GL_NV_shader_noperspective_interpolation), so the GPU plugin's shaders fail to compile. Write
     * copies without it into the GPU classpath directory, which sits ahead of RuneLite's jar.
     */
    private static void patchShaders() {
        String dir = System.getProperty("runelite.android.gpuDir");
        if (dir == null) return;
        try {
            Class<?> gpu = Class.forName("net.runelite.client.plugins.gpu.GpuPlugin", false, GpuMode.class.getClassLoader());
            java.io.File jar = new java.io.File(gpu.getProtectionDomain().getCodeSource().getLocation().toURI());
            java.io.File out = new java.io.File(dir, "net/runelite/client/plugins/gpu");
            out.mkdirs();
            for (java.io.File old : out.listFiles()) old.delete();
            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar)) {
                java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zip.entries();
                while (en.hasMoreElements()) {
                    java.util.zip.ZipEntry e = en.nextElement();
                    String n = e.getName();
                    if (!n.startsWith("net/runelite/client/plugins/gpu/") || !n.endsWith(".glsl") || n.indexOf('/', 32) >= 0) continue;
                    String src = new String(zip.getInputStream(e).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    if (!src.contains("noperspective")) continue;
                    java.nio.file.Files.write(new java.io.File(out, n.substring(32)).toPath(),
                            src.replace("noperspective ", "").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    System.out.println("GpuMode: patched " + n.substring(32) + " (no noperspective on Mali)");
                }
            }
        } catch (Throwable t) {
            System.err.println("GpuMode: shader patch failed: " + t);
        }
    }

    private static void startForSession(PluginManager pm, Plugin p) throws Exception {
        java.lang.reflect.Field f = PluginManager.class.getDeclaredField("activePlugins");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Collection<Plugin> active = (java.util.Collection<Plugin>) f.get(pm);
        active.add(p);
        java.lang.reflect.Method startUp = Plugin.class.getDeclaredMethod("startUp");
        startUp.setAccessible(true);
        try {
            startUp.invoke(p);
        } catch (Exception e) {
            active.remove(p);
            throw e;
        }
        RuneLite.getInjector().getInstance(net.runelite.client.eventbus.EventBus.class).register(p);
    }

}
