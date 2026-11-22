package com.codyedwin.runelitemobile.shim;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;

/**
 * Touch Mode sends every one-finger drag as a middle-button (camera) drag. This routes it by
 * where the finger went down:
 *  - on a scrollable list (bank, chat, quest list...): the drag scrolls it (mouse wheel notches);
 *  - on any other interface (inventory slot, scrollbar thumb, tab): a left-button drag, so items
 *    can be moved;
 *  - on the game world: the camera drag, unchanged.
 * Only active in Touch Mode (-Drunelite.android.touchMode=true).
 */
final class TouchRouter extends java.awt.EventQueue {
    private static final int SCROLL_STEP_PX = Integer.getInteger("runelite.android.swipeStep", 14);
    private enum Mode { NONE, CAMERA, LEFT, SCROLL, SWING }

    private static Client sClient;
    private Mode mode = Mode.NONE;
    private int lastY;
    private int scrollAcc;
    private javax.swing.JViewport viewport;

    private TouchRouter() {
    }

    static void install() {
        if (!Boolean.getBoolean("runelite.android.touchMode")) return;
        Toolkit.getDefaultToolkit().getSystemEventQueue().push(new TouchRouter());
        // Hover tooltips never get a mouse-exit on touch and were left half-drawn on screen.
        javax.swing.SwingUtilities.invokeLater(() -> javax.swing.ToolTipManager.sharedInstance().setEnabled(false));
        System.out.println("TouchRouter: installed");
    }

    @Override
    protected void dispatchEvent(AWTEvent event) {
        if (event instanceof MouseEvent && !(event instanceof MouseWheelEvent)) {
            MouseEvent e = (MouseEvent) event;
            boolean middle = e.getButton() == MouseEvent.BUTTON2
                    || (e.getModifiersEx() & InputEvent.BUTTON2_DOWN_MASK) != 0;
            if (middle || mode != Mode.NONE) {
                AWTEvent routed = route(e, middle);
                if (routed == null) return;
                event = routed;
            }
        }
        super.dispatchEvent(event);
    }

