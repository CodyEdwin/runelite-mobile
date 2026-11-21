package com.codyedwin.runelitemobile;

import android.content.*;
import android.graphics.*;
import android.text.*;
import android.util.*;
import android.view.*;

import java.util.*;
import com.codyedwin.runelitemobile.utils.*;

public class AWTCanvasView extends TextureView implements TextureView.SurfaceTextureListener, Runnable {
    /** Default virtual screen size used by jar installers. */
    public static final int DEFAULT_AWT_CANVAS_WIDTH = 720;
    public static final int DEFAULT_AWT_CANVAS_HEIGHT = 600;
    /**
     * RuneLite's client is 765x503px plus a ~240px plugin sidebar and its toolbar/menu bar.
     * The virtual screen is this tall and at least this wide; the width is widened to the
     * device aspect ratio (see {@link #getRuneLiteCanvasSize}) so nothing is letterboxed.
     */
    public static final int RUNELITE_AWT_CANVAS_WIDTH = 1024;
    public static final int RUNELITE_AWT_CANVAS_HEIGHT = 600;

    private static volatile int sAwtCanvasWidth = DEFAULT_AWT_CANVAS_WIDTH;
    private static volatile int sAwtCanvasHeight = DEFAULT_AWT_CANVAS_HEIGHT;

    private static final int MAX_SIZE = 100;
    private static final double NANOS = 1000000000.0;
    private volatile boolean mIsDestroyed = false;
    private static volatile boolean sFpsOverlayEnabled = true;
    private final TextPaint mFpsPaint;

    /** Size of the virtual screen that caciocavallo renders into. */
    public static int getCanvasWidth() {
        return sAwtCanvasWidth;
    }

    public static int getCanvasHeight() {
        return sAwtCanvasHeight;
    }

    /** Must be called before the JVM (and therefore caciocavallo) is started. */
    public static void setCanvasSize(int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid AWT canvas size");
        sAwtCanvasWidth = width;
        sAwtCanvasHeight = height;
    }

    public static void resetCanvasSize() {
        setCanvasSize(DEFAULT_AWT_CANVAS_WIDTH, DEFAULT_AWT_CANVAS_HEIGHT);
    }

    public static void setFpsOverlayEnabled(boolean enabled) {
        sFpsOverlayEnabled = enabled;
    }

    /**
     * Virtual screen size for RuneLite: fixed height, width matched to the (landscape) display
     * aspect ratio so the whole Android surface is used without letterboxing.
     */
    public static Point getRuneLiteCanvasSize(int displayWidth, int displayHeight) {
        int longSide = Math.max(displayWidth, displayHeight);
        int shortSide = Math.min(displayWidth, displayHeight);
        int height = RUNELITE_AWT_CANVAS_HEIGHT;
        int width = RUNELITE_AWT_CANVAS_WIDTH;
        if (longSide > 0 && shortSide > 0) {
            width = Math.max(width, Math.round(height * (float) longSide / shortSide));
        }
        // Keep the width even; some blit paths dislike odd strides.
        return new Point(width + (width & 1), height);
    }

    /** Transform from the AWT virtual screen to view coordinates (uniform scale + centring). */
    private volatile float mScale = 1f;
    private volatile float mOffsetX = 0f;
    private volatile float mOffsetY = 0f;

    /**
     * Map a point in this view's local coordinates to the AWT virtual screen, using the same
     * fit-centre transform that is used to display the frame. Points in the letterbox area are
     * clamped to the nearest edge.
     */
    public void viewToCanvas(float viewX, float viewY, PointF out) {
        float x = (viewX - mOffsetX) / mScale;
        float y = (viewY - mOffsetY) / mScale;
        out.x = Math.max(0, Math.min(getCanvasWidth() - 1, x));
        out.y = Math.max(0, Math.min(getCanvasHeight() - 1, y));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (getSurfaceTexture() != null) getSurfaceTexture().setDefaultBufferSize(getCanvasWidth(), getCanvasHeight());
        updateFitTransform(w, h);
        sForceFrame = true;
    }

