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

    static void install() {
        if (!ENABLED) return;
        patchShaders();
        final int[] attempts = {0};
        Timer timer = new Timer(1000, null);
        timer.addActionListener(e -> {
            if (tryStart() || ++attempts[0] > 60) timer.stop();
        });
        timer.start();
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

    private static boolean tryStart() {
        try {
            if (RuneLite.getInjector() == null) return false;
            PluginManager pm = RuneLite.getInjector().getInstance(PluginManager.class);
            boolean started = false;
            for (Plugin p : pm.getPlugins()) started |= pm.isPluginActive(p);
            if (!started) return false; // wait for RuneLite's own startPlugins()
            for (Plugin p : pm.getPlugins()) {
                if (!STRETCHED.equals(p.getClass().getName())) continue;
                if (pm.isPluginActive(p)) {
                    System.out.println("GpuMode: GPU plugin already active");
                    return true;
                }
                if (pm.isPluginEnabled(p)) {
                    // Enabled by the user: PluginManager starts it itself.
                    return pm.startPlugin(p) || pm.isPluginActive(p);
                }
                startForSession(pm, p);
                System.out.println("GpuMode: started GPU plugin for this session");
                return true;
            }
            return false; // plugins not loaded yet
        } catch (Throwable t) {
            System.err.println("GpuMode: " + t);
            return true;
        }
    }
}
