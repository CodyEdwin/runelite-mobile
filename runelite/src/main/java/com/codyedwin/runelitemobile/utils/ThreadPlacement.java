package com.codyedwin.runelitemobile.utils;

import android.util.Log;

import java.io.File;

/**
 * CPU placement for the RuneLite process on big.LITTLE phones. The game's software renderer runs
 * on the JVM "Client" thread and is what limits FPS; the scheduler kept putting the Android blitter
 * thread on the same big core. Keep "Client" on the fastest cores and the blitter on the others.
 */
public final class ThreadPlacement {
    private static final String TAG = "ThreadPlacement";
    private static long sBigMask = -1, sLittleMask = -1;
    private static int sLastClientTid = -1;

    private ThreadPlacement() {
    }

    private static synchronized void computeMasks() {
        if (sBigMask != -1) return;
        int n = Runtime.getRuntime().availableProcessors();
        long[] freq = new long[n];
        long max = 0;
        for (int i = 0; i < n; i++) {
            freq[i] = readLong("/sys/devices/system/cpu/cpu" + i + "/cpufreq/cpuinfo_max_freq");
            max = Math.max(max, freq[i]);
        }
        long big = 0, little = 0;
        for (int i = 0; i < n; i++) {
            if (freq[i] == max) big |= 1L << i; else little |= 1L << i;
        }
        if (max <= 0 || big == 0 || little == 0) { big = 0; little = 0; } // homogeneous / unknown
        sBigMask = big;
        sLittleMask = little;
        Log.i(TAG, "big cores mask=0x" + Long.toHexString(big) + " little=0x" + Long.toHexString(little));
    }

    /** Call on the blitter thread itself. */
    public static void placeRenderThread() {
        computeMasks();
        if (sLittleMask == 0) return;
        int r = JREUtils.setThreadAffinity(android.os.Process.myTid(), sLittleMask);
        Log.i(TAG, "blitter thread -> little cores: " + r);
    }

    /** Find the JVM's game thread ("Client") and keep it on the big cores. Cheap; call periodically. */
    public static void placeGameThreads() {
        computeMasks();
        if (sBigMask == 0) return;
        File[] tasks = new File("/proc/self/task").listFiles();
        if (tasks == null) return;
        for (File task : tasks) {
            String comm = readString(new File(task, "comm"));
            if (!"Client".equals(comm)) continue;
            int tid;
            try { tid = Integer.parseInt(task.getName()); } catch (NumberFormatException e) { continue; }
            if (tid == sLastClientTid) return;
            int r = JREUtils.setThreadAffinity(tid, sBigMask);
            Log.i(TAG, "Client thread " + tid + " -> big cores: " + r);
            if (r == 0) sLastClientTid = tid;
            return;
        }
    }

    private static long readLong(String path) {
        try {
            return Long.parseLong(readString(new File(path)));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static String readString(File f) {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f))) {
            String line = r.readLine();
            return line == null ? "" : line.trim();
        } catch (Exception e) {
            return "";
        }
    }
}
