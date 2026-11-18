package net.kdt.pojavlaunch.runelite;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.GameTick;
import net.runelite.client.RuneLite;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;

/**
 * Diagnostics for the periodic frame skip: samples the Client thread's stack every 10 ms and, when
 * a frame takes much longer than usual, logs where the Client thread was during that frame (top
 * frames and first plugin frame) and how long after the last game tick it happened.
 * Off by default; enable with -Drunelite.android.stallProfiler=true.
 */
public final class StallProfiler {
    static final boolean ENABLED = Boolean.getBoolean("runelite.android.stallProfiler");
    private static final ArrayDeque<long[]> times = new ArrayDeque<>();
    private static final ArrayDeque<String[]> stacks = new ArrayDeque<>();
    private static volatile Thread client;
    private static long lastFrame, lastTick, lastLog;
    private static double avg = 50;
    private static int logged;

    private StallProfiler() {
    }

    static void install() {
        if (!ENABLED) return;
        Thread t = new Thread(StallProfiler::sampleLoop, "StallProfiler");
        t.setDaemon(true);
        t.setPriority(Thread.MAX_PRIORITY);
        t.start();
    }

    private static void sampleLoop() {
        boolean registered = false;
        while (true) {
            try {
                Thread.sleep(10);
                if (client == null) {
                    for (Thread th : Thread.getAllStackTraces().keySet()) if ("Client".equals(th.getName())) client = th;
                    continue;
                }
                if (!registered && RuneLite.getInjector() != null) {
                    RuneLite.getInjector().getInstance(EventBus.class).register(new StallProfiler.Listener());
                    registered = true;
                    System.out.println("StallProfiler: active");
                }
                StackTraceElement[] st = client.getStackTrace();
                String[] s = new String[Math.min(st.length, 40)];
                for (int i = 0; i < s.length; i++) s[i] = st[i].getClassName() + "." + st[i].getMethodName();
                synchronized (stacks) {
                    times.add(new long[]{System.nanoTime()});
                    stacks.add(s);
                    while (times.size() > 300) { times.poll(); stacks.poll(); }
                }
            } catch (Throwable e) {
                System.err.println("StallProfiler: " + e);
                try { Thread.sleep(1000); } catch (InterruptedException ignored) { }
            }
        }
    }

    private static boolean interesting(String f) {
        if (f.startsWith("java.") || f.startsWith("jdk.") || f.startsWith("sun.") || f.startsWith("com.google.")
                || f.startsWith("net.runelite.client.eventbus.") || f.startsWith("net.runelite.client.callback.")
                || f.startsWith("net.kdt.")) return false;
        int dot = f.lastIndexOf('.', f.lastIndexOf('.') - 1);
        String cls = f.substring(0, f.lastIndexOf('.'));
        return cls.contains(".") || cls.length() > 4; // skip obfuscated client classes (ud, client, ...)
    }

    static void frame() {
        long now = System.nanoTime();
        long prev = lastFrame;
        lastFrame = now;
        if (prev == 0) return;
        double ms = (now - prev) / 1e6;
        boolean stall = ms > Math.max(avg * 1.7, avg + 35);
        avg = avg * 0.95 + Math.min(ms, avg * 3) * 0.05;
        if (!stall || logged >= 200 || now - lastLog < 1_000_000_000L) return;
        lastLog = now;
        logged++;
        Map<String, Integer> top = new HashMap<>(), plugin = new HashMap<>();
        int n = 0;
        synchronized (stacks) {
            java.util.Iterator<long[]> ti = times.iterator();
            java.util.Iterator<String[]> si = stacks.iterator();
            while (ti.hasNext()) {
                long t = ti.next()[0];
                String[] s = si.next();
                if (t < prev || t > now) continue;
                n++;
                if (s.length > 0) top.merge(s[0] + (s.length > 1 ? " < " + s[1] : ""), 1, Integer::sum);
                String p = "-";
                for (String f : s) if (interesting(f)) { p = f; break; }
                plugin.merge(p, 1, Integer::sum);
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("StallProfiler: frame %.0f ms (avg %.0f), %.0f ms after game tick, %d samples%n", ms, avg,
                lastTick == 0 ? -1.0 : (prev - lastTick) / 1e6, n));
        append(sb, "  top ", top);
        append(sb, "  code ", plugin);
        System.out.print(sb);
    }

    private static void append(StringBuilder sb, String label, Map<String, Integer> m) {
        m.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(5)
                .forEach(e -> sb.append(label).append(e.getValue()).append("x ").append(e.getKey()).append('\n'));
    }

    public static final class Listener {
        @Subscribe
        public void onBeforeRender(BeforeRender e) {
            frame();
        }

        @Subscribe
        public void onGameTick(GameTick e) {
            lastTick = System.nanoTime();
        }
    }
}
