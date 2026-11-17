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
     * RuneLite's client is 765x503px plus a 225px plugin sidebar and its own title/menu bar.
     * Keep the virtual screen just large enough for that frame so the game fills the view.
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

        post(this::refreshSize);
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture texture, int w, int h) {
        texture.setDefaultBufferSize(getCanvasWidth(), getCanvasHeight());
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
        final int canvasWidth = getCanvasWidth();
        final int canvasHeight = getCanvasHeight();
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

    /** Make the view fit the proper aspect ratio of the surface */
    private void refreshSize(){
        ViewGroup.LayoutParams layoutParams = getLayoutParams();

        if(getHeight() < getWidth()){
            layoutParams.width = getCanvasWidth() * getHeight() / getCanvasHeight();
        }else{
            layoutParams.height = getCanvasHeight() * getWidth() / getCanvasWidth();
        }

        setLayoutParams(layoutParams);
    }

}
