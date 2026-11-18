package net.kdt.pojavlaunch.runelitemobile;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.preference.PreferenceManager;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.runeliteupdate.RuneLiteUpdates;

import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;

/** RuneLite Mobile's own start screen: profile, toggles, plugins, updates, settings and PLAY. */
public class RuneLiteMobileActivity extends AppCompatActivity {
    private static final int REQUEST_IMPORT_PLUGIN = 4101;
    private static final int ORANGE = 0xFFDC8A00;
    private static final int PANEL = 0xFF2B2B2B;

    private SharedPreferences prefs;
    private Spinner profileSpinner;
    private TextView status;
    private Button play;
    private volatile boolean launching;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadProfiles();
        launching = false;
        play.setEnabled(true);
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(0xFF1B1B1B);
        root.setPadding(dp(24), dp(16), dp(24), dp(16));

        // Left: brand + profile + PLAY
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setGravity(Gravity.CENTER_HORIZONTAL);
        android.widget.ImageView logo = new android.widget.ImageView(this);
        logo.setImageResource(R.drawable.runelite_mobile_logo);
        left.addView(logo, new LinearLayout.LayoutParams(dp(72), dp(72)));
        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextColor(ORANGE);
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        left.addView(title);
        TextView version = new TextView(this);
        version.setText("RuneLite " + RuneLiteUpdates.bundledVersion(this) + " (bundled)");
        version.setTextColor(0xFFAAAAAA);
        version.setGravity(Gravity.CENTER);
        left.addView(version);

        TextView profileLabel = label("RuneLite profile");
        profileLabel.setPadding(0, dp(20), 0, dp(4));
        left.addView(profileLabel);
        profileSpinner = new Spinner(this);
        profileSpinner.setBackground(panel(PANEL));
        left.addView(profileSpinner, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        profileSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                Object item = parent.getItemAtPosition(position);
                if (item instanceof RuneLiteProfiles.Profile) {
                    prefs.edit().putString(LauncherPreferences.PREF_KEY_RUNELITE_PROFILE, ((RuneLiteProfiles.Profile) item).name).apply();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        play = new Button(this);
        play.setText("PLAY");
        play.setTextColor(Color.WHITE);
        play.setTextSize(22);
        play.setTypeface(Typeface.DEFAULT_BOLD);
        play.setBackground(panel(ORANGE));
        LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64));
        playLp.topMargin = dp(16);
        left.addView(play, playLp);
        play.setOnClickListener(v -> play());

