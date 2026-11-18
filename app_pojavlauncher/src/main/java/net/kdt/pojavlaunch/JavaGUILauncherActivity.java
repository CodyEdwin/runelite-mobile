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
    /** Name of the Java runtime that should be used (optional, avoids re-picking it). */
    public static final String EXTRA_RUNTIME = "runtimeName";

    private static WeakReference<JavaGUILauncherActivity> sRunningInstance = new WeakReference<>(null);

    private boolean mRuneLite;
    private String mRequestedRuntime;
    private AWTCanvasView mTextureView;
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

    /** Activity hosting the AWT surface, used by MainActivity#openLink for Desktop#browse. */
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
        mRuneLite = getIntent().getBooleanExtra(EXTRA_RUNELITE, false);
        mRequestedRuntime = getIntent().getStringExtra(EXTRA_RUNTIME);
        AWTCanvasView.setFpsOverlayEnabled(!mRuneLite);
        if (mRuneLite) {
            DisplayMetrics metrics = new DisplayMetrics();
            getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
            Point size = AWTCanvasView.getRuneLiteCanvasSize(metrics.widthPixels, metrics.heightPixels);
            AWTCanvasView.setCanvasSize(size.x, size.y);
        } else {
            AWTCanvasView.resetCanvasSize();
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
        MainActivity.GLOBAL_CLIPBOARD = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        mTouchCharInput = findViewById(R.id.awt_touch_char);
        mTouchCharInput.setCharacterSender(new AwtCharSender());

        mTouchPad = findViewById(R.id.main_touchpad);
        mLoggerView = findViewById(R.id.launcherLoggerView);
        mMousePointerImageView = findViewById(R.id.main_mouse_pointer);
        mTextureView = findViewById(R.id.installmod_surfaceview);
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
            final String javaArgs = extras.getString("javaArgs");
            final Uri resourceUri = (Uri) extras.getParcelable("modUri");
            if(extras.getBoolean("openLogOutput", false)) openLogOutput(null);
            if (mRuneLite) {
                startRuneLite();
            } else if (javaArgs != null) {
                startModInstaller(null, javaArgs);
            }else if(resourceUri != null) {
                ProgressDialog barrierDialog = Tools.getWaitingDialog(this, R.string.multirt_progress_caching);
                PojavApplication.sExecutorService.execute(()->{
                    startModInstallerWithUri(resourceUri);
                    runOnUiThread(barrierDialog::dismiss);
                });
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

    private void startModInstallerWithUri(Uri uri) {
        try {
            File cacheFile = new File(getCacheDir(), "mod-installer-temp");
            InputStream contentStream = getContentResolver().openInputStream(uri);
            if(contentStream == null) throw new IOException("Failed to open content stream");
            try (FileOutputStream fileOutputStream = new FileOutputStream(cacheFile)) {
                IOUtils.copy(contentStream, fileOutputStream);
            }
            contentStream.close();
            startModInstaller(cacheFile, null);
        }catch (IOException e) {
            Tools.showError(this, e, true);
        }
    }

    private void startRuneLite() {
        new Thread(() -> {
            try {
                File jar = Tools.getRuneLiteJar();
                if(!jar.isFile()) {
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

                List<String> args = new ArrayList<>();
                // RuneLite only enables developer mode for development runs with assertions.
                args.add("-ea");
                args.add("-Djava.awt.headless=false");
                // Keep RuneLite's ~/.runelite inside its own instance directory
                args.add("-Duser.home=" + Tools.getRuneLiteDir().getAbsolutePath());
                args.add("-cp");
                // runelite-android.jar must come first: it overrides JagexAuthService and its
                // entry point makes the client frame fill the caciocavallo virtual screen.
                File androidJar = Tools.getRuneLiteAndroidJar();
                if (androidJar.isFile()) {
                    args.add(androidJar.getAbsolutePath() + File.pathSeparator + jar.getAbsolutePath());
                    args.add("net.kdt.pojavlaunch.runelite.AndroidRuneLite");
                } else {
                    Log.w("JavaGUILauncher", "runelite-android.jar missing, starting RuneLite without Android fixes");
                    args.add(jar.getAbsolutePath());
                    args.add("net.runelite.client.RuneLite");
                }
                args.add("--developer-mode");
                launchJavaRuntime(runtime, null, args);
            } catch (Throwable t) {
                Tools.showError(this, t, true);
            }
        }, "JREMainThread").start();
    }

    public Runtime selectRuntime(File modFile) {
        int javaVersion = getJavaVersion(modFile);
        if(javaVersion == -1) {
            finalErrorDialog(getString(R.string.execute_jar_failed_to_read_file));
            return null;
        }

        // Find the nearest installed JRE that is version 17 or below (AWT GUI only supports <= 17)
        List<Runtime> runtimes = MultiRTUtils.getInstalledRuntimes();
        List<Runtime> compatibleRuntimes = new ArrayList<>();
        for (Runtime r : runtimes) {
            if (r.javaVersion <= 17) {
                compatibleRuntimes.add(r);
            }
        }

        MathUtils.RankedValue<Runtime> nearestRankedRuntime = MathUtils.findNearestPositive(javaVersion, compatibleRuntimes, (runtime)->runtime.javaVersion);
        Runtime selectedRuntime = nearestRankedRuntime != null ? nearestRankedRuntime.value : null;

        if (selectedRuntime == null) {
            finalErrorDialog(getString(R.string.multirt_nocompatiblert, javaVersion) 
                + "\n\n(Пожалуйста, установите Java 17 или Java 8 в настройках лаунчера для запуска установщиков)");
            return null;
        }

        int selectedJavaVersion = Math.max(javaVersion, selectedRuntime.javaVersion);
        // Don't allow versions higher than Java 17 because our caciocavallo implementation does not allow for it
        if(selectedJavaVersion > 17) {
            finalErrorDialog(getString(R.string.execute_jar_incompatible_runtime, selectedJavaVersion)
                + "\n\n(Установщик требует Java " + selectedJavaVersion + ", но запуск .jar поддерживает только Java 17 и ниже)");
            return null;
        }
        return selectedRuntime;
    }

    private File findModPath(List<String> argList) {
        int argsSize = argList.size();
        for(int i = 0; i < argsSize; i++) {
            // Look for the -jar argument
            if(!argList.get(i).equals("-jar")) continue;
            int pathIndex = i+1;
            // Check if the supposed path is out of the argument bounds
            if(pathIndex >= argsSize) return null;
            // Use the path as a file
            return new File(argList.get(pathIndex));
        }
        return null;
    }

    private void startModInstaller(File modFile, String javaArgs) {
        new Thread(() -> {
            // Maybe replace with more advanced arg parsing logic later
            List<String> argList = javaArgs != null ? Arrays.asList(javaArgs.split(" ")) : null;
            File selectedMod = modFile;
            if(selectedMod == null && argList != null) {
                // If modFile is not specified directly, try to extract the -jar argument from the javaArgs
                selectedMod = findModPath(argList);
            }
            Runtime selectedRuntime;
            if(selectedMod == null || DEFAULT_PREF.getBoolean("disable_autojre_select", false)) {
                // If we are unable to find out the path to the mod or the user explicitly desires so, we use the default runtime
                selectedRuntime = MultiRTUtils.forceReread(LauncherPreferences.PREF_DEFAULT_RUNTIME);
            }else {
                // Autoselect it properly in the other case.
                selectedRuntime = selectRuntime(selectedMod);
                // If the selection failed, just return. The autoselect function has already shown the dialog.
                if(selectedRuntime == null) return;
            }
            launchJavaRuntime(selectedRuntime, modFile, argList);
        }, "JREMainThread").start();
    }

    private void finalErrorDialog(CharSequence msg) {
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
        final int uiOptions = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
        final View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(uiOptions);
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
        for (int i = 0; i < event.getPointerCount(); i++) {
            if (event.getToolType(i) == MotionEvent.TOOL_TYPE_MOUSE) return true;
        }
        return false;
    }

    private void sendHardwareMouseEvent(MotionEvent event) {
        mTextureView.getLocationOnScreen(mMouseViewLocation);
        sendLocalMousePosition(
                event.getRawX() - mMouseViewLocation[0],
                event.getRawY() - mMouseViewLocation[1]);

        int buttonState = event.getButtonState();
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
            AWTInputBridge.sendMousePress(AWTInputEvent.BUTTON3_DOWN_MASK,
                    event.getAction() == KeyEvent.ACTION_DOWN);
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
        AWTInputBridge.sendMousePos((int) mCanvasPoint.x, (int) mCanvasPoint.y);
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

        boolean onTouch(MotionEvent event) {
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
            
            if (LauncherPreferences.PREF_JAVA_SANDBOX && !mRuneLite) {
                Collections.reverse(javaArgList);
                javaArgList.add("-Xbootclasspath/a:" + Tools.DIR_DATA + "/security/pro-grade.jar");
                javaArgList.add("-Djava.security.manager=net.sourceforge.prograde.sm.ProGradeJSM");
                javaArgList.add("-Djava.security.policy=" + Tools.DIR_DATA + "/security/java_sandbox.policy");
                Collections.reverse(javaArgList);
            }

            Logger.appendToLog("Info: Java arguments: " + Arrays.toString(javaArgList.toArray(new String[0])));

            File gameDirectory = mRuneLite ? Tools.getRuneLiteDir() : null;
            JREUtils.launchJavaVM(this, runtime, gameDirectory, javaArgList, LauncherPreferences.PREF_CUSTOM_JAVA_ARGS, !mRuneLite);
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

    public int getJavaVersion(File modFile) {
        try (ZipFile zipFile = new ZipFile(modFile)){
            ZipEntry manifest = zipFile.getEntry("META-INF/MANIFEST.MF");
            if(manifest == null) return -1;

            String manifestString = Tools.read(zipFile.getInputStream(manifest));
            String mainClass = Tools.extractUntilCharacter(manifestString, "Main-Class:", '\n');
            if(mainClass == null) return -1;

            mainClass = mainClass.trim().replace('.', '/') + ".class";
            ZipEntry mainClassFile = zipFile.getEntry(mainClass);
            if(mainClassFile == null) return -1;

            InputStream classStream = zipFile.getInputStream(mainClassFile);
            byte[] bytesWeNeed = new byte[8];
            int readCount = classStream.read(bytesWeNeed);
            classStream.close();
            if(readCount < 8) return -1;

            ByteBuffer byteBuffer = ByteBuffer.wrap(bytesWeNeed);
            if(byteBuffer.getInt() != 0xCAFEBABE) return -1;
            short minorVersion = byteBuffer.getShort();
            short majorVersion = byteBuffer.getShort();
            Log.i("JavaGUILauncher", majorVersion+","+minorVersion);
            return classVersionToJavaVersion(majorVersion);
        }catch (Exception e) {
            Log.e("JavaVersion", "Exception thrown", e);
            return -1;
        }
    }
    public static int classVersionToJavaVersion(int majorVersion) {
        if(majorVersion < 46) return 2; // there isn't even an arm64 port of jre 1.1 (or anything before 1.8 in fact)
        return majorVersion - 44;
    }
}
