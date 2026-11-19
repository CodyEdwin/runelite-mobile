package com.codyedwin.runelitemobile.shim;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.PaintEvent;
import javax.swing.JRootPane;
import javax.swing.RepaintManager;
import javax.swing.RootPaneContainer;
import javax.swing.SwingUtilities;

/**
 * GUI flicker fix without any locking.
 *
 * Caciocavallo turns every expose (a tooltip or popup closing, the sidebar opening, ...) into AWT
 * PAINT events. Dispatching one first fills the heavyweight's area with its background colour and
 * only then lets Swing paint, so the Android blitter (which copies the screen whenever it changed)
 * sometimes published the dark-grey half state.
 *
 * Instead of dispatching those events:
 *  - PAINT for a Swing window becomes a dirty region on its root pane: Swing repaints it through its
 *    double buffer on the next RepaintPump tick, in one blit, and nothing is ever cleared first;
 *  - PAINT for the game canvas (and the applet panel holding it) is dropped: the client redraws
 *    that area itself every frame.
 * Nothing here waits on the screen lock, so it cannot stall the game's Client thread or the blitter.
 * Disable with -Drunelite.android.noPaintRedirect=true or ~/.runelite/android-no-paint-redirect.
 */
final class PaintRedirect extends java.awt.EventQueue {
    private static final String CLIENT_PANEL = "net.runelite.client.ui.ClientPanel";

    private PaintRedirect() {
    }

    static void install() {
        if (Boolean.getBoolean("runelite.android.noPaintRedirect")
                || new java.io.File(System.getProperty("user.home"), ".runelite/android-no-paint-redirect").exists()) return;
        Toolkit.getDefaultToolkit().getSystemEventQueue().push(new PaintRedirect());
        System.out.println("PaintRedirect: installed");
    }

    @Override
    protected void dispatchEvent(AWTEvent event) {
        if (event instanceof PaintEvent) {
            Object source = event.getSource();
            if (source instanceof Window && source instanceof RootPaneContainer) {
                Window w = (Window) source;
                JRootPane root = ((RootPaneContainer) w).getRootPane();
                if (root != null && root.isShowing()) {
                    Rectangle r = ((PaintEvent) event).getUpdateRect();
                    Rectangle rr = SwingUtilities.convertRectangle(w, r, root);
                    RepaintManager.currentManager(root).addDirtyRegion(root, rr.x, rr.y, rr.width, rr.height);
                    return;
                }
            } else if (isGameSurface(source)) {
                return;
            }
        }
        super.dispatchEvent(event);
    }

    private static boolean isGameSurface(Object source) {
        if (!(source instanceof Component) || source instanceof Window) return false;
        Component c = (Component) source;
        if (c.isLightweight()) return false;
        for (Component p = c.getParent(); p != null; p = p.getParent()) {
            if (CLIENT_PANEL.equals(p.getClass().getName())) return true;
        }
        return false;
    }
}
