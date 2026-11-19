package com.codyedwin.runelitemobile.launcher;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.core.content.ContextCompat;

import com.codyedwin.runelitemobile.R;

/**
 * Animated RuneLite Mobile loading screen: the purple R logo pulsing with a glow, a rotating
 * gold/purple rune ring around it, a status line and a thin progress bar. One lightweight custom
 * View drawn with Canvas (no extra dependencies); it consumes touches while visible.
 */
public class RuneLoadingView extends View {
    private static final String[] RUNES = {"ᚱ", "ᚢ", "ᚾ", "ᛖ", "ᛚ", "ᛁ", "ᛏ", "ᛖ", "ᛗ", "ᛟ", "ᛒ", "ᛁ"};
    private final Drawable logo;
    private final Paint bg = new Paint();
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring2 = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rune = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint log = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final int LOG_LINES = 5;
    private final java.util.ArrayDeque<String> logLines = new java.util.ArrayDeque<>();
    private static final java.util.regex.Pattern LOGBACK = java.util.regex.Pattern.compile(
            "^\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d(?:[.,]\\d+)? ?\\S* \\[[^\\]]*\\] (\\w+)\\s+\\S+ - (.*)$");
    private final Paint barBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final android.graphics.Path clip = new android.graphics.Path();
    private final long start = SystemClock.uptimeMillis();
    private final float density;
    private volatile String status = "";
    private volatile float progress = -1f; // <0: indeterminate
    private float shownProgress;
    private int lastW, lastH;

    public RuneLoadingView(Context ctx) {
        super(ctx);
        density = ctx.getResources().getDisplayMetrics().density;
        logo = ContextCompat.getDrawable(ctx, R.drawable.runelite_mobile_logo);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeCap(Paint.Cap.ROUND);
        ring2.setStyle(Paint.Style.STROKE);
        ring2.setStrokeCap(Paint.Cap.ROUND);
        rune.setColor(0xFFE8C15A);
        rune.setTextAlign(Paint.Align.CENTER);
        text.setColor(0xFFE6DDF5);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        text.setTextSize(15 * density);
        barBg.setColor(0x33FFFFFF);
        log.setTextAlign(Paint.Align.CENTER);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextSize(11 * density);
        setClickable(true);
        setFocusable(true);
    }

    public void setStatus(String s, float p) {
        s = s == null ? "" : s;
        if (!s.equals(status) && !s.isEmpty()) addLog("» " + s);
        status = s;
        progress = p;
        postInvalidateOnAnimation();
    }

    /** Feed raw JVM/launcher output (may hold several lines); shown live under the progress bar. */
    public void addLog(String chunk) {
        if (chunk == null) return;
        boolean added = false;
        for (String raw : chunk.split("\r?\n")) {
            String line = clean(raw);
            if (line == null) continue;
            synchronized (logLines) {
                logLines.addLast(line);
                while (logLines.size() > LOG_LINES) logLines.removeFirst();
            }
            added = true;
        }
        if (added) postInvalidateOnAnimation();
    }

    static String clean(String raw) {
        String s = raw.replace('\t', ' ').trim();
        if (s.isEmpty() || s.startsWith("at ") || s.startsWith("... ") || s.startsWith("Caused by") && s.length() > 200) return null;
        java.util.regex.Matcher m = LOGBACK.matcher(s);
        if (m.matches()) {
            String level = m.group(1), msg = m.group(2).trim();
            s = ("WARN".equals(level) || "ERROR".equals(level)) ? level + ": " + msg : msg;
        }
        s = PATH.matcher(s).replaceAll("$1"); // /storage/.../sideloaded-plugins/foo.jar -> foo.jar
        return s.isEmpty() ? null : s;
    }