    /**
     * TextureView stretches its buffer to the view bounds. Undo that and fit the virtual screen
     * with a uniform scale, centred, so the image is never distorted and touch mapping is exact.
     * (The old code changed the view's LayoutParams once, right after creation; with
     * sensorLandscape + configChanges="orientation" that often happened while the window was
     * still portrait, leaving a wrongly sized view for the rest of the session.)
     */
    private void updateFitTransform(int viewWidth, int viewHeight) {
        if (viewWidth <= 0 || viewHeight <= 0) return;
        int bufferWidth = getCanvasWidth();
        int bufferHeight = getCanvasHeight();
        float scale = Math.min((float) viewWidth / bufferWidth, (float) viewHeight / bufferHeight);
        float contentWidth = bufferWidth * scale;
        float contentHeight = bufferHeight * scale;
        mScale = scale;
        mOffsetX = (viewWidth - contentWidth) / 2f;
        mOffsetY = (viewHeight - contentHeight) / 2f;

        Matrix matrix = new Matrix();
        matrix.setScale(contentWidth / viewWidth, contentHeight / viewHeight);
        matrix.postTranslate(mOffsetX, mOffsetY);
        setTransform(matrix);
        int[] loc = new int[2];
        getLocationOnScreen(loc);
        android.util.Log.i("AWTCanvasView", "fit: view " + viewWidth + "x" + viewHeight + " at " + loc[0] + "," + loc[1]
                + " buffer " + bufferWidth + "x" + bufferHeight + " scale " + scale + " offset " + mOffsetX + "," + mOffsetY);
    }

    // Temporary count fps https://stackoverflow.com/a/13729241
    private final LinkedList<Long> mTimes = new LinkedList<Long>(){{add(System.nanoTime());}};
    
    public AWTCanvasView(Context ctx) {
        this(ctx, null);
    }
    
    public AWTCanvasView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        
        mFpsPaint = new TextPaint();
        mFpsPaint.setColor(Color.WHITE);
        mFpsPaint.setTextSize(20);


