package com.codyedwin.runelitemobile;

import static android.os.Build.VERSION.SDK_INT;
import static android.os.Build.VERSION_CODES.P;
import static com.codyedwin.runelitemobile.Architecture.archAsStringAndroid;
import static com.codyedwin.runelitemobile.Architecture.getDeviceArchitecture;
import static com.codyedwin.runelitemobile.RuneLiteApplication.sExecutorService;
import static com.codyedwin.runelitemobile.prefs.LauncherPreferences.PREF_IGNORE_NOTCH;
import static com.codyedwin.runelitemobile.prefs.LauncherPreferences.PREF_NOTCH_SIZE;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.Cursor;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.ArrayMap;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.InputDevice;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationManagerCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import com.codyedwin.runelitemobile.lifecycle.ContextExecutor;
import com.codyedwin.runelitemobile.lifecycle.ContextExecutorTask;
import com.codyedwin.runelitemobile.lifecycle.LifecycleAwareAlertDialog;
import com.codyedwin.runelitemobile.memory.MemoryHoleFinder;
import com.codyedwin.runelitemobile.memory.SelfMapsParser;
import com.codyedwin.runelitemobile.multirt.MultiRTUtils;
import com.codyedwin.runelitemobile.multirt.Runtime;
import com.codyedwin.runelitemobile.prefs.LauncherPreferences;
import com.codyedwin.runelitemobile.tasks.AsyncAssetManager;
import com.codyedwin.runelitemobile.utils.DownloadUtils;
import com.codyedwin.runelitemobile.utils.FileUtils;
import com.codyedwin.runelitemobile.utils.GLInfoUtils;
import com.codyedwin.runelitemobile.utils.JREUtils;
import com.codyedwin.runelitemobile.utils.JSONUtils;

import org.apache.commons.codec.binary.Hex;
import org.apache.commons.io.IOUtils;
import org.lwjgl.glfw.CallbackBridge;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@SuppressWarnings("IOStreamConstructor")
public final class Tools {
    public  static final float BYTE_TO_MB = 1024 * 1024;
    public static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    public static String APP_NAME = "RuneLite Mobile";

    public static final Gson GLOBAL_GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final String URL_HOME = "https://wiki.angelauramc.dev";
    public static String NATIVE_LIB_DIR;
    public static String DIR_DATA; //Initialized later to get context
    public static File DIR_CACHE;
    public static String MULTIRT_HOME;
    public static int DEVICE_ARCHITECTURE;

    // New since 3.3.1
    public static String DIR_ACCOUNT_NEW;
    public static String DIR_GAME_HOME = Environment.getExternalStorageDirectory().getAbsolutePath() + "/games/RuneLiteMobile";
    public static String DIR_GAME_NEW;
    public static String GAME_PROFILES_FILE;

    // New since 3.0.0
    public static String DIRNAME_HOME_JRE = "lib";

    // New since 2.4.2
    public static String DIR_HOME_VERSION;
    public static String DIR_HOME_LIBRARY;

    public static String DIR_HOME_CRASH;

    public static String ASSETS_PATH;
    public static String OBSOLETE_RESOURCES_PATH;
    public static String CTRLMAP_PATH;
    public static String CTRLDEF_FILE;
    private static RenderersList sCompatibleRenderers;
    public static int iLwjglVersion = 0;
    public static String sLwjglVersion = null;
    public static String lwjglNativesDir = null;

    private static File getStorageRoot(Context ctx) {
        if(SDK_INT >= 29) {
            return ctx.getExternalFilesDir(null);
        }else{
            return new File(Environment.getExternalStorageDirectory(),"games/RuneLiteMobile");
        }
    }

    /**
     * Checks if the app storage root is accessible and read-writable
     * @param context context to get the storage root if it's not set yet
     * @return true if storage is fine, false if storage is not accessible
     */
    public static boolean checkStorageRoot(Context context) {
        File externalFilesDir = DIR_GAME_HOME  == null ? Tools.getStorageRoot(context) : new File(DIR_GAME_HOME);
        //externalFilesDir == null when the storage is not mounted if it was obtained with the context call
        return externalFilesDir != null && Environment.getExternalStorageState(externalFilesDir).equals(Environment.MEDIA_MOUNTED);
    }

    /**
     * Checks if the app storage root is accessible and read-writable. If it's not, starts
     * the MissingStorageActivity and finishes the supplied activity.
     * @param context the Activity that checks for storage availability
     * @return whether the storage is available or not.
     */
    public static boolean checkStorageInteractive(Activity context) {
        if(!Tools.checkStorageRoot(context)) {
            context.startActivity(new Intent(context, MissingStorageActivity.class));
            context.finish();
            return false;
        }
        return true;
    }

