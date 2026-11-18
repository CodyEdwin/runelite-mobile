package net.kdt.pojavlaunch.runelite;

import java.awt.Frame;
import java.awt.Window;
import javax.swing.RepaintManager;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * Swing repaints on the caciocavallo screen only became visible once the pointer moved over the
 * component (the sidebar and plugin panels stayed stale until hovered). Caciocavallo has no
 * native expose/damage loop: on desktop toolkits the window system keeps generating PAINT events
 * and the EDT is woken by native input/paint traffic, here only by our injected mouse events.
 *
 * This pump runs on the EDT at ~30 Hz: it validates any pending layout and flushes Swing's dirty
 * regions into cacio's screen buffer, which AWTCanvasView blits every vsync. When nothing is
 * dirty both calls are no-ops, so the cost is negligible.
 */
final class RepaintPump {
    private static final int PERIOD_MS = Integer.getInteger("runelite.android.repaintPumpMs", 33);
    private static Timer sTimer;

    private RepaintPump() {
    }

    static void install() {
        if (PERIOD_MS <= 0) return;
        SwingUtilities.invokeLater(() -> {
            if (sTimer != null) return;
            sTimer = new Timer(PERIOD_MS, e -> pump());
            sTimer.setCoalesce(true);
            sTimer.setRepeats(true);
            sTimer.start();
        });
    }

    private static int sTicks;

    private static void pump() {
        InputFocus.ensureFocusOwner();
        WindowFitter.enforce();
        if ((sTicks++ % 30) == 0) simpleScrollViewports();
        if (sTicks % 150 == 0 && sTicks <= 150 * 12) WindowFitter.debugDump();
        for (Window window : Window.getWindows()) {
            if (!window.isShowing()) continue;
            if (!window.isValid()) window.validate();
            RepaintManager manager = RepaintManager.currentManager(window);
            manager.validateInvalidComponents();
            manager.paintDirtyRegions();
        }
        PopupOverdraw.snapshot();
    }

    /**
     * JViewport's default BLIT_SCROLL_MODE scrolls with Graphics#copyArea on the window and only
     * repaints the newly exposed strip; on the cacio screen that left scrolled plugin lists blank.
     */
    private static void simpleScrollViewports() {
        for (Window window : Window.getWindows()) {
            if (window.isShowing()) simpleScroll(window);
        }
    }

    private static void simpleScroll(java.awt.Component c) {
        if (c instanceof javax.swing.JViewport) {
            javax.swing.JViewport v = (javax.swing.JViewport) c;
            if (v.getScrollMode() != javax.swing.JViewport.SIMPLE_SCROLL_MODE) {
                v.setScrollMode(javax.swing.JViewport.SIMPLE_SCROLL_MODE);
                v.repaint();
            }
        }
        if (c instanceof java.awt.Container) {
            for (java.awt.Component child : ((java.awt.Container) c).getComponents()) simpleScroll(child);
        }
    }

    /** Full repaint of every visible window, used after layout changes such as the sidebar toggle. */
    static void repaintAll() {
        SwingUtilities.invokeLater(() -> {
            for (Window window : Window.getWindows()) {
                if (window.isShowing()) window.repaint();
            }
        });
    }

    static boolean isFrame(Window window) {
        return window instanceof Frame;
    }
}
