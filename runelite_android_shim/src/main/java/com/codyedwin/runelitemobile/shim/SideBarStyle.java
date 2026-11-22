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
        sidebar.putClientProperty("FlatLaf.style",
                "tabInsets: 10,12,10,12; variableSize: true; deselectable: true; tabHeight: 46;"
                        + " tabAreaInsets: 4,0,4,0; background: #161616");
        JPanel keys = new JPanel();
        keys.setOpaque(true);
        keys.setBackground(STRIP);
        keys.setLayout(new BoxLayout(keys, BoxLayout.Y_AXIS));
        keys.setBorder(BorderFactory.createEmptyBorder(6, 4, 6, 4));
        keys.add(key("Shift", KeyEvent.VK_SHIFT, sidebar));
        keys.add(javax.swing.Box.createVerticalStrut(8));
        keys.add(key("Alt", KeyEvent.VK_ALT, sidebar));
        sidebar.putClientProperty("JTabbedPane.leadingComponent", keys);
        sidebar.revalidate();
        sidebar.repaint();
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
                g2.setFont(getFont());
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
        Dimension size = new Dimension(54, 42);
        button.setPreferredSize(size);
        button.setMaximumSize(size);
        button.setMinimumSize(size);
        button.addActionListener(e -> {
            sendKey(keyCode, button.isSelected());
            sidebar.repaint();
        });
        return button;
    }

    /** Presses or releases a key on the game canvas, as a hardware keyboard would. */
    private static void sendKey(int keyCode, boolean down) {
        Component canvas = gameCanvas();
        if (canvas == null) return;
        int modifier = keyCode == KeyEvent.VK_SHIFT ? KeyEvent.SHIFT_DOWN_MASK : KeyEvent.ALT_DOWN_MASK;
        KeyEvent event = new KeyEvent(canvas, down ? KeyEvent.KEY_PRESSED : KeyEvent.KEY_RELEASED,
                System.currentTimeMillis(), down ? modifier : 0, keyCode, KeyEvent.CHAR_UNDEFINED,
                KeyEvent.KEY_LOCATION_LEFT);
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
