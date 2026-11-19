package net.kdt.pojavlaunch.runelitemobile;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

import net.kdt.pojavlaunch.JavaGUILauncherActivity;
import net.kdt.pojavlaunch.TestStorageActivity;

import java.util.List;

/**
 * Trampoline for the home screen widget and the "Play" app shortcut. If RuneLite is already
 * running its task is brought to the front; otherwise the normal launcher is opened with
 * {@link #ACTION_PLAY}, which makes it press PLAY by itself with the saved settings (same checks,
 * same code path). Anything missing (storage, runtime, RuneLite) is reported on the normal launcher UI.
 */
public class PlayRuneLiteActivity extends Activity {
    public static final String ACTION_PLAY = "com.codyedwin.runelitemobile.action.PLAY";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!bringGameToFront(this)) {
            Intent intent = new Intent(this, TestStorageActivity.class);
            intent.setAction(ACTION_PLAY);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        }
        finish();
        overridePendingTransition(0, 0);
    }

    /** True if the game process is alive and its activity was moved to the front. */
    public static boolean bringGameToFront(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false;
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null || !isGameProcessAlive(context, am)) return false;
            String game = JavaGUILauncherActivity.class.getName();
            for (ActivityManager.AppTask task : am.getAppTasks()) {
                ActivityManager.RecentTaskInfo info = task.getTaskInfo();
                if (info == null) continue;
                if (is(info.topActivity, game) || is(info.baseActivity, game)) {
                    task.moveToFront();
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean is(ComponentName c, String cls) {
        return c != null && cls.equals(c.getClassName());
    }

    private static boolean isGameProcessAlive(Context context, ActivityManager am) {
        List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
        if (procs == null) return false;
        String name = context.getPackageName() + ":gui_installer";
        for (ActivityManager.RunningAppProcessInfo p : procs) {
            if (name.equals(p.processName)) return true;
        }
        return false;
    }
}
