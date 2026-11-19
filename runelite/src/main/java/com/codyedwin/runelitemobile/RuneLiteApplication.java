package com.codyedwin.runelitemobile;

import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.content.res.*;
import android.os.*;
import androidx.core.app.*;

import android.util.*;
import java.io.*;
import java.text.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.codyedwin.runelitemobile.lifecycle.ContextExecutor;
import com.codyedwin.runelitemobile.prefs.LauncherPreferences;
import com.codyedwin.runelitemobile.tasks.AsyncAssetManager;
import com.codyedwin.runelitemobile.utils.*;
import com.codyedwin.runelitemobile.utils.FileUtils;

public class RuneLiteApplication extends Application {
	public static final String CRASH_REPORT_TAG = "RuneLiteCrashReport";
	public static final ExecutorService sExecutorService = new ThreadPoolExecutor(4, 4, 500, TimeUnit.MILLISECONDS,  new LinkedBlockingQueue<>());
	
	@Override
	public void onCreate() {
		ContextExecutor.setApplication(this);
		Thread.setDefaultUncaughtExceptionHandler((thread, th) -> {
			boolean storagePermAllowed = (Build.VERSION.SDK_INT < 23 || Build.VERSION.SDK_INT >= 29 ||
					ActivityCompat.checkSelfPermission(RuneLiteApplication.this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) && Tools.checkStorageRoot(RuneLiteApplication.this);
			File crashFile = new File(storagePermAllowed ? Tools.DIR_GAME_HOME : Tools.DIR_DATA, "latestcrash.txt");
			try {
				// Write to file, since some devices may not able to show error
				FileUtils.ensureParentDirectory(crashFile);
				PrintStream crashStream = new PrintStream(crashFile);
				crashStream.append("RuneLite Mobile crash report\n");
				crashStream.append(" - Time: ").append(DateFormat.getDateTimeInstance().format(new Date())).append("\n");
				crashStream.append(" - Device: ").append(Build.PRODUCT).append(" ").append(Build.MODEL).append("\n");
				crashStream.append(" - Android version: ").append(Build.VERSION.RELEASE).append("\n");
				crashStream.append(" - Crash stack trace:\n");
				crashStream.append(" - Launcher version: " + BuildConfig.VERSION_NAME + "\n");
				crashStream.append(Log.getStackTraceString(th));
				crashStream.close();
			} catch (Throwable throwable) {
				Log.e(CRASH_REPORT_TAG, " - Exception attempt saving crash stack trace:", throwable);
				Log.e(CRASH_REPORT_TAG, " - The crash stack trace was:", th);
			}

			FatalErrorActivity.showError(RuneLiteApplication.this, crashFile.getAbsolutePath(), storagePermAllowed, th);
			Tools.fullyExit();
		});
		
		try {
			super.onCreate();
			if(Tools.checkStorageRoot(this)){
				// Implicitly initializes early constants and storage constants.
				// Required to run the main activity properly.
				LauncherPreferences.loadPreferences(this);
			} else {
				// In other cases, only initialize enough for the basicmost basics to work
				// and not explode.
				Tools.initEarlyConstants(this);
			}
			Tools.DEVICE_ARCHITECTURE = Architecture.getDeviceArchitecture();
			//Force x86 lib directory for Asus x86 based zenfones
			if(Architecture.isx86Device() && Architecture.is32BitsDevice()){
				String originalJNIDirectory = getApplicationInfo().nativeLibraryDir;
				getApplicationInfo().nativeLibraryDir = originalJNIDirectory.substring(0,
												originalJNIDirectory.lastIndexOf("/"))
												.concat("/x86");
			}
			AsyncAssetManager.unpackRuntime(getAssets());
		} catch (Throwable throwable) {
			Intent ferrorIntent = new Intent(this, FatalErrorActivity.class);
			ferrorIntent.putExtra("throwable", throwable);
			ferrorIntent.setFlags(FLAG_ACTIVITY_NEW_TASK);
			startActivity(ferrorIntent);
		}
	}

	@Override
	public void onTerminate() {
		super.onTerminate();
		ContextExecutor.clearApplication();
	}

	@Override
    protected void attachBaseContext(Context base) {
        // The Application's base context must stay the framework's ContextImpl: ActivityThread
        // casts app.getBaseContext() to ContextImpl when it instantiates a BroadcastReceiver
        // (e.g. the home-screen widget), so wrapping it crashed with a ClassCastException.
        // Apply the forced locale to the resources instead; activities wrap their own context
        // in BaseActivity#attachBaseContext.
        super.attachBaseContext(base);
        LocaleUtils.setLocale(base);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        LocaleUtils.setLocale(this);
    }
}