    /**
     * Initialize context constants most necessary for launcher's early startup phase
     * that are not dependent on user storage.
     * All values that depend on DIR_DATA and are not dependent on DIR_GAME_HOME must
     * be initialized here.
     * @param ctx the context for initialization.
     */
    public static void initEarlyConstants(Context ctx) {
        DIR_CACHE = ctx.getCacheDir();
        DIR_DATA = ctx.getFilesDir().getParent();
        MULTIRT_HOME = DIR_DATA + "/runtimes";
        DIR_ACCOUNT_NEW = DIR_DATA + "/accounts";
        NATIVE_LIB_DIR = ctx.getApplicationInfo().nativeLibraryDir;
    }

    /**
     * Initialize context constants that depend on user storage.
     * Any value (in)directly dependent on DIR_GAME_HOME should be set only here.
     * You ABSOLUTELY MUST check for storage presence using checkStorageRoot() before calling this.
     */
    public static void initStorageConstants(Context ctx){
        initEarlyConstants(ctx);
        DIR_GAME_HOME = getStorageRoot(ctx).getAbsolutePath();
        DIR_GAME_NEW = DIR_GAME_HOME + "/.minecraft";
        DIR_HOME_VERSION = DIR_GAME_NEW + "/versions";
        DIR_HOME_LIBRARY = DIR_GAME_NEW + "/libraries";
        DIR_HOME_CRASH = DIR_GAME_NEW + "/crash-reports";
        ASSETS_PATH = DIR_GAME_NEW + "/assets";
        OBSOLETE_RESOURCES_PATH = DIR_GAME_NEW + "/resources";
        CTRLMAP_PATH = DIR_GAME_HOME + "/controlmap";
        CTRLDEF_FILE = DIR_GAME_HOME + "/controlmap/default.json";
        GAME_PROFILES_FILE = Tools.DIR_GAME_NEW + "/launcher_profiles.json";
    }

    /**
     * Initialize OpenGL and do checks to see if the GPU of the device is affected by the render
     * distance issue.

     * Currently only checks whether the user has an Adreno GPU capable of OpenGL ES 3.

     * This issue is caused by a very severe limit on the amount of GL buffer names that could be allocated
     * by the Adreno properietary GLES driver.

    private static String[] sodiumMods = {"sodium", "embeddium", "rubidium", "xenon"};

    /* ===================== RuneLite ===================== */

    /** Synthetic instance id used for the bundled RuneLite client. */
    public static final String RUNELITE_VERSION_ID = "runelite";

    /** RuneLite needs Java 11+, but the caciocavallo AWT agent is built for Java 17 exactly. */
    public static final int RUNELITE_MIN_JAVA = 17;
    public static final int RUNELITE_MAX_JAVA = 17;

    /** Directory holding the extracted RuneLite jar and its ~/.runelite data. */
    public static File getRuneLiteDir() {
        return new File(DIR_GAME_HOME, "runelite");
    }

    public static File getRuneLiteJar() {
        return new File(getRuneLiteDir(), "runelite.jar");
    }

    /**
     * Folder users drop plugin jars into. RuneLite itself loads them from ~/.runelite/sideloaded-plugins
     * (developer mode, which the Android launcher always enables), but ".runelite" is a hidden folder
     * that file managers / the system file picker don't show, so jars are mirrored from this visible
     * folder on every launch (removing one here removes it from RuneLite too).
     */
    public static File getRuneLiteSideloadDir() {
        return new File(getRuneLiteDir(), "sideloaded-plugins");
    }

    /** Bundled plugin jars (Cody's VPS plugins) inside the APK. */
    public static final String RUNELITE_BUNDLED_PLUGINS_ASSET = "runelite/sideloaded-plugins";
    /** Plugins that cannot work on Android (no OpenGL), never copied into RuneLite's folder. */
    public static boolean isKnownBrokenRuneLitePlugin(String name) {
        return name.toLowerCase(java.util.Locale.ROOT).startsWith("117hd");
    }

    /**
     * Build RuneLite's ~/.runelite/sideloaded-plugins (only read in developer mode) from the plugins
     * bundled in the APK plus the jars the user put in the visible sideloaded-plugins folder.
     * When {@code enabled} is false the folder is emptied so RuneLite loads none of them; the
     * visible folder is never touched.
     */
    /** Pref key: true = this sideloadable jar is switched off (RuneLitePluginsActivity). Default on. */
    public static String runeLitePluginOffKey(String jarName) {
        return "runelitePluginOff:" + jarName;
    }

