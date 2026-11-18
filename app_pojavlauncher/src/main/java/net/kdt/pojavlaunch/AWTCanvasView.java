package net.kdt.pojavlaunch;

import android.content.*;
import android.graphics.*;
import android.text.*;
import android.util.*;
import android.view.*;

import java.util.*;
import net.kdt.pojavlaunch.utils.*;

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
        updateFitTransform(w, h);
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
        mIsDestroyed = false;
        new Thread(this, "AndroidAWTRenderer").start();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
        mIsDestroyed = true;
        return true;
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int w, int h) {
        // Keep the virtual AWT buffer size independent of the Android view size.
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture texture) {
        // Resizing the buffer after every frame causes flicker and rendering corruption.
    }

    @Override
    public void run() {
        Canvas canvas;
        int canvasWidth = getCanvasWidth();
        int canvasHeight = getCanvasHeight();
        Surface surface = new Surface(getSurfaceTexture());
        Bitmap rgbArrayBitmap = Bitmap.createBitmap(canvasWidth, canvasHeight, Bitmap.Config.ARGB_8888);
        Paint paint = new Paint();
        boolean hasFrame = false;
        boolean loggedInvalidFrame = false;
        try {
            while (!mIsDestroyed && surface.isValid()) {
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
                    if (hasFrame) canvas.drawBitmap(rgbArrayBitmap, 0, 0, paint);
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