    private AWTEvent route(MouseEvent e, boolean middle) {
        int id = e.getID();
        if (id == MouseEvent.MOUSE_PRESSED && e.getButton() == MouseEvent.BUTTON2) {
            Client client = client();
            Component canvas = client == null ? null : client.getCanvas();
            javax.swing.JViewport port = canvas == null ? null : swingViewportAt(e);
            if (port != null) {
                // Swing (sidebar icon strip, plugin panels): a swipe drags the list under the finger.
                mode = Mode.SWING;
                viewport = port;
                lastY = e.getYOnScreen();
                return null;
            }
            if (canvas == null || !isCanvasEvent(e, canvas)) {
                mode = Mode.CAMERA;
                return e;
            }
            startSnapshots();
            java.awt.Point cp = javax.swing.SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), canvas);
            int x = cp.x, y = cp.y;
            if (contains(sScroll, x, y)) {
                mode = Mode.SCROLL;
                lastY = e.getY();
                scrollAcc = 0;
                return null;
            }
            if (!contains(sUi, x, y)) {
                mode = Mode.CAMERA;
                return e;
            }
            mode = Mode.LEFT;
            return left(e);
        }
        switch (mode) {
            case SCROLL:
                if (id == MouseEvent.MOUSE_DRAGGED || id == MouseEvent.MOUSE_RELEASED) {
                    scrollAcc += lastY - e.getY();
                    lastY = e.getY();
                    int notches = scrollAcc / SCROLL_STEP_PX;
                    if (notches != 0) {
                        scrollAcc -= notches * SCROLL_STEP_PX;
                        // Finger up = content up = scroll down (positive rotation).
                        super.dispatchEvent(new MouseWheelEvent(e.getComponent(), MouseEvent.MOUSE_WHEEL,
                                e.getWhen(), 0, e.getX(), e.getY(), 0, false,
                                MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, notches));
                    }
                }
                if (id == MouseEvent.MOUSE_RELEASED) mode = Mode.NONE;
                return null;
            case SWING:
                if (id == MouseEvent.MOUSE_DRAGGED || id == MouseEvent.MOUSE_RELEASED) {
                    int dy = lastY - e.getYOnScreen();
                    lastY = e.getYOnScreen();
                    java.awt.Component view = viewport.getView();
                    if (view != null && dy != 0) {
                        java.awt.Point p = viewport.getViewPosition();
                        int max = Math.max(0, view.getHeight() - viewport.getExtentSize().height);
                        p.y = Math.max(0, Math.min(max, p.y + dy));
                        viewport.setViewPosition(p);
                        viewport.repaint();
                    }
                }
                if (id == MouseEvent.MOUSE_RELEASED) { mode = Mode.NONE; viewport = null; }
                return null;
            case LEFT:
                if (id == MouseEvent.MOUSE_RELEASED) mode = Mode.NONE;
                return left(e);
            case CAMERA:
                if (id == MouseEvent.MOUSE_RELEASED && e.getButton() == MouseEvent.BUTTON2) mode = Mode.NONE;
                return e;
            default:
                return e;
        }
    }

    private static boolean isCanvasEvent(MouseEvent e, Component canvas) {
        Component src = e.getComponent();
        if (src == canvas) return true;
        if (src == null || !canvas.isShowing()) return false;
        java.awt.Point p = javax.swing.SwingUtilities.convertPoint(src, e.getPoint(), canvas);
        return canvas.contains(p);
    }

    /** The scrollable Swing viewport under the press (outside the game canvas), or null. */
    private static javax.swing.JViewport swingViewportAt(MouseEvent e) {
        Component src = e.getComponent();
        if (src == null) return null;
        java.awt.Window w = src instanceof java.awt.Window ? (java.awt.Window) src
                : javax.swing.SwingUtilities.getWindowAncestor(src);
        if (w == null) return null;
        java.awt.Point p = javax.swing.SwingUtilities.convertPoint(src, e.getPoint(), w);
        Component hit = javax.swing.SwingUtilities.getDeepestComponentAt(w, p.x, p.y);
        Client client = client();
        Component canvas = client == null ? null : client.getCanvas();
        for (Component c = hit; c != null; c = c.getParent()) {
            if (c == canvas) return null;
            if (c instanceof javax.swing.JViewport) {
                javax.swing.JViewport port = (javax.swing.JViewport) c;
                Component view = port.getView();
                if (view != null && view.getHeight() > port.getExtentSize().height) return port;
            }
        }
        return null;
    }

    private static MouseEvent left(MouseEvent e) {
        int mods = (e.getModifiersEx() & ~InputEvent.BUTTON2_DOWN_MASK);
        if (e.getID() != MouseEvent.MOUSE_RELEASED) mods |= InputEvent.BUTTON1_DOWN_MASK;
        int button = e.getButton() == MouseEvent.BUTTON2 ? MouseEvent.BUTTON1 : e.getButton();
        return new MouseEvent(e.getComponent(), e.getID(), e.getWhen(), mods, e.getX(), e.getY(),
                e.getClickCount(), false, button);
    }

    private static volatile Rectangle[] sUi = new Rectangle[0];
    private static volatile Rectangle[] sScroll = new Rectangle[0];
    private static boolean sSnapshotting;
    private static long sLastSnap;

    private static boolean contains(Rectangle[] rects, int x, int y) {
        for (Rectangle r : rects) if (r.contains(x, y)) return true;
        return false;
    }

    /** Widget APIs are client-thread only (asserted with -ea), so geometry is snapshotted there. */
    private static void startSnapshots() {
        if (sSnapshotting) return;
        try {
            net.runelite.client.callback.ClientThread thread = net.runelite.client.RuneLite.getInjector()
                    .getInstance(net.runelite.client.callback.ClientThread.class);
            sSnapshotting = true;
            thread.invokeLater(() -> {
                long now = System.nanoTime();
                if (now - sLastSnap > 100_000_000L) {
                    sLastSnap = now;
                    try {
                        snapshot(client());
                    } catch (Throwable t) {
                        // widget tree changed mid-walk; keep the last snapshot
                    }
                }
                return false;
            });
        } catch (Throwable t) {
            // injector not ready yet; retry on the next press
        }
    }

    private static void snapshot(Client client) {
        if (client == null) return;
        Widget[] roots = client.getWidgetRoots();
        Component canvas = client.getCanvas();
        if (roots == null || canvas == null) return;
        java.util.List<Rectangle> ui = new java.util.ArrayList<>(), scroll = new java.util.ArrayList<>();
        int fullW = canvas.getWidth() * 9 / 10, fullH = canvas.getHeight() * 9 / 10;
        for (Widget root : roots) walk(root, fullW, fullH, ui, scroll, 0);
        sUi = ui.toArray(new Rectangle[0]);
        sScroll = scroll.toArray(new Rectangle[0]);
    }

    private static void walk(Widget w, int fullW, int fullH, java.util.List<Rectangle> ui,
                             java.util.List<Rectangle> scroll, int depth) {
        if (w == null || depth > 24 || w.isHidden()) return;
        Rectangle b = w.getBounds();
        if (b == null || b.width <= 0 || b.height <= 0) return;
        boolean fullScreen = b.width >= fullW && b.height >= fullH;
        if (w.getScrollHeight() > w.getHeight() && w.getHeight() > 0) scroll.add(b);
        else if (!fullScreen && w.getType() != 0) ui.add(b);
        Widget[][] kids = {w.getDynamicChildren(), w.getNestedChildren(), w.getStaticChildren()};
        for (Widget[] group : kids) {
            if (group == null) continue;
            for (Widget k : group) walk(k, fullW, fullH, ui, scroll, depth + 1);
        }
    }

    private static Client client() {
        if (sClient == null) {
            try {
                sClient = net.runelite.client.RuneLite.getInjector().getInstance(Client.class);
            } catch (Throwable t) {
                return null;
            }
        }
        return sClient;
    }
}
