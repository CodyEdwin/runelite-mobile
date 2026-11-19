package com.codyedwin.runelitemobile.runeliteupdate;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * Live "what is the updater doing" line for the loading screen. Every step goes to logcat as
 * "Updater: ...", to an optional on-screen sink, and into a buffer that the game activity writes
 * to latestlog.txt (the launcher and game run in different processes, and the game process
 * resets latestlog.txt when it starts), so the steps show up in "Share log file".
 */
public final class UpdaterStatus {
    public interface Sink {
        void onUpdaterStatus(String line);
    }

    private static final String TAG = "Updater";
    private static final int MAX_LINES = 200;
    private static final ArrayList<String> LINES = new ArrayList<>();
    private static volatile Sink sink;
    private static volatile long lastProgressLine;
    private static volatile String lastProgressKey;

    private UpdaterStatus() {
    }

    public static void setSink(Sink s) {
        sink = s;
    }

    /** Start of a new launch: forget the previous run's lines. */
    public static void begin() {
        synchronized (LINES) {
            LINES.clear();
        }
        lastProgressKey = null;
    }

    public static void log(String msg) {
        if (msg == null || msg.isEmpty()) return;
        Log.i(TAG, msg);
        String stamp = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date());
        synchronized (LINES) {
            LINES.add(stamp + " Updater: " + msg);
            while (LINES.size() > MAX_LINES) LINES.remove(0);
        }
        Sink s = sink;
        if (s != null) {
            try {
                s.onUpdaterStatus(msg);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Frequent progress (download bytes): always shown on screen, but only logged about once a
     * second per item so the log stays readable.
     */
    public static void progress(String key, String msg) {
        long now = System.currentTimeMillis();
        boolean logIt = !key.equals(lastProgressKey) || now - lastProgressLine >= 1000;
        if (logIt) {
            lastProgressKey = key;
            lastProgressLine = now;
            log(msg);
        } else {
            Sink s = sink;
            if (s != null) {
                try {
                    s.onUpdaterStatus(msg);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    public static ArrayList<String> snapshot() {
        synchronized (LINES) {
            return new ArrayList<>(LINES);
        }
    }

    static String host(String url) {
        try {
            return new java.net.URL(url).getHost();
        } catch (Exception e) {
            return url;
        }
    }

    static String bytes(long b) {
        if (b < 0) return "?";
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format(Locale.ROOT, "%.0f KB", b / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", b / (1024.0 * 1024.0));
    }

    static String reason(Throwable e) {
        String m = e.getMessage();
        String n = e.getClass().getSimpleName();
        return m == null || m.isEmpty() ? n : (e instanceof java.io.IOException && m.startsWith("HTTP ") ? m : n + ": " + m);
    }
}