        status = new TextView(this);
        status.setTextColor(0xFFAAAAAA);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(8), 0, 0);
        left.addView(status);

        // Right: toggles + actions
        ScrollView rightScroll = new ScrollView(this);
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        rightScroll.addView(right);

        right.addView(toggle("Performance mode (lower resolution, higher FPS)",
                LauncherPreferences.PREF_KEY_RUNELITE_PERFORMANCE, false));
        right.addView(toggle("GPU mode (experimental)",
                LauncherPreferences.PREF_KEY_RUNELITE_GPU, false));
        right.addView(toggle("Load sideloaded plugins",
                LauncherPreferences.PREF_KEY_RUNELITE_SIDELOADED, true));
        right.addView(action("Import plugin (.jar)", v -> importPlugin()));
        right.addView(action("Open game directory", v -> Tools.openPath(this, Tools.getRuneLiteDir(), false)));
        right.addView(action("RuneLite version & updates", v -> RuneLiteUpdates.showDialog(this)));
        right.addView(action("Settings (memory)", v -> startActivity(new Intent(this, RuneLiteMobileSettingsActivity.class))));
        right.addView(action("Share log file", v -> Tools.shareLog(this)));
        right.addView(action("About RuneLite Mobile", v -> showAbout()));

        LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        leftLp.rightMargin = dp(24);
        root.addView(left, leftLp);
        root.addView(rightScroll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.3f));
        return root;
    }

    private GradientDrawable panel(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(8));
        return d;
    }

    private TextView label(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(0xFFCCCCCC);
        return t;
    }

    private View toggle(String text, String key, boolean def) {
        SwitchCompat s = new SwitchCompat(this);
        s.setText(text);
        s.setTextColor(Color.WHITE);
        s.setTextSize(16);
        s.setPadding(dp(16), 0, dp(16), 0);
        s.setBackground(panel(PANEL));
        s.setChecked(prefs.getBoolean(key, def));
        s.setOnCheckedChangeListener((b, checked) -> {
            // commit() so the value is on disk before PLAY starts the RuneLite process, which
            // reads it from disk; then verify it stuck.
            boolean ok = prefs.edit().putBoolean(key, checked).commit();
            boolean stored = prefs.getBoolean(key, def);
            if (!ok || stored != checked) {
                String reason = "Setting '" + text + "' did not save (commit=" + ok + ", stored=" + stored + ")";
                android.util.Log.w("RuneLiteMobile", reason);
                Toast.makeText(this, reason, Toast.LENGTH_LONG).show();
            } else {
                android.util.Log.i("RuneLiteMobile", key + " = " + checked);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        lp.bottomMargin = dp(8);
        s.setLayoutParams(lp);
        return s;
    }

    private View action(String text, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(16);
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        b.setPadding(dp(16), 0, dp(16), 0);
        b.setBackground(panel(PANEL));
        b.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        lp.bottomMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private void loadProfiles() {
        List<RuneLiteProfiles.Profile> profiles = RuneLiteProfiles.load();
        if (profiles.isEmpty()) profiles.add(new RuneLiteProfiles.Profile("default", true));
        ArrayAdapter<RuneLiteProfiles.Profile> adapter = new ArrayAdapter<RuneLiteProfiles.Profile>(this,
                android.R.layout.simple_spinner_item, profiles) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView v = (TextView) super.getView(position, convertView, parent);
                v.setTextColor(Color.WHITE);
                v.setTextSize(16);
                v.setPadding(dp(12), 0, dp(12), 0);
                return v;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        profileSpinner.setAdapter(adapter);
        String saved = prefs.getString(LauncherPreferences.PREF_KEY_RUNELITE_PROFILE, null);
        int select = 0;
        for (int i = 0; i < profiles.size(); i++) {
            if (saved != null ? profiles.get(i).name.equals(saved) : profiles.get(i).active) select = i;
        }
        profileSpinner.setSelection(select);
    }

    private void play() {
        if (launching) return;
        launching = true;
        play.setEnabled(false);
        Object item = profileSpinner.getSelectedItem();
        String profile = item instanceof RuneLiteProfiles.Profile ? ((RuneLiteProfiles.Profile) item).name : null;
        status.setText("");
        final RuneLoadingView loading = RuneLoadingView.attach(this, "Checking for updates…", 0.1f);
        loadingView = loading;
        new Thread(() -> {
            try {
                // First start: wait for the bundled components (cacio, shim, RuneLite) to unpack.
                if (ProgressKeeper.hasOngoingTasks()) loading.setStatus("Unpacking RuneLite…", 0.05f);
                while (ProgressKeeper.hasOngoingTasks()) Thread.sleep(200);
                loading.setStatus("Checking for updates…", 0.2f);
                Tools.startRuneLite(this, profile);
                loading.setStatus("Starting Java…", 0.4f);
            } catch (Throwable t) {
                runOnUiThread(() -> {
                    status.setText("");
                    launching = false;
                    play.setEnabled(true);
                    dismissLoading();
                });
                Tools.showError(this, t);
            }
        }, "RuneLiteLauncher").start();
    }

    private RuneLoadingView loadingView;

    private void dismissLoading() {
        RuneLoadingView v = loadingView;
        loadingView = null;
        if (v != null) v.dismiss();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // RuneLite runs in its own activity, which shows its own loading screen from here on.
        if (loadingView != null) {
            dismissLoading();
            launching = false;
            if (play != null) play.setEnabled(true);
        }
    }

    private void showAbout() {
        String appVersion = "?";
        try {
            appVersion = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        String html = "<big><b>Created by Cody Reichenbach</b></big><br>"
                + "GitHub: <a href=\"https://github.com/CodyEdwin\">CodyEdwin</a><br>"
                + "Project: <a href=\"https://github.com/CodyEdwin/runelite-mobile\">github.com/CodyEdwin/runelite-mobile</a><br><br>"
                + "<b>RuneLite Mobile</b> " + appVersion + "<br>"
                + "RuneLite " + RuneLiteUpdates.bundledVersion(this) + " (bundled) on a Java 17 runtime, "
                + "drawn through a caciocavallo AWT backend.<br><br>"
                + "<b>Built on</b><br>"
                + "&#8226; <a href=\"https://github.com/runelite/runelite\">RuneLite</a> (BSD 2-Clause)<br>"
                + "&#8226; <a href=\"https://github.com/PojavLauncherTeam/PojavLauncher\">PojavLauncher</a> (LGPL-3.0): JVM launch and input bridge<br>"
                + "&#8226; <a href=\"https://github.com/PojavLauncherTeam/caciocavallo\">Caciocavallo</a> and OpenJDK (GPLv2 + Classpath Exception)<br>"
                + "&#8226; Apache Commons, Gson, JNA, xz, bytehook<br><br>"
                + "RuneLite Mobile is an unofficial port. It is not affiliated with or endorsed by Jagex Ltd or the RuneLite project. "
                + "Old School RuneScape is a trademark of Jagex Ltd.";
        TextView text = new TextView(this);
        text.setText(androidx.core.text.HtmlCompat.fromHtml(html, androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY));
        text.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        text.setPadding(dp(24), dp(16), dp(24), 0);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(text);
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("About")
                .setIcon(R.drawable.runelite_mobile_logo)
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void importPlugin() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(intent, REQUEST_IMPORT_PLUGIN);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_IMPORT_PLUGIN || resultCode != Activity.RESULT_OK || data == null) return;
        int count = 0;
        try {
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                    if (copyPlugin(data.getClipData().getItemAt(i).getUri())) count++;
                }
            } else if (data.getData() != null && copyPlugin(data.getData())) {
                count++;
            }
        } catch (Exception e) {
            Tools.showError(this, e);
        }
        Toast.makeText(this, count + " plugin(s) imported into sideloaded-plugins", Toast.LENGTH_LONG).show();
    }

    private boolean copyPlugin(Uri uri) throws Exception {
        String name = null;
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) name = c.getString(0);
        }
        if (name == null) name = "plugin-" + System.currentTimeMillis() + ".jar";
        if (!name.toLowerCase().endsWith(".jar")) {
            Toast.makeText(this, name + " is not a .jar", Toast.LENGTH_SHORT).show();
            return false;
        }
        name = new File(name).getName();
        File dir = Tools.getRuneLiteSideloadDir();
        dir.mkdirs();
        try (InputStream in = getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(new File(dir, name))) {
            IOUtils.copy(in, out);
        }
        return true;
    }
}