    private static final java.util.regex.Pattern PATH = java.util.regex.Pattern.compile("(?:/[^\\s/:]+)+/([^\\s/:]+)");

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w != lastW || h != lastH) {
            lastW = w;
            lastH = h;
            bg.setShader(new RadialGradient(w / 2f, h * 0.33f, Math.max(w, h) * 0.75f,
                    0xFF2A0F45, 0xFF050208, Shader.TileMode.CLAMP));
        }
        c.drawRect(0, 0, w, h, bg);
        float t = (SystemClock.uptimeMillis() - start) / 1000f;
        float cx = w / 2f, cy = h * 0.33f;
        float base = Math.min(w, h) * 0.155f;
        float pulse = 0.5f + 0.5f * (float) Math.sin(t * 2.6f);

        // Glow behind the logo.
        float gr = base * (1.55f + 0.2f * pulse);
        glow.setShader(new RadialGradient(cx, cy, gr, Color.argb((int) (120 + 90 * pulse), 170, 70, 255),
                Color.TRANSPARENT, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, gr, glow);

        // Rotating rune ring: gold/purple sweep arcs + rune glyphs.
        float rr = base * 1.32f;
        oval.set(cx - rr, cy - rr, cx + rr, cy + rr);
        ring.setStrokeWidth(3.5f * density);
        ring.setShader(new SweepGradient(cx, cy, new int[]{0xFFE8C15A, 0xFF9B4DFF, 0xFFE8C15A, 0xFF9B4DFF, 0xFFE8C15A}, null));
        c.save();
        c.rotate(t * 50f, cx, cy);
        for (int i = 0; i < 4; i++) c.drawArc(oval, i * 90f + 8f, 74f, false, ring);
        rune.setTextSize(base * 0.24f);
        float rt = rr + base * 0.2f;
        for (int i = 0; i < RUNES.length; i++) {
            double a = Math.toRadians(i * 360.0 / RUNES.length);
            float x = cx + (float) Math.cos(a) * rt, y = cy + (float) Math.sin(a) * rt;
            rune.setAlpha((int) (140 + 115 * (0.5f + 0.5f * Math.sin(t * 3f + i))));
            c.drawText(RUNES[i], x, y + rune.getTextSize() * 0.35f, rune);
        }
        c.restore();
        float rr2 = base * 1.12f;
        oval.set(cx - rr2, cy - rr2, cx + rr2, cy + rr2);
        ring2.setStrokeWidth(1.5f * density);
        ring2.setColor(0xAA9B4DFF);
        c.save();
        c.rotate(-t * 80f, cx, cy);
        for (int i = 0; i < 6; i++) c.drawArc(oval, i * 60f, 34f, false, ring2);
        c.restore();

        // Logo with a gentle scale pulse.
        if (logo != null) {
            float s = base * (1.12f + 0.07f * pulse);
            logo.setBounds((int) (cx - s), (int) (cy - s), (int) (cx + s), (int) (cy + s));
            clip.reset();
            clip.addCircle(cx, cy, s * 0.97f, android.graphics.Path.Direction.CW);
            c.save();
            c.clipPath(clip);
            logo.draw(c);
            c.restore();
        }

        // Status line + progress bar.
        float ty = cy + rt + base * 0.65f;
        c.drawText(status, cx, ty, text);
        float bw = Math.min(w * 0.42f, 360 * density), bh = 3 * density, by = ty + 14 * density;
        float bx = cx - bw / 2f;
        c.drawRoundRect(bx, by, bx + bw, by + bh, bh, bh, barBg);
        float p = progress;
        if (p >= 0) {
            shownProgress += (Math.min(1f, p) - shownProgress) * 0.12f;
            bar.setShader(new LinearGradient(bx, 0, bx + bw, 0, 0xFF9B4DFF, 0xFFE8C15A, Shader.TileMode.CLAMP));
            c.drawRoundRect(bx, by, bx + bw * shownProgress, by + bh, bh, bh, bar);
        } else {
            float seg = bw * 0.25f, pos = ((t * 0.7f) % 1f) * (bw + seg) - seg;
            float l = Math.max(bx, bx + pos), r = Math.min(bx + bw, bx + pos + seg);
            bar.setShader(new LinearGradient(l, 0, r, 0, 0xFF9B4DFF, 0xFFE8C15A, Shader.TileMode.CLAMP));
            if (r > l) c.drawRoundRect(l, by, r, by + bh, bh, bh, bar);
        }
        // Live RuneLite / launcher log: newest line bright at the bottom, older ones fading out above it.
        String[] lines;
        synchronized (logLines) {
            lines = logLines.toArray(new String[0]);
        }
        float lh = log.getTextSize() * 1.35f;
        float maxW = w * 0.86f;
        float ly = by + bh + 12 * density + log.getTextSize();
        for (int i = 0; i < lines.length; i++) {
            boolean newest = i == lines.length - 1;
            int age = lines.length - 1 - i;
            log.setColor(newest ? 0xFFF3E9FF : 0xFFB9A7D6);
            log.setAlpha(newest ? 255 : Math.max(50, 170 - age * 30));
            String l = lines[i];
            if (log.measureText(l) > maxW) {
                int n = log.breakText(l, true, maxW - log.measureText("…"), null);
                l = l.substring(0, Math.max(0, n)) + "…";
            }
            c.drawText(l, cx, ly + i * lh, log);
        }
        if (getVisibility() == VISIBLE && getAlpha() > 0f) postInvalidateOnAnimation();
    }

    /** Adds a full-screen loading view on top of the activity's content. */
    public static RuneLoadingView attach(Activity activity, String status, float progress) {
        RuneLoadingView v = new RuneLoadingView(activity);
        v.setStatus(status, progress);
        ((ViewGroup) activity.findViewById(android.R.id.content)).addView(v,
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        v.setElevation(1000f);
        return v;
    }

    /** Fades out and removes the view. Safe from any thread. */
    public void dismiss() {
        post(() -> animate().alpha(0f).setDuration(450).withEndAction(() -> {
            setVisibility(GONE);
            ViewGroup p = (ViewGroup) getParent();
            if (p != null) p.removeView(this);
        }).start());
    }
}
