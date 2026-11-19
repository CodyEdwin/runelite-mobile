package com.codyedwin.runelitemobile.launcher;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import com.codyedwin.runelitemobile.Tools;
import com.codyedwin.runelitemobile.prefs.LauncherPreferences;

/** Settings that matter for RuneLite: the Java heap size (-Xms/-Xmx). */
public class RuneLiteMobileSettingsActivity extends AppCompatActivity {
    private static final int MIN_RAM = 512;
    private static final int STEP = 8;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("RuneLite Mobile settings");
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        int pad = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24, getResources().getDisplayMetrics());
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(0xFF1B1B1B);

        int deviceRam = Tools.getTotalDeviceMemory(this);
        int max = Math.max(MIN_RAM + STEP, Math.min(com.codyedwin.runelitemobile.utils.JREUtils.safeMaxHeapMb(this), 4096));
        int current = Math.max(MIN_RAM, Math.min(max, prefs.getInt("allocation", LauncherPreferences.PREF_RAM_ALLOCATION)));

        TextView title = new TextView(this);
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setText("Memory for RuneLite (Java heap)");
        root.addView(title);
        TextView value = new TextView(this);
        value.setTextColor(0xFFDC8A00);
        value.setTextSize(24);
        root.addView(value);
        SeekBar bar = new SeekBar(this);
        bar.setMax((max - MIN_RAM) / STEP);
        bar.setProgress((current - MIN_RAM) / STEP);
        root.addView(bar);
        TextView hint = new TextView(this);
        hint.setTextColor(0xFFAAAAAA);
        hint.setText("Device memory: " + deviceRam + " MB. Applied as the maximum Java heap (-Xmx) the next time you press PLAY; the heap starts small and only grows as needed. "
                + "More is not always faster: leave room for Android (about 1 GB+), or the app may be killed in the background.");
        root.addView(hint);
        value.setText(current + " MB");
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int mb = MIN_RAM + progress * STEP;
                value.setText(mb + " MB");
                if (fromUser) {
                    prefs.edit().putInt("allocation", mb).apply();
                    LauncherPreferences.PREF_RAM_ALLOCATION = mb;
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        setContentView(root);
    }
}
