/*
 * Patched copy of caciocavallo 1.18 CTCScreen (decompiled): adds SCREEN_LOCK so the Android blitter
 * never copies the screen while Swing is in the middle of painting a batch of dirty regions, and
 * uses an opaque INT_RGB screen buffer.
 */
package com.github.caciocavallosilano.cacio.ctc;

import com.github.caciocavallosilano.cacio.peer.WindowClippedGraphics;
import com.github.caciocavallosilano.cacio.peer.managed.FullScreenWindowFactory;
import com.github.caciocavallosilano.cacio.peer.managed.PlatformScreen;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.geom.Area;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.io.File;
import java.util.List;

public class CTCScreen
implements PlatformScreen {
    private BufferedImage screenBuffer;
    private static CTCScreen instance;
    private static int[] dataBufAux;
    /**
     * Held by the Swing side (runelite_android_shim RepaintPump/LockingRepaintManager) while it
     * paints dirty regions, and by {@link #getCurrentScreenRGB()} while copying the screen, so the
     * Android view only ever shows complete Swing paints (no half-drawn panels / flicker).
     */
    public static final Object SCREEN_LOCK = new Object();

    static CTCScreen getInstance() {
        if (instance == null) {
            instance = new CTCScreen();
        }
        return instance;
    }

    private CTCScreen() {
        Dimension d = FullScreenWindowFactory.getScreenDimension();
        // Opaque RGB: the game's frames (also INT_RGB) are then drawn with a plain copy instead of a
        // per-pixel RGB->ARGB conversion on the Client thread. The screen itself is never translucent.
        this.screenBuffer = new BufferedImage(d.width, d.height, BufferedImage.TYPE_INT_RGB);
    }

    @Override
    public ColorModel getColorModel() {
        return this.screenBuffer.getColorModel();
    }

    @Override
    public GraphicsConfiguration getGraphicsConfiguration() {
        return GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
    }

    @Override
    public Rectangle getBounds() {
        Dimension d = FullScreenWindowFactory.getScreenDimension();
        return new Rectangle(0, 0, d.width, d.height);
    }

    @Override
    public Graphics2D getClippedGraphics(Color fg, Color bg, Font f, List<Rectangle> clipRects) {
        Graphics2D g2d = (Graphics2D)this.screenBuffer.getGraphics();
        if (clipRects != null && clipRects.size() > 0) {
            Area a = new Area(this.getBounds());
            for (Rectangle clip : clipRects) {
                a.subtract(new Area(clip));
            }
            g2d = new WindowClippedGraphics(g2d, a);
        }
        return g2d;
    }

    int[] getRGBPixels(Rectangle bounds) {
        return this.screenBuffer.getRGB(bounds.x, bounds.y, bounds.width, bounds.height, null, 0, bounds.width);
    }

    public static int[] getCurrentScreenRGB() {
        if (CTCScreen.instance.screenBuffer == null) {
            return null;
        }
        if (dataBufAux == null) {
            dataBufAux = new int[(int)FullScreenWindowFactory.getScreenDimension().getWidth() * (int)FullScreenWindowFactory.getScreenDimension().getHeight()];
        }
        synchronized (SCREEN_LOCK) {
            CTCScreen.instance.screenBuffer.getRaster().getDataElements(0, 0, (int)FullScreenWindowFactory.getScreenDimension().getWidth(), (int)FullScreenWindowFactory.getScreenDimension().getHeight(), dataBufAux);
        }
        for (int i = 0; i < dataBufAux.length; i++) dataBufAux[i] |= 0xFF000000; // INT_RGB has no alpha
        return dataBufAux;
    }

    private static sun.java2d.StateTracker sScreenTracker;
    private static long sLastFullCopy;

    /**
     * Android blitter fast path (called from native code with {@link #SCREEN_LOCK} held): returns
     * the screen's backing int[] (no copy) when it changed since the last call, else null. Change
     * detection uses the raster's StateTrackable, which Java2D marks dirty on every rendering
     * operation; a refresh is still forced every 250 ms in case some path bypasses it.
     */
    public static int[] getScreenPixelsIfChanged(boolean force) {
        CTCScreen screen = instance;
        if (screen == null || screen.screenBuffer == null) return null;
        java.awt.image.DataBuffer db = screen.screenBuffer.getRaster().getDataBuffer();
        if (!(db instanceof java.awt.image.DataBufferInt)) return null;
        long now = System.nanoTime();
        boolean stale = now - sLastFullCopy > 250_000_000L;
        if (!force && !stale && sScreenTracker != null && sScreenTracker.isCurrent()) return null;
        sScreenTracker = sun.awt.image.SunWritableRaster.stealTrackable(db).getStateTracker();
        sLastFullCopy = now;
        return sun.awt.image.SunWritableRaster.stealData((java.awt.image.DataBufferInt) db, 0);
    }

    static {
        try {
            for (String ldLib : System.getenv("LD_LIBRARY_PATH").split(":")) {
                File currLibFile;
                if (ldLib.isEmpty() || !(currLibFile = new File(ldLib, "libpojavexec_awt.so")).exists()) continue;
                System.load(currLibFile.getAbsolutePath());
                break;
            }
        }
        catch (Throwable th) {
            th.printStackTrace();
        }
    }
}
