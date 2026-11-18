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

    /** Written as soon as RuneLite's main window shows: the launcher's loading screen fades out then. */
    static final String READY_FILE_PROPERTY = "runelite.android.readyFile";

    static void start() {
        final String path = System.getProperty(OK_FILE_PROPERTY);
        final String ready = System.getProperty(READY_FILE_PROPERTY);
        if ((path == null || path.isEmpty()) && (ready == null || ready.isEmpty())) return;
        Thread t = new Thread(() -> {
            long deadline = System.currentTimeMillis() + 180_000;
            try {
                while (System.currentTimeMillis() < deadline) {
                    for (Frame frame : Frame.getFrames()) {
                        if (frame.isShowing() && "net.runelite.client.ui.ContainableFrame".equals(frame.getClass().getName())) {
                            if (ready != null && !ready.isEmpty()) write(ready);
                            if (path != null && !path.isEmpty()) {
                                write(path);
                                System.out.println("LaunchConfirm: RuneLite window is up, marked " + new File(path).getName() + " as working");
                            }
                            return;
                        }
                    }
                    Thread.sleep(250);
                }
                System.err.println("LaunchConfirm: RuneLite's window never appeared");
            } catch (Throwable e) {
                System.err.println("LaunchConfirm: " + e);
            }
        }, "RuneLite-LaunchConfirm");
        t.setDaemon(true);
        t.start();
    }

    private static void write(String path) throws java.io.IOException {
        File f = new File(path);
        File dir = f.getParentFile();
        if (dir != null) dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(String.valueOf(System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8));
        }
    }
}
