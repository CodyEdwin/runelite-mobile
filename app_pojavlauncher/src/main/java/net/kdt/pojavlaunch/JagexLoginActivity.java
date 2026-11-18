package net.kdt.pojavlaunch;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

/**
 * Android replacement for RuneLite's native JagexAuth helper (a GTK/WebKit2 program that cannot
 * run on Android). Performs the same two-step Jagex account OAuth flow in a WebView:
 *
 * <ol>
 *   <li>Authorization-code + PKCE login on account.jagex.com; the launcher-redirect page hands the
 *   code over through a {@code jagex:} URL, which is exchanged for tokens.</li>
 *   <li>A consent request with {@code id_token_hint} that redirects to {@code http://localhost/#...}
 *   with the game-session id_token.</li>
 * </ol>
 *
 * The resulting id_token is sent back to RuneLite (JagexAuthService in runelite-android.jar),
 * which waits on a loopback socket, as {@code "<secret> id_token: <jwt>"}.
 *
 * Endpoints/client ids mirror the strings in the bundled JagexAuth-Linux-* helper.
 */
public class JagexLoginActivity extends Activity {
    public static final String SCHEME = "runelite-jagexauth";
    private static final String TAG = "JagexLogin";

    private static final String AUTH_HOST = "https://account.jagex.com";
    private static final String LOGIN_CLIENT_ID = "1u76DALbWRovjgUGacSMeU";
    private static final String LOGIN_SCOPE = "openid offline gamesso.token.create user.profile.read";
    private static final String LOGIN_REDIRECT = "https://secure.runescape.com/m=weblogin/launcher-redirect";
    private static final String CONSENT_CLIENT_ID = "1fddee4e-b100-4f4e-b2b0-097f9088f9d2";
    private static final String CONSENT_SCOPE = "openid offline";
    private static final String CONSENT_REDIRECT = "http://localhost";

    private static final String EXTRA_PORT = "port";
    private static final String EXTRA_SECRET = "secret";

    private final SecureRandom mRandom = new SecureRandom();
    private WebView mWebView;
    private int mPort;
    private String mSecret;
    private String mCodeVerifier;
    private String mLoginState;
    private String mConsentState;
    private String mNonce;
    private boolean mExchanging;
    private volatile boolean mResultSent;

    /** Called from MainActivity#openLink for runelite-jagexauth://login?port=..&secret=.. */
    public static void start(Context context, Uri uri) {
        String port = uri.getQueryParameter(EXTRA_PORT);
        String secret = uri.getQueryParameter(EXTRA_SECRET);
        if (port == null || secret == null) {
            Log.w(TAG, "Ignoring malformed Jagex login request");
            return;
        }
        Intent intent = new Intent(context, JagexLoginActivity.class);
        intent.putExtra(EXTRA_PORT, Integer.parseInt(port));
        intent.putExtra(EXTRA_SECRET, secret);
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mPort = getIntent().getIntExtra(EXTRA_PORT, -1);
        mSecret = getIntent().getStringExtra(EXTRA_SECRET);
        if (mPort <= 0 || mSecret == null) {
            finish();
            return;
        }
        setTitle("Jagex Account");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // This activity runs in its own process; keep its WebView data separate from the
            // launcher's (Microsoft login) WebView, Android forbids sharing it across processes.
            try {
                WebView.setDataDirectorySuffix("jagexauth");
            } catch (IllegalStateException ignored) {
            }
        }

        mWebView = new WebView(this);
        WebSettings settings = mWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(mWebView, true);
        mWebView.setWebViewClient(new Client());
        setContentView(mWebView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mCodeVerifier = randomToken(48);
        mLoginState = randomToken(16);
        String url = AUTH_HOST + "/oauth2/auth?response_type=code"
                + "&client_id=" + encode(LOGIN_CLIENT_ID)
                + "&scope=" + encode(LOGIN_SCOPE)
                + "&redirect_uri=" + encode(LOGIN_REDIRECT)
                + "&code_challenge=" + encode(sha256Base64Url(mCodeVerifier))
                + "&code_challenge_method=S256"
                + "&prompt=login"
                + "&state=" + encode(mLoginState);
        mWebView.loadUrl(url);
    }

