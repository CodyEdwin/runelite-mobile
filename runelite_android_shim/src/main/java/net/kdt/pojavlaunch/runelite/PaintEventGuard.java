package net.kdt.pojavlaunch.runelite;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.EventQueue;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.PaintEvent;

/**
 * Fixes the GUI flicker.
 *
 * Caciocavallo turns every "expose" (a tooltip or popup closing, a window moving, the sidebar
 * opening, ...) into AWT PAINT events for each heavyweight peer below it. Handling such an event
 * first paints the peer's own proxy component, i.e. fills the whole area with the background
 * colour, and only then lets the component paint itself. The Android blitter copies the cacio
 * screen from another thread, so it regularly published the half-done state: sidebar panels and the
 * game area flashing dark grey.
 *
 * 1. PAINT/UPDATE events are dispatched while holding CTCScreen.SCREEN_LOCK (the lock the blitter
 *    copies under), so a clear and the repaint that follows it are atomic for the blitter.
 * 2. PAINT events for the game canvas (and the applet panel that holds it) are dropped: the client
 *    redraws that area itself every game frame, and the event would only clear it to background
 *    until the next frame arrives.
 */
final class PaintEventGuard extends EventQueue {
    private static final String CLIENT_PANEL = "net.runelite.client.ui.ClientPanel";
    private static int sDropped;

    private PaintEventGuard() {
    }

    static void install() {
        if (Boolean.getBoolean("runelite.android.noPaintGuard")) return;
        Toolkit.getDefaultToolkit().getSystemEventQueue().push(new PaintEventGuard());
    }

    @Override
    protected void dispatchEvent(AWTEvent event) {
        if (event instanceof PaintEvent) {
            Object source = event.getSource();
            if (isGameSurface(source)) {
                if (WindowFitter.DEBUG && sDropped++ < 20) System.out.println("PaintEventGuard: dropped " + event);
                return;
            }
            Object lock = LockingRepaintManager.LOCK;
            if (lock != null) {
                synchronized (lock) {
                    super.dispatchEvent(event);
                }
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
