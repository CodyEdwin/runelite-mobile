package com.codyedwin.runelitemobile;

import android.os.Environment;
import android.util.Log;

import com.codyedwin.runelitemobile.launcher.RuneLiteScreenshotsActivity;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** Copies RuneLite's screenshots to /sdcard/RuneLite/screenshots so they survive the app folder. */
public final class ScreenshotBackup {
    private static Thread sThread;

    private ScreenshotBackup() {
    }

    public static File backupDir() {
        return new File(Environment.getExternalStorageDirectory(), "RuneLite/screenshots");
    }

    /** Copies every screenshot that has no up-to-date backup yet. No-op without All files access. */
    public static synchronized void syncNow() {
        if (!Tools.hasAllFilesAccess()) return;
        sync(RuneLiteScreenshotsActivity.screenshotDir(), backupDir());
    }

    private static void sync(File src, File dst) {
        File[] files = src.listFiles();
        if (files == null) return;
        for (File f : files) {
            File target = new File(dst, f.getName());
            if (f.isDirectory()) {
                sync(f, target);
                continue;
            }
            // RuneLite writes the file in one go; skip ones modified in the last second (still being written)
            if (target.length() == f.length() || System.currentTimeMillis() - f.lastModified() < 1000) continue;
            try {
                dst.mkdirs();
                File part = new File(dst, f.getName() + ".part");
                try (InputStream in = new FileInputStream(f); OutputStream out = new FileOutputStream(part)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                if (!part.renameTo(target)) part.delete();
                target.setLastModified(f.lastModified());
            } catch (Exception e) {
                Log.w("ScreenshotBackup", "could not back up " + f + ": " + e);
            }
        }
    }

    /** Background thread that backs up new screenshots every few seconds while the game runs. */
    public static synchronized void start() {
        if (sThread != null) return;
        sThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                syncNow();
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "ScreenshotBackup");
        sThread.setDaemon(true);
        sThread.setPriority(Thread.MIN_PRIORITY);
        sThread.start();
    }

    public static synchronized void stop() {
        if (sThread != null) sThread.interrupt();
        sThread = null;
    }
}
