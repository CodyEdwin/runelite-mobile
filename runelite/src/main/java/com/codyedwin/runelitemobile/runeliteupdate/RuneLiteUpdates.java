package com.codyedwin.runelitemobile.runeliteupdate;

import android.app.Activity;
import android.content.Context;
import android.util.Log;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.codyedwin.runelitemobile.views.ProgressLayout;

import com.codyedwin.runelitemobile.Tools;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;

/** Android side of {@link RuneLiteBootstrap}: launch-time update, settings dialog. */
public final class RuneLiteUpdates {
    private static final String TAG = "RuneLiteUpdates";
    private static final String CERT_ASSET = "runelite-update/runelite.crt";
    private static final String BUNDLED_VERSION_ASSET = "components/runelite/version";

    private RuneLiteUpdates() {
    }

    public static RuneLiteBootstrap bootstrap(Context context) throws IOException {
        try (InputStream cert = context.getAssets().open(CERT_ASSET)) {
            RuneLiteBootstrap b = new RuneLiteBootstrap(Tools.getRuneLiteDir(), cert, Tools.getRuneLiteAndroidJar());
            b.setUserAgent("RuneLite-Android-Launcher/" + Tools.APP_NAME);
            return b;
        }
    }

    /** "4e8b0bc5f-1.13.7-SNAPSHOT" -> "1.13.7-SNAPSHOT" */
    public static String bundledVersion(Context context) {
        try (InputStream in = context.getAssets().open(BUNDLED_VERSION_ASSET)) {
            String v = Tools.read(in).trim();
            int dash = v.indexOf('-');
            if (dash > 0 && v.substring(0, dash).matches("[0-9a-fA-F]{6,40}")) v = v.substring(dash + 1);
            return v;
        } catch (IOException e) {
            return "bundled";
        }
    }

    /**
     * Run on a background thread right before RuneLite starts: update if the mode asks for it
     * (failures, e.g. offline, just keep the installed version) and pick the client to launch.
     */
    public static RuneLiteBootstrap.LaunchPlan prepareLaunch(Activity activity) {
        String bundled = bundledVersion(activity);
        UpdaterStatus.begin();
        UpdaterStatus.log("bundled client " + bundled);
        try {
            RuneLiteBootstrap b = bootstrap(activity);
            RuneLiteBootstrap.State state = b.loadState();
            UpdaterStatus.log("client mode " + RuneLiteBootstrap.modeLabel(state.mode));
            if (!(RuneLiteBootstrap.MODE_AUTO.equals(state.mode) || RuneLiteBootstrap.MODE_OFFICIAL.equals(state.mode)))
                UpdaterStatus.log("no update check (mode " + RuneLiteBootstrap.modeLabel(state.mode) + ")");
            if (RuneLiteBootstrap.MODE_AUTO.equals(state.mode) || RuneLiteBootstrap.MODE_OFFICIAL.equals(state.mode)) {
                try {
                    RuneLiteBootstrap.UpdateResult r = b.checkForUpdate((msg, pct) ->
                            ProgressLayout.setProgress(ProgressLayout.DOWNLOAD_RUNELITE, pct, msg));
                    Log.i(TAG, r.message + (r.compat != null ? "\n" + r.compat : ""));
                    UpdaterStatus.log(r.message + (r.downloaded ? " (" + UpdaterStatus.bytes(r.bytesDownloaded) + " downloaded)" : ""));
                    if (r.downloaded || (r.compat != null && !r.compat.isCompatible())) toast(activity, r.message);
                } catch (IOException e) {
                    Log.w(TAG, "RuneLite update check failed", e);
                    UpdaterStatus.log("couldn't check for updates (" + UpdaterStatus.reason(e) + "), keeping installed clients");
                    toast(activity, "Couldn't check for RuneLite updates: " + e.getMessage());
                } finally {
                    ProgressLayout.clearProgress(ProgressLayout.DOWNLOAD_RUNELITE);
                }
            }
            RuneLiteBootstrap.LaunchPlan plan = b.planLaunch(bundled);
            if (plan.note != null) {
                Log.i(TAG, plan.note);
                if (!plan.note.startsWith("Using the bundled RuneLite " + bundled + " (newer")) toast(activity, plan.note);
            }
            Log.i(TAG, "Launching RuneLite " + plan.version + (plan.isBundled() ? " (bundled)" : " (official)"));
            UpdaterStatus.log("launching RuneLite " + plan.version + (plan.isBundled() ? " (bundled)" : " (official)"));
            return plan;
        } catch (Exception e) {
            Log.w(TAG, "Falling back to the bundled RuneLite", e);
            UpdaterStatus.log("updater error (" + UpdaterStatus.reason(e) + "), launching bundled " + bundled);
            RuneLiteBootstrap.LaunchPlan plan = new RuneLiteBootstrap.LaunchPlan();
            plan.version = bundled;
            return plan;
        }
    }