    private class Client extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return handleUrl(request.getUrl().toString());
        }

        @SuppressWarnings("deprecation")
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return handleUrl(url);
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            if (handleUrl(url)) view.stopLoading();
            else super.onPageStarted(view, url, favicon);
        }
    }

    /** @return true when the URL is one of our OAuth callbacks and must not be loaded. */
    private boolean handleUrl(String url) {
        if (url == null) return false;
        if (url.startsWith("jagex:")) {
            onLoginCallback(parseParams(url.substring("jagex:".length())));
            return true;
        }
        if (url.startsWith(LOGIN_REDIRECT + "?")) {
            onLoginCallback(parseParams(url.substring(url.indexOf('?') + 1)));
            return true;
        }
        if (url.startsWith(CONSENT_REDIRECT)) {
            int hash = url.indexOf('#');
            int query = url.indexOf('?');
            String params = hash >= 0 ? url.substring(hash + 1) : query >= 0 ? url.substring(query + 1) : "";
            onConsentCallback(parseParams(params));
            return true;
        }
        return false;
    }

    private void onLoginCallback(Map<String, String> params) {
        if (mExchanging) return;
        if (params.containsKey("error")) {
            fail("authorization failed: " + params.get("error"));
            return;
        }
        String code = params.get("code");
        if (code == null || !mLoginState.equals(params.get("state"))) {
            fail("OAuth state validation failed");
            return;
        }
        mExchanging = true;
        new Thread(() -> {
            try {
                String form = "grant_type=authorization_code"
                        + "&client_id=" + encode(LOGIN_CLIENT_ID)
                        + "&code=" + encode(code)
                        + "&code_verifier=" + encode(mCodeVerifier)
                        + "&redirect_uri=" + encode(LOGIN_REDIRECT);
                JSONObject token = new JSONObject(httpPost(AUTH_HOST + "/oauth2/token", form));
                String idToken = token.optString("id_token", null);
                if (idToken == null || idToken.isEmpty()) throw new Exception("Token response is missing ID token");

                mConsentState = randomToken(16);
                mNonce = randomToken(24);
                String consentUrl = AUTH_HOST + "/oauth2/auth?id_token_hint=" + encode(idToken)
                        + "&nonce=" + encode(mNonce)
                        + "&prompt=consent"
                        + "&redirect_uri=" + encode(CONSENT_REDIRECT)
                        + "&response_type=" + encode("id_token code")
                        + "&state=" + encode(mConsentState)
                        + "&client_id=" + encode(CONSENT_CLIENT_ID)
                        + "&scope=" + encode(CONSENT_SCOPE);
                runOnUiThread(() -> {
                    if (mWebView != null) mWebView.loadUrl(consentUrl);
                });
            } catch (Exception e) {
                Log.e(TAG, "Token exchange failed", e);
                runOnUiThread(() -> fail("token exchange failed: " + e.getMessage()));
            }
        }, "JagexTokenExchange").start();
    }

    private void onConsentCallback(Map<String, String> params) {
        if (mConsentState == null) {
            fail("Consent callback arrived before an authorization request");
            return;
        }
        if (params.containsKey("error")) {
            fail("consent failed: " + params.get("error"));
            return;
        }
        String idToken = params.get("id_token");
        if (idToken == null || !mConsentState.equals(params.get("state"))) {
            fail("Consent state validation failed");
            return;
        }
        if (!mNonce.equals(jwtClaim(idToken, "nonce"))) {
            fail("ID token nonce validation failed");
            return;
        }
        sendResult("id_token: " + idToken);
        finish();
    }

    private void fail(String message) {
        Log.e(TAG, message);
        Toast.makeText(this, "Jagex login failed: " + message, Toast.LENGTH_LONG).show();
        sendResult("error " + message);
        finish();
    }

    @Override
    protected void onDestroy() {
        // Closing the activity (back button) cancels the login on the RuneLite side as well.
        sendResult("error cancelled");
        if (mWebView != null) {
            mWebView.destroy();
            mWebView = null;
        }
        super.onDestroy();
    }

    private synchronized void sendResult(String payload) {
        if (mResultSent || mSecret == null) return;
        mResultSent = true;
        final String line = mSecret + " " + payload.replace('\n', ' ') + "\n";
        new Thread(() -> {
            try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), mPort);
                 OutputStream out = socket.getOutputStream()) {
                out.write(line.getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (Exception e) {
                Log.e(TAG, "Unable to hand the Jagex login result back to RuneLite", e);
            }
        }, "JagexLoginResult").start();
    }

    private static String httpPost(String url, String form) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            connection.setRequestProperty("User-Agent", "RuneLite-Jagex-Auth/1.0");
            try (OutputStreamWriter writer = new OutputStreamWriter(connection.getOutputStream(), StandardCharsets.UTF_8)) {
                writer.write(form);
            }
            int status = connection.getResponseCode();
            InputStream in = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            if (in != null) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = in.read(buffer)) != -1) body.write(buffer, 0, read);
                in.close();
            }
            String text = body.toString("UTF-8");
            if (status < 200 || status >= 300) throw new Exception("HTTP " + status + ": " + text);
            return text;
        } finally {
            connection.disconnect();
        }
    }

    private static Map<String, String> parseParams(String params) {
        Map<String, String> result = new HashMap<>();
        // launcher-redirect delivers "jagex:code=..,state=..,intent=.."; query strings use '&'.
        for (String pair : params.split("[&,]")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            result.put(Uri.decode(pair.substring(0, eq)), Uri.decode(pair.substring(eq + 1)));
        }
        return result;
    }

    private static String jwtClaim(String jwt, String claim) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) return null;
            byte[] payload = Base64.decode(parts[1], Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
            return new JSONObject(new String(payload, StandardCharsets.UTF_8)).optString(claim, null);
        } catch (Exception e) {
            return null;
        }
    }

    private String randomToken(int bytes) {
        byte[] data = new byte[bytes];
        mRandom.nextBytes(data);
        return Base64.encodeToString(data, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    private static String sha256Base64Url(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
            return Base64.encodeToString(digest, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
