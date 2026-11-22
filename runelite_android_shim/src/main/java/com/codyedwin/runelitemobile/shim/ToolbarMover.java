package com.codyedwin.runelitemobile.shim;

import java.awt.Container;
import java.awt.LayoutManager;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JPanel;

/**
 * RuneLite always builds its navigation toolbar for "custom chrome" (ClientUI.withTitleBar =
 * true): a horizontal ClientToolbarPanel inside a JMenuBar that FlatLaf merges into the window's
 * title bar. WindowFitter removes the title bar (Android has no window decorations), which left
 * that menu bar as a horizontal strip across the top of the client, with the bottom group of nav
 * buttons (notes, info, profiles, sidebar toggle) at the top instead of under the plugin icons.
 *
 * Switch RuneLite to its no-title-bar layout: the same toolbar panel, vertical, as the sidebar
 * JTabbedPane's trailing component (bottom of the right-hand icon strip), no menu bar, and the
 * in-game sidebar toggle drawn by ClientUI#paintOverlays.
 */
final class ToolbarMover {
    private ToolbarMover() {
    }

    static void relocate(JFrame frame) {
        try {
            Class<?> runeLite = Class.forName("net.runelite.client.RuneLite");
            Object injector = runeLite.getMethod("getInjector").invoke(null);
            if (injector == null) return;
            Class<?> clientUiClass = Class.forName("net.runelite.client.ui.ClientUI");
            // Call through the public Injector interface: InjectorImpl itself is package-private.
                      Object ui = Class.forName("com.google.inject.Injector").getMethod("getInstance", Class.class)
                              .invoke(injector, clientUiClass);

            Field withTitleBar = field(clientUiClass, "withTitleBar");
            JPanel toolbar = (JPanel) field(clientUiClass, "toolbarPanel").get(ui);
            JComponent sidebar = (JComponent) field(clientUiClass, "sidebar").get(ui);
            if (sidebar != null && sidebar.getClientProperty("JTabbedPane.leadingComponent") == null) {
                SideBarStyle.apply(sidebar);
            }
            if (!withTitleBar.getBoolean(ui)) return;
            if (toolbar == null || sidebar == null) return;

            Container parent = toolbar.getParent();
            if (parent != null) parent.remove(toolbar);
            frame.setJMenuBar(null);

            LayoutManager vertical = (LayoutManager) Class.forName("net.runelite.client.ui.DynamicGridLayout")
                    .getConstructor(int.class, int.class, int.class, int.class).newInstance(0, 1, 0, 4);
            toolbar.setLayout(vertical);
            toolbar.setMaximumSize(null);
            toolbar.setMaximumSize(toolbar.getPreferredSize());

            Method createSidebarPanel = toolbar.getClass().getDeclaredMethod("createSidebarPanel");
            createSidebarPanel.setAccessible(true);
            JPanel wrap = (JPanel) createSidebarPanel.invoke(toolbar);
            sidebar.putClientProperty("JTabbedPane.trailingComponent", wrap);

            withTitleBar.setBoolean(ui, false);
            frame.getRootPane().revalidate();
            frame.repaint();
            System.out.println("ToolbarMover: navigation toolbar moved into the sidebar");
        } catch (Throwable t) {
            System.err.println("ToolbarMover: unable to move the RuneLite toolbar: " + t);
        }
    }

    private static Field field(Class<?> cls, String name) throws NoSuchFieldException {
        Field f = cls.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }
}
