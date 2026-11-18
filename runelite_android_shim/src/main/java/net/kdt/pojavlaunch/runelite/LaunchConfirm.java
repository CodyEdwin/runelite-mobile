package net.kdt.pojavlaunch.runelite;

import java.awt.Frame;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Tells the Android launcher that this RuneLite version really started: once RuneLite's main
 * window (net.runelite.client.ui.ContainableFrame) is visible, the file named by
 * -Drunelite.android.okFile is written. The launcher falls back to the previous working client
 * when a freshly downloaded version never gets this far.
 */
final class LaunchConfirm {
    static final String OK_FILE_PROPERTY = "runelite.android.okFile";

    private LaunchConfirm() {
    }

    static void start() {
        final String path = System.getProperty(OK_FILE_PROPERTY);
        if (path == null || path.isEmpty()) return;
        Thread t = new Thread(() -> {
            long deadline = System.currentTimeMillis() + 180_000;
            try {
                while (System.currentTimeMillis() < deadline) {
                    for (Frame frame : Frame.getFrames()) {
                        if (frame.isShowing() && "net.runelite.client.ui.ContainableFrame".equals(frame.getClass().getName())) {
                            File ok = new File(path);
                            File dir = ok.getParentFile();
                            if (dir != null) dir.mkdirs();
                            try (FileOutputStream out = new FileOutputStream(ok)) {
                                out.write(String.valueOf(System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8));
                            }
                            System.out.println("LaunchConfirm: RuneLite window is up, marked " + ok.getName() + " as working");
                            return;
                        }
                    }
                    Thread.sleep(1000);
                }
                System.err.println("LaunchConfirm: RuneLite's window never appeared");
            } catch (Throwable e) {
                System.err.println("LaunchConfirm: " + e);
            }
        }, "RuneLite-LaunchConfirm");
        t.setDaemon(true);
        t.start();
    }
}
