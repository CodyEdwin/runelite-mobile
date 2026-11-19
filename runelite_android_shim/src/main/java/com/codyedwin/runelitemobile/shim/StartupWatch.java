package com.codyedwin.runelitemobile.shim;

import java.awt.Window;

/**
 * RuneLite.main catches startup errors itself: it logs "Failure during startup", queues a
 * FatalErrorDialog and returns normally, leaving the JVM alive with no main window. On Android
 * that dialog sits behind the launcher's loading screen, so the launcher waited forever (FPS 0).
 * After main returns, watch the windows: if a FatalErrorDialog shows up before RuneLite's main
 * window, exit with {@link AndroidRuneLite#EXIT_STARTUP_FAILED} so the launcher shows an error
 * with "Share log".
 */
final class StartupWatch {
    private static final String MAIN_FRAME = "net.runelite.client.ui.ContainableFrame";
    private static final String FATAL_DIALOG = "net.runelite.client.ui.FatalErrorDialog";

    private StartupWatch() {
    }

    static void start() {
        Thread t = new Thread(() -> {
            long deadline = System.currentTimeMillis() + 180_000;
            try {
                while (System.currentTimeMillis() < deadline) {
                    for (Window w : Window.getWindows()) {
                        if (isA(w.getClass(), MAIN_FRAME) && w.isShowing()) return;
                    }
                    for (Window w : Window.getWindows()) {
                        if (isA(w.getClass(), FATAL_DIALOG)) {
                            System.err.println("StartupWatch: RuneLite showed its startup error dialog before the main window");
                            AndroidRuneLite.startupFailed();
                            return;
                        }
                    }
                    Thread.sleep(250);
                }
            } catch (Throwable e) {
                System.err.println("StartupWatch: " + e);
            }
        }, "RuneLite-StartupWatch");
        t.setDaemon(true);
        t.start();
    }

    private static boolean isA(Class<?> c, String name) {
        for (; c != null; c = c.getSuperclass()) if (name.equals(c.getName())) return true;
        return false;
    }
}
