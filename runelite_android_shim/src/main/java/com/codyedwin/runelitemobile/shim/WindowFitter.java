package com.codyedwin.runelitemobile.shim;

import java.awt.AWTEvent;
import java.awt.Dialog;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowEvent;
import java.util.Map;
import java.util.WeakHashMap;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JRootPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * Tiny window manager for the caciocavallo virtual screen.
 *
 * <ul>
 *   <li>The RuneLite main frame is kept at exactly the virtual screen bounds, so the game area is
 *   laid out for the whole Android surface instead of sitting in a corner. RuneLite's own layout
 *   then grows the game panel into the extra space, just as when a desktop window is resized.</li>
 *   <li>The custom (FlatLaf) title bar and the 4px chrome border are removed from the main frame:
 *   there is no window to drag/close on Android and they only shift the game canvas.</li>
 *   <li>Dialogs and secondary frames are clamped into, and centred on, the virtual screen so
 *   they can never open off-screen (where they could not be seen or clicked).</li>
 * </ul>
 */
final class WindowFitter {
    private static final String MAIN_FRAME_CLASS = "net.runelite.client.ui.ContainableFrame";
    /**
     * RuneLite re-packs its frame after many UI changes (sidebar toggles, panel rebuilds), often
     * several times in a row. Re-fit once things settle instead of on every intermediate event.
     */
    private static final int REFIT_DELAY_MS = 120;

    private static final Map<Window, Boolean> sDecorated = new WeakHashMap<>();
    private static final Map<Window, Timer> sRefitTimers = new WeakHashMap<>();
    private static final boolean DEBUG = Boolean.getBoolean("runelite.android.debugWindows");

    private WindowFitter() {
    }

    static void install() {
        Toolkit.getDefaultToolkit().addAWTEventListener(WindowFitter::onEvent,
                AWTEvent.WINDOW_EVENT_MASK | AWTEvent.COMPONENT_EVENT_MASK | AWTEvent.WINDOW_STATE_EVENT_MASK);
    }

    private static Rectangle screenBounds() {
        return GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration().getBounds();
    }

    private static boolean isMainFrame(Window window) {
        return window instanceof Frame && MAIN_FRAME_CLASS.equals(window.getClass().getName());
    }

    private static void onEvent(AWTEvent event) {
        Object source = event.getSource();
        if (!(source instanceof Window)) return;
        Window window = (Window) source;
        int id = event.getID();

        if (id == WindowEvent.WINDOW_OPENED || id == ComponentEvent.COMPONENT_SHOWN) {
            SwingUtilities.invokeLater(() -> fit(window, true));
        } else if (id == ComponentEvent.COMPONENT_MOVED || id == ComponentEvent.COMPONENT_RESIZED
                || id == WindowEvent.WINDOW_STATE_CHANGED) {
            if (window.isShowing()) scheduleRefit(window);
        }
    }

    private static void scheduleRefit(Window window) {
        Timer timer = sRefitTimers.get(window);
        if (timer == null) {
            timer = new Timer(REFIT_DELAY_MS, e -> fit(window, false));
            timer.setRepeats(false);
            sRefitTimers.put(window, timer);
        }
        timer.restart();
    }

    private static void fit(Window window, boolean opened) {
        if (!window.isDisplayable()) return;
        Rectangle screen = screenBounds();
        if (isMainFrame(window)) {
            if (opened) stripChrome((Frame) window);
            // Plugins (e.g. a "Fullscreen" plugin calling GraphicsDevice#setFullScreenWindow and then
            // Frame#setExtendedState(previousState)) or RuneLite itself can reset the frame to NORMAL.
            // RuneLite then grows the frame by the sidebar width instead of shrinking the game.
            boolean relayout = markMaximized((Frame) window);
            if (relayout && window instanceof JFrame) {
                ((JFrame) window).getContentPane().invalidate();
                ((JFrame) window).getRootPane().revalidate();
            }
            Rectangle target = mainFrameBounds(window, screen);
            if (!window.getBounds().equals(target)) {
                if (DEBUG) System.out.println("WindowFitter: main frame " + window.getBounds() + " -> " + target);
                setBounds(window, target);
                RepaintPump.repaintAll();
            }
        } else if (window instanceof Dialog || window instanceof Frame) {
            Rectangle b = window.getBounds();
            Rectangle fitted = new Rectangle(b);
            fitted.width = Math.min(b.width, screen.width);
            fitted.height = Math.min(b.height, screen.height);
            if (opened) {
                // Dialogs are usually positioned relative to the (previously tiny) owner window.
                fitted.x = screen.x + (screen.width - fitted.width) / 2;
                fitted.y = screen.y + (screen.height - fitted.height) / 2;
            } else {
                fitted.x = Math.max(screen.x, Math.min(b.x, screen.x + screen.width - fitted.width));
                fitted.y = Math.max(screen.y, Math.min(b.y, screen.y + screen.height - fitted.height));
            }
            if (!fitted.equals(b)) setBounds(window, fitted);
        }
        // Other Window types (tooltips, heavyweight popup menus) are positioned by Swing itself.
        if (DEBUG && isMainFrame(window)) dump(window, screen);
    }

    private static int sDumps;

    /**
     * Called on every RepaintPump tick. Cacio does not post COMPONENT_RESIZED/MOVED for frames whose
     * bounds are changed by the application (ClientUI#containedSetSize, plugins, Frame#setBounds),
     * so the event listener alone missed RuneLite shrinking the frame (e.g. to its 503px minimum
     * height, leaving a black band at the bottom) or growing it past the right edge.
     */
    private static int sEnforced;

