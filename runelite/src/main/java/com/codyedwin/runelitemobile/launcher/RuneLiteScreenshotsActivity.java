package com.codyedwin.runelitemobile.launcher;

import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.codyedwin.runelitemobile.ScreenshotBackup;
import com.codyedwin.runelitemobile.Tools;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Gallery for the screenshots RuneLite saves in ~/.runelite/screenshots (per character / category). */
public class RuneLiteScreenshotsActivity extends AppCompatActivity {
    private final List<File> mFiles = new ArrayList<>();
    private final ExecutorService mDecoder = Executors.newFixedThreadPool(2);
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> mThumbs = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private BaseAdapter mAdapter;
    private TextView mEmpty;
    private int mThumbSize;

    public static File screenshotDir() {
        return new File(Tools.getRuneLiteDir(), ".runelite/screenshots");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Screenshots");
        mThumbSize = dp(160);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF1B1B1B);
        GridView grid = new GridView(this);
        grid.setColumnWidth(mThumbSize);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(dp(6));
        grid.setVerticalSpacing(dp(6));
        grid.setPadding(dp(8), dp(8), dp(8), dp(8));
        mAdapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return mFiles.size();
            }

            @Override
            public Object getItem(int position) {
                return mFiles.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                LinearLayout cell = (LinearLayout) convertView;
                if (cell == null) {
                    cell = new LinearLayout(RuneLiteScreenshotsActivity.this);
                    cell.setOrientation(LinearLayout.VERTICAL);
                    ImageView img = new ImageView(RuneLiteScreenshotsActivity.this);
                    img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    img.setBackgroundColor(0xFF2A2A2A);
                    cell.addView(img, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, mThumbSize * 9 / 16));
                    TextView label = new TextView(RuneLiteScreenshotsActivity.this);
                    label.setTextColor(0xFFCCCCCC);
                    label.setTextSize(11);
                    label.setSingleLine(true);
                    label.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                    cell.addView(label);
                }
                File f = mFiles.get(position);
                ImageView img = (ImageView) cell.getChildAt(0);
                ((TextView) cell.getChildAt(1)).setText(caption(f));
                img.setTag(f.getPath());
                Bitmap cached = mThumbs.get(f.getPath());
                img.setImageBitmap(cached);
                if (cached == null) {
                    mDecoder.execute(() -> {
                        Bitmap b = decode(f, mThumbSize);
                        if (b == null) return;
                        mThumbs.put(f.getPath(), b);
                        mMain.post(() -> {
                            if (f.getPath().equals(img.getTag())) img.setImageBitmap(b);
                        });
                    });
                }
                return cell;
            }
        };
        grid.setAdapter(mAdapter);
        grid.setOnItemClickListener((p, v, position, id) -> open(mFiles.get(position)));
        root.addView(grid);
        mEmpty = new TextView(this);
        mEmpty.setTextColor(0xFFAAAAAA);
        mEmpty.setGravity(Gravity.CENTER);
        mEmpty.setPadding(dp(24), dp(24), dp(24), dp(24));
        mEmpty.setText("No screenshots yet.\nRuneLite's Screenshot plugin saves them to\n" + screenshotDir()
                + "\n\nWith All files access on, the app also copies them to " + ScreenshotBackup.backupDir());
        root.addView(mEmpty, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        reload();
        mDecoder.execute(() -> {
            ScreenshotBackup.syncNow();
            mMain.post(this::reload);
        });
    }

    @Override
    protected void onDestroy() {
        mDecoder.shutdownNow();
        super.onDestroy();
    }

    private void reload() {
        mFiles.clear();
        collect(screenshotDir(), mFiles);
        java.util.Set<String> have = new java.util.HashSet<>();
        for (File f : mFiles) have.add(relative(f));
        List<File> backups = new ArrayList<>();
        collect(ScreenshotBackup.backupDir(), backups);
        for (File f : backups) if (have.add(relative(f))) mFiles.add(f);
        mFiles.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        mEmpty.setVisibility(mFiles.isEmpty() ? View.VISIBLE : View.GONE);
        setTitle("Screenshots (" + mFiles.size() + ")");
        mAdapter.notifyDataSetChanged();
    }

    private static void collect(File dir, List<File> out) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) collect(f, out);
            else {
                String n = f.getName().toLowerCase(java.util.Locale.ROOT);
                if (n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg")) out.add(f);
            }
        }
    }

    /** "Character / Category" from ~/.runelite/screenshots/<character>/<category>/file.png */
    private static String caption(File f) {
        String rel = relative(f);
        int slash = rel.lastIndexOf('/');
        return slash < 0 ? f.getName() : rel.substring(0, slash).replace("/", " / ");
    }

    /** Path below the screenshots root (app folder or the /sdcard backup). */
    private static String relative(File f) {
        String path = f.getAbsolutePath();
        for (File root : new File[]{screenshotDir(), ScreenshotBackup.backupDir()}) {
            String r = root.getAbsolutePath() + "/";
            if (path.startsWith(r)) return path.substring(r.length());
        }
        return f.getName();
    }

    private static Bitmap decode(File f, int target) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= target && o.outHeight / (sample * 2) >= target * 9 / 16) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    private void open(File f) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(0xFF000000);
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        box.addView(img, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        TextView info = new TextView(this);
        info.setTextColor(0xFFCCCCCC);
        info.setPadding(dp(8), dp(4), dp(8), 0);
        info.setText(caption(f) + "\n" + f.getName() + " · " + DateFormat.getDateTimeInstance().format(new Date(f.lastModified())));
        box.addView(info);
        LinearLayout buttons = new LinearLayout(this);
        box.addView(buttons);
        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen).setView(box).create();
        buttons.addView(button("Share", v -> Tools.openPath(this, f, true)));
        buttons.addView(button("Save to Pictures", v -> saveToPictures(f)));
        buttons.addView(button("Delete", v -> new AlertDialog.Builder(this)
                .setMessage("Delete " + f.getName() + "?")
                .setPositiveButton("Delete", (d, w) -> {
                    File backup = new File(ScreenshotBackup.backupDir(), relative(f));
                    File app = new File(screenshotDir(), relative(f));
                    backup.delete();
                    app.delete();
                    if (!f.exists()) {
                        mThumbs.remove(f.getPath());
                        dialog.dismiss();
                        reload();
                    } else {
                        Toast.makeText(this, "Could not delete " + f.getName(), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show()));
        buttons.addView(button("Close", v -> dialog.dismiss()));
        dialog.show();
        int max = Math.max(getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels);
        mDecoder.execute(() -> {
            Bitmap b = decode(f, max);
            mMain.post(() -> img.setImageBitmap(b));
        });
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return b;
    }

    /** Copies the screenshot to Pictures/RuneLite so it shows up in the phone's gallery apps. */
    private void saveToPictures(File f) {
        String mime = f.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".png") ? "image/png" : "image/jpeg";
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Images.Media.DISPLAY_NAME, f.getName());
                v.put(MediaStore.Images.Media.MIME_TYPE, mime);
                v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/RuneLite");
                Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                if (uri == null) throw new java.io.IOException("MediaStore insert failed");
                try (InputStream in = new FileInputStream(f); OutputStream out = getContentResolver().openOutputStream(uri)) {
                    copy(in, out);
                }
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "RuneLite");
                dir.mkdirs();
                File dst = new File(dir, f.getName());
                try (InputStream in = new FileInputStream(f); OutputStream out = new java.io.FileOutputStream(dst)) {
                    copy(in, out);
                }
                android.media.MediaScannerConnection.scanFile(this, new String[]{dst.getPath()}, new String[]{mime}, null);
            }
            Toast.makeText(this, "Saved to Pictures/RuneLite", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Save failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static void copy(InputStream in, OutputStream out) throws java.io.IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