    /** Bundled plugin jar names in the APK (without known-broken ones). */
    public static java.util.List<String> listBundledRuneLitePlugins(Context context) {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            String[] bundled = context.getAssets().list(RUNELITE_BUNDLED_PLUGINS_ASSET);
            if (bundled != null) for (String n : bundled) if (n.endsWith(".jar") && !isKnownBrokenRuneLitePlugin(n)) out.add(n);
        } catch (IOException ignored) {
        }
        return out;
    }

    public static void syncRuneLiteSideloadedPlugins(Context context, boolean enabled) throws IOException {
        File visible = getRuneLiteSideloadDir();
        File target = new File(getRuneLiteDir(), ".runelite/sideloaded-plugins");
        FileUtils.ensureDirectory(visible);
        FileUtils.ensureDirectory(target);
        File manifest = new File(target, ".mirrored");
        File bundledMarker = new File(target, ".bundled-version");
        if (!enabled) {
            File[] old = target.listFiles((dir, name) -> name.endsWith(".jar"));
            if (old != null) for (File f : old) f.delete();
            manifest.delete();
            bundledMarker.delete();
            Log.i("RuneLitePlugins", "Sideloaded plugins disabled: cleared " + target);
            return;
        }
        android.content.SharedPreferences off = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context);
        java.util.Set<String> previous = new java.util.HashSet<>();
        if (manifest.isFile()) {
            try (InputStream input = new FileInputStream(manifest)) {
                for (String line : read(input).split("\n")) if (!line.trim().isEmpty()) previous.add(line.trim());
            }
        }
        java.util.Set<String> present = new java.util.LinkedHashSet<>();

        // 1. Bundled jars: re-extract only when the APK changed (or a jar is missing).
        String[] bundled = context.getAssets().list(RUNELITE_BUNDLED_PLUGINS_ASSET);
        String apkVersion;
        try {
            android.content.pm.PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            apkVersion = info.lastUpdateTime + ":" + info.versionCode;
        } catch (Exception e) {
            apkVersion = "unknown";
        }
        String installedVersion = null;
        if (bundledMarker.isFile()) {
            try (InputStream input = new FileInputStream(bundledMarker)) {
                installedVersion = read(input).trim();
            }
        }
        boolean refreshBundled = !apkVersion.equals(installedVersion);
        if (bundled != null) {
            for (String name : bundled) {
                if (!name.endsWith(".jar") || isKnownBrokenRuneLitePlugin(name)) continue;
                if (off.getBoolean(runeLitePluginOffKey(name), false)) continue; // switched off by the user
                present.add(name);
                File copy = new File(target, name);
                if (refreshBundled || !copy.isFile()) {
                    try (InputStream in = context.getAssets().open(RUNELITE_BUNDLED_PLUGINS_ASSET + "/" + name);
                         FileOutputStream out = new FileOutputStream(copy)) {
                        IOUtils.copy(in, out);
                    }
                }
            }
        }
        try (FileOutputStream output = new FileOutputStream(bundledMarker)) {
            output.write(apkVersion.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        // 2. User jars from the visible folder (a same-named user jar wins over the bundled one).
        File[] jars = visible.listFiles((dir, name) -> name.endsWith(".jar"));
        if (jars != null) {
            for (File jar : jars) {
                if (isKnownBrokenRuneLitePlugin(jar.getName())) {
                    Log.i("RuneLitePlugins", "Skipping " + jar.getName() + " (needs OpenGL, not available on Android)");
                    continue;
                }
                if (off.getBoolean(runeLitePluginOffKey(jar.getName()), false)) {
                    Log.i("RuneLitePlugins", "Skipping " + jar.getName() + " (switched off)");
                    continue;
                }
                present.add(jar.getName());
                File copy = new File(target, jar.getName());
                if (!copy.isFile() || copy.length() != jar.length() || copy.lastModified() < jar.lastModified()) {
                    org.apache.commons.io.FileUtils.copyFile(jar, copy);
                }
            }
        }
        // 3. Drop jars we put there earlier that are gone now (and anything known broken).
        for (String name : previous) {
            if (!present.contains(name)) new File(target, name).delete();
        }
        File[] existing = target.listFiles((dir, name) -> name.endsWith(".jar"));
        if (existing != null) for (File f : existing) {
            // known broken, or switched off by the user (only RuneLite's mirror copy; the APK and the
            // visible folder are untouched, so switching it back on restores it)
            if (isKnownBrokenRuneLitePlugin(f.getName()) || off.getBoolean(runeLitePluginOffKey(f.getName()), false)) f.delete();
        }
        StringBuilder mirrored = new StringBuilder();
        for (String name : present) mirrored.append(name).append('\n');
        try (FileOutputStream output = new FileOutputStream(manifest)) {
            output.write(mirrored.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        Log.i("RuneLitePlugins", "Sideloaded plugins: " + present.size() + " jars in " + target);
    }

    /** Android entry point + overrides, see runelite_android_shim/ in the repository root. */
    public static File getRuneLiteAndroidJar() {
        return new File(getRuneLiteDir(), "runelite-android.jar");
    }

    /**
     * Make sure the bundled RuneLite jar is present. Components are normally unpacked in the
     * background at startup; this is a safety net for the first tap on "Play".
     */
    private static final String RUNELITE_JAR_REVISION = "xdarl-android-shim-1";
    private static final String RUNELITE_JAR_REVISION_FILE = ".jar-revision";

    public static void ensureRuneLiteInstalled(Context context) throws IOException {
        File runeLiteDir = getRuneLiteDir();
        File jar = getRuneLiteJar();
        File revisionFile = new File(runeLiteDir, RUNELITE_JAR_REVISION_FILE);

        boolean jarMissing = !jar.isFile();
        String installedRevision = null;
        if (revisionFile.isFile()) {
            try (InputStream input = new FileInputStream(revisionFile)) {
                installedRevision = read(input).trim();
            }
        }

        if (!jarMissing && RUNELITE_JAR_REVISION.equals(installedRevision)) {
            // The Android shim is tiny and changes more often than the client: always refresh it.
            copyAssetFile(context, "components/runelite/runelite-android.jar",
                    runeLiteDir.getAbsolutePath(), true);
            return;
        }

        FileUtils.ensureDirectory(runeLiteDir);
        if (jarMissing) {
            String[] assetList = context.getAssets().list("components/runelite");
            if (assetList == null || assetList.length == 0)
                throw new IOException("The launcher APK does not contain the RuneLite client");
            for (String assetName : assetList) {
                copyAssetFile(context, "components/runelite/" + assetName,
                        runeLiteDir.getAbsolutePath(), true);
            }
        } else {
            // Replace only the client jars so existing RuneLite profiles and settings survive.
            copyAssetFile(context, "components/runelite/runelite.jar",
                    runeLiteDir.getAbsolutePath(), true);
            copyAssetFile(context, "components/runelite/runelite-android.jar",
                    runeLiteDir.getAbsolutePath(), true);
        }

        if (!jar.isFile() || !getRuneLiteAndroidJar().isFile())
            throw new IOException("Failed to unpack the RuneLite client");
        try (FileOutputStream output = new FileOutputStream(revisionFile)) {
            output.write(RUNELITE_JAR_REVISION.getBytes("UTF-8"));
        }
    }

    /**
     * Find an installed runtime usable by both RuneLite and the AWT bridge, downloading Java 17
     * when none is present.
     * @return the runtime name, to be passed to {@link JavaGUILauncherActivity}.
     */
    public static String getRuneLiteRuntimeName(Context context) throws IOException {
        Runtime bestRuntime = null;
        for (Runtime candidate : MultiRTUtils.getInstalledRuntimes()) {
            if (candidate.javaVersion < RUNELITE_MIN_JAVA || candidate.javaVersion > RUNELITE_MAX_JAVA) continue;
            if (bestRuntime == null || candidate.javaVersion > bestRuntime.javaVersion) bestRuntime = candidate;
        }
        if (bestRuntime != null) return bestRuntime.name;

        if (!isOnline(context)) throw new IOException(context.getString(R.string.multirt_no_internet));
        NewJREUtil.tryDownloadRuntime(context, RUNELITE_MAX_JAVA);
        for (Runtime candidate : MultiRTUtils.getInstalledRuntimes()) {
            if (candidate.javaVersion == RUNELITE_MAX_JAVA) return candidate.name;
        }
        throw new IOException(context.getString(R.string.runelite_no_runtime));
    }

    /**
     * Ensure the bundled RuneLite client is available and open the AWT host activity.
     * RuneLite is a Swing application, so it runs on the caciocavallo AWT surface
     * (the same one used by jar installers) instead of the Minecraft GL surface.
     */
    public static void startRuneLite(final Activity activity) throws Throwable {
        startRuneLite(activity, null);
    }

    public static void startRuneLite(final Activity activity, String runeLiteProfile) throws Throwable {
        ensureRuneLiteInstalled(activity);
        String runtimeName = getRuneLiteRuntimeName(activity);
        // Official RuneLite release from bootstrap.json (downloaded/verified here), or the bundled jar.
        com.codyedwin.runelitemobile.runeliteupdate.RuneLiteBootstrap.LaunchPlan plan =
                com.codyedwin.runelitemobile.runeliteupdate.RuneLiteUpdates.prepareLaunch(activity);

        Intent intent = new Intent(activity, JavaGUILauncherActivity.class);
        intent.putExtra(JavaGUILauncherActivity.EXTRA_RUNELITE, true);
        intent.putExtra(JavaGUILauncherActivity.EXTRA_RUNTIME, runtimeName);
        if (runeLiteProfile != null) intent.putExtra(JavaGUILauncherActivity.EXTRA_RUNELITE_PROFILE, runeLiteProfile);
        intent.putExtra(JavaGUILauncherActivity.EXTRA_RUNELITE_VERSION, plan.version);
        if (!plan.isBundled()) {
            StringBuilder classpath = new StringBuilder();
            for (File file : plan.classpath) {
                if (classpath.length() > 0) classpath.append(File.pathSeparator);
                classpath.append(file.getAbsolutePath());
            }
            intent.putExtra(JavaGUILauncherActivity.EXTRA_RUNELITE_CLASSPATH, classpath.toString());
            intent.putStringArrayListExtra(JavaGUILauncherActivity.EXTRA_RUNELITE_JVM_ARGS,
                    new ArrayList<>(plan.manifest.jvmArgs));
        }
        activity.startActivity(intent);
    }

    /* =================== End RuneLite =================== */

    private static Logger.eventLogListener controllableMitigationLogListener;

    private static Logger.eventLogListener oldL4JMitigationLogListener;

    public static void buildNotificationChannel(Context context){
        if(Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(
                context.getString(R.string.notif_channel_id),
                context.getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_DEFAULT);
        NotificationManagerCompat manager = NotificationManagerCompat.from(context);
        manager.createNotificationChannel(channel);
    }

    public static void getCacioJavaArgs(List<String> javaArgList, boolean isJava8, Activity activity) {
        // Caciocavallo config AWT-enabled version
        javaArgList.add("-Djava.awt.headless=false");
        javaArgList.add("-Dcacio.managed.screensize=" + AWTCanvasView.getCanvasWidth() + "x" + AWTCanvasView.getCanvasHeight());
        javaArgList.add("-Dcacio.font.fontmanager=sun.awt.X11FontManager");
        javaArgList.add("-Dcacio.font.fontscaler=sun.font.FreetypeFontScaler");
        javaArgList.add("-Dswing.defaultlaf=javax.swing.plaf.metal.MetalLookAndFeel");
        if (isJava8) {
            javaArgList.add("-Dawt.toolkit=net.java.openjdk.cacio.ctc.CTCToolkit");
            javaArgList.add("-Djava.awt.graphicsenv=net.java.openjdk.cacio.ctc.CTCGraphicsEnvironment");
        } else {
            File caciocavavallo17Dir = new File(Tools.DIR_GAME_HOME, "caciocavallo17");
            File[] caciocavallo17Jars = caciocavavallo17Dir.listFiles((f, s) ->s.contains("cacio-tta"));
            if(caciocavallo17Jars == null || caciocavallo17Jars.length < 1) {
            // We wanna avoid the launch being interrupted so we extract again if it isn't found
                AsyncAssetManager.unpackComponents(activity);
                caciocavallo17Jars = caciocavavallo17Dir.listFiles((f, s) ->s.contains("cacio-tta"));
                if(caciocavallo17Jars == null || caciocavallo17Jars.length < 1)
                    throw new RuntimeException("Failed to extract required assets!");
            }
            javaArgList.add("-javaagent:"+caciocavallo17Jars[0].getAbsolutePath());
            javaArgList.add("-Dawt.toolkit=com.github.caciocavallosilano.cacio.ctc.CTCToolkit");
            javaArgList.add("-Djava.awt.graphicsenv=com.github.caciocavallosilano.cacio.ctc.CTCGraphicsEnvironment");
            // This approach breaks kilt so we use an agent instead
//          javaArgList.add("-Djava.system.class.loader=com.github.caciocavallosilano.cacio.ctc.CTCPreloadClassLoader");
            javaArgList.add("--add-exports=java.desktop/java.awt=ALL-UNNAMED");
            javaArgList.add("--add-exports=java.desktop/java.awt.peer=ALL-UNNAMED");
            javaArgList.add("--add-exports=java.desktop/sun.awt.image=ALL-UNNAMED");
            javaArgList.add("--add-exports=java.desktop/sun.java2d=ALL-UNNAMED");
            javaArgList.add("--add-exports=java.desktop/java.awt.dnd.peer=ALL-UNNAMED");
            javaArgList.add("--add-exports=java.desktop/sun.awt=ALL-UNNAMED");
            javaArgList.add("--add-exports=java.desktop/sun.awt.event=ALL-UNNAMED");
            javaArgList.add("--add-exports=java.desktop/sun.awt.datatransfer=ALL-UNNAMED");
            javaArgList.add("--add-exports=java.desktop/sun.font=ALL-UNNAMED");
            javaArgList.add("--add-exports=java.base/sun.security.action=ALL-UNNAMED");
            javaArgList.add("--add-opens=java.base/java.util=ALL-UNNAMED");
            javaArgList.add("--add-opens=java.desktop/java.awt=ALL-UNNAMED");
            javaArgList.add("--add-opens=java.desktop/sun.font=ALL-UNNAMED");
            javaArgList.add("--add-opens=java.desktop/sun.java2d=ALL-UNNAMED");
            javaArgList.add("--add-opens=java.base/java.lang.reflect=ALL-UNNAMED");

            // Opens the java.net package to Arc DNS injector on Java 9+
            javaArgList.add("--add-opens=java.base/java.net=ALL-UNNAMED");
        }

        StringBuilder cacioClasspath = new StringBuilder();
        cacioClasspath.append("-Xbootclasspath/").append(isJava8 ? "p" : "a");
        File cacioDir = new File(DIR_GAME_HOME + "/caciocavallo" + (isJava8 ? "" : "17"));
        File[] cacioFiles = cacioDir.listFiles();
        if (cacioFiles != null) {
            for (File file : cacioFiles) {
                if (file.getName().endsWith(".jar")) {
                    cacioClasspath.append(":").append(file.getAbsolutePath());
                }
            }
        }
        javaArgList.add(cacioClasspath.toString());
    }

    public static DisplayMetrics getDisplayMetrics(Activity activity) {
        DisplayMetrics displayMetrics = new DisplayMetrics();

        if(SDK_INT >= Build.VERSION_CODES.N && (activity.isInMultiWindowMode() || activity.isInPictureInPictureMode())){
            //For devices with free form/split screen, we need window size, not screen size.
            displayMetrics = activity.getResources().getDisplayMetrics();
        }else{
            if (SDK_INT >= Build.VERSION_CODES.R) {
                activity.getDisplay().getRealMetrics(displayMetrics);
            } else { // Removed the clause for devices with unofficial notch support, since it also ruins all devices with virtual nav bars before P
                activity.getWindowManager().getDefaultDisplay().getRealMetrics(displayMetrics);
            }
            if(!PREF_IGNORE_NOTCH){
                //Remove notch width when it isn't ignored.
                if(activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT)
                    displayMetrics.heightPixels -= PREF_NOTCH_SIZE;
                else
                    displayMetrics.widthPixels -= PREF_NOTCH_SIZE;
            }
        }
        currentDisplayMetrics = displayMetrics;
        return displayMetrics;
    }

    public static void setFullscreen(Activity activity, boolean fullscreen) {
        final View decorView = activity.getWindow().getDecorView();
        View.OnSystemUiVisibilityChangeListener visibilityChangeListener = visibility -> {
            boolean multiWindowMode = SDK_INT >= 24 && activity.isInMultiWindowMode();
            // When in multi-window mode, asking for fullscreen makes no sense (cause the launcher runs in a window)
            // So, ignore the fullscreen setting when activity is in multi window mode
            if(fullscreen && !multiWindowMode){
                if ((visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
                    decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
                }
            }else{
                decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            }

        };
        decorView.setOnSystemUiVisibilityChangeListener(visibilityChangeListener);
        visibilityChangeListener.onSystemUiVisibilityChange(decorView.getSystemUiVisibility()); //call it once since the UI state may not change after the call, so the activity wont become fullscreen
    }

    public static DisplayMetrics currentDisplayMetrics;

    public static void updateWindowSize(Activity activity) {
        currentDisplayMetrics = getDisplayMetrics(activity);

        View dimensionView = activity.findViewById(R.id.dimension_tracker);

        if(dimensionView != null) {
            int width = dimensionView.getWidth();
            int height = dimensionView.getHeight();
            if(width != 0 && height != 0) {
                Log.i("Tools", "Using dimension_tracker for display dimensions; W="+width+" H="+height);
                CallbackBridge.physicalWidth = width;
                CallbackBridge.physicalHeight = height;
                return;
            }else{
                Log.e("Tools","Dimension tracker detected but dimensions out of date. Please check usage.", new Exception());
            }
        }

        CallbackBridge.physicalWidth = currentDisplayMetrics.widthPixels;
        CallbackBridge.physicalHeight = currentDisplayMetrics.heightPixels;
    }

    public static void copyAssetFile(Context ctx, String fileName, String output, boolean overwrite) throws IOException {
        copyAssetFile(ctx, fileName, output, new File(fileName).getName(), overwrite);
    }

    public static void copyAssetFile(Context ctx, String fileName, String output, String outputName, boolean overwrite) throws IOException {
        File parentFolder = new File(output);
        FileUtils.ensureDirectory(parentFolder);
        File destinationFile = new File(output, outputName);
        if(!destinationFile.exists() || overwrite){
            try(InputStream inputStream = ctx.getAssets().open(fileName)) {
                try (OutputStream outputStream = new FileOutputStream(destinationFile)){
                    IOUtils.copy(inputStream, outputStream);
                }
            }
        }
    }

    public static String printToString(Throwable throwable) {
        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        throwable.printStackTrace(printWriter);
        printWriter.close();
        return stringWriter.toString();
    }

    public static void showError(Context ctx, Throwable e) {
        showError(ctx, e, false);
    }

    public static void showError(final Context ctx, final Throwable e, final boolean exitIfOk) {
        showError(ctx, R.string.global_error, null ,e, exitIfOk, false);
    }
    public static void showError(final Context ctx, final int rolledMessage, final Throwable e) {
        showError(ctx, R.string.global_error, ctx.getString(rolledMessage), e, false, false);
    }
    public static void showError(final Context ctx, final String rolledMessage, final Throwable e) {
        showError(ctx, R.string.global_error, rolledMessage, e, false, false);
    }
    public static void showError(final Context ctx, final String rolledMessage, final Throwable e, boolean exitIfOk) {
        showError(ctx, R.string.global_error, rolledMessage, e, exitIfOk, false);
    }
    public static void showError(final Context ctx, final int titleId, final Throwable e, final boolean exitIfOk) {
        showError(ctx, titleId, null, e, exitIfOk, false);
    }

    private static void showError(final Context ctx, final int titleId, final String rolledMessage, final Throwable e, final boolean exitIfOk, final boolean showMore) {
        if(e instanceof ContextExecutorTask) {
            ContextExecutor.execute((ContextExecutorTask) e);
            return;
        }
        e.printStackTrace();

        Runnable runnable = () -> {
            final String errMsg = showMore ? printToString(e) : rolledMessage != null ? rolledMessage : e.getMessage();
            AlertDialog.Builder builder = new AlertDialog.Builder(ctx)
                    .setTitle(titleId)
                    .setMessage(errMsg)
                    .setPositiveButton(android.R.string.ok, (p1, p2) -> {
                        if(exitIfOk) {
                            if (ctx instanceof JavaGUILauncherActivity) {
                                fullyExit();
                            } else if (ctx instanceof Activity) {
                                ((Activity) ctx).finish();
                            }
                        }
                    })
                    .setNegativeButton(showMore ? R.string.error_show_less : R.string.error_show_more, (p1, p2) -> showError(ctx, titleId, rolledMessage, e, exitIfOk, !showMore))
                    .setNeutralButton(android.R.string.copy, (p1, p2) -> {
                        ClipboardManager mgr = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                        mgr.setPrimaryClip(ClipData.newPlainText("error", printToString(e)));
                        if(exitIfOk) {
                            if (ctx instanceof JavaGUILauncherActivity) {
                                fullyExit();
                            } else {
                                ((Activity) ctx).finish();
                            }
                        }
                    })
                    .setCancelable(!exitIfOk);
            try {
                builder.show();
            } catch (Throwable th) {
                th.printStackTrace();
            }
        };

        if (ctx instanceof Activity) {
            ((Activity) ctx).runOnUiThread(runnable);
        } else {
            runnable.run();
        }
    }

    /**
     * Show the error remotely in a context-aware fashion. Has generally the same behaviour as
     * Tools.showError when in an activity, but when not in one, sends a notification that opens an
     * activity and calls Tools.showError().
     * NOTE: If the Throwable is a ContextExecutorTask and when not in an activity,
     * its executeWithApplication() method will never be called.
     * @param e the error (throwable)
     */
    public static void showErrorRemote(Throwable e) {
        showErrorRemote(null, e);
    }
    public static void showErrorRemote(Context context, int rolledMessage, Throwable e) {
        showErrorRemote(context.getString(rolledMessage), e);
    }
    public static void showErrorRemote(String rolledMessage, Throwable e) {
        // I WILL embrace layer violations because Android's concept of layers is STUPID
        // We live in the same process anyway, why make it any more harder with this needless
        // abstraction?

        // Add your Context-related rage here
        ContextExecutor.execute(new ShowErrorActivity.RemoteErrorTask(e, rolledMessage));
    }

    public static void dialogOnUiThread(final Activity activity, final CharSequence title, final CharSequence message) {
        activity.runOnUiThread(()->dialog(activity, title, message));
    }
    public static void dialogOnUiThread(final Activity activity, final int title, final int message) {
        dialogOnUiThread(activity, activity.getString(title), activity.getString(message));
    }

    public static void dialog(final Context context, final CharSequence title, final CharSequence message) {
        new AlertDialog.Builder(context)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    public static String read(InputStream is) throws IOException {
        String readResult = IOUtils.toString(is, StandardCharsets.UTF_8);
        is.close();
        return readResult;
    }

    public static String read(String path) throws IOException {
        return read(new File(path));
    }

    public static String read(File path) throws IOException {
        String content = read(new FileInputStream(path));
        return content;
    }

    public static void write(String path, String content) throws IOException {
        File file = new File(path);
        FileUtils.ensureParentDirectory(file);
        try(FileOutputStream outStream = new FileOutputStream(file)) {
            IOUtils.write(content, outStream);
        }
    }

    public static void downloadFile(String urlInput, String nameOutput) throws IOException {
        File file = new File(nameOutput);
        DownloadUtils.downloadFile(urlInput, file);
    }

    public static void fullyExit() {
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    public interface DownloaderFeedback {
        void updateProgress(int curr, int max);
        default boolean isCancelled() {
            return false;
        }
        default void onDownloadConnectionOpened(HttpURLConnection connection) {
        }
        default void onDownloadConnectionClosed(HttpURLConnection connection) {
        }
    }

    public static boolean compareSHA1(File f, String sourceSHA) {
        try {
            String sha1_dst;
            try (InputStream is = new FileInputStream(f)) {
                sha1_dst = new String(Hex.encodeHex(org.apache.commons.codec.digest.DigestUtils.sha1(is)));
            }
            if(sourceSHA != null) {
                return sha1_dst.equalsIgnoreCase(sourceSHA);
            } else{
                return true; // fake match
            }
        }catch (IOException e) {
            Log.i("SHA1","Fake-matching a hash due to a read error",e);
            return true;
        }
    }

    public static void ignoreNotch(boolean shouldIgnore, Activity ctx){
        if (SDK_INT >= P) {
            if (shouldIgnore) {
                ctx.getWindow().getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            } else {
                ctx.getWindow().getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER;
            }
            ctx.getWindow().setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
            Tools.updateWindowSize(ctx);
        }
    }

    public static int getTotalDeviceMemory(Context ctx){
        ActivityManager actManager = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
        actManager.getMemoryInfo(memInfo);
        return (int) (memInfo.totalMem / 1048576L);
    }

    public static String getFileName(Context ctx, Uri uri) {
        Cursor c = ctx.getContentResolver().query(uri, null, null, null, null);
        if(c == null) return uri.getLastPathSegment(); // idk myself but it happens on asus file manager
        c.moveToFirst();
        int columnIndex = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
        if(columnIndex == -1) return uri.getLastPathSegment();
        String fileName = c.getString(columnIndex);
        c.close();
        return fileName;
    }

    public static String extractUntilCharacter(String input, String whatFor, char terminator) {
        int whatForStart = input.indexOf(whatFor);
        if(whatForStart == -1) return null;
        whatForStart += whatFor.length();
        int terminatorIndex = input.indexOf(terminator, whatForStart);
        if(terminatorIndex == -1) return null;
        return input.substring(whatForStart, terminatorIndex);
    }

    public static void runOnUiThread(Runnable runnable) {
        MAIN_HANDLER.post(runnable);
    }

    /** Triggers the share intent chooser, with the latestlog file attached to it */
    public static void shareLog(Context context){
        openPath(context, new File(Tools.DIR_GAME_HOME, "latestlog.txt"), true);
    }

    /**
     * Determine the MIME type of a File.
     * @param file The file to determine the type of
     * @return the type, or the default value *slash* if cannot be determined
     */
    public static String getMimeType(File file) {
        if(file.isDirectory()) return DocumentsContract.Document.MIME_TYPE_DIR;
        String mimeType = null;
        try (FileInputStream fileInputStream = new FileInputStream(file)){
            // Theoretically we don't even need the buffer since we don't care about the
            // contents of the file after the guess, but mark-supported streams
            // are a requirement of URLConnection.guessContentTypeFromStream()
            try(BufferedInputStream bufferedInputStream = new BufferedInputStream(fileInputStream)) {
                mimeType = URLConnection.guessContentTypeFromStream(bufferedInputStream);
            }
        }catch (IOException e) {
            Log.w("FileMimeType", "Failed to determine MIME type by stream", e);
        }
        if(mimeType != null) return mimeType;
        mimeType = URLConnection.guessContentTypeFromName(file.getName());
        if(mimeType != null) return mimeType;
        return "*/*";
    }

    /**
     * Open the path specified by a File in a file explorer or in a relevant application.
     * @param context the current Context
     * @param file the File to open
     * @param share whether to open a "Share" or an "Open" dialog.
     */
    public static void openPath(Context context, File file, boolean share) {
        Uri contentUri = DocumentsContract.buildDocumentUri(context.getString(R.string.storageProviderAuthorities), file.getAbsolutePath());
        String mimeType = getMimeType(file);
        Intent intent = new Intent();
        if(share) {
            intent.setAction(Intent.ACTION_SEND);
            intent.setType(getMimeType(file));
            intent.putExtra(Intent.EXTRA_STREAM, contentUri);
        }else {
            intent.setAction(Intent.ACTION_VIEW);
            intent.setDataAndType(contentUri, mimeType);
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Intent chooserIntent = Intent.createChooser(intent, file.getName());
        context.startActivity(chooserIntent);
    }

    /**
     * Check if the device is one of the devices that may be affected by the hanging linker issue.
     * The device is affected if the linker causes the process to lock up when dlopen() is called within
     * dl_iterate_phdr().
     * For now, the only affected firmware that I know of is Android 5.1, EMUI 3.1 on MTK-based Huawei
     * devices.
     * @return if the device is affected by the hanging linker issue.
     */
    public static boolean deviceHasHangingLinker() {
        // Android Oreo and onwards have GSIs and most phone firmwares at that point were not modified
        // *that* intrusively. So assume that we are not affected.
        if(SDK_INT >= Build.VERSION_CODES.O) return false;
        // Since the affected function in LWJGL is rarely used (and when used, it's mainly for debug prints)
        // we can make the search scope a bit more broad and check if we are running on a Huawei device.
        return Build.MANUFACTURER.toLowerCase(Locale.ROOT).contains("huawei");
    }

    public static class RenderersList {
        public final List<String> rendererIds;
        public final String[] rendererDisplayNames;

        public RenderersList(List<String> rendererIds, String[] rendererDisplayNames) {
            this.rendererIds = rendererIds;
            this.rendererDisplayNames = rendererDisplayNames;
        }
    }

    public static <T> T getWeakReference(WeakReference<T> weakReference) {
        if(weakReference == null) return null;
        return weakReference.get();
    }

    public static void dialogForceClose(Context ctx) {
        new AlertDialog.Builder(ctx)
                .setMessage(R.string.mcn_exit_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (p1, p2) -> {
                    try {
                        Tools.fullyExit();
                    } catch (Throwable th) {
                        Log.w(Tools.APP_NAME, "Could not enable System.exit() method!", th);
                    }
                }).show();
    }

    private static NetworkInfo getActiveNetworkInfo(Context ctx) {
        ConnectivityManager connMgr = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo networkInfo = connMgr.getActiveNetworkInfo();
        return networkInfo; // This can return null when there is no wifi or data connected
    }

    public static boolean isOnline(Context ctx) {
        NetworkInfo info = getActiveNetworkInfo(ctx);
        if(info == null) return false;
        return (info.isConnected());
    }

}