    static void enforce() {
        Rectangle screen = null;
        for (Window w : Window.getWindows()) {
            if (!w.isShowing() || !isMainFrame(w)) continue;
            if (screen == null) screen = screenBounds();
            boolean lostMaximized = (((Frame) w).getExtendedState() & Frame.MAXIMIZED_BOTH) != Frame.MAXIMIZED_BOTH;
            if (lostMaximized || !w.getBounds().equals(mainFrameBounds(w, screen))) {
                if (DEBUG && sEnforced++ < 50) System.out.println("WindowFitter: enforce #" + sEnforced + " " + w.getBounds() + " state=" + ((Frame) w).getExtendedState());
                fit(w, false);
            }
        }
    }

    /** Debug: periodic layout dump of the main frame (RepaintPump). */
    static void debugDump() {
        if (!DEBUG) return;
        for (Window w : Window.getWindows()) {
            if (w.isShowing() && isMainFrame(w)) dump(w, screenBounds());
        }
    }

    private static void dump(Window window, Rectangle screen) {
        if (sDumps++ > 200) return;
        StringBuilder sb = new StringBuilder("WindowFitter: screen=").append(screen)
                .append(" state=").append(((Frame) window).getExtendedState()).append('\n');
        dump(sb, window, 0);
        System.out.print(sb);
    }

    private static void dump(StringBuilder sb, java.awt.Component c, int depth) {
        for (int i = 0; i < depth; i++) sb.append("  ");
        sb.append(c.getClass().getName()).append(' ').append(c.getBounds())
                .append(c.isVisible() ? "" : " hidden").append(" pref=").append(c.getPreferredSize())
                .append(" min=").append(c.getMinimumSize()).append('\n');
        if (depth < 6 && c instanceof java.awt.Container) {
            for (java.awt.Component child : ((java.awt.Container) c).getComponents()) dump(sb, child, depth + 1);
        }
    }

    private static java.awt.Insets sLoggedInsets;

    /**
     * Screen bounds grown by the frame's insets: cacio reserves insets for a native title bar it
     * never draws (an empty black strip at the top), so push that area off-screen.
     */
    private static Rectangle mainFrameBounds(Window window, Rectangle screen) {
        java.awt.Insets in = window.getInsets();
        if (in == null) return screen;
        if (!in.equals(sLoggedInsets)) {
            sLoggedInsets = in;
            System.out.println("WindowFitter: main frame insets " + in);
        }
        return new Rectangle(screen.x - in.left, screen.y - in.top,
                screen.width + in.left + in.right, screen.height + in.top + in.bottom);
    }

    private static void setBounds(Window window, Rectangle bounds) {
        window.setBounds(bounds);
        window.validate();
    }

    /**
     * Make the frame report MAXIMIZED_BOTH. Caciocavallo rejects setExtendedState(MAXIMIZED_BOTH)
     * (Toolkit#isFrameStateSupported only accepts NORMAL), so RuneLite always thinks it is a
     * normal desktop window: with "keep game size" it grows the frame by the sidebar width when
     * the sidebar opens (pushing it off-screen) instead of shrinking the game area, and it adds a
     * 4px border. With the state set, ClientUI.Layout sizes the game as contentWidth - sidebar,
     * exactly like a maximised desktop window. Needs --add-opens java.desktop/java.awt.
     */
    private static boolean markMaximized(Frame frame) {
        if ((frame.getExtendedState() & Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH) return false;
        try {
            java.lang.reflect.Field state = Frame.class.getDeclaredField("state");
            state.setAccessible(true);
            state.setInt(frame, (state.getInt(frame) & ~Frame.ICONIFIED) | Frame.MAXIMIZED_BOTH);
            System.out.println("WindowFitter: main frame state was " + frame.getExtendedState() + ", re-marked maximised");
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("WindowFitter: unable to mark the RuneLite frame maximised: " + e);
            return false;
        }
    }

    private static void stripChrome(Frame frame) {
        if (!(frame instanceof JFrame) || sDecorated.containsKey(frame)) return;
        sDecorated.put(frame, Boolean.TRUE);
        JFrame jFrame = (JFrame) frame;
        JRootPane rootPane = jFrame.getRootPane();
        // Removes the look-and-feel drawn title bar (title, minimise/maximise/close), which showed
        // as an empty black strip across the top. RuneLite (custom chrome) can set the decoration
        // style again after this runs, so keep forcing it off.
        rootPane.putClientProperty("JRootPane.useWindowDecorations", Boolean.FALSE);
        if (rootPane.getWindowDecorationStyle() != JRootPane.NONE) rootPane.setWindowDecorationStyle(JRootPane.NONE);
        rootPane.addPropertyChangeListener("windowDecorationStyle", e -> {
            if (rootPane.getWindowDecorationStyle() != JRootPane.NONE) {
                SwingUtilities.invokeLater(() -> {
                    rootPane.setWindowDecorationStyle(JRootPane.NONE);
                    rootPane.revalidate();
                    rootPane.repaint();
                });
            }
        });
        markMaximized(frame);
        SwingUtilities.invokeLater(() -> {
            ToolbarMover.relocate(jFrame);
            PopupOverdraw.install();
        });
        if (jFrame.getContentPane() instanceof JComponent) {
            // RuneLite adds a 4px border when the frame is not maximised; cacio never reports
            // a maximised state, so the border would always be there.
            ((JComponent) jFrame.getContentPane()).setBorder(null);
        }
        rootPane.revalidate();
        rootPane.repaint();
    }
}
