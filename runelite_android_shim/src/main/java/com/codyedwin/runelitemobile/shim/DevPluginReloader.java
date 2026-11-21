package com.codyedwin.runelitemobile.shim;

import com.google.common.reflect.ClassPath;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.SwingUtilities;
import net.runelite.client.RuneLite;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.events.ExternalPluginsChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;

/**
 * Hot-reloads plugin jars from /sdcard/RuneLite/plugins: when a jar there changes (or is added or
 * removed) its plugins are stopped and the new build is loaded and started, without restarting
 * RuneLite. The launcher already copied the jars present at PLAY into sideloaded-plugins. With the
 * custom client, /sdcard/RuneLite/custom-plugins ("Update custom plugins") is watched too.
 */
final class DevPluginReloader {
    private static final Map<String, String> stamps = new HashMap<>();
    private static final Map<String, List<Plugin>> loaded = new HashMap<>();

    private DevPluginReloader() {
    }

    static File dir() {
        String ext = System.getenv("EXTERNAL_STORAGE");
        return new File(ext != null && !ext.isEmpty() ? ext : "/sdcard", "RuneLite/plugins");
    }

    static void install() {
        List<File> dirs = new ArrayList<>();
        dirs.add(dir());
        if (Boolean.getBoolean("runelite.android.custom")) dirs.add(new File(dir().getParentFile(), "custom-plugins"));
        Thread t = new Thread(() -> {
            try {
                scan(dirs, true);
            } catch (Exception e) {
                System.err.println("DevPlugins: " + e);
            }
            while (true) {
                try {
                    Thread.sleep(2000);
                    scan(dirs, false);
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable e) {
                    System.err.println("DevPlugins: " + e);
                }
            }
        }, "RuneLite-DevPlugins");
        t.setDaemon(true);
        t.start();
    }

    /** Stamps are keyed by full path; plugins are matched by jar name. */
    private static void scan(List<File> dirs, boolean baseline) throws Exception {
        Map<String, String> now = new HashMap<>();
        for (File dir : dirs) {
            File[] jars = dir.listFiles((d, n) -> n.endsWith(".jar"));
            if (jars != null) for (File f : jars) now.put(f.getPath(), f.lastModified() + ":" + f.length());
        }
        if (baseline) {
            stamps.putAll(now);
            return;
        }
        for (Map.Entry<String, String> e : now.entrySet()) {
            if (e.getValue().equals(stamps.get(e.getKey()))) continue;
            File jar = new File(e.getKey());
            // Wait until the copy has finished (size stable).
            long len = jar.length();
            Thread.sleep(500);
            if (jar.length() != len) continue;
            stamps.put(e.getKey(), e.getValue());
            reload(jar.getName(), jar);
        }
        for (String gone : new ArrayList<>(stamps.keySet())) {
            if (now.containsKey(gone)) continue;
            stamps.remove(gone);
            reload(new File(gone).getName(), null);
        }
    }

    private static List<Plugin> pluginsFrom(PluginManager pm, String jarName) {
        List<Plugin> list = loaded.get(jarName);
        if (list != null) return list;
        list = new ArrayList<>();
        for (Plugin p : pm.getPlugins()) {
            try {
                URL loc = p.getClass().getProtectionDomain().getCodeSource().getLocation();
                if (loc != null && new File(loc.toURI()).getName().equals(jarName)) list.add(p);
            } catch (Exception ignored) {
            }
        }
        return list;
    }

    private static void reload(String jarName, File jar) throws Exception {
        if (RuneLite.getInjector() == null) return;
        PluginManager pm = RuneLite.getInjector().getInstance(PluginManager.class);
        List<Plugin> old = pluginsFrom(pm, jarName);
        List<Class<?>> classes = new ArrayList<>();
        if (jar != null) {
            File cache = new File(System.getProperty("user.home"), ".runelite/dev-plugin-cache");
            cache.mkdirs();
            File copy = new File(cache, System.currentTimeMillis() + "-" + jarName);
            Files.copy(jar.toPath(), copy.toPath(), StandardCopyOption.REPLACE_EXISTING);
            ClassLoader cl = new URLClassLoader(new URL[]{copy.toURI().toURL()}, PluginManager.class.getClassLoader());
            for (ClassPath.ClassInfo info : ClassPath.from(cl).getAllClasses()) {
                if (!(info.url().toString().startsWith("jar:" + copy.toURI()))) continue;
                try {
                    classes.add(info.load());
                } catch (Throwable ignored) {
                }
            }
        }
        List<Plugin> fresh = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            for (Plugin p : old) {
                try {
                    pm.stopPlugin(p);
                } catch (Throwable e) {
                    System.err.println("DevPlugins: stopping " + p.getName() + ": " + e);
                }
                pm.remove(p);
            }
            if (!classes.isEmpty()) {
                try {
                    fresh.addAll(pm.loadPlugins(classes, null));
                    pm.loadDefaultPluginConfiguration(fresh);
                    for (Plugin p : fresh) if (pm.isPluginEnabled(p)) pm.startPlugin(p);
                } catch (Throwable e) {
                    System.err.println("DevPlugins: loading " + jarName + ": " + e);
                }
            }
        });
        loaded.put(jarName, fresh);
        RuneLite.getInjector().getInstance(EventBus.class).post(new ExternalPluginsChanged());
        System.out.println("DevPlugins: " + (jar == null ? "removed " : "reloaded ") + jarName + " (" + old.size() + " -> " + fresh.size() + " plugins)");
    }
}
