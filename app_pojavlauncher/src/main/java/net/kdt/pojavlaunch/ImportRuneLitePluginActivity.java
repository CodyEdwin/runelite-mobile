package net.kdt.pojavlaunch;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * "Open with" / "Share" target for RuneLite plugin jars: copies them into the sideloaded-plugins
 * folder (see {@link Tools#getRuneLiteSideloadDir()}); RuneLite loads them on its next start.
 */
public class ImportRuneLitePluginActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        List<Uri> uris = new ArrayList<>();
        Intent intent = getIntent();
        if (intent != null) {
            if (intent.getData() != null) uris.add(intent.getData());
            Uri stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (stream != null && !uris.contains(stream)) uris.add(stream);
            ArrayList<Uri> streams = Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())
                    ? intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM) : null;
            if (streams != null) for (Uri u : streams) if (!uris.contains(u)) uris.add(u);
            ClipData clip = intent.getClipData();
            if (clip != null) for (int i = 0; i < clip.getItemCount(); i++) {
                Uri u = clip.getItemAt(i).getUri();
                if (u != null && !uris.contains(u)) uris.add(u);
            }
        }
        int imported = 0;
        StringBuilder errors = new StringBuilder();
        try {
            Tools.initStorageConstants(getApplicationContext());
            File dir = Tools.getRuneLiteSideloadDir();
            net.kdt.pojavlaunch.utils.FileUtils.ensureDirectory(dir);
            for (Uri uri : uris) {
                String name = null;
                try { name = Tools.getFileName(this, uri); } catch (Exception ignored) { }
                if (name == null || name.isEmpty()) name = uri.getLastPathSegment();
                if (name == null || name.isEmpty()) name = "plugin";
                name = new File(name).getName().replaceAll("[^A-Za-z0-9._ -]", "_");
                if (!name.toLowerCase().endsWith(".jar")) name += ".jar";
                try (InputStream in = getContentResolver().openInputStream(uri);
                     OutputStream out = new FileOutputStream(new File(dir, name))) {
                    if (in == null) throw new java.io.IOException("unreadable");
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    imported++;
                } catch (Exception e) {
                    errors.append(name).append(": ").append(e.getMessage()).append('\n');
                }
            }
        } catch (Exception e) {
            errors.append(e.getMessage());
        }
        String msg = imported > 0
                ? "Imported " + imported + " RuneLite plugin jar(s). Restart RuneLite to load them."
                : "No plugin imported. " + errors;
        Toast.makeText(this, msg.trim(), Toast.LENGTH_LONG).show();
        finish();
    }
}
