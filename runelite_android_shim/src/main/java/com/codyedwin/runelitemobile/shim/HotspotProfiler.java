package com.codyedwin.runelitemobile.shim;

import java.util.HashMap;
import java.util.Map;

/**
 * Samples the Client thread's stack every 20 ms and logs, every 15 s, where it spent its time: the
 * top frames (a native GL call shows up as org.lwjgl...ngl*) and the first non-JDK code frame.
 * Off by default; enable with -Drunelite.android.hotspots=true.
 */
final class HotspotProfiler {
    private static final boolean ENABLED = Boolean.getBoolean("runelite.android.hotspots");
    private static final long WINDOW_NS = 15_000_000_000L;

    private HotspotProfiler() {
    }

    static void install() {
        if (!ENABLED) return;
        Thread t = new Thread(HotspotProfiler::loop, "HotspotProfiler");
        t.setDaemon(true);
        t.start();
    }

    private static void loop() {
        Thread client = null;
        Map<String, Integer> top = new HashMap<>(), code = new HashMap<>();
        int n = 0, gl = 0;
        long start = System.nanoTime();
        while (true) {
            try {
                Thread.sleep(20);
                if (client == null || !client.isAlive()) {
                    client = null;
                    for (Thread th : Thread.getAllStackTraces().keySet()) if ("Client".equals(th.getName())) client = th;
                    continue;
                }
                StackTraceElement[] st = client.getStackTrace();
                if (st.length == 0) continue;
                n++;
                String f0 = st[0].getClassName() + "." + st[0].getMethodName();
                if (f0.startsWith("org.lwjgl.")) gl++;
                top.merge(f0 + (st.length > 1 ? " < " + st[1].getClassName() + "." + st[1].getMethodName() : ""), 1, Integer::sum);
                String c = "-";
                for (StackTraceElement e : st) {
                    String f = e.getClassName() + "." + e.getMethodName();
                    if (!f.startsWith("org.lwjgl.") && StallProfiler.interesting(f)) { c = f; break; }
                }
                code.merge(c, 1, Integer::sum);
                long now = System.nanoTime();
                if (now - start >= WINDOW_NS) {
                    StringBuilder sb = new StringBuilder(String.format("Hotspots: %d Client samples, %d%% in GL calls%n", n, n == 0 ? 0 : gl * 100 / n));
                    append(sb, "  top ", top, n, 8);
                    append(sb, "  code ", code, n, 6);
                    System.out.print(sb);
                    top.clear();
                    code.clear();
                    n = gl = 0;
                    start = now;
                }
            } catch (Throwable e) {
                System.err.println("HotspotProfiler: " + e);
                try { Thread.sleep(1000); } catch (InterruptedException ignored) { }
            }
        }
    }

    private static void append(StringBuilder sb, String label, Map<String, Integer> m, int n, int limit) {
        m.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(limit)
                .forEach(e -> sb.append(label).append(e.getValue() * 100 / Math.max(1, n)).append("% ").append(e.getKey()).append('\n'));
    }
}
