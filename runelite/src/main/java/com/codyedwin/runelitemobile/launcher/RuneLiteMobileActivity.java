package com.codyedwin.runelitemobile.launcher;

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

import com.codyedwin.runelitemobile.R;
import com.codyedwin.runelitemobile.Tools;
import com.codyedwin.runelitemobile.prefs.LauncherPreferences;
import com.codyedwin.runelitemobile.progresskeeper.ProgressKeeper;
import com.codyedwin.runelitemobile.runeliteupdate.RuneLiteUpdates;

import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;

/** RuneLite Mobile's own start screen: profile, toggles, plugins, updates, settings and PLAY. */
public class RuneLiteMobileActivity extends AppCompatActivity {
    private static final int REQUEST_IMPORT_PLUGIN = 4101;
    private static final int REQUEST_IMPORT_PROFILE = 4102;
    private static final int REQUEST_EXPORT_PROFILE = 4103;
    private RuneLiteProfiles.Profile exporting;
    private static final int ORANGE = 0xFFF5A623;
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
        publishPlayShortcut();
        if (savedInstanceState == null) takeQuickPlay(getIntent());
    }

    /** Set when the widget / "Play" shortcut opened us: press PLAY once the UI is ready. */
    private boolean quickPlay;

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        takeQuickPlay(intent);
    }

    private void takeQuickPlay(Intent intent) {
        if (intent != null && PlayRuneLiteActivity.ACTION_PLAY.equals(intent.getAction())) {
            quickPlay = true;
            intent.setAction(null); // don't replay on recreation
            setIntent(intent);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadProfiles();
        updateModeSummary();
        launching = false;
        play.setEnabled(true);
        if (quickPlay) {
            quickPlay = false;
            // Game already up (e.g. this launcher was in another task): just show it.
            if (!PlayRuneLiteActivity.bringGameToFront(this)) play.post(this::play);
        }
    }

    /** Long-press "Play" on the app icon (dynamic so it follows the .debug package id). */
    private void publishPlayShortcut() {
        try {
            Intent intent = new Intent(this, PlayRuneLiteActivity.class);
            intent.setAction(PlayRuneLiteActivity.ACTION_PLAY);
            androidx.core.content.pm.ShortcutInfoCompat info =
                    new androidx.core.content.pm.ShortcutInfoCompat.Builder(this, "play_runelite")
                            .setShortLabel(getString(R.string.shortcut_play_short))
                            .setLongLabel(getString(R.string.shortcut_play_long))
                            .setIcon(androidx.core.graphics.drawable.IconCompat.createWithResource(this, R.mipmap.ic_launcher))
                            .setIntent(intent)
                            .build();
            androidx.core.content.pm.ShortcutManagerCompat.pushDynamicShortcut(this, info);
        } catch (Throwable ignored) {
        }
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private static final int BG_TOP = 0xFF1A0F2E;
    private static final int BG_BOTTOM = 0xFF09070F;
    private static final int CARD = 0xF01C1430;
    private static final int CARD_STROKE = 0xFF3B2A5C;
    private static final int PURPLE = 0xFF9B6BFF;
    private static final int TEXT_DIM = 0xFFB4A9C9;

    private android.widget.FrameLayout container;
    private View homePage, settingsPage;
    private TextView modeSummary;

    private View buildUi() {
        container = new android.widget.FrameLayout(this);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{BG_TOP, BG_BOTTOM});
        container.setBackground(bg);
        homePage = buildHome();
        settingsPage = buildSettings();
        settingsPage.setVisibility(View.GONE);
        container.addView(homePage);
        container.addView(settingsPage);
        return container;
    }

    private void showSettings(boolean show) {
        settingsPage.setVisibility(show ? View.VISIBLE : View.GONE);
        homePage.setVisibility(show ? View.GONE : View.VISIBLE);
        if (!show) updateModeSummary();
    }

    @Override
    public void onBackPressed() {
        if (settingsPage != null && settingsPage.getVisibility() == View.VISIBLE) showSettings(false);
        else super.onBackPressed();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean("settingsOpen", settingsPage != null && settingsPage.getVisibility() == View.VISIBLE);
    }

    @Override
    protected void onRestoreInstanceState(Bundle in) {
        super.onRestoreInstanceState(in);
        if (in.getBoolean("settingsOpen")) showSettings(true);
    }

    private boolean landscape() {
        return getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
    }

    private View buildHome() {
        boolean wide = landscape();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(28), dp(20), dp(28), dp(20));

        // Brand
        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        brand.setGravity(Gravity.CENTER);
        android.widget.ImageView logo = new android.widget.ImageView(this);
        logo.setImageResource(R.drawable.runelite_mobile_logo);
        brand.addView(logo, new LinearLayout.LayoutParams(dp(wide ? 150 : 128), dp(wide ? 150 : 128)));
        TextView title = new TextView(this);
        title.setText(getString(R.string.app_name).toUpperCase(java.util.Locale.ROOT));
        title.setTextColor(ORANGE);
        title.setTextSize(24);
        title.setLetterSpacing(0.12f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setGravity(Gravity.CENTER);
        title.setShadowLayer(dp(8), 0, 0, 0x889B6BFF);
        brand.addView(title);
        TextView version = new TextView(this);
        version.setText("RuneLite " + RuneLiteUpdates.bundledVersion(this) + "  ·  v" + appVersion());
        version.setTextColor(TEXT_DIM);
        version.setTextSize(12);
        version.setGravity(Gravity.CENTER);
        version.setPadding(0, dp(2), 0, 0);
        brand.addView(version);
        modeSummary = new TextView(this);
        modeSummary.setTextColor(PURPLE);
        modeSummary.setTextSize(12);
        modeSummary.setGravity(Gravity.CENTER);
        modeSummary.setPadding(dp(12), dp(4), dp(12), dp(4));
        GradientDrawable chip = new GradientDrawable();
        chip.setColor(0x332A1A4A);
        chip.setStroke(dp(1), CARD_STROKE);
        chip.setCornerRadius(dp(14));
        modeSummary.setBackground(chip);
        LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chipLp.topMargin = dp(10);
        brand.addView(modeSummary, chipLp);
        updateModeSummary();

        // Controls card
        LinearLayout controls = card();
        TextView profileLabel = sectionLabel("PROFILE");
        controls.addView(profileLabel);
        profileSpinner = new Spinner(this);
        GradientDrawable spinnerBg = new GradientDrawable();
        spinnerBg.setColor(0xFF140E22);
        spinnerBg.setStroke(dp(1), CARD_STROKE);
        spinnerBg.setCornerRadius(dp(10));
        profileSpinner.setBackground(spinnerBg);
        controls.addView(profileSpinner, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
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
        LinearLayout profileActions = new LinearLayout(this);
        profileActions.addView(linkButton("Import", v -> importProfile()), new LinearLayout.LayoutParams(0, dp(40), 1f));
        profileActions.addView(linkButton("Export", v -> exportProfile()), new LinearLayout.LayoutParams(0, dp(40), 1f));
        controls.addView(profileActions);

        play = new Button(this);
        play.setText("PLAY");
        play.setTextColor(Color.WHITE);
        play.setTextSize(22);
        play.setLetterSpacing(0.2f);
        play.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable playBg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xFFF5A623, 0xFFD97706});
        playBg.setCornerRadius(dp(14));
        play.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x55FFFFFF), playBg, null));
        play.setElevation(dp(6));
        LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64));
        playLp.topMargin = dp(10);
        controls.addView(play, playLp);
        play.setOnClickListener(v -> play());

        status = new TextView(this);
        status.setTextColor(TEXT_DIM);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(6), 0, 0);
        controls.addView(status);

        LinearLayout tiles = new LinearLayout(this);
        tiles.setPadding(0, dp(6), 0, 0);
        tiles.addView(tile("◫", "Screenshots", v -> startActivity(new Intent(this, RuneLiteScreenshotsActivity.class))));
        tiles.addView(tile("⧉", "Plugins", v -> startActivity(new Intent(this, RuneLitePluginsActivity.class))));
        tiles.addView(tile("≡", "Logs", v -> showLogs()));
        tiles.addView(tile("⚙", "Settings", v -> showSettings(true)));
        tiles.addView(tile("⟳", "Update", v -> updateCustomPlugins()));
        controls.addView(tiles);

        if (wide) {
            root.addView(brand, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.15f);
            clp.leftMargin = dp(28);
            root.addView(controls, clp);
            ScrollView scroll = new ScrollView(this);
            scroll.setFillViewport(true);
            scroll.addView(root);
            return scroll;
        }
        root.addView(brand);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(24);
        root.addView(controls, clp);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root);
        return scroll;
    }

    private View buildSettings() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(8), dp(8), dp(16), dp(8));
        header.setBackgroundColor(0x661A0F2E);
        Button back = new Button(this);
        back.setText("←");
        back.setTextSize(22);
        back.setTextColor(Color.WHITE);
        back.setBackground(ripple(null));
        back.setOnClickListener(v -> showSettings(false));
        header.addView(back, new LinearLayout.LayoutParams(dp(52), dp(48)));
        TextView title = new TextView(this);
        title.setText("Settings");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title);
        page.addView(header);

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int side = landscape() ? dp(96) : dp(16);
        list.setPadding(side, dp(8), side, dp(24));

        LinearLayout graphics = section(list, "GRAPHICS");
        Button scaleButton = (Button) action(renderScaleLabel(), null);
        scaleButton.setOnClickListener(v -> chooseRenderScale(scaleButton));
        add(graphics, scaleButton);
        add(graphics, toggle("GPU mode (experimental)", LauncherPreferences.PREF_KEY_RUNELITE_GPU, false));
        add(graphics, toggle("Pad game from rounded screen corners", LauncherPreferences.PREF_KEY_RUNELITE_CORNER_PAD, false));

        LinearLayout client = section(list, "CLIENT & PLUGINS");
        add(client, toggle("Use custom RuneLite (my plugins only)", LauncherPreferences.PREF_KEY_RUNELITE_CUSTOM, false));
        add(client, toggle("Load sideloaded plugins", LauncherPreferences.PREF_KEY_RUNELITE_SIDELOADED, true));
        add(client, action("Choose sideloaded plugins", v -> startActivity(new Intent(this, RuneLitePluginsActivity.class))));
        add(client, action("Import plugin (.jar)", v -> importPlugin()));
        add(client, action("Dev plugins: /sdcard/RuneLite/plugins", v -> sdcardPlugins()));
        add(client, action("Update custom plugins (download latest)", v -> updateCustomPlugins()));
        add(client, action("RuneLite version & updates", v -> RuneLiteUpdates.showDialog(this)));

        LinearLayout controls = section(list, "CONTROLS");
        add(controls, toggle("Touch Mode (OSRS mobile controls)", LauncherPreferences.PREF_KEY_RUNELITE_TOUCH_MODE, false));
        Button lockButton = (Button) action("Touch lock shortcut: " + LauncherPreferences.touchLockLabel(prefs) + "  (or 5-finger tap)", null);
        lockButton.setOnClickListener(v -> chooseTouchLockKey(lockButton));
        add(controls, lockButton);

        LinearLayout overlays = section(list, "OVERLAYS");
        add(overlays, toggle("FPS / temperature", LauncherPreferences.PREF_KEY_RUNELITE_STATS_OVERLAY, false));
        add(overlays, toggle("Battery %  (Ctrl+Shift+B in game)", LauncherPreferences.PREF_KEY_RUNELITE_BATTERY_OVERLAY, false));
        add(overlays, toggle("RSBuddy start/stop button (custom RuneLite)", LauncherPreferences.PREF_KEY_RUNELITE_RSBUDDY_BUTTON, true));

        LinearLayout files = section(list, "FILES & SYSTEM");
        add(files, action("Open game directory", v -> Tools.openPath(this, Tools.getRuneLiteDir(), false)));
        add(files, action("Open .runelite folder (settings, profiles)", v -> {
            File dotRuneLite = new File(Tools.getRuneLiteDir(), ".runelite");
            //noinspection ResultOfMethodCallIgnored
            dotRuneLite.mkdirs();
            Tools.openPath(this, dotRuneLite, false);
        }));
        add(files, action("Memory", v -> startActivity(new Intent(this, RuneLiteMobileSettingsActivity.class))));
        add(files, action("View logs", v -> showLogs()));
        add(files, action("About RuneLite Mobile", v -> showAbout()));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);
        page.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return page;
    }

    private void updateModeSummary() {
        if (modeSummary == null) return;
        StringBuilder sb = new StringBuilder(prefs.getBoolean(LauncherPreferences.PREF_KEY_RUNELITE_CUSTOM, false) ? "Custom client" : "Standard client");
        sb.append("  ·  ").append(prefs.getBoolean(LauncherPreferences.PREF_KEY_RUNELITE_GPU, false) ? "GPU" : "Software");
        if (prefs.getBoolean(LauncherPreferences.PREF_KEY_RUNELITE_TOUCH_MODE, false)) sb.append("  ·  Touch Mode");
        int scale = LauncherPreferences.renderScale(prefs);
        if (scale < 100) sb.append("  ·  ").append(scale).append('%');
        modeSummary.setText(sb);
    }

    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(10));
        GradientDrawable d = new GradientDrawable();
        d.setColor(CARD);
        d.setStroke(dp(1), CARD_STROKE);
        d.setCornerRadius(dp(18));
        c.setBackground(d);
        c.setElevation(dp(4));
        return c;
    }

    private TextView sectionLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(PURPLE);
        t.setTextSize(12);
        t.setLetterSpacing(0.15f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(4), 0, 0, dp(6));
        return t;
    }

    private LinearLayout section(LinearLayout list, String title) {
        TextView label = sectionLabel(title);
        label.setPadding(dp(8), dp(18), 0, dp(8));
        list.addView(label);
        LinearLayout c = card();
        c.setPadding(0, dp(4), 0, dp(4));
        list.addView(c);
        return c;
    }

    private void add(LinearLayout section, View row) {
        if (section.getChildCount() > 0) {
            View divider = new View(this);
            divider.setBackgroundColor(0x553B2A5C);
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
            dl.leftMargin = dp(16);
            dl.rightMargin = dp(16);
            section.addView(divider, dl);
        }
        section.addView(row);
    }

    private android.graphics.drawable.RippleDrawable ripple(android.graphics.drawable.Drawable content) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(12));
        return new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x339B6BFF), content, mask);
    }

    private Button linkButton(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(ORANGE);
        b.setTextSize(14);
        b.setBackground(ripple(null));
        b.setOnClickListener(l);
        return b;
    }

    private View tile(String icon, String text, View.OnClickListener l) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(8), 0, dp(8));
        t.setBackground(ripple(null));
        t.setClickable(true);
        t.setOnClickListener(l);
        TextView i = new TextView(this);
        i.setText(icon);
        i.setTextSize(22);
        i.setTextColor(PURPLE);
        i.setGravity(Gravity.CENTER);
        t.addView(i);
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(12);
        label.setTextColor(TEXT_DIM);
        label.setGravity(Gravity.CENTER);
        t.addView(label);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return t;
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

    /**
     * Row switch that toggles on a plain tap only. SwitchCompat's own touch handling starts a thumb
     * drag on small finger movement; inside the scrolling settings column the ScrollView then steals
     * the gesture and the switch snaps back without ever changing (no listener call), which is what
     * the GPU switch did on the OnePlus Nord. Any later programmatic change is logged with its caller.
     */
    private final class RowSwitch extends SwitchCompat {
        private float downX, downY;
        private boolean userChange, ready;
        private final int slop = android.view.ViewConfiguration.get(RuneLiteMobileActivity.this).getScaledTouchSlop() * 3;

        RowSwitch() {
            super(RuneLiteMobileActivity.this);
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent e) {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    downX = e.getX();
                    downY = e.getY();
                    setPressed(true);
                    break;
                case android.view.MotionEvent.ACTION_UP:
                    setPressed(false);
                    if (Math.abs(e.getX() - downX) < slop && Math.abs(e.getY() - downY) < slop) {
                        final boolean want = !isChecked();
                        userChange = true;
                        setChecked(want);
                        userChange = false;
                        postDelayed(() -> {
                            if (isChecked() != want) {
                                String m = "'" + getText() + "' was switched back by the app";
                                android.util.Log.w("RuneLiteMobile", m);
                                Toast.makeText(getContext(), m, Toast.LENGTH_LONG).show();
                            }
                        }, 500);
                    }
                    break;
                case android.view.MotionEvent.ACTION_CANCEL:
                    setPressed(false);
                    break;
            }
            return true;
        }

        @Override
        public void setChecked(boolean checked) {
            if (ready && !userChange && checked != isChecked()) {
                android.util.Log.w("RuneLiteMobile", "'" + getText() + "' changed to " + checked + " by code", new Throwable());
            }
            super.setChecked(checked);
        }
    }

    private View toggle(String text, String key, boolean def) {
        RowSwitch s = new RowSwitch();
        s.setText(text);
        s.setTextColor(Color.WHITE);
        s.setTextSize(16);
        s.setPadding(dp(16), 0, dp(16), 0);
        s.setBackground(ripple(null));
        s.setThumbTintList(new android.content.res.ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{ORANGE, 0xFF8A80A0}));
        s.setTrackTintList(new android.content.res.ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{0x88F5A623, 0x553B2A5C}));
        s.setChecked(prefs.getBoolean(key, def));
        s.ready = true;
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
        s.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
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
        b.setBackground(ripple(null));
        b.setOnClickListener(listener);
        b.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));
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
        // Live updater steps under "Checking for updates…" (also logged as "Updater: ...").
        com.codyedwin.runelitemobile.runeliteupdate.UpdaterStatus.setSink(loading::setDebug);
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
        com.codyedwin.runelitemobile.runeliteupdate.UpdaterStatus.setSink(null);
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

    private String renderScaleLabel() {
        int s = LauncherPreferences.renderScale(prefs);
        return "Render resolution: " + s + "%" + (s >= 100 ? " (full)" : " (higher FPS)");
    }

    /** Records a key combo from a hardware keyboard for the in-game touch lock. */
    private void chooseTouchLockKey(Button button) {
        androidx.appcompat.app.AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Touch lock shortcut")
                .setMessage("Press the key combination on your keyboard (e.g. Ctrl+Shift+L).\n\nIn game it blocks finger "
                        + "touches; mouse and keyboard keep working. Press it again to unlock.\n\nCurrent: "
                        + LauncherPreferences.touchLockLabel(prefs))
                .setNeutralButton("Turn off", (d, w) -> {
                    prefs.edit().putString(LauncherPreferences.PREF_KEY_RUNELITE_TOUCH_LOCK_KEY, "").apply();
                    button.setText("Touch lock shortcut: Off  (5-finger tap still works)");
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (event.getAction() != android.view.KeyEvent.ACTION_DOWN || keyCode == android.view.KeyEvent.KEYCODE_BACK
                    || android.view.KeyEvent.isModifierKey(keyCode)) return false;
            int mods = event.getMetaState() & LauncherPreferences.TOUCH_LOCK_MODS;
            prefs.edit().putString(LauncherPreferences.PREF_KEY_RUNELITE_TOUCH_LOCK_KEY, mods + ":" + keyCode).apply();
            button.setText("Touch lock shortcut: " + LauncherPreferences.touchLockLabel(prefs) + "  (or 5-finger tap)");
            d.dismiss();
            return true;
        });
        dialog.show();
    }

    private void chooseRenderScale(Button button) {
        int current = LauncherPreferences.renderScale(prefs);
        TextView value = new TextView(this);
        value.setGravity(Gravity.CENTER);
        value.setTextSize(18);
        android.widget.SeekBar bar = new android.widget.SeekBar(this);
        bar.setMax(15); // 25..100 in steps of 5
        bar.setProgress((current - 25) / 5);
        value.setText(current + "%");
        bar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar s, int p, boolean user) { value.setText((25 + p * 5) + "%"); }
            @Override public void onStartTrackingTouch(android.widget.SeekBar s) { }
            @Override public void onStopTrackingTouch(android.widget.SeekBar s) { }
        });
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(16), dp(24), 0);
        box.addView(value);
        box.addView(bar);
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Render resolution")
                .setMessage("Lower renders the game at fewer pixels and stretches it to the screen (higher FPS). 100% = full resolution.")
                .setView(box)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    prefs.edit().putInt(LauncherPreferences.PREF_KEY_RUNELITE_RENDER_SCALE, 25 + bar.getProgress() * 5).apply();
                    button.setText(renderScaleLabel());
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showLogs() {
        List<File> logs = new java.util.ArrayList<>();
        File latest = new File(Tools.DIR_GAME_HOME, "latestlog.txt");
        if (latest.isFile()) logs.add(latest);
        File client = new File(Tools.getRuneLiteDir(), ".runelite/logs/client.log");
        if (client.isFile()) logs.add(client);
        File gc = new File(Tools.DIR_GAME_HOME, "gc.log");
        if (gc.isFile()) logs.add(gc);
        File[] crashes = Tools.getRuneLiteDir().listFiles((d, n) -> n.startsWith("hs_err_pid") && n.endsWith(".log"));
        if (crashes != null) {
            java.util.Arrays.sort(crashes, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            logs.addAll(java.util.Arrays.asList(crashes));
        }
        if (logs.isEmpty()) {
            Toast.makeText(this, "No logs yet. Press PLAY first.", Toast.LENGTH_LONG).show();
            return;
        }
        String[] names = new String[logs.size()];
        java.text.DateFormat fmt = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT);
        for (int i = 0; i < names.length; i++) {
            File f = logs.get(i);
            names[i] = (f.getName().startsWith("hs_err") ? "Crash: " : "") + f.getName() + "  (" + fmt.format(new java.util.Date(f.lastModified())) + ")";
        }
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Logs")
                .setItems(names, (d, which) -> viewLog(logs.get(which)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void viewLog(File file) {
        String text;
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r")) {
            long max = 256 * 1024;
            long start = Math.max(0, raf.length() - max);
            byte[] b = new byte[(int) (raf.length() - start)];
            raf.seek(start);
            raf.readFully(b);
            text = (start > 0 ? "… (last 256 KB)\n" : "") + new String(b, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            text = "Unable to read " + file + ": " + e;
        }
        final String content = text;
        TextView tv = new TextView(this);
        tv.setText(content);
        tv.setTextSize(10);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(8), dp(8), dp(8), dp(8));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(tv);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(file.getName())
                .setView(scroll)
                .setPositiveButton("Share", (d, w) -> Tools.openPath(this, file, true))
                .setNeutralButton("Copy", (d, w) -> {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText(file.getName(), content));
                    Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Close", null)
                .show();
    }

    /**
     * Downloads the newest custom-client plugin jars into /sdcard/RuneLite/custom-plugins, where they
     * replace the bundled ones (custom mode) and hot-reload while the game runs.
     */
    private void updateCustomPlugins() {
        if (!Tools.hasAllFilesAccess()) {
            sdcardPlugins();
            return;
        }
        Toast.makeText(this, "Checking for plugin updates...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String result = Tools.updateCustomPlugins(this);
            final String message = result;
            runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
        }, "UpdateCustomPlugins").start();
    }

    private void sdcardPlugins() {
        if (!Tools.hasAllFilesAccess()) {
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Allow all files access")
                    .setMessage("Jars in /sdcard/RuneLite/plugins load at every launch (normal and custom mode). "
                            + "Android needs \"All files access\" for that folder.")
                    .setPositiveButton("Allow", (d, w) -> {
                        try {
                            startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:" + getPackageName())));
                        } catch (Exception e) {
                            startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        File dir = Tools.getSdcardPluginDir();
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File[] jars = dir.listFiles((d, name) -> name.endsWith(".jar"));
        Toast.makeText(this, (jars == null ? 0 : jars.length) + " jar(s) in " + dir + " load on PLAY", Toast.LENGTH_LONG).show();
    }

    private void importProfile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_IMPORT_PROFILE);
    }

    private void exportProfile() {
        Object item = profileSpinner.getSelectedItem();
        if (!(item instanceof RuneLiteProfiles.Profile) || ((RuneLiteProfiles.Profile) item).id < 0
                || !((RuneLiteProfiles.Profile) item).configFile().isFile()) {
            Toast.makeText(this, "This profile has no saved settings yet. Play once first.", Toast.LENGTH_LONG).show();
            return;
        }
        exporting = (RuneLiteProfiles.Profile) item;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE, exporting.name + ".properties");
        startActivityForResult(intent, REQUEST_EXPORT_PROFILE);
    }

    private void onProfileResult(int requestCode, Uri uri) {
        try {
            if (requestCode == REQUEST_EXPORT_PROFILE) {
                if (exporting == null) return;
                try (InputStream in = new java.io.FileInputStream(exporting.configFile());
                     java.io.OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                    IOUtils.copy(in, out);
                }
                Toast.makeText(this, "Exported " + exporting.name, Toast.LENGTH_LONG).show();
                exporting = null;
            } else {
                String name = null;
                try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                    if (c != null && c.moveToFirst()) name = c.getString(0);
                }
                if (name == null) name = "Imported Profile";
                if (name.toLowerCase(java.util.Locale.ROOT).endsWith(".properties")) name = name.substring(0, name.length() - 11);
                String added;
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    added = RuneLiteProfiles.importProfile(name, in);
                }
                prefs.edit().putString(LauncherPreferences.PREF_KEY_RUNELITE_PROFILE, added).apply();
                loadProfiles();
                Toast.makeText(this, "Imported profile \"" + added + "\"", Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Tools.showError(this, e);
        }
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
        if ((requestCode == REQUEST_IMPORT_PROFILE || requestCode == REQUEST_EXPORT_PROFILE)
                && resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
            onProfileResult(requestCode, data.getData());
            return;
        }
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
