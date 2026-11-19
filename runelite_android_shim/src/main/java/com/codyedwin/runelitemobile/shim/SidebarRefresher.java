package com.codyedwin.runelitemobile.shim;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.MouseEvent;
import java.lang.reflect.Field;
import javax.swing.JComponent;
import javax.swing.JTabbedPane;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;

/**
 * Keeps the RuneLite sidebar (plugin icon strip + the open plugin panel) fully drawn.
 *
 * On the cacio screen several sidebar changes never produce a damage region Swing acts on: the
 * tab strip after the sidebar is collapsed/expanded or the frame is refit, a panel swapped in by a
 * tab click, a config section expanded, a list scrolled. That left a stale or half-drawn sidebar
 * (one icon in the strip, the old panel after a tab switch, blank strips after scrolling).
 *
 * Driven from RepaintPump (EDT, ~30 Hz), repaint only (never waits on a lock):
 *  - the sidebar is repainted when its bounds, selected tab, panel size or a scroll position change,
 *    and again ~100 ms and ~400 ms later (deferred layout);
 *  - mouse press/release/wheel/drag inside it schedule the same repaints;
 *  - a 1 Hz safety repaint while it is showing.
 * Disable with -Drunelite.android.noSidebarRefresh=true or ~/.runelite/android-no-sidebar-refresh.
 */
final class SidebarRefresher {
    private static final int SAFETY_TICKS = Integer.getInteger("runelite.android.sidebarRefreshTicks", 30);
    private static boolean sEnabled;
    private static JComponent sSidebar;
    private static long sLookupAt;
    private static String sSignature = "";
    private static int sTick;
    private static int sPendingA = -1, sPendingB = -1;
    private static volatile boolean sInputHit;

    private SidebarRefresher() {
    }

    static void install() {
        if (Boolean.getBoolean("runelite.android.noSidebarRefresh")
                || new java.io.File(System.getProperty("user.home"), ".runelite/android-no-sidebar-refresh").exists()) return;
        sEnabled = true;
        Toolkit.getDefaultToolkit().addAWTEventListener(e -> {
            int id = e.getID();
            if (id == MouseEvent.MOUSE_PRESSED || id == MouseEvent.MOUSE_RELEASED
                    || id == MouseEvent.MOUSE_WHEEL || id == MouseEvent.MOUSE_DRAGGED) {
                JComponent sb = sSidebar;
                Object src = e.getSource();
                if (sb != null && src instanceof Component && SwingUtilities.isDescendingFrom((Component) src, sb)) {
                    sInputHit = true;
                }
            }
        }, AWTEvent.MOUSE_EVENT_MASK | AWTEvent.MOUSE_WHEEL_EVENT_MASK | AWTEvent.MOUSE_MOTION_EVENT_MASK);
    }

    /** EDT, each RepaintPump tick, before dirty regions are painted. */
    static void tick() {
        if (!sEnabled) return;
        sTick++;
        JComponent sb = sidebar();
        if (sb == null || !sb.isShowing()) return;
        String sig = signature(sb);
        boolean changed = !sig.equals(sSignature);
        sSignature = sig;
        if (changed || sInputHit) {
            sInputHit = false;
            sb.repaint();
            sPendingA = sTick + 3;
            sPendingB = sTick + 12;
            return;
        }
        if (sTick == sPendingA || sTick == sPendingB || (SAFETY_TICKS > 0 && sTick % SAFETY_TICKS == 0)) {
            sb.repaint();
        }
    }

    private static String signature(JComponent sb) {
        StringBuilder s = new StringBuilder();
        Rectangle r = sb.getBounds();
        s.append(r.x).append(',').append(r.y).append(',').append(r.width).append(',').append(r.height);
        if (sb instanceof JTabbedPane) {
            JTabbedPane tp = (JTabbedPane) sb;
            s.append('|').append(tp.getSelectedIndex()).append('|').append(tp.getTabCount());
            Component sel = tp.getSelectedComponent();
            if (sel != null) {
                s.append('|').append(System.identityHashCode(sel)).append(':').append(sel.getWidth()).append('x').append(sel.getHeight());
                appendScroll(s, sel, 0);
            }
        }
        return s.toString();
    }

    private static void appendScroll(StringBuilder s, Component c, int depth) {
        if (depth > 12 || !c.isVisible()) return;
        if (c instanceof JViewport) {
            JViewport v = (JViewport) c;
            java.awt.Point p = v.getViewPosition();
            Component view = v.getView();
            s.append('|').append(p.x).append(',').append(p.y);
            if (view != null) s.append(',').append(view.getHeight());
        }
        if (c instanceof java.awt.Container) {
            for (Component child : ((java.awt.Container) c).getComponents()) appendScroll(s, child, depth + 1);
        }
    }

    private static JComponent sidebar() {
        if (sSidebar != null) return sSidebar;
        long now = System.currentTimeMillis();
        if (now - sLookupAt < 2000) return null;
        sLookupAt = now;
        try {
            Object injector = Class.forName("net.runelite.client.RuneLite").getMethod("getInjector").invoke(null);
            if (injector == null) return null;
            Class<?> clientUiClass = Class.forName("net.runelite.client.ui.ClientUI");
            Object ui = Class.forName("com.google.inject.Injector").getMethod("getInstance", Class.class)
                    .invoke(injector, clientUiClass);
            Field f = clientUiClass.getDeclaredField("sidebar");
            f.setAccessible(true);
            sSidebar = (JComponent) f.get(ui);
            if (sSidebar != null) System.out.println("SidebarRefresher: tracking " + sSidebar.getClass().getName());
        } catch (Throwable ignored) {
        }
        return sSidebar;
    }
}
