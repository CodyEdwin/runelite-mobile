package net.kdt.pojavlaunch;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.Keep;

import java.io.File;

/**
 * Static callbacks the JVM side (awt_bridge.c, CallbackBridge) uses to reach Android:
 * Desktop#browse/open and the system clipboard. RuneLite runs in JavaGUILauncherActivity.
 */
@Keep
public final class AwtHost {
    public static volatile ClipboardManager GLOBAL_CLIPBOARD;

    private AwtHost() {
    }

    @Keep
    public static void openLink(String link) {
        Activity activity = JavaGUILauncherActivity.getRunningInstance();
        if (activity == null) {
            Log.w("AwtHost", "openLink: no activity to open " + link);
            return;
        }
        activity.runOnUiThread(() -> {
            try {
                Uri uri = Uri.parse(link);
                if (JagexLoginActivity.SCHEME.equals(uri.getScheme())) {
                    JagexLoginActivity.start(activity, uri);
                } else if (link.startsWith("file:")) {
                    int truncLength = link.startsWith("file://") ? 7 : 5;
                    Tools.openPath(activity, new File(link.substring(truncLength)), false);
                } else if ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) {
                    // Browsers do not match ACTION_VIEW intents carrying a "*/*" MIME type.
                    Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                    intent.addCategory(Intent.CATEGORY_BROWSABLE);
                    activity.startActivity(intent);
                } else {
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setDataAndType(uri, "*/*");
                    activity.startActivity(intent);
                }
            } catch (Throwable th) {
                Tools.showError(activity, th);
            }
        });
    }

    @Keep
    public static void querySystemClipboard() {
        Tools.runOnUiThread(() -> {
            ClipData clipData = GLOBAL_CLIPBOARD == null ? null : GLOBAL_CLIPBOARD.getPrimaryClip();
            CharSequence text = clipData == null ? null : clipData.getItemAt(0).getText();
            if (text == null) {
                AWTInputBridge.nativeClipboardReceived(null, null);
            } else {
                AWTInputBridge.nativeClipboardReceived(text.toString(), "plain");
            }
        });
    }

    @Keep
    public static void putClipboardData(String data, String mimeType) {
        Tools.runOnUiThread(() -> {
            ClipData clipData = null;
            if ("text/plain".equals(mimeType)) clipData = ClipData.newPlainText("AWT Paste", data);
            else if ("text/html".equals(mimeType)) clipData = ClipData.newHtmlText("AWT Paste", data, data);
            if (clipData != null && GLOBAL_CLIPBOARD != null) GLOBAL_CLIPBOARD.setPrimaryClip(clipData);
        });
    }
}
