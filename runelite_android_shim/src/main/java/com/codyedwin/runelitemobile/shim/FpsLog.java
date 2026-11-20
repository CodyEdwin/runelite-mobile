package com.codyedwin.runelitemobile.shim;

import java.lang.reflect.Method;

/** Logs RuneLite's own FPS every 5 s ("RuneLiteFPS: n"), to compare with the frames the Android blitter posts. */
final class FpsLog {
    private FpsLog() {
    }

    static void install() {
        if (!Boolean.getBoolean("runelite.android.perflog")) return;
        Thread t = new Thread(() -> {
            Object client = null;
            Method getFps = null;
            while (true) {
                try {
                    Thread.sleep(5000);
                    if (client == null) {
                        Object injector = Class.forName("net.runelite.client.RuneLite").getMethod("getInjector").invoke(null);
                        if (injector == null) continue;
                        Class<?> clientClass = Class.forName("net.runelite.api.Client");
                        client = Class.forName("com.google.inject.Injector").getMethod("getInstance", Class.class).invoke(injector, clientClass);
                        getFps = clientClass.getMethod("getFPS");
                    }
                    System.out.println("RuneLiteFPS: " + getFps.invoke(client));
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable ignored) {
                    client = null;
                }
            }
        }, "RuneLite-FpsLog");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }
}
