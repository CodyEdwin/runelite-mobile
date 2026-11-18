package net.kdt.pojavlaunch.runelite;

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
    /** Stop fighting the application if it keeps resizing the frame (e.g. min size > screen). */
    private static final int MAX_REFITS_PER_SECOND = 8;

    private static final Map<Window, Boolean> sDecorated = new WeakHashMap<>();
    private static boolean sFitting;
    private static long sRefitWindowStart;
    private static int sRefitCount;

    private WindowFitter() {
    }

    static void install() {
        Toolkit.getDefaultToolkit().addAWTEventListener(WindowFitter::onEvent,
                AWTEvent.WINDOW_EVENT_MASK | AWTEvent.COMPONENT_EVENT_MASK);
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
        } else if (id == ComponentEvent.COMPONENT_MOVED || id == ComponentEvent.COMPONENT_RESIZED) {
            if (!sFitting && window.isShowing()) SwingUtilities.invokeLater(() -> fit(window, false));
        }
    }

    private static void fit(Window window, boolean opened) {
        if (!window.isDisplayable()) return;
        Rectangle screen = screenBounds();
        if (isMainFrame(window)) {
            if (opened) stripChrome((Frame) window);
            if (!window.getBounds().equals(screen) && allowRefit()) {
                setBounds(window, screen);
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
    }

    private static boolean allowRefit() {
        long now = System.currentTimeMillis();
        if (now - sRefitWindowStart > 1000) {
            sRefitWindowStart = now;
            sRefitCount = 0;
        }
        return ++sRefitCount <= MAX_REFITS_PER_SECOND;
    }

    private static void setBounds(Window window, Rectangle bounds) {
        sFitting = true;
        try {
            window.setBounds(bounds);
            window.validate();
        } finally {
            sFitting = false;
        }
    }

    private static void stripChrome(Frame frame) {
        if (!(frame instanceof JFrame) || sDecorated.containsKey(frame)) return;
        sDecorated.put(frame, Boolean.TRUE);
        JFrame jFrame = (JFrame) frame;
        JRootPane rootPane = jFrame.getRootPane();
        if (rootPane.getWindowDecorationStyle() != JRootPane.NONE) {
            // Removes the look-and-feel drawn title bar (title, minimise/maximise/close).
            // RuneLite's toolbar lives in the JMenuBar, which stays visible.
            rootPane.setWindowDecorationStyle(JRootPane.NONE);
        }
        if (jFrame.getContentPane() instanceof JComponent) {
            // RuneLite adds a 4px border when the frame is not maximised; cacio never reports
            // a maximised state, so the border would always be there.
            ((JComponent) jFrame.getContentPane()).setBorder(null);
        }
        rootPane.revalidate();
        rootPane.repaint();
    }
}
