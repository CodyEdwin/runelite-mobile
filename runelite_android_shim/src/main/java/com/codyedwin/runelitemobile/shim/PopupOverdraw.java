package com.codyedwin.runelitemobile.shim;

import java.awt.Component;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JRootPane;
import javax.swing.RootPaneContainer;

/**
 * Keeps heavyweight popups (tooltips, popup menus, dialogs) visible above the game canvas.
 *
 * The OSRS client caches the Graphics of its canvas and blits every game frame through it.
 * Caciocavallo computes a window's occlusion clip only when a Graphics is created, so that cached
 * Graphics never learns about windows opened later and the game frame overwrites any popup that
 * overlaps the canvas (sidebar tooltips were cut off at the canvas edge).
 *
 * Fix: on the EDT (RepaintPump tick) snapshot every visible secondary window that overlaps the
 * canvas, and right after each game frame (DrawManager every-frame listener, same thread that just
 * drew the frame) draw the snapshot back through a freshly created window Graphics.
 */
final class PopupOverdraw {
    private static final class Entry {
        final Window window;
        final BufferedImage image;
        Entry(Window window, BufferedImage image) {
            this.window = window;
            this.image = image;
        }
    }

    private static volatile List<Entry> sEntries = new ArrayList<>();
    private static boolean sInstalled;

    private PopupOverdraw() {
    }

    static void install() {
        if (sInstalled) return;
        try {
            Class<?> runeLite = Class.forName("net.runelite.client.RuneLite");
            Object injector = runeLite.getMethod("getInjector").invoke(null);
            if (injector == null) return;
            Class<?> drawManagerClass = Class.forName("net.runelite.client.ui.DrawManager");
            Object drawManager = Class.forName("com.google.inject.Injector").getMethod("getInstance", Class.class)
                    .invoke(injector, drawManagerClass);
            Runnable listener = PopupOverdraw::afterGameFrame;
            drawManagerClass.getMethod("registerEveryFrameListener", Runnable.class).invoke(drawManager, listener);
            sInstalled = true;
            System.out.println("PopupOverdraw: installed");
        } catch (Throwable t) {
            System.err.println("PopupOverdraw: unable to install: " + t);
        }
    }

    /** EDT: refresh snapshots of secondary windows that overlap the game canvas. */
    static void snapshot() {
        if (!sInstalled) return;
        Rectangle canvas = null;
        for (Window w : Window.getWindows()) {
            if (w instanceof Frame && w.isShowing()) {
                Component c = InputFocus.findCanvas(w);
                if (c != null) {
                    canvas = new Rectangle(c.getLocationOnScreen(), c.getSize());
                    break;
                }
            }
        }
        List<Entry> entries = new ArrayList<>();
        if (canvas != null) {
            for (Window w : Window.getWindows()) {
                if (!w.isShowing() || w instanceof Frame || !(w instanceof RootPaneContainer)) continue;
                if (w.getWidth() <= 0 || w.getHeight() <= 0) continue;
                Rectangle bounds = new Rectangle(w.getLocationOnScreen(), w.getSize());
                if (!bounds.intersects(canvas)) continue;
                BufferedImage image = new BufferedImage(w.getWidth(), w.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D g = image.createGraphics();
                try {
                    JRootPane root = ((RootPaneContainer) w).getRootPane();
                    root.paint(g);
                } finally {
                    g.dispose();
                }
                entries.add(new Entry(w, image));
            }
        }
        sEntries = entries;
    }

    /** Client thread, right after the game frame was blitted onto the canvas. */
    private static void afterGameFrame() {
        List<Entry> entries = sEntries;
        if (entries.isEmpty()) return;
        for (Entry e : entries) {
            if (!e.window.isShowing()) continue;
            Graphics g = e.window.getGraphics();
            if (g == null) continue;
            Object lock = LockingRepaintManager.LOCK != null ? LockingRepaintManager.LOCK : g;
            synchronized (lock) {
                try {
                    g.drawImage(e.image, 0, 0, null);
                } finally {
                    g.dispose();
                }
            }
        }
    }
}
