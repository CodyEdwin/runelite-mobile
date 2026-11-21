package com.codyedwin.runelitemobile.shim;

import javax.swing.Timer;
import net.runelite.client.RuneLite;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;

/**
 * Opt-in "Performance mode" from the launcher (-Drunelite.android.performanceMode=true).
 *
 * The game's software renderer (the JVM "Client" thread) is the FPS limit and its cost scales with
 * the number of 3D viewport pixels. Performance mode starts RuneLite's own Stretched Mode plugin for
 * this session only (nothing is written to the profile, so turning the toggle off restores the
 * normal full-resolution picture): the scene is rendered at a lower resolution and stretched up,
 * using the user's Stretched Mode settings (scaling factor, "increased performance" filtering).
 * When the toggle is off this class does nothing.
 */
final class PerformanceMode {
    static final boolean ENABLED = Boolean.getBoolean("runelite.android.performanceMode");
    private static final String STRETCHED = "net.runelite.client.plugins.stretchedmode.StretchedModePlugin";
    /** Render resolution slider (percent of the screen); 0 = use the Stretched Mode settings. */
    private static final int SCALE = Integer.getInteger("runelite.android.renderScale", 0);

    private PerformanceMode() {
    }

    static void install() {
        if (!ENABLED) return;
        final int[] attempts = {0};
        Timer timer = new Timer(1000, null);
        timer.addActionListener(e -> {
            if (tryStart() || ++attempts[0] > 60) {
                timer.stop();
                applyScale();
            }
        });
        timer.start();
    }

    /**
     * Same steps as PluginManager.startPlugin, minus its "is enabled in the profile" check, so the
     * plugin runs for this session without writing runelite.stretchedmodeplugin=true to the profile.
     */
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

    /** Stretched Mode renders resizable at real size / (1 + scalingFactor / 100). */
    private static void applyScale() {
        if (SCALE < 25 || SCALE >= 100) return;
        try {
            net.runelite.api.Client client = RuneLite.getInjector().getInstance(net.runelite.api.Client.class);
            client.setScalingFactor((int) Math.round((100.0 / SCALE - 1) * 100));
            client.invalidateStretching(true);
            System.out.println("PerformanceMode: render resolution " + SCALE + "%");
        } catch (Throwable t) {
            System.err.println("PerformanceMode: " + t);
        }
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
                    System.out.println("PerformanceMode: Stretched Mode already active");
                    return true;
                }
                if (pm.isPluginEnabled(p)) {
                    // Enabled by the user: PluginManager starts it itself.
                    return pm.startPlugin(p) || pm.isPluginActive(p);
                }
                startForSession(pm, p);
                System.out.println("PerformanceMode: started Stretched Mode for this session");
                return true;
            }
            return false; // plugins not loaded yet
        } catch (Throwable t) {
            System.err.println("PerformanceMode: " + t);
            return true;
        }
    }
}
