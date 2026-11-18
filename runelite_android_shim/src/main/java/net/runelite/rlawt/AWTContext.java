package net.runelite.rlawt;

import java.awt.Component;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

/**
 * RuneLite Mobile replacement for rlawt (GPU mode, experimental). rlawt draws GL straight into the
 * AWT window via GLX, which does not exist under caciocavallo on Android. Instead this renders into
 * an offscreen EGL (GLES3) context through MobileGlues, into an FBO, and reads every frame back
 * asynchronously (PBOs, one frame of latency) into a BufferedImage that is drawn onto the canvas.
 * Same public API as the real class so RuneLite's GPU plugin links against it unchanged.
 */
public final class AWTContext {
    private static boolean nativesLoaded;
    private final Component component;
    private boolean created;
    private BufferedImage image;
    private int[] pixels = new int[0];
    private final int[] wh = new int[2];
    private long fpsStart = System.nanoTime();
    private int fpsFrames;

    public static synchronized void loadNatives() {
        if (nativesLoaded) return;
        if (!Boolean.getBoolean("runelite.android.gpu")) {
            throw new UnsatisfiedLinkError("GPU mode is off (enable 'GPU mode (experimental)' in the launcher)");
        }
        System.load(System.getProperty("runelite.android.nativeDir") + "/librlawt_android.so");
        String mg = System.getProperty("org.lwjgl.opengl.libname");
        if (!nInit(mg)) throw new UnsatisfiedLinkError("could not load " + mg);
        nativesLoaded = true;
    }

    public AWTContext(Component component) {
        loadNatives();
        this.component = component;
    }

    public void destroy() {
        if (created) nDestroy();
        created = false;
    }

    public void configurePixelFormat(int alpha, int depth, int stencil) {
    }

    public void configureMultisamples(int samples) {
    }

    public int getFramebuffer(boolean front) {
        return nFramebuffer(component.getWidth(), component.getHeight());
    }

    public int getBufferMode() {
        return getFramebuffer(true) == 0 ? 1028 : 36064; // GL_FRONT : GL_COLOR_ATTACHMENT0
    }

    public void createGLContext() {
        String r = nCreate();
        System.out.println("GPU mode: " + r);
        if (!r.startsWith("OK")) throw new IllegalStateException("GPU mode: " + r);
        created = true;
    }

    public int setSwapInterval(int interval) {
        return 0; // presentation is paced by the AWT screen copy, not by EGL
    }

    public void makeCurrent() {
        nMakeCurrent(true);
    }

    public void detachCurrent() {
        nMakeCurrent(false);
    }

    public void swapBuffers() {
        int need = Math.max(1, component.getWidth()) * Math.max(1, component.getHeight());
        if (pixels.length < need) pixels = new int[need];
        if (nSwap(pixels, wh) == 0) return;
        int w = wh[0], h = wh[1];
        if (image == null || image.getWidth() != w || image.getHeight() != h) {
            image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        }
        System.arraycopy(pixels, 0, ((DataBufferInt) image.getRaster().getDataBuffer()).getData(), 0, w * h);
        Graphics g = component.getGraphics();
        if (g != null) {
            try {
                g.drawImage(image, 0, 0, null);
            } finally {
                g.dispose();
            }
        }
        fpsFrames++;
        long now = System.nanoTime();
        if (now - fpsStart >= 5_000_000_000L) {
            System.out.printf("GPU mode: %.1f frames/s presented (%dx%d)%n", fpsFrames * 1e9 / (now - fpsStart), w, h);
            fpsStart = now;
            fpsFrames = 0;
        }
    }

    public long getGLContext() {
        return 0;
    }

    public long getCGLShareGroup() {
        return 0;
    }

    public long getGLXDisplay() {
        return 0;
    }

    public long getWGLHDC() {
        return 0;
    }

    private static native boolean nInit(String mobileGluesPath);
    private static native String nCreate();
    private static native void nMakeCurrent(boolean on);
    private static native int nSwapInterval(int interval);
    private static native int nFramebuffer(int w, int h);
    private static native int nSwap(int[] dst, int[] outWH);
    private static native void nDestroy();
}
