package net.kdt.pojavlaunch;

import static net.kdt.pojavlaunch.prefs.LauncherPreferences.DEFAULT_PREF;

import android.annotation.SuppressLint;
import android.app.ProgressDialog;
import android.content.ClipboardManager;
import android.net.Uri;
import android.graphics.Point;
import android.graphics.PointF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.GestureDetector;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;

import com.kdt.LoggerView;

import net.kdt.pojavlaunch.customcontrols.keyboard.AwtCharSender;
import net.kdt.pojavlaunch.customcontrols.keyboard.TouchCharInput;
import net.kdt.pojavlaunch.multirt.MultiRTUtils;
import net.kdt.pojavlaunch.multirt.Runtime;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.utils.JREUtils;
import net.kdt.pojavlaunch.utils.MathUtils;

import org.apache.commons.io.IOUtils;
import org.lwjgl.glfw.CallbackBridge;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class JavaGUILauncherActivity extends BaseActivity implements View.OnTouchListener {

    /** Launch the bundled RuneLite client instead of a jar installer. */
    public static final String EXTRA_RUNELITE = "runeLite";
    /** Classpath of a downloaded official RuneLite release; absent = the bundled runelite.jar. */
    public static final String EXTRA_RUNELITE_CLASSPATH = "runeLiteClasspath";
    public static final String EXTRA_RUNELITE_VERSION = "runeLiteVersion";
    public static final String EXTRA_RUNELITE_JVM_ARGS = "runeLiteJvmArgs";
    /** Name of the Java runtime that should be used (optional, avoids re-picking it). */
    public static final String EXTRA_RUNTIME = "runtimeName";
    /** RuneLite profile name (from ~/.runelite/profiles2) to start with, or null for the active one. */
    public static final String EXTRA_RUNELITE_PROFILE = "runeLiteProfile";

    private static WeakReference<JavaGUILauncherActivity> sRunningInstance = new WeakReference<>(null);

    private boolean mRuneLite;
    private String mRequestedRuntime;
    private AWTCanvasView mTextureView;
    private net.kdt.pojavlaunch.runelitemobile.RuneLoadingView mLoadingView;
    private Logger.eventLogListener mLoadingLogListener;
    private LoggerView mLoggerView;
    private TouchCharInput mTouchCharInput;

    private LinearLayout mTouchPad;
    private ImageView mMousePointerImageView;
    private GestureDetector mGestureDetector;

    private boolean mIsVirtualMouseEnabled;
    private boolean mRuneLiteShiftDown;
    private int mMouseButtonState;
    private final int[] mMouseViewLocation = new int[2];
    private final PointF mCanvasPoint = new PointF();
    private RuneLiteTouchHandler mRuneLiteTouchHandler;

    /** Activity hosting the AWT surface, used by AwtHost#openLink for Desktop#browse. */
    public static JavaGUILauncherActivity getRunningInstance() {
        return sRunningInstance.get();
    }
    
    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Read the launch mode before inflating the layout: AWTCanvasView uses a static
        // virtual screen size which must match -Dcacio.managed.screensize later on.
        sRunningInstance = new WeakReference<>(this);
        // Keep this process (the RuneLite JVM) alive while Chrome is in front for the Jagex login.
        try {
            android.content.Intent service = new android.content.Intent(this, net.kdt.pojavlaunch.services.RuneLiteService.class);
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(service);
            else startService(service);
        } catch (Exception e) {
            android.util.Log.w("JavaGUILauncher", "Unable to start the RuneLite foreground service", e);
        }
        mRuneLite = getIntent().getBooleanExtra(EXTRA_RUNELITE, false);
        mRequestedRuntime = getIntent().getStringExtra(EXTRA_RUNTIME);
        AWTCanvasView.setFpsOverlayEnabled(!mRuneLite);
        AWTCanvasView.setFastBlitEnabled(mRuneLite);
        if (mRuneLite) {
            DisplayMetrics metrics = new DisplayMetrics();
            getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
            Point size = AWTCanvasView.getRuneLiteCanvasSize(metrics.widthPixels, metrics.heightPixels);
            AWTCanvasView.setCanvasSize(size.x, size.y);
        } else {
            AWTCanvasView.resetCanvasSize();
        }

        if (mRuneLite && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            // Draw under the camera cutout too, so the view matches the display size the AWT
            // virtual screen was computed from (otherwise it is letterboxed by the cutout inset).
            android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        setContentView(R.layout.activity_java_gui_launcher);

        try {
            File latestLogFile = new File(Tools.DIR_GAME_HOME, "latestlog.txt");
            if (!latestLogFile.exists() && !latestLogFile.createNewFile())
                throw new IOException("Failed to create a new log file");
            Logger.begin(latestLogFile.getAbsolutePath());
        }catch (IOException e) {
            Tools.showError(this, e, true);
        }
        AwtHost.GLOBAL_CLIPBOARD = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        mTouchCharInput = findViewById(R.id.awt_touch_char);
        mTouchCharInput.setCharacterSender(new AwtCharSender());

        mTouchPad = findViewById(R.id.main_touchpad);
        mLoggerView = findViewById(R.id.launcherLoggerView);
        mMousePointerImageView = findViewById(R.id.main_mouse_pointer);
        mTextureView = findViewById(R.id.installmod_surfaceview);
        if (mRuneLite) {
            mLoadingView = net.kdt.pojavlaunch.runelitemobile.RuneLoadingView.attach(this, "Starting Java…", 0.45f);
            final net.kdt.pojavlaunch.runelitemobile.RuneLoadingView lv = mLoadingView;
            mLoadingLogListener = lv::addLog; // JVM stdout/stderr (RuneLite's logback output) as it arrives
            Logger.addLogListener(mLoadingLogListener);
        }
        mGestureDetector = new GestureDetector(this, new SingleTapConfirm());
        mTouchPad.setFocusable(false);
        mTouchPad.setVisibility(View.GONE);

        findViewById(R.id.installmod_mouse_pri).setOnTouchListener(this);
        findViewById(R.id.installmod_mouse_sec).setOnTouchListener(this);
        findViewById(R.id.installmod_window_moveup).setOnTouchListener(this);
        findViewById(R.id.installmod_window_movedown).setOnTouchListener(this);
        findViewById(R.id.installmod_window_moveleft).setOnTouchListener(this);
        findViewById(R.id.installmod_window_moveright).setOnTouchListener(this);

        mMousePointerImageView.post(() -> {
            ViewGroup.LayoutParams params = mMousePointerImageView.getLayoutParams();
            params.width = (int) (36 * LauncherPreferences.PREF_MOUSESCALE);
            params.height = (int) (54 * LauncherPreferences.PREF_MOUSESCALE);
        });

        mTouchPad.setOnTouchListener(new View.OnTouchListener() {
            float prevX = 0, prevY = 0;
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                // MotionEvent reports input details from the touch screen
                // and other input controls. In this case, you are only
                // interested in events where the touch position changed.
                // int index = event.getActionIndex();
                int action = event.getActionMasked();

                float x = event.getX();
                float y = event.getY();
                float mouseX, mouseY;

                mouseX = mMousePointerImageView.getX();
                mouseY = mMousePointerImageView.getY();

                if (mGestureDetector.onTouchEvent(event)) {
                    sendScaledMousePosition(mouseX,mouseY);
                    AWTInputBridge.sendMousePress(AWTInputEvent.BUTTON1_DOWN_MASK);
                } else {
                    if (action == MotionEvent.ACTION_MOVE) { // 2
                        mouseX = Math.max(0, Math.min(CallbackBridge.physicalWidth, mouseX + x - prevX));
                        mouseY = Math.max(0, Math.min(CallbackBridge.physicalHeight, mouseY + y - prevY));
                        placeMouseAt(mouseX, mouseY);
                        sendScaledMousePosition(mouseX, mouseY);
                    }
                }

                prevY = y;
                prevX = x;
                return true;
            }
        });

        if (mRuneLite) {
            mRuneLiteTouchHandler = new RuneLiteTouchHandler();
        }
        mTextureView.setOnTouchListener((v, event) -> {
            if (mRuneLiteTouchHandler != null) return mRuneLiteTouchHandler.onTouch(event);
            float x = event.getX();
            float y = event.getY();
            if (mGestureDetector.onTouchEvent(event)) {
                sendScaledMousePosition(x + mTextureView.getX(), y + mTextureView.getY());
                AWTInputBridge.sendMousePress(AWTInputEvent.BUTTON1_DOWN_MASK);
                return true;
            }

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_UP: // 1
                case MotionEvent.ACTION_CANCEL: // 3
                case MotionEvent.ACTION_POINTER_UP: // 6
                    break;
                case MotionEvent.ACTION_MOVE: // 2
                    sendScaledMousePosition(x + mTextureView.getX(), y + mTextureView.getY());
                    break;
            }
            return true;
        });

        if (mRuneLite) {
            ViewGroup canvasParent = (ViewGroup) mTextureView.getParent();
            for (int i = 0; i < canvasParent.getChildCount(); i++) {
                View child = canvasParent.getChildAt(i);
                if (child instanceof Button) child.setVisibility(View.GONE);
            }
        }

        try {

            mTextureView.post(() -> placeMouseAt(
                    mTextureView.getX() + mTextureView.getWidth() / 2f,
                    mTextureView.getY() + mTextureView.getHeight() / 2f));
            Bundle extras = getIntent().getExtras();
            if(extras == null) {
                finish();
                return;
            }
            if(extras.getBoolean("openLogOutput", false)) openLogOutput(null);
            if (mRuneLite) {
                startRuneLiteWhenLaidOut();
            } else {
                finish();
            }
        } catch (Throwable th) {
            Tools.showError(this, th, true);
        }


        getOnBackPressedDispatcher().addCallback(new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                Tools.dialogForceClose(JavaGUILauncherActivity.this);
            }
        });
    }

    /**
     * Size the AWT virtual screen from the space the TextureView really gets, not the raw display:
     * some devices (Motorola's per-app "full screen" setting, OEM cutout policies) keep the window
     * out of the camera cutout regardless of layoutInDisplayCutoutMode, and a virtual screen with the
     * display's aspect ratio is then shrunk and letterboxed inside the smaller view.
     */
    private void startRuneLiteWhenLaidOut() {
        final Handler handler = new Handler(Looper.getMainLooper());
        final boolean[] started = {false};
        final Runnable start = () -> {
            if (started[0]) return;
            started[0] = true;
            int w = mTextureView.getWidth(), h = mTextureView.getHeight();
            if (w > 0 && h > 0) {
                Point size = AWTCanvasView.getRuneLiteCanvasSize(Math.max(w, h), Math.min(w, h));
                AWTCanvasView.setCanvasSize(size.x, size.y);
                android.util.Log.i("JavaGUILauncher", "RuneLite screen " + size.x + "x" + size.y + " for view " + w + "x" + h);
            }
            startRuneLite();
        };
        mTextureView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (started[0]) return;
            handler.removeCallbacks(start);
            handler.postDelayed(start, 400); // let immersive mode / insets settle
        });
        handler.postDelayed(start, 2500); // fallback if no further layout pass happens
    }


    private void loadingStatus(String status, float progress) {
        net.kdt.pojavlaunch.runelitemobile.RuneLoadingView v = mLoadingView;
        if (v != null) v.setStatus(status, progress);
    }

    private void dismissLoading() {
        net.kdt.pojavlaunch.runelitemobile.RuneLoadingView v = mLoadingView;
        mLoadingView = null;
        Logger.eventLogListener l = mLoadingLogListener;
        mLoadingLogListener = null;
        if (l != null) Logger.removeLogListener(l);
        if (v != null) v.dismiss();
    }

    /** Fades the loading screen out once the shim reports RuneLite's main window (or after 3 minutes). */
    private void watchRuneLiteReady(File readyFile) {
        Thread t = new Thread(() -> {
            long begin = System.currentTimeMillis();
            float p = 0.7f;
            while (mLoadingView != null && System.currentTimeMillis() - begin < 180_000) {
                if (readyFile.exists()) {
                    loadingStatus("Ready", 1f);
                    try { Thread.sleep(350); } catch (InterruptedException ignored) { }
                    break;
                }
                p += (0.97f - p) * 0.01f; // creep while RuneLite loads (its own progress is not exposed)
                loadingStatus("Launching RuneLite…", p);
                try { Thread.sleep(250); } catch (InterruptedException e) { break; }
            }
            dismissLoading();
        }, "RuneLiteReadyWatch");
        t.setDaemon(true);
        t.start();
    }

    private void startRuneLite() {
        new Thread(() -> {
            try {
                File jar = Tools.getRuneLiteJar();
                String clientClasspath = getIntent().getStringExtra(EXTRA_RUNELITE_CLASSPATH);
                String clientVersion = getIntent().getStringExtra(EXTRA_RUNELITE_VERSION);
                if (clientClasspath != null) {
                    for (String entry : clientClasspath.split(File.pathSeparator)) {
                        if (!new File(entry).isFile()) {
                            Log.w("JavaGUILauncher", "Downloaded RuneLite is incomplete (" + entry + "), using the bundled client");
                            clientClasspath = null;
                            clientVersion = null;
                            break;
                        }
                    }
                }
                if(clientClasspath == null && !jar.isFile()) {
                    finalErrorDialog(getString(R.string.runelite_not_installed, jar.getAbsolutePath()));
                    return;
                }

                Runtime runtime = null;
                if (mRequestedRuntime != null) {
                    Runtime requested = MultiRTUtils.forceReread(mRequestedRuntime);
                    if (requested != null
                            && requested.javaVersion >= Tools.RUNELITE_MIN_JAVA
                            && requested.javaVersion <= Tools.RUNELITE_MAX_JAVA) {
                        runtime = requested;
                    }
                }
                if (runtime == null) {
                    // the caciocavallo AWT agent used to host the Swing UI is built for Java 17
                    for (Runtime candidate : MultiRTUtils.getInstalledRuntimes()) {
                        if (candidate.javaVersion < Tools.RUNELITE_MIN_JAVA
                                || candidate.javaVersion > Tools.RUNELITE_MAX_JAVA) continue;
                        if (runtime == null || candidate.javaVersion > runtime.javaVersion) runtime = candidate;
                    }
                }
                if (runtime == null) {
                    finalErrorDialog(getString(R.string.runelite_requires_java));
                    return;
                }

                // Memory setting from the launcher (separate process: read it from disk).
                LauncherPreferences.PREF_RAM_ALLOCATION = androidx.preference.PreferenceManager
                        .getDefaultSharedPreferences(this).getInt("allocation", LauncherPreferences.PREF_RAM_ALLOCATION);
                List<String> args = new ArrayList<>();
                // RuneLite only enables developer mode for development runs with assertions.
                args.add("-ea");
                args.add("-Djava.awt.headless=false");
                // Read from disk: this activity runs in its own process (:gui_installer).
                if (androidx.preference.PreferenceManager.getDefaultSharedPreferences(this).getBoolean(
                        net.kdt.pojavlaunch.prefs.LauncherPreferences.PREF_KEY_RUNELITE_PERFORMANCE, false)) {
                    args.add("-Drunelite.android.performanceMode=true");
                }
                String gpuClasspath = "";
                // Native helpers loaded by the shim (AAudio sound output, GPU mode).
                args.add("-Drunelite.android.nativeDir=" + getApplicationInfo().nativeLibraryDir);
                // GPU mode (experimental): RuneLite's GPU plugin on MobileGlues (desktop GL over GLES)
                // through an offscreen EGL context (see net.runelite.rlawt.AWTContext in the shim).
                if (androidx.preference.PreferenceManager.getDefaultSharedPreferences(this).getBoolean(
                        net.kdt.pojavlaunch.prefs.LauncherPreferences.PREF_KEY_RUNELITE_GPU, false)) {
                    String nativeDir = getApplicationInfo().nativeLibraryDir;
                    args.add("-Drunelite.android.gpu=true");
                    args.add("-Dorg.lwjgl.librarypath=" + nativeDir);
                    // LWJGL's "libGL" is our wrapper: it forwards to MobileGlues and fixes GLES quirks.
                    args.add("-Dorg.lwjgl.opengl.libname=" + nativeDir + "/librlawt_android.so");
                    args.add("-Drunelite.android.mgLib=" + nativeDir + "/libmobileglues.so");
                    JREUtils.gpuMode = true;
                    // RuneLite's jar carries glibc builds of liblwjgl(_opengl).so that LWJGL extracts
                    // before looking at org.lwjgl.librarypath. A classpath directory ahead of it with
                    // the same resource names makes LWJGL load our Android builds straight from disk.
                    try {
                        File lwjglDir = new File(getFilesDir(), "gpu-natives/linux/arm64/org/lwjgl");
                        new File(lwjglDir, "opengl").mkdirs();
                        String[][] links = {{"liblwjgl.so", "liblwjgl.so"}, {"liblwjgl_opengl.so", "opengl/liblwjgl_opengl.so"}};
                        for (String[] l : links) {
                            File link = new File(lwjglDir, l[1]);
                            link.delete();
                            android.system.Os.symlink(nativeDir + "/" + l[0], link.getAbsolutePath());
                        }
                        String gpuDir = new File(getFilesDir(), "gpu-natives").getAbsolutePath();
                        args.add("-Drunelite.android.gpuDir=" + gpuDir);
                        gpuClasspath = gpuDir + File.pathSeparator;
                    } catch (Exception e) {
                        Log.w("JavaGUILauncher", "GPU mode: could not link LWJGL natives", e);
                    }
                }
                // Keep RuneLite's ~/.runelite inside its own instance directory
                args.add("-Duser.home=" + Tools.getRuneLiteDir().getAbsolutePath());
                String clientJars = clientClasspath != null ? clientClasspath : jar.getAbsolutePath();
                if (clientClasspath != null) {
                    java.util.ArrayList<String> extraArgs = getIntent().getStringArrayListExtra(EXTRA_RUNELITE_JVM_ARGS);
                    if (extraArgs != null) args.addAll(extraArgs);
                }
                if (clientVersion != null) {
                    // The shim writes this once RuneLite's window is up, so the launcher knows the
                    // version works (a downloaded version that never gets there is rolled back).
                    args.add("-Drunelite.android.okFile=" + net.kdt.pojavlaunch.runeliteupdate.RuneLiteUpdates.okFile(this, clientVersion).getAbsolutePath());
                }
                Log.i("JavaGUILauncher", "Starting RuneLite " + (clientVersion != null ? clientVersion : "(bundled)")
                        + (clientClasspath != null ? " from the official release" : " from the bundled runelite.jar"));
                args.add("-cp");
                // runelite-android.jar must come first: it overrides JagexAuthService and its
                // entry point makes the client frame fill the caciocavallo virtual screen.
                File androidJar = Tools.getRuneLiteAndroidJar();
                if (androidJar.isFile()) {
                    args.add(gpuClasspath + androidJar.getAbsolutePath() + File.pathSeparator + clientJars);
                    args.add("net.kdt.pojavlaunch.runelite.AndroidRuneLite");
                } else {
                    Log.w("JavaGUILauncher", "runelite-android.jar missing, starting RuneLite without Android fixes");
                    args.add(clientJars);
                    args.add("net.runelite.client.RuneLite");
                }
                args.add("--developer-mode");
                String profile = getIntent().getStringExtra(EXTRA_RUNELITE_PROFILE);
                if (profile != null && !profile.isEmpty()) {
                    args.add("--profile");
                    args.add(profile);
                }
                loadingStatus("Loading plugins…", 0.55f);
                try {
                    Tools.syncRuneLiteSideloadedPlugins(this,
                            androidx.preference.PreferenceManager.getDefaultSharedPreferences(this).getBoolean(
                                    net.kdt.pojavlaunch.prefs.LauncherPreferences.PREF_KEY_RUNELITE_SIDELOADED, true));
                } catch (Exception e) {
                    Log.w("JavaGUILauncher", "Unable to sync sideloaded plugins", e);
                }
                File readyFile = new File(getCacheDir(), "runelite-window-ready");
                //noinspection ResultOfMethodCallIgnored
                readyFile.delete();
                args.add(0, "-Drunelite.android.readyFile=" + readyFile.getAbsolutePath());
                loadingStatus("Launching RuneLite…", 0.7f);
                watchRuneLiteReady(readyFile);
                launchJavaRuntime(runtime, null, args);
            } catch (Throwable t) {
                dismissLoading();
                Tools.showError(this, t, true);
            }
        }, "JREMainThread").start();
    }




    private void finalErrorDialog(CharSequence msg) {
        dismissLoading();
        runOnUiThread(()-> new AlertDialog.Builder(this)
                .setTitle(R.string.global_error)
                .setMessage(msg)
                .setPositiveButton(android.R.string.ok, (d,w)->this.finish())
                .setCancelable(false)
                .show());
    }

    @Override
    public void onResume() {
        super.onResume();
        final int uiOptions = mRuneLite
                ? View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                : View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
        final View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(uiOptions);
        if (mRuneLite && android.os.Build.VERSION.SDK_INT >= 30) {
            // Lay out edge-to-edge permanently: when the Jagex login popup / Chrome brought the
            // system bars back, the view shrank and the whole RuneLite screen was re-fitted smaller.
            getWindow().setDecorFitsSystemWindows(false);
            android.view.WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(android.view.WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // Re-enter immersive mode after a popup (Jagex login, Chrome, keyboard) took focus.
        if (hasFocus && mRuneLite) onResume_immersive();
    }

    private void onResume_immersive() {
        final View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) controller.hide(android.view.WindowInsets.Type.systemBars());
        }
    }



    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouch(View v, MotionEvent e) {
        boolean isDown;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: // 0
            case MotionEvent.ACTION_POINTER_DOWN: // 5
                isDown = true;
                break;
            case MotionEvent.ACTION_UP: // 1
            case MotionEvent.ACTION_CANCEL: // 3
            case MotionEvent.ACTION_POINTER_UP: // 6
                isDown = false;
                break;
            default:
                return false;
        }
        
        switch (v.getId()) {
            case R.id.installmod_mouse_pri:
                AWTInputBridge.sendMousePress(AWTInputEvent.BUTTON1_DOWN_MASK, isDown);
                break;
                
            case R.id.installmod_mouse_sec:
                AWTInputBridge.sendMousePress(AWTInputEvent.BUTTON3_DOWN_MASK, isDown);
                break;
        }
        if(isDown) switch(v.getId()) {
            case R.id.installmod_window_moveup:
                AWTInputBridge.nativeMoveWindow(0, -10);
                break;
            case R.id.installmod_window_movedown:
                AWTInputBridge.nativeMoveWindow(0, 10);
                break;
            case R.id.installmod_window_moveleft:
                AWTInputBridge.nativeMoveWindow(-10, 0);
                break;
            case R.id.installmod_window_moveright:
                AWTInputBridge.nativeMoveWindow(10, 0);
                break;
        }
        return true;
    }

    private boolean isHardwareMouse(MotionEvent event) {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE) || event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE)
                || event.isFromSource(InputDevice.SOURCE_TOUCHPAD)) return true;
        for (int i = 0; i < event.getPointerCount(); i++) {
            if (event.getToolType(i) == MotionEvent.TOOL_TYPE_MOUSE) return true;
        }
        return false;
    }

    private float mScrollRemainder;
    private int mLastMouseX = Integer.MIN_VALUE, mLastMouseY = Integer.MIN_VALUE;

    private void sendHardwareMouseEvent(MotionEvent event) {
        // Map through the texture view's own coordinate space. getRawX() minus
        // getLocationOnScreen() is off by the display-cutout / window inset on some devices.
        float[] local = {event.getRawX(), event.getRawY()};
        mTextureView.getLocationOnScreen(mMouseViewLocation);
        if (event.getSource() != 0 && mTextureView.isAttachedToWindow()) {
            View decor = getWindow().getDecorView();
            int[] decorLocation = new int[2];
            decor.getLocationOnScreen(decorLocation);
            // event.getX/Y are relative to the window that dispatched it (the decor view).
            float dx = decorLocation[0] - mMouseViewLocation[0], dy = decorLocation[1] - mMouseViewLocation[1];
            local[0] = event.getX() + dx;
            local[1] = event.getY() + dy;
            // Android batches mouse motion per frame: replay the batched samples too, so a
            // middle-button camera drag moves in small even steps instead of one jump per frame.
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_HOVER_MOVE) {
                for (int h = 0; h < event.getHistorySize(); h++) {
                    sendLocalMousePosition(event.getHistoricalX(h) + dx, event.getHistoricalY(h) + dy);
                }
            }
        }
        sendLocalMousePosition(local[0], local[1]);

        if (event.getActionMasked() == MotionEvent.ACTION_SCROLL) {
            mScrollRemainder += event.getAxisValue(MotionEvent.AXIS_VSCROLL);
            int notches = (int) mScrollRemainder;
            mScrollRemainder -= notches;
            AWTInputBridge.sendScroll(notches);
            return;
        }

        int buttonState = event.getButtonState();
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_BUTTON_PRESS) buttonState |= event.getActionButton();
        else if (action == MotionEvent.ACTION_BUTTON_RELEASE) buttonState &= ~event.getActionButton();
        else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) buttonState = 0;
        else if (action == MotionEvent.ACTION_DOWN && buttonState == 0) buttonState = MotionEvent.BUTTON_PRIMARY;
        // Stylus/touchpad buttons map onto the mouse ones.
        if ((buttonState & MotionEvent.BUTTON_STYLUS_PRIMARY) != 0) buttonState |= MotionEvent.BUTTON_SECONDARY;
        int changedButtons = mMouseButtonState ^ buttonState;
        if ((changedButtons & MotionEvent.BUTTON_PRIMARY) != 0) {
            AWTInputBridge.sendMousePress(AWTInputEvent.BUTTON1_DOWN_MASK,
                    (buttonState & MotionEvent.BUTTON_PRIMARY) != 0);
        }
        if ((changedButtons & MotionEvent.BUTTON_SECONDARY) != 0) {
            AWTInputBridge.sendMousePress(AWTInputEvent.BUTTON3_DOWN_MASK,
                    (buttonState & MotionEvent.BUTTON_SECONDARY) != 0);
        }
        if ((changedButtons & MotionEvent.BUTTON_TERTIARY) != 0) {
            AWTInputBridge.sendMousePress(AWTInputEvent.BUTTON2_DOWN_MASK,
                    (buttonState & MotionEvent.BUTTON_TERTIARY) != 0);
        }
        mMouseButtonState = buttonState;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (mRuneLite && isHardwareMouse(event)) {
            sendHardwareMouseEvent(event);
            return true;
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (mRuneLite && isHardwareMouse(event)) {
            sendHardwareMouseEvent(event);
            return true;
        }
        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (!mRuneLite) return super.dispatchKeyEvent(event);

        int keyCode = event.getKeyCode();
        if (event.getDevice() != null
                && ((event.getSource() & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE)
                && keyCode == KeyEvent.KEYCODE_BACK) {
            // Fallback right click synthesized by Android for an unconsumed secondary button: the
            // button itself is already handled from the MotionEvent button state, so just eat it
            // (it must not reach the activity as BACK).
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event);

        int awtKeyCode = androidToAwtKeyCode(keyCode);
        if (awtKeyCode == 0 || (event.getFlags() & KeyEvent.FLAG_SOFT_KEYBOARD) != 0) {
            return super.dispatchKeyEvent(event);
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN || event.getAction() == KeyEvent.ACTION_UP) {
            char keyChar = event.getAction() == KeyEvent.ACTION_DOWN
                    ? (char) event.getUnicodeChar(event.getMetaState()) : 0;
            AWTInputBridge.sendKey(keyChar, awtKeyCode, event.getAction() == KeyEvent.ACTION_DOWN ? 1 : 0);
            return true;
        }
        return true;
    }

    private static int androidToAwtKeyCode(int keyCode) {
        if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) {
            return AWTInputEvent.VK_A + keyCode - KeyEvent.KEYCODE_A;
        }
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            return AWTInputEvent.VK_0 + keyCode - KeyEvent.KEYCODE_0;
        }
        if (keyCode >= KeyEvent.KEYCODE_F1 && keyCode <= KeyEvent.KEYCODE_F12) {
            return AWTInputEvent.VK_F1 + keyCode - KeyEvent.KEYCODE_F1;
        }
        if (keyCode >= KeyEvent.KEYCODE_NUMPAD_0 && keyCode <= KeyEvent.KEYCODE_NUMPAD_9) {
            return AWTInputEvent.VK_NUMPAD0 + keyCode - KeyEvent.KEYCODE_NUMPAD_0;
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER: return AWTInputEvent.VK_ENTER;
            case KeyEvent.KEYCODE_DEL: return AWTInputEvent.VK_BACK_SPACE;
            case KeyEvent.KEYCODE_FORWARD_DEL: return AWTInputEvent.VK_DELETE;
            case KeyEvent.KEYCODE_TAB: return AWTInputEvent.VK_TAB;
            case KeyEvent.KEYCODE_SPACE: return AWTInputEvent.VK_SPACE;
            case KeyEvent.KEYCODE_ESCAPE: return AWTInputEvent.VK_ESCAPE;
            case KeyEvent.KEYCODE_SHIFT_LEFT:
            case KeyEvent.KEYCODE_SHIFT_RIGHT: return AWTInputEvent.VK_SHIFT;
            case KeyEvent.KEYCODE_CTRL_LEFT:
            case KeyEvent.KEYCODE_CTRL_RIGHT: return AWTInputEvent.VK_CONTROL;
            case KeyEvent.KEYCODE_ALT_LEFT:
            case KeyEvent.KEYCODE_ALT_RIGHT: return AWTInputEvent.VK_ALT;
            case KeyEvent.KEYCODE_CAPS_LOCK: return AWTInputEvent.VK_CAPS_LOCK;
            case KeyEvent.KEYCODE_NUM_LOCK: return AWTInputEvent.VK_NUM_LOCK;
            case KeyEvent.KEYCODE_SCROLL_LOCK: return AWTInputEvent.VK_SCROLL_LOCK;
            case KeyEvent.KEYCODE_DPAD_LEFT: return AWTInputEvent.VK_LEFT;
            case KeyEvent.KEYCODE_DPAD_UP: return AWTInputEvent.VK_UP;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return AWTInputEvent.VK_RIGHT;
            case KeyEvent.KEYCODE_DPAD_DOWN: return AWTInputEvent.VK_DOWN;
            case KeyEvent.KEYCODE_PAGE_UP: return AWTInputEvent.VK_PAGE_UP;
            case KeyEvent.KEYCODE_PAGE_DOWN: return AWTInputEvent.VK_PAGE_DOWN;
            case KeyEvent.KEYCODE_MOVE_HOME: return AWTInputEvent.VK_HOME;
            case KeyEvent.KEYCODE_MOVE_END: return AWTInputEvent.VK_END;
            case KeyEvent.KEYCODE_INSERT: return AWTInputEvent.VK_INSERT;
            case KeyEvent.KEYCODE_SYSRQ: return AWTInputEvent.VK_PRINTSCREEN;
            case KeyEvent.KEYCODE_BREAK: return AWTInputEvent.VK_PAUSE;
            case KeyEvent.KEYCODE_GRAVE: return AWTInputEvent.VK_BACK_QUOTE;
            case KeyEvent.KEYCODE_MINUS: return AWTInputEvent.VK_MINUS;
            case KeyEvent.KEYCODE_NUMPAD_SUBTRACT: return AWTInputEvent.VK_SUBTRACT;
            case KeyEvent.KEYCODE_EQUALS: return AWTInputEvent.VK_EQUALS;
            case KeyEvent.KEYCODE_LEFT_BRACKET: return AWTInputEvent.VK_OPEN_BRACKET;
            case KeyEvent.KEYCODE_RIGHT_BRACKET: return AWTInputEvent.VK_CLOSE_BRACKET;
            case KeyEvent.KEYCODE_BACKSLASH: return AWTInputEvent.VK_BACK_SLASH;
            case KeyEvent.KEYCODE_SEMICOLON: return AWTInputEvent.VK_SEMICOLON;
            case KeyEvent.KEYCODE_APOSTROPHE: return AWTInputEvent.VK_QUOTE;
            case KeyEvent.KEYCODE_SLASH: return AWTInputEvent.VK_SLASH;
            case KeyEvent.KEYCODE_NUMPAD_DIVIDE: return AWTInputEvent.VK_DIVIDE;
            case KeyEvent.KEYCODE_PERIOD: return AWTInputEvent.VK_PERIOD;
            case KeyEvent.KEYCODE_NUMPAD_DOT: return AWTInputEvent.VK_DECIMAL;
            case KeyEvent.KEYCODE_COMMA: return AWTInputEvent.VK_COMMA;
            case KeyEvent.KEYCODE_NUMPAD_MULTIPLY: return AWTInputEvent.VK_MULTIPLY;
            case KeyEvent.KEYCODE_NUMPAD_ADD: return AWTInputEvent.VK_ADD;
            default: return 0;
        }
    }

    @Override
    protected void onDestroy() {
        mMouseButtonState = 0;
        super.onDestroy();
    }

    public void placeMouseAt(float x, float y) {
        mMousePointerImageView.setX(x);
        mMousePointerImageView.setY(y);
    }

    @SuppressWarnings("SuspiciousNameCombination")
    void sendScaledMousePosition(float x, float y){
        sendLocalMousePosition(x - mTextureView.getX(), y - mTextureView.getY());
    }

    /** @param x,y position in the texture view's local coordinates */
    private void sendLocalMousePosition(float x, float y) {
        // Use the exact inverse of the transform used to display the AWT screen.
        mTextureView.viewToCanvas(x, y, mCanvasPoint);
        int cx = Math.round(mCanvasPoint.x), cy = Math.round(mCanvasPoint.y);
        if (cx == mLastMouseX && cy == mLastMouseY) return; // no duplicate MOUSE_MOVED/DRAGGED
        mLastMouseX = cx;
        mLastMouseY = cy;
        AWTInputBridge.sendMousePos(cx, cy);
    }

    /**
     * Direct touch input for RuneLite:
     * <ul>
     *   <li>tap: left click at the finger;</li>
     *   <li>hold still: right click (opens the OSRS/RuneLite context menu);</li>
     *   <li>drag: left button held while moving (scrollbars, dragging items, sliders).</li>
     * </ul>
     * Previously a click was only sent from GestureDetector#onSingleTapUp, which is skipped as
     * soon as the finger moves past the touch slop or is held longer than the tap timeout, so
     * most taps on a phone only moved the cursor and never clicked.
     */
    private class RuneLiteTouchHandler {
        private final Handler mHandler = new Handler(Looper.getMainLooper());
        private final int mTouchSlop = ViewConfiguration.get(JavaGUILauncherActivity.this).getScaledTouchSlop();
        private float mDownX, mDownY;
        private boolean mDragging, mLongPressed, mTracking;

        private final Runnable mLongPress = () -> {
            if (!mTracking || mDragging) return;
            mLongPressed = true;
            mTextureView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            sendLocalMousePosition(mDownX, mDownY);
            AWTInputBridge.sendMousePress(AWTInputEvent.BUTTON3_DOWN_MASK);
        };

        private boolean mMiddleDragging;
        private boolean mMultiTouch;

        private float centroidX(MotionEvent e) {
            float sum = 0;
            for (int i = 0; i < e.getPointerCount(); i++) sum += e.getX(i);
            return sum / e.getPointerCount();
        }

        private float centroidY(MotionEvent e) {
            float sum = 0;
            for (int i = 0; i < e.getPointerCount(); i++) sum += e.getY(i);
            return sum / e.getPointerCount();
        }

        /**
         * Two fingers: middle-button drag at the fingers' midpoint (OSRS camera rotation).
         * Three fingers: toggle the soft keyboard.
         */
        private boolean onMultiTouch(MotionEvent event) {
            int action = event.getActionMasked();
            int pointers = event.getPointerCount();
            if (action == MotionEvent.ACTION_POINTER_DOWN) {
                mMultiTouch = true;
                mHandler.removeCallbacks(mLongPress);
                if (mDragging) {
                    AWTInputBridge.sendMousePress(AWTInputEvent.BUTTON1_DOWN_MASK, false);
                    mDragging = false;
                }
                if (pointers == 3) {
                    if (mMiddleDragging) {
                        AWTInputBridge.sendMousePress(AWTInputEvent.BUTTON2_DOWN_MASK, false);
                        mMiddleDragging = false;
                    }
                    toggleKeyboard(null);
                } else if (pointers == 2 && !mMiddleDragging) {
                    sendLocalMousePosition(centroidX(event), centroidY(event));
                    AWTInputBridge.sendMousePress(AWTInputEvent.BUTTON2_DOWN_MASK, true);
                    mMiddleDragging = true;
                }
                return true;
            }
            if (action == MotionEvent.ACTION_MOVE && mMiddleDragging) {
                sendLocalMousePosition(centroidX(event), centroidY(event));
                return true;
            }
            if (action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_UP
                    || action == MotionEvent.ACTION_CANCEL) {
                if (mMiddleDragging) {
                    AWTInputBridge.sendMousePress(AWTInputEvent.BUTTON2_DOWN_MASK, false);
                    mMiddleDragging = false;
                }
                if (action != MotionEvent.ACTION_POINTER_UP) {
                    mMultiTouch = false;
                    mTracking = false;
                }
                return true;
            }
            return true;
        }

        boolean onTouch(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN || mMultiTouch) {
                return onMultiTouch(event);
            }
            float x = event.getX();
            float y = event.getY();
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mDownX = x;
                    mDownY = y;
                    mDragging = false;
                    mLongPressed = false;
                    mTracking = true;
                    sendLocalMousePosition(x, y);
                    mHandler.postDelayed(mLongPress, ViewConfiguration.getLongPressTimeout());
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (!mTracking) break;
                    if (!mDragging && !mLongPressed
                            && Math.hypot(x - mDownX, y - mDownY) > mTouchSlop) {
                        mHandler.removeCallbacks(mLongPress);
                        mDragging = true;
                        sendLocalMousePosition(mDownX, mDownY);
                        AWTInputBridge.sendMousePress(AWTInputEvent.BUTTON1_DOWN_MASK, true);
                    }
                    sendLocalMousePosition(x, y);
                    break;
                case MotionEvent.ACTION_UP:
                    if (!mTracking) break;
                    mHandler.removeCallbacks(mLongPress);
                    if (mDragging) {
                        sendLocalMousePosition(x, y);
                        AWTInputBridge.sendMousePress(AWTInputEvent.BUTTON1_DOWN_MASK, false);
                    } else if (!mLongPressed) {
                        // Click where the finger went down; small jitter must not move it.
                        sendLocalMousePosition(mDownX, mDownY);
                        AWTInputBridge.sendMousePress(AWTInputEvent.BUTTON1_DOWN_MASK);
                    }
                    mTracking = false;
                    break;
                case MotionEvent.ACTION_CANCEL:
                    mHandler.removeCallbacks(mLongPress);
                    if (mDragging) AWTInputBridge.sendMousePress(AWTInputEvent.BUTTON1_DOWN_MASK, false);
                    mTracking = false;
                    break;
                default:
                    break;
            }
            return true;
        }
    }

    public void forceClose(View v) {
        Tools.dialogForceClose(this);
    }

    public void openLogOutput(View v) {
        mLoggerView.setVisibility(View.VISIBLE);
    }

    public void toggleVirtualMouse(View v) {
        mIsVirtualMouseEnabled = !mIsVirtualMouseEnabled;
        mTouchPad.setVisibility(mIsVirtualMouseEnabled ? View.VISIBLE : View.GONE);
        Toast.makeText(this,
                mIsVirtualMouseEnabled ? R.string.control_mouseon : R.string.control_mouseoff,
                Toast.LENGTH_SHORT).show();
    }

    /**
     * RuneLite has no Minecraft-style control layout, so add a small keypad with the keys that
     * are needed to play: Escape (menus), the arrow keys (camera), Enter, Space and Shift.
     */
    private void setupRuneLiteKeyBar() {
        final int[] keyCodes = {
                AWTInputEvent.VK_ESCAPE, AWTInputEvent.VK_LEFT, AWTInputEvent.VK_UP,
                AWTInputEvent.VK_DOWN, AWTInputEvent.VK_RIGHT, AWTInputEvent.VK_ENTER,
                AWTInputEvent.VK_SPACE
        };
        final String[] keyLabels = {"Esc", "◀", "▲", "▼", "▶", "Enter", "Space"};

        float density = getResources().getDisplayMetrics().density;
        int horizontalPadding = (int) (8 * density);
        int verticalPadding = (int) (2 * density);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(0x66000000);

        for (int i = 0; i < keyCodes.length; i++) {
            final int keyCode = keyCodes[i];
            Button button = createKeyButton(keyLabels[i], horizontalPadding, verticalPadding);
            button.setOnClickListener(v -> AWTInputBridge.sendKey('\0', keyCode));
            bar.addView(button);
        }

        Button shiftButton = createKeyButton("Shift", horizontalPadding, verticalPadding);
        shiftButton.setOnClickListener(v -> {
            mRuneLiteShiftDown = !mRuneLiteShiftDown;
            AWTInputBridge.sendKey('\0', AWTInputEvent.VK_SHIFT, mRuneLiteShiftDown ? 1 : 0);
            shiftButton.setSelected(mRuneLiteShiftDown);
            shiftButton.setTextColor(mRuneLiteShiftDown ? 0xFFE0A030 : 0xFFFFFFFF);
        });
        bar.addView(shiftButton);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        params.bottomMargin = (int) (6 * density);
        ((ViewGroup) findViewById(android.R.id.content)).addView(bar, params);
    }

    private Button createKeyButton(String label, int horizontalPadding, int verticalPadding) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(11);
        button.setTextColor(0xFFFFFFFF);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding);
        button.setBackgroundResource(R.drawable.control_button);
        return button;
    }

    public void launchJavaRuntime(Runtime runtime, File modFile, List<String> javaArgs) {
        JREUtils.redirectAndPrintJRELog();
        try {
            List<String> javaArgList = new ArrayList<>();
            // Enable Caciocavallo
            Tools.getCacioJavaArgs(javaArgList,runtime.javaVersion == 8, this);
            if(javaArgs != null) {
                javaArgList.addAll(javaArgs);
            }
            if(modFile != null) {
                javaArgList.add("-jar");
                javaArgList.add(modFile.getAbsolutePath());
            }
            
            Logger.appendToLog("Info: Java arguments: " + Arrays.toString(javaArgList.toArray(new String[0])));

            File gameDirectory = mRuneLite ? Tools.getRuneLiteDir() : null;
            JREUtils.launchJavaVM(this, runtime, gameDirectory, javaArgList, LauncherPreferences.PREF_CUSTOM_JAVA_ARGS);
        } catch (Throwable th) {
            Tools.showError(this, th, true);
        }
    }

    public void toggleKeyboard(View view) {
        mTouchCharInput.switchKeyboardState();
    }
    public void performCopy(View view) {
        AWTInputBridge.sendKey(' ', AWTInputEvent.VK_CONTROL, 1);
        AWTInputBridge.sendKey(' ', AWTInputEvent.VK_C);
        AWTInputBridge.sendKey(' ', AWTInputEvent.VK_CONTROL, 0);
    }

    public void performPaste(View view) {
        AWTInputBridge.sendKey(' ', AWTInputEvent.VK_CONTROL, 1);
        AWTInputBridge.sendKey(' ', AWTInputEvent.VK_V);
        AWTInputBridge.sendKey(' ', AWTInputEvent.VK_CONTROL, 0);
    }

}