        setSurfaceTextureListener(this);
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture texture, int w, int h) {
        texture.setDefaultBufferSize(getCanvasWidth(), getCanvasHeight());
        updateFitTransform(getWidth(), getHeight());
        // On return from another app the view may still be mid-layout; refit once it settles.
        post(() -> {
            if (getSurfaceTexture() != null) getSurfaceTexture().setDefaultBufferSize(getCanvasWidth(), getCanvasHeight());
            updateFitTransform(getWidth(), getHeight());
            sForceFrame = true;
        });
        sForceFrame = true;
        mIsDestroyed = false;
        // One renderer per SurfaceTexture: an older thread (still sleeping when the surface was
        // recreated) sees it is no longer current and exits instead of racing the new one.
        Thread t = new Thread(this, "AndroidAWTRenderer");
        mRenderThread = t;
        t.start();
    }

    private volatile Thread mRenderThread;

    private boolean isCurrentRenderer() {
        return !mIsDestroyed && Thread.currentThread() == mRenderThread;
    }

    /**
     * Returning from another app: Android destroyed the TextureView's hardware layer when the
     * window was hidden but kept its SurfaceTexture, and on the next draw rebuilds the layer and
     * silently calls setDefaultBufferSize(viewWidth, viewHeight) - no listener callback. Our
     * canvas-sized frames then filled only the top-left part of a view-sized buffer (the game
     * shrank into a corner). Re-apply our size and post a full frame whenever we become visible.
     */
    private void reapplyBufferSize() {
        SurfaceTexture st = getSurfaceTexture();
        if (st != null) st.setDefaultBufferSize(getCanvasWidth(), getCanvasHeight());
        updateFitTransform(getWidth(), getHeight());
        sForceFrame = true;
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) {
            post(this::reapplyBufferSize);
            postDelayed(this::reapplyBufferSize, 300);
        }
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        if (isVisible) post(this::reapplyBufferSize);
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
        mIsDestroyed = true;
        mRenderThread = null;
        return true;
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int w, int h) {
        // TextureView resets the buffer to the view size whenever it is re-laid out (e.g. the
        // system bars appearing while switching apps). Our frames are canvas-sized, so the game then
        // filled only a corner of the buffer and stayed small. Re-apply our size and the fit.
        texture.setDefaultBufferSize(getCanvasWidth(), getCanvasHeight());
        updateFitTransform(getWidth(), getHeight());
        sForceFrame = true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture texture) {
        // Resizing the buffer after every frame causes flicker and rendering corruption.
    }

    /** Fast path (cacio17 / RuneLite): native copy into the bitmap, only when the screen changed. */
    private static volatile boolean sFastBlit;
    /** Frames posted to the screen (FPS overlay). */
    public static volatile int sFramesPosted;
    /** Set when the surface was (re)created or resized: post a full frame even if nothing changed. */
    private static volatile boolean sForceFrame;

    public static void setFastBlitEnabled(boolean enabled) {
        sFastBlit = enabled;
    }

    /**
     * Fastest path: native code converts the screen straight into the Surface's buffer and posts it
     * (no Bitmap copy, no texture upload). Falls back to {@link #runFast} if no ANativeWindow.
     */
    private static final boolean sDirectBlit = false;
    private static final boolean PERF_LOG = false;

    private boolean runDirect() {
        Surface surface = new Surface(getSurfaceTexture());
        long window = JREUtils.nativeWindowFromSurface(surface);
        if (window == 0) {
            surface.release();
            return false;
        }
        int canvasWidth = getCanvasWidth();
        int canvasHeight = getCanvasHeight();
        long statsStart = System.nanoTime();
        int posted = 0, polls = 0;
        boolean force = true;
        com.codyedwin.runelitemobile.utils.ThreadPlacement.placeRenderThread();
        try {
            while (isCurrentRenderer() && surface.isValid()) {
                polls++;
                if (sForceFrame) { sForceFrame = false; force = true; }
                int r = JREUtils.renderAWTScreenFrameToWindow(window, canvasWidth, canvasHeight, force);
                if (r < 0) {
                    if (-r == getCanvasWidth() * getCanvasHeight()) {
                        canvasWidth = getCanvasWidth();
                        canvasHeight = getCanvasHeight();
                        post(() -> updateFitTransform(getWidth(), getHeight()));
                        Log.i("AWTCanvasView", "virtual screen resized to " + canvasWidth + "x" + canvasHeight);
                        force = true;
                    } else {
                        Thread.sleep(16);
                    }
                    continue;
                }
                if (r == 0) {
                    Thread.sleep(4);
                } else {
                    force = false;
                    posted++;
                    sFramesPosted++;
                }
                long now = System.nanoTime();
                if (PERF_LOG && now - statsStart >= 5_000_000_000L) {
                    Log.i("AWTCanvasView", String.format(java.util.Locale.ROOT, "direct blit: %.1f frames/s posted, %.0f polls/s",
                            posted * 1e9 / (now - statsStart), polls * 1e9 / (now - statsStart)));
                    statsStart = now;
                    posted = 0;
                    polls = 0;
                    com.codyedwin.runelitemobile.utils.ThreadPlacement.placeGameThreads();
                }
            }
        } catch (Throwable throwable) {
            Tools.showError(getContext(), throwable);
        }
        JREUtils.nativeWindowRelease(window);
        surface.release();
        return true;
    }

    private void runFast() {
        int canvasWidth = getCanvasWidth();
        int canvasHeight = getCanvasHeight();
        Surface surface = new Surface(getSurfaceTexture());
        Bitmap bitmap = Bitmap.createBitmap(canvasWidth, canvasHeight, Bitmap.Config.ARGB_8888);
        boolean hardware = android.os.Build.VERSION.SDK_INT >= 23;
        long statsStart = System.nanoTime();
        int posted = 0, polls = 0;
        boolean force = true;
        com.codyedwin.runelitemobile.utils.ThreadPlacement.placeRenderThread();
        try {
            final Rect dst = new Rect();
            final Paint scalePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
            while (isCurrentRenderer() && surface.isValid()) {
                polls++;
                if (sForceFrame) { sForceFrame = false; force = true; }
                int r = JREUtils.renderAWTScreenFrameToBitmap(bitmap, force);
                if (r < 0) {
                    // Screen size changed (finalised from the laid-out view after this thread started).
                    if (-r == getCanvasWidth() * getCanvasHeight()) {
                        canvasWidth = getCanvasWidth();
                        canvasHeight = getCanvasHeight();
                        bitmap.recycle();
                        bitmap = Bitmap.createBitmap(canvasWidth, canvasHeight, Bitmap.Config.ARGB_8888);
                        getSurfaceTexture().setDefaultBufferSize(canvasWidth, canvasHeight);
                        post(() -> updateFitTransform(getWidth(), getHeight()));
                        Log.i("AWTCanvasView", "virtual screen resized to " + canvasWidth + "x" + canvasHeight);
                        force = true;
                    } else {
                        Thread.sleep(16);
                    }
                    continue;
                }
                if (r == 0) {
                    // Nothing new: the TextureView keeps showing the last posted frame.
                    Thread.sleep(4);
                } else {
                    force = false;
                    Canvas canvas = hardware ? surface.lockHardwareCanvas() : surface.lockCanvas(null);
                    try {
                        drawFrame(canvas, bitmap, dst, scalePaint);
                    } finally {
                        surface.unlockCanvasAndPost(canvas);
                    }
                    posted++;
                    sFramesPosted++;
                }
                long now = System.nanoTime();
                if (PERF_LOG && now - statsStart >= 5_000_000_000L) {
                    Log.i("AWTCanvasView", String.format(java.util.Locale.ROOT, "blit: %.1f frames/s posted, %.0f polls/s",
                            posted * 1e9 / (now - statsStart), polls * 1e9 / (now - statsStart)));
                    statsStart = now;
                    posted = 0;
                    polls = 0;
                    com.codyedwin.runelitemobile.utils.ThreadPlacement.placeGameThreads();
                }
            }
        } catch (Throwable throwable) {
            Tools.showError(getContext(), throwable);
        }
        bitmap.recycle();
        surface.release();
    }

    /**
     * Draw a canvas-sized frame so it always fills the whole buffer. If the buffer is not
     * canvas-sized (TextureView reset it to the view size behind our back), scale the frame into it
     * - the view transform stretches any buffer to the same fitted rectangle - and ask for the
     * right size again for the following frames.
     */
    private static long sLastScaleLog;

    private void drawFrame(Canvas canvas, Bitmap frame, Rect dst, Paint scalePaint) {
        int cw = canvas.getWidth(), ch = canvas.getHeight();
        if (cw == frame.getWidth() && ch == frame.getHeight()) {
            canvas.drawBitmap(frame, 0, 0, null);
            return;
        }
        dst.set(0, 0, cw, ch);
        canvas.drawBitmap(frame, null, dst, scalePaint);
        SurfaceTexture st = getSurfaceTexture();
        if (st != null) st.setDefaultBufferSize(getCanvasWidth(), getCanvasHeight());
        sForceFrame = true;
        long now = System.currentTimeMillis();
        if (now - sLastScaleLog > 2000) {
            sLastScaleLog = now;
            Log.i("AWTCanvasView", "buffer was " + cw + "x" + ch + ", frame " + frame.getWidth() + "x" + frame.getHeight() + ": scaled and re-sized");
        }
    }

    @Override
    public void run() {
        if (sFastBlit) {
            // runDirect (ANativeWindow) measured slower in software mode on a OnePlus Nord: opt-in only.
            if (!sDirectBlit || !runDirect()) runFast();
            return;
        }
        Canvas canvas;
        int canvasWidth = getCanvasWidth();
        int canvasHeight = getCanvasHeight();
        Surface surface = new Surface(getSurfaceTexture());
        Bitmap rgbArrayBitmap = Bitmap.createBitmap(canvasWidth, canvasHeight, Bitmap.Config.ARGB_8888);
        Paint paint = new Paint();
        boolean hasFrame = false;
        boolean loggedInvalidFrame = false;
        try {
            final Rect dst = new Rect();
            final Paint scalePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
            while (isCurrentRenderer() && surface.isValid()) {
                canvas = surface.lockCanvas(null);
                try {
                    int[] rgbArray = JREUtils.renderAWTScreenFrame();
                    if (rgbArray != null && rgbArray.length != canvasWidth * canvasHeight
                            && rgbArray.length == getCanvasWidth() * getCanvasHeight()) {
                        // The virtual screen size was finalised (from the laid-out view) after
                        // this thread started: follow it.
                        canvasWidth = getCanvasWidth();
                        canvasHeight = getCanvasHeight();
                        rgbArrayBitmap.recycle();
                        rgbArrayBitmap = Bitmap.createBitmap(canvasWidth, canvasHeight, Bitmap.Config.ARGB_8888);
                        getSurfaceTexture().setDefaultBufferSize(canvasWidth, canvasHeight);
                        post(() -> updateFitTransform(getWidth(), getHeight()));
                        hasFrame = false;
                        Log.i("AWTCanvasView", "virtual screen resized to " + canvasWidth + "x" + canvasHeight);
                    }
                    if (rgbArray != null && rgbArray.length == canvasWidth * canvasHeight) {
                        rgbArrayBitmap.setPixels(rgbArray, 0, canvasWidth, 0, 0, canvasWidth, canvasHeight);
                        hasFrame = true;
                    } else if (rgbArray != null && !loggedInvalidFrame) {
                        Log.w("AWTCanvasView", "Ignoring frame with " + rgbArray.length
                                + " pixels; expected " + (canvasWidth * canvasHeight));
                        loggedInvalidFrame = true;
                    }
                    // Keep displaying the last complete frame while the JVM has no new frame.
                    if (hasFrame) drawFrame(canvas, rgbArrayBitmap, dst, scalePaint);
                    else canvas.drawRGB(0, 0, 0);
                    if (sFpsOverlayEnabled) {
                        canvas.drawText("FPS: " + (Math.round(fps() * 10) / 10)
                                + ", drawing=" + hasFrame, 0, 20, mFpsPaint);
                    }
                } finally {
                    surface.unlockCanvasAndPost(canvas);
                }
            }
        } catch (Throwable throwable) {
            Tools.showError(getContext(), throwable);
        }
        rgbArrayBitmap.recycle();
        surface.release();
    }

    /** Calculates and returns frames per second */
    private double fps() {
        long lastTime = System.nanoTime();
        double difference = (lastTime - mTimes.getFirst()) / NANOS;
        mTimes.addLast(lastTime);
        int size = mTimes.size();
        if (size > MAX_SIZE) {
            mTimes.removeFirst();
        }
        return difference > 0 ? mTimes.size() / difference : 0.0;
    }

}