    /** Where the shim confirms that {@code version} started (see LaunchConfirm in the shim). */
    public static File okFile(Context context, String version) throws IOException {
        return new File(bootstrap(context).getOkDir(), RuneLiteBootstrap.safeName(version));
    }

    /* ------------------------------------------------------------------ dialog */

    public static void showDialog(Activity activity) {
        final RuneLiteBootstrap b;
        try {
            b = bootstrap(activity);
        } catch (IOException e) {
            Tools.showError(activity, e);
            return;
        }
        RuneLiteBootstrap.State state = b.loadState();
        String bundled = bundledVersion(activity);

        ArrayList<String> labels = new ArrayList<>();
        ArrayList<String> modes = new ArrayList<>();
        labels.add("Automatic: newest of the official release and the bundled client, updates on launch");
        modes.add(RuneLiteBootstrap.MODE_AUTO);
        labels.add("Latest official RuneLite release, updates on launch");
        modes.add(RuneLiteBootstrap.MODE_OFFICIAL);
        labels.add("Bundled client only (" + bundled + "), no downloads");
        modes.add(RuneLiteBootstrap.MODE_BUNDLED);
        if (state.previous != null && b.loadManifest(state.previous) != null) {
            labels.add("Previous official release (" + state.previous + "), no update checks");
            modes.add(RuneLiteBootstrap.MODE_PREVIOUS);
        }
        int checked = Math.max(0, modes.indexOf(state.mode));

        new AlertDialog.Builder(activity)
                .setTitle("RuneLite client\n" + statusLine(b, state, bundled))
                .setSingleChoiceItems(labels.toArray(new String[0]), checked, (dialog, which) -> {
                    try {
                        b.setMode(modes.get(which));
                        b.resetAttempts();
                    } catch (IOException e) {
                        Tools.showError(activity, e);
                    }
                })
                .setPositiveButton("Check for RuneLite updates", (d, w) -> checkNow(activity, b))
                .setNegativeButton(android.R.string.ok, null)
                .show();
    }

    private static String statusLine(RuneLiteBootstrap b, RuneLiteBootstrap.State state, String bundled) {
        StringBuilder sb = new StringBuilder();
        sb.append("Bundled: ").append(bundled);
        sb.append("\nOfficial installed: ").append(state.current != null ? state.current : "none");
        if (state.previous != null) sb.append(" (fallback ").append(state.previous).append(')');
        if (state.latestSeen != null && !state.latestSeen.equals(state.current)) sb.append("\nLatest release: ").append(state.latestSeen);
        if (state.lastCheck > 0) sb.append("\nLast check: ").append(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(state.lastCheck)));
        if (state.lastError != null) sb.append("\n").append(state.lastError);
        return sb.toString();
    }

    private static void checkNow(Activity activity, RuneLiteBootstrap b) {
        toast(activity, "Checking for RuneLite updates…");
        new Thread(() -> {
            String message;
            try {
                RuneLiteBootstrap.UpdateResult r = b.checkForUpdate((msg, pct) ->
                        ProgressLayout.setProgress(ProgressLayout.DOWNLOAD_RUNELITE, pct, msg));
                message = r.message;
                if (r.compat != null) Log.i(TAG, r.compat.toString());
                RuneLiteBootstrap.State s = b.loadState();
                if (RuneLiteBootstrap.MODE_BUNDLED.equals(s.mode) || RuneLiteBootstrap.MODE_PREVIOUS.equals(s.mode))
                    message += ". Pick \"Automatic\" or \"Latest official\" to play it.";
                else if (RuneLiteBootstrap.MODE_AUTO.equals(s.mode) && s.current != null
                        && RuneLiteBootstrap.compareVersions(s.current, bundledVersion(activity)) < 0)
                    message += ". The bundled " + bundledVersion(activity) + " is newer, so Automatic keeps using it.";
            } catch (IOException e) {
                Log.w(TAG, "RuneLite update check failed", e);
                message = "RuneLite update check failed: " + e.getMessage();
            } finally {
                ProgressLayout.clearProgress(ProgressLayout.DOWNLOAD_RUNELITE);
            }
            final String text = message;
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                new AlertDialog.Builder(activity)
                        .setTitle("RuneLite client")
                        .setMessage(text)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            });
        }, "RuneLiteUpdateCheck").start();
    }

    private static void toast(Activity activity, String text) {
        activity.runOnUiThread(() -> Toast.makeText(activity, text, Toast.LENGTH_LONG).show());
    }
}
