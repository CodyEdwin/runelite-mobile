package net.kdt.pojavlaunch.runelite;

import java.awt.AWTEvent;
import java.awt.Canvas;
import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import javax.swing.SwingUtilities;

/**
 * Keyboard focus for the caciocavallo screen. On a desktop the window system moves keyboard focus
 * to whatever heavyweight was clicked (the OSRS game canvas); cacio only tracks a focused
 * ManagedWindow and never makes the clicked AWT Canvas the KeyboardFocusManager focus owner, so
 * key events had no focus owner and were dropped. Do what the window system would do: focus the
 * clicked component (game canvas or a focusable Swing component), and make sure some component of
 * the active frame owns focus when a key arrives.
 */
final class InputFocus {
    private static final boolean DEBUG = !"false".equals(System.getProperty("runelite.android.debugInput"));
    private static int sLogged;

    private InputFocus() {
    }

    static void install() {
        Toolkit.getDefaultToolkit().addAWTEventListener(InputFocus::onEvent,
                AWTEvent.MOUSE_EVENT_MASK | AWTEvent.KEY_EVENT_MASK);
    }

    private static long sLastCheck;

    /** Called on the EDT by RepaintPump: never leave the visible client without a focus owner. */
    static void ensureFocusOwner() {
        long now = System.currentTimeMillis();
        if (now - sLastCheck < 500) return;
        sLastCheck = now;
        KeyboardFocusManager kfm = KeyboardFocusManager.getCurrentKeyboardFocusManager();
        if (kfm.getFocusOwner() != null) return;
        for (Window w : Window.getWindows()) {
            if (!w.isShowing() || !(w instanceof java.awt.Frame)) continue;
            Component canvas = findCanvas(w);
            Component target = canvas != null ? canvas : w;
            if (DEBUG) System.out.println("InputFocus: no focus owner, focusing " + target.getClass().getName());
            w.toFront();
            target.requestFocus();
            return;
        }
    }

    private static Component findCanvas(Component c) {
        if (c instanceof Canvas && c.isShowing()) return c;
        if (c instanceof java.awt.Container) {
            for (Component child : ((java.awt.Container) c).getComponents()) {
                Component found = findCanvas(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void onEvent(AWTEvent event) {
        if (event instanceof MouseEvent && event.getID() == MouseEvent.MOUSE_PRESSED) {
            Component c = ((MouseEvent) event).getComponent();
            if (c == null) return;
            Component target = c;
            while (target != null && !(target.isFocusable() && target.isShowing() && target.isEnabled())) {
                if (target instanceof Window) break;
                target = target.getParent();
            }
            if (target != null && !target.isFocusOwner()) {
                // Swing components request focus themselves; AWT heavyweights (game canvas) don't.
                if (c instanceof Canvas || KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() == null) {
                    final Component t = target;
                    SwingUtilities.invokeLater(t::requestFocus);
                }
            }
        } else if (event instanceof KeyEvent && DEBUG && sLogged < 40) {
            sLogged++;
            KeyEvent k = (KeyEvent) event;
            System.out.println("InputFocus: key id=" + k.getID() + " code=" + k.getKeyCode() + " char=" + (int) k.getKeyChar()
                    + " src=" + k.getComponent().getClass().getName()
                    + " owner=" + KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner());
        }
    }
}
