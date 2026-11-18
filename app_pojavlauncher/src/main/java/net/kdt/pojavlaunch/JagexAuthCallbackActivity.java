package net.kdt.pojavlaunch;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/**
 * Receives the {@code jagex:code=...,state=...} URL that Jagex's launcher-redirect page opens after
 * a login done in Chrome (JagexLoginActivity's Custom Tab fallback) and hands it back to the
 * waiting JagexLoginActivity, closing the browser tab above it. Also receives the consent step's
 * {@code http://localhost/#code=...&id_token=...} redirect: Chrome hands it to this app when the
 * user approved "localhost" under the app's "Open supported links" (Android 12+ requires that for
 * unverified web links), or when the page is shared to the app.
 */
public class JagexAuthCallbackActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Uri data = getIntent() == null ? null : getIntent().getData();
        if (data == null && getIntent() != null && Intent.ACTION_SEND.equals(getIntent().getAction())) {
            // Manual fallback: "Share" the http://localhost/#... page from Chrome to RuneLite.
            String text = getIntent().getStringExtra(Intent.EXTRA_TEXT);
            int start = text == null ? -1 : text.indexOf("http://localhost");
            if (start >= 0) data = Uri.parse(text.substring(start).split("\\s")[0]);
        }
        if (data != null) {
            Intent forward = new Intent(this, JagexLoginActivity.class);
            forward.setData(data);
            forward.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(forward);
        }
        finish();
    }
}
