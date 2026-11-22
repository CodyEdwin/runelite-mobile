package com.codyedwin.runelitemobile.shim;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JToggleButton;

/**
 * Mobile look for RuneLite's right-hand icon strip: a darker, roomier strip with large touch
 * targets and sticky Shift / Alt keys at the top (tap once to hold the key, tap again to release),
 * since a phone has no physical modifier keys for shift-click drop or overlay dragging.
 */
final class SideBarStyle {
    private static final Color STRIP = new Color(0x16, 0x16, 0x16);
    private static final Color KEY_FILL = new Color(0x3A, 0x10, 0x14);
    private static final Color KEY_EDGE = new Color(0xB8, 0x3A, 0x48);
    private static final Color KEY_TEXT = new Color(0xFF, 0x8A, 0x96);
    private static final Color KEY_ON = new Color(0xE0, 0xA0, 0x30);

    private SideBarStyle() {
    }

    static void apply(JComponent sidebar) {
        if (Boolean.getBoolean("runelite.android.classicSidebar")) return;
        sidebar.setBackground(STRIP);
        if (sidebar instanceof javax.swing.JTabbedPane) {
            // One scrollable column instead of RuneLite's wrapped multi-column grid.
            ((javax.swing.JTabbedPane) sidebar).setTabLayoutPolicy(javax.swing.JTabbedPane.SCROLL_TAB_LAYOUT);
            sidebar.putClientProperty("JTabbedPane.scrollButtonsPolicy", "never");
        }
        sidebar.putClientProperty("FlatLaf.style",
                "tabInsets: 14,16,14,16; variableSize: true; deselectable: true; tabHeight: 52;"
                        + " tabAreaInsets: 4,0,4,0; background: #161616");
        JPanel keys = new JPanel();
        keys.setOpaque(true);
        keys.setBackground(STRIP);
        keys.setLayout(new BoxLayout(keys, BoxLayout.Y_AXIS));
        keys.setBorder(BorderFactory.createEmptyBorder(6, 4, 6, 4));
        String[] pins = System.getProperty("runelite.android.pinnedKeys", "Shift,Ctrl,Alt,Space,1,2,3,4").split(",");
        for (String pin : pins) {
            int code = keyCode(pin.trim());
            if (code == KeyEvent.VK_UNDEFINED) continue;
            keys.add(code == KeyEvent.VK_SHIFT || code == KeyEvent.VK_ALT || code == KeyEvent.VK_CONTROL
                    ? key(pin.trim(), code, sidebar) : tap(pin.trim(), code));
            keys.add(javax.swing.Box.createVerticalStrut(4));
        }
        sidebar.putClientProperty("JTabbedPane.leadingComponent", keys);
        // RuneLite hides the whole strip when the sidebar is closed; keep the strip and just close the panel.
        sidebar.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentHidden(java.awt.event.ComponentEvent e) {
                javax.swing.SwingUtilities.invokeLater(() -> keepStrip(sidebar));
            }
        });
        if (!sidebar.isVisible()) keepStrip(sidebar);
        sidebar.revalidate();
        sidebar.repaint();
        System.out.println("SideBarStyle: applied to " + sidebar.getClass().getName() + ", showing=" + sidebar.isShowing());
    }

    private static JToggleButton key(String label, int keyCode, JComponent sidebar) {
        JToggleButton button = new JToggleButton(label) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                int w = getWidth() - 3, h = getHeight() - 3;
                boolean on = isSelected();
                g2.setColor(on ? KEY_ON.darker().darker() : KEY_FILL);
                g2.fillRoundRect(1, 1, w, h, 14, 14);
                g2.setStroke(new BasicStroke(1.6f));
                g2.setColor(on ? KEY_ON : KEY_EDGE);
                g2.drawRoundRect(1, 1, w, h, 14, 14);
                g2.setFont(getFont().deriveFont(Font.BOLD, 15f));
                g2.setColor(on ? KEY_ON : KEY_TEXT);
                int tw = g2.getFontMetrics().stringWidth(label);
                g2.drawString(label, (getWidth() - tw) / 2,
                        (getHeight() + g2.getFontMetrics().getAscent() - g2.getFontMetrics().getDescent()) / 2);
                g2.dispose();
            }
        };
        button.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        button.setFocusable(false);
        button.setContentAreaFilled(false);
        button.setBorderPainted(false);
        button.setAlignmentX(Component.CENTER_ALIGNMENT);
        Dimension size = new Dimension(64, 42);
        button.setPreferredSize(size);
        button.setMaximumSize(size);
        button.setMinimumSize(size);
        button.addActionListener(e -> {
            sendKey(keyCode, button.isSelected());
            if (sidebar != null) sidebar.repaint();
        });
        return button;
    }

    private static void keepStrip(JComponent sidebar) {
        if (sidebar.isVisible()) return;
        if (sidebar instanceof javax.swing.JTabbedPane) ((javax.swing.JTabbedPane) sidebar).setSelectedIndex(-1);
        sidebar.setVisible(true);
        if (sidebar.getParent() != null) sidebar.getParent().revalidate();
        sidebar.repaint();
    }

    private static int keyCode(String name) {
        switch (name) {
            case "Shift": return KeyEvent.VK_SHIFT;
            case "Ctrl": return KeyEvent.VK_CONTROL;
            case "Alt": return KeyEvent.VK_ALT;
            case "Space": return KeyEvent.VK_SPACE;
            case "Enter": return KeyEvent.VK_ENTER;
            case "Esc": return KeyEvent.VK_ESCAPE;
            default:
                return name.length() == 1 && Character.isDigit(name.charAt(0))
                        ? KeyEvent.VK_0 + (name.charAt(0) - '0') : KeyEvent.VK_UNDEFINED;
        }
    }

    /** A one-shot key: press and release, with the key's character for typed input. */
    private static JToggleButton tap(String label, int keyCode) {
        JToggleButton button = key(label, keyCode, null);
        for (java.awt.event.ActionListener l : button.getActionListeners()) button.removeActionListener(l);
        button.addActionListener(e -> {
            button.setSelected(false);
            sendKey(keyCode, true);
            char c = keyCode == KeyEvent.VK_SPACE ? ' ' : keyCode == KeyEvent.VK_ENTER ? '\n'
                    : keyCode >= KeyEvent.VK_0 && keyCode <= KeyEvent.VK_9 ? (char) ('0' + keyCode - KeyEvent.VK_0) : 0;
            Component canvas = gameCanvas();
            if (c != 0 && canvas != null) {
                Toolkit.getDefaultToolkit().getSystemEventQueue().postEvent(new KeyEvent(canvas, KeyEvent.KEY_TYPED,
                        System.currentTimeMillis(), 0, KeyEvent.VK_UNDEFINED, c));
            }
            sendKey(keyCode, false);
        });
        return button;
    }

    /** Presses or releases a key on the game canvas, as a hardware keyboard would. */
    private static void sendKey(int keyCode, boolean down) {
        Component canvas = gameCanvas();
        if (canvas == null) return;
        int modifier = keyCode == KeyEvent.VK_SHIFT ? KeyEvent.SHIFT_DOWN_MASK
                : keyCode == KeyEvent.VK_ALT ? KeyEvent.ALT_DOWN_MASK
                : keyCode == KeyEvent.VK_CONTROL ? KeyEvent.CTRL_DOWN_MASK : 0;
        KeyEvent event = new KeyEvent(canvas, down ? KeyEvent.KEY_PRESSED : KeyEvent.KEY_RELEASED,
                System.currentTimeMillis(), down ? modifier : 0, keyCode, KeyEvent.CHAR_UNDEFINED,
                modifier != 0 ? KeyEvent.KEY_LOCATION_LEFT : KeyEvent.KEY_LOCATION_STANDARD);
        EventQueue queue = Toolkit.getDefaultToolkit().getSystemEventQueue();
        queue.postEvent(event);
    }

    private static Component gameCanvas() {
        try {
            Object injector = Class.forName("net.runelite.client.RuneLite").getMethod("getInjector").invoke(null);
            Object client = Class.forName("com.google.inject.Injector").getMethod("getInstance", Class.class)
                    .invoke(injector, Class.forName("net.runelite.api.Client"));
            return (Component) client.getClass().getMethod("getCanvas").invoke(client);
        } catch (Throwable t) {
            System.err.println("SideBarStyle: no game canvas: " + t);
            return null;
        }
    }
}
