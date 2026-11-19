package com.codyedwin.runelitemobile.launcher;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import com.codyedwin.runelitemobile.Tools;
import com.codyedwin.runelitemobile.prefs.LauncherPreferences;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

/** Per-jar on/off switches for the sideloadable plugins (bundled in the APK + the user's folder). */
public class RuneLitePluginsActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Sideloaded plugins");
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(16), dp(24), dp(16));
        root.setBackgroundColor(0xFF1B1B1B);

        TextView hint = new TextView(this);
        hint.setTextColor(0xFFAAAAAA);
        boolean master = prefs.getBoolean(LauncherPreferences.PREF_KEY_RUNELITE_SIDELOADED, true);
        hint.setText((master ? "" : "'Load sideloaded plugins' is OFF in the launcher, so none of these load right now.\n")
                + "Changes apply the next time you press PLAY. Turning a plugin off only skips it; nothing is deleted.");
        hint.setPadding(0, 0, 0, dp(12));
        root.addView(hint);

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // display name -> [file name, source]
        TreeMap<String, String[]> plugins = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String name : Tools.listBundledRuneLitePlugins(this)) plugins.put(friendly(name) + "\u0000" + name, new String[]{name, "Bundled"});
        File[] user = Tools.getRuneLiteSideloadDir().listFiles((d, n) -> n.endsWith(".jar"));
        if (user != null) for (File f : user) {
            if (Tools.isKnownBrokenRuneLitePlugin(f.getName())) continue;
            plugins.put(friendly(f.getName()) + "\u0000" + f.getName(), new String[]{f.getName(), "Added by you"});
        }
        // a user jar with a bundled jar's file name replaces it: keep one row per file name
        java.util.Map<String, String[]> byFile = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, String[]> e : plugins.entrySet()) {
            String[] v = e.getValue();
            String[] old = byFile.get(v[0]);
            byFile.put(v[0], old == null ? v : new String[]{v[0], "Added by you (replaces bundled)"});
        }
        if (byFile.isEmpty()) {
            TextView none = new TextView(this);
            none.setTextColor(Color.WHITE);
            none.setText("No sideloadable plugins found.");
            list.addView(none);
        }
        for (String[] v : byFile.values()) list.addView(row(prefs, v[0], v[1]));
        setContentView(root);
    }

    private android.view.View row(SharedPreferences prefs, String file, String source) {
        String key = Tools.runeLitePluginOffKey(file);
        TapSwitch s = new TapSwitch(this);
        s.setText(friendly(file) + "\n" + source + " · " + file);
        s.setTextColor(Color.WHITE);
        s.setTextSize(15);
        s.setPadding(dp(16), dp(6), dp(16), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF2B2B2B);
        bg.setCornerRadius(dp(8));
        s.setBackground(bg);
        s.setChecked(!prefs.getBoolean(key, false));
        s.setOnCheckedChangeListener((b, on) -> {
            boolean ok = prefs.edit().putBoolean(key, !on).commit();
            if (!ok || prefs.getBoolean(key, false) == on)
                Toast.makeText(this, "Could not save the setting for " + friendly(file), Toast.LENGTH_LONG).show();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        s.setMinHeight(dp(56));
        s.setLayoutParams(lp);
        return s;
    }

    /** "quest-helper_CIIM2N...BU.jar" -> "Quest Helper" (Plugin Hub jars carry a hash suffix). */
    static String friendly(String file) {
        String n = file.endsWith(".jar") ? file.substring(0, file.length() - 4) : file;
        n = n.replaceFirst("_[A-Za-z0-9_-]{40,}$", "");
        List<String> words = new ArrayList<>();
        for (String w : n.split("[-_\\s]+")) {
            if (w.isEmpty()) continue;
            words.add(w.substring(0, 1).toUpperCase(Locale.ROOT) + w.substring(1));
        }
        return words.isEmpty() ? file : String.join(" ", words);
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
