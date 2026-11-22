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
    private enum Mode { NONE, CAMERA, LEFT, SCROLL }

    private static Client sClient;
    private Mode mode = Mode.NONE;
    private int lastY;
    private int scrollAcc;

    private TouchRouter() {
    }

    static void install() {
        if (!Boolean.getBoolean("runelite.android.touchMode")) return;
        Toolkit.getDefaultToolkit().getSystemEventQueue().push(new TouchRouter());
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
            if (canvas == null || e.getComponent() != canvas) {
                mode = Mode.CAMERA;
                return e;
            }
            Widget hit = null;
            try {
                hit = hitTest(client, e.getX(), e.getY());
            } catch (Throwable t) {
                // widget tree changed mid-walk; treat as the game world
            }
            if (hit == null) {
                mode = Mode.CAMERA;
                return e;
            }
            if (scrollable(hit) != null) {
                mode = Mode.SCROLL;
                lastY = e.getY();
                scrollAcc = 0;
                return null;
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

    private static MouseEvent left(MouseEvent e) {
        int mods = (e.getModifiersEx() & ~InputEvent.BUTTON2_DOWN_MASK);
        if (e.getID() != MouseEvent.MOUSE_RELEASED) mods |= InputEvent.BUTTON1_DOWN_MASK;
        int button = e.getButton() == MouseEvent.BUTTON2 ? MouseEvent.BUTTON1 : e.getButton();
        return new MouseEvent(e.getComponent(), e.getID(), e.getWhen(), mods, e.getX(), e.getY(),
                e.getClickCount(), false, button);
    }

    /** Deepest visible interface widget under the point, ignoring full-screen containers. */
    private static Widget hitTest(Client client, int x, int y) {
        Widget[] roots = client.getWidgetRoots();
        if (roots == null) return null;
        Component canvas = client.getCanvas();
        int fullW = canvas.getWidth() * 9 / 10, fullH = canvas.getHeight() * 9 / 10;
        Widget best = null;
        for (int i = roots.length - 1; i >= 0 && best == null; i--) {
            best = deepest(roots[i], x, y, fullW, fullH);
        }
        return best;
    }

    private static Widget deepest(Widget w, int x, int y, int fullW, int fullH) {
        if (w == null || w.isHidden()) return null;
        Rectangle b = w.getBounds();
        if (b == null || !b.contains(x, y)) return null;
        Widget[][] kids = {w.getDynamicChildren(), w.getNestedChildren(), w.getStaticChildren()};
        for (Widget[] group : kids) {
            if (group == null) continue;
            for (int i = group.length - 1; i >= 0; i--) {
                Widget hit = deepest(group[i], x, y, fullW, fullH);
                if (hit != null) return hit;
            }
        }
        boolean fullScreen = b.width >= fullW && b.height >= fullH;
        boolean drawn = w.getType() != 0 || w.getScrollHeight() > w.getHeight();
        return !fullScreen && drawn ? w : null;
    }

    private static Widget scrollable(Widget w) {
        for (Widget p = w; p != null; p = p.getParent()) {
            if (p.getScrollHeight() > p.getHeight() && p.getHeight() > 0) return p;
        }
        return null;
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
