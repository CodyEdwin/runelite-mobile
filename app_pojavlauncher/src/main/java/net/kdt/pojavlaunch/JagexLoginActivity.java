package net.kdt.pojavlaunch;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.graphics.Color;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.webkit.UserAgentMetadata;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewFeature;

import java.util.Collections;
import java.util.Arrays;

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
    private static final String EXTRA_BROWSER = "browser";

    private final SecureRandom mRandom = new SecureRandom();
    private WebView mWebView;
    private int mPort;
    private String mSecret;
    private String mCodeVerifier;
    private String mLoginState;
    private String mConsentState;
    private String mNonce;
    private boolean mExchanging;
    private boolean mBrowserLogin;
    private String mLoginUrl;
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
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(savedInstanceState);
        mPort = getIntent().getIntExtra(EXTRA_PORT, -1);
        mSecret = getIntent().getStringExtra(EXTRA_SECRET);
        if (mPort <= 0 || mSecret == null) {
            finish();
            return;
        }
        setTitle("RuneLite Jagex Account Authentication");

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
        configureBrowserIdentity(settings);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(mWebView, true);
        mWebView.setWebViewClient(new Client());
        mWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage message) {
                Log.i(TAG, "console: " + message.message() + " (" + message.sourceId() + ":" + message.lineNumber() + ")");
                return true;
            }
        });
        setContentView(buildPopup(mWebView));

        if (savedInstanceState != null && savedInstanceState.getString("verifier") != null) {
            // Android killed this (:jagexauth) process while Chrome was in front; the Chrome login
            // continues and the jagex: callback arrives through onNewIntent after this restore.
            mCodeVerifier = savedInstanceState.getString("verifier");
            mLoginState = savedInstanceState.getString("loginState");
            mConsentState = savedInstanceState.getString("consentState");
            mNonce = savedInstanceState.getString("nonce");
            mBrowserLogin = savedInstanceState.getBoolean("browser");
            mLoginUrl = savedInstanceState.getString("loginUrl");
            Log.i(TAG, "Restored login state after process death");
            mWebView.loadDataWithBaseURL(null, WAITING_HTML, "text/html", "UTF-8", null);
            return;
        }
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
        mLoginUrl = url;
        if (USE_WEBVIEW || getIntent().getBooleanExtra(EXTRA_WEBVIEW, false)) {
            mWebView.loadUrl(url);
        } else {
            // account.jagex.com's Cloudflare WAF blocks Android WebViews on the credential POST
            // ("Sorry, you have been blocked") whatever the UA/headers, so log in with Chrome.
            mWebView.loadDataWithBaseURL(null, WAITING_HTML, "text/html", "UTF-8", null);
            openLoginInBrowser();
        }
    }

    private static final boolean USE_WEBVIEW = false;
    private static final String EXTRA_WEBVIEW = "webview";
    private static final String WAITING_HTML = "<html><body style='background:#1e1e1e;color:#dcdcdc;"
            + "font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:90vh'>"
            + "<div style='text-align:center'><h3 style='color:#ff981f'>Log in with your Jagex account in Chrome</h3>"
            + "<p>After the login, when Chrome shows <i>localhost refused to connect</i>, tap the green "
            + "&#10003; in Chrome's toolbar (or Share &rarr; RuneLite Jagex login) to return here.</p></div></body></html>";


    /**
     * Sized like the desktop helper's 800x700-ish WebKitGTK window: a floating popup over the
     * RuneLite screen (the activity uses a dialog theme, so RuneLite stays visible behind it),
     * with a desktop-style title bar and close button.
     */
    private ViewGroup buildPopup(WebView webView) {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        Window window = getWindow();
        window.setLayout((int) (metrics.widthPixels * 0.92f), (int) (metrics.heightPixels * 0.94f));
        window.setGravity(Gravity.CENTER);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(0.5f);

        float density = metrics.density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF1E1E1E);

        LinearLayout titleBar = new LinearLayout(this);
        titleBar.setOrientation(LinearLayout.HORIZONTAL);
        titleBar.setGravity(Gravity.CENTER_VERTICAL);
        titleBar.setBackgroundColor(0xFF2B2B2B);
        int pad = (int) (8 * density);
        titleBar.setPadding(pad * 2, 0, 0, 0);

        TextView title = new TextView(this);
        title.setText("RuneLite Jagex Account Authentication");
        title.setTextColor(0xFFDCDCDC);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        title.setSingleLine(true);
        titleBar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView chrome = new TextView(this);
        chrome.setText("Open in Chrome");
        chrome.setTextColor(0xFFFF981F);
        chrome.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        chrome.setGravity(Gravity.CENTER);
        chrome.setPadding(pad * 2, pad, pad * 2, pad);
        chrome.setOnClickListener(v -> openLoginInBrowser());
        titleBar.addView(chrome, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, (int) (36 * density)));

        TextView close = new TextView(this);
        close.setText("\u2715");
        close.setTextColor(Color.WHITE);
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        close.setGravity(Gravity.CENTER);
        close.setPadding(pad * 2, pad, pad * 2, pad);
        close.setOnClickListener(v -> finish());
        titleBar.addView(close, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, (int) (36 * density)));

        root.addView(titleBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(webView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    /**
     * Keep the WebView's own (mobile Chrome) identity. Cloudflare Turnstile on account.jagex.com
     * cross-checks the user agent against client hints and JS fingerprints (navigator.platform,
     * touch points, WebGL renderer); spoofing a desktop Linux UA made the challenge loop forever
     * (HTTP 403 on /oauth2/auth after every "Verifying..."). The only thing removed is the
     * "X-Requested-With: <package>" header, which identifies the embedding app.
     */
    private void configureBrowserIdentity(WebSettings settings) {
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setDatabaseEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        // Same engine, same platform: present the WebView exactly like Chrome for Android. The
        // default UA carries "; wv" and "Version/4.0", which Jagex's Cloudflare WAF answers with
        // "Sorry, you have been blocked" on POST /api/auth/login/jagex.
        String ua = settings.getUserAgentString()
                .replace("; wv)", ")")
                .replace(" Version/4.0", "");
        settings.setUserAgentString(ua);
        String chromeVersion = "131.0.0.0";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("Chrome/([0-9.]+)").matcher(ua);
        if (m.find()) chromeVersion = m.group(1);
        String chromeMajor = chromeVersion.split("\\.")[0];
        if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
            try {
                UserAgentMetadata current = WebSettingsCompat.getUserAgentMetadata(settings);
                UserAgentMetadata metadata = new UserAgentMetadata.Builder(current)
                        .setBrandVersionList(Arrays.asList(
                                new UserAgentMetadata.BrandVersion.Builder().setBrand("Google Chrome")
                                        .setMajorVersion(chromeMajor).setFullVersion(chromeVersion).build(),
                                new UserAgentMetadata.BrandVersion.Builder().setBrand("Chromium")
                                        .setMajorVersion(chromeMajor).setFullVersion(chromeVersion).build(),
                                new UserAgentMetadata.BrandVersion.Builder().setBrand("Not_A Brand")
                                        .setMajorVersion("24").setFullVersion("24.0.0.0").build()))
                        .build();
                WebSettingsCompat.setUserAgentMetadata(settings, metadata);
            } catch (Throwable t) {
                Log.w(TAG, "Unable to override user-agent client hints", t);
            }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, Collections.emptySet());
        } else {
            Log.w(TAG, "X-Requested-With header cannot be disabled on this WebView version");
        }
        Log.i(TAG, "Using user agent " + ua);
    }

    /**
     * Fallback when Cloudflare still blocks the embedded WebView: run the credential step in the
     * real Chrome browser (Custom Tab). launcher-redirect then hands the code over through a
     * {@code jagex:} URL, which JagexAuthCallbackActivity catches and routes back here
     * (onNewIntent); the token exchange and consent continue in this activity.
     */
    private static android.graphics.Bitmap checkIcon() {
        int size = 72;
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
        android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        paint.setColor(0xFF4CAF50);
        paint.setStyle(android.graphics.Paint.Style.STROKE);
        paint.setStrokeWidth(size * 0.13f);
        paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
        android.graphics.Path path = new android.graphics.Path();
        path.moveTo(size * 0.18f, size * 0.52f);
        path.lineTo(size * 0.42f, size * 0.75f);
        path.lineTo(size * 0.84f, size * 0.27f);
        canvas.drawPath(path, paint);
        return bitmap;
    }

    private void openLoginInBrowser() {
        if (mBrowserLogin) return;
        mBrowserLogin = true;
        Toast.makeText(this, "Jagex blocked the in-app browser, continuing the login in Chrome", Toast.LENGTH_LONG).show();
        openInBrowser(mLoginUrl);
    }

    private void openInBrowser(String url) {
        openInBrowser(url, false);
    }

    /**
     * @param finishButton add a toolbar button + menu item that sends the tab's current URL to
     *     JagexAuthCallbackActivity. The consent step ends on http://localhost/#code=..&id_token=..
     *     which Chrome cannot hand to an app (no server on port 80, and Android 12+ never routes
     *     unverified web links like localhost to apps), so the user confirms it with one tap.
     */
    private void openInBrowser(String url, boolean finishButton) {
        Uri uri = Uri.parse(url);
        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
        Bundle extras = new Bundle();
        extras.putBinder("android.support.customtabs.extra.SESSION", null); // => Custom Tab
        if (finishButton) {
            int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                    : PendingIntent.FLAG_UPDATE_CURRENT;
            PendingIntent finish = PendingIntent.getActivity(this, 0x4A41,
                    new Intent(this, JagexAuthCallbackActivity.class), flags);
            Bundle button = new Bundle();
            button.putParcelable("android.support.customtabs.customaction.ICON", checkIcon());
            button.putString("android.support.customtabs.customaction.DESCRIPTION", "Return to RuneLite");
            button.putParcelable("android.support.customtabs.customaction.PENDING_INTENT", finish);
            extras.putBundle("android.support.customtabs.extra.ACTION_BUTTON_BUNDLE", button);
            extras.putBoolean("android.support.customtabs.extra.TINT_ACTION_BUTTON", false);
            Bundle menu = new Bundle();
            menu.putString("android.support.customtabs.customaction.MENU_ITEM_TITLE", "Return to RuneLite");
            menu.putParcelable("android.support.customtabs.customaction.PENDING_INTENT", finish);
            java.util.ArrayList<Bundle> menus = new java.util.ArrayList<>();
            menus.add(menu);
            extras.putParcelableArrayList("android.support.customtabs.extra.MENU_ITEMS", menus);
        }
        intent.putExtras(extras);
        intent.putExtra("android.support.customtabs.extra.TOOLBAR_COLOR", 0xFF2B2B2B);
        intent.addCategory(Intent.CATEGORY_BROWSABLE);
        try {
            intent.setPackage("com.android.chrome");
            startActivity(intent);
        } catch (android.content.ActivityNotFoundException e) {
            intent.setPackage(null);
            try {
                startActivity(intent);
            } catch (android.content.ActivityNotFoundException e2) {
                fail("no browser available");
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("verifier", mCodeVerifier);
        out.putString("loginState", mLoginState);
        out.putString("consentState", mConsentState);
        out.putString("nonce", mNonce);
        out.putBoolean("browser", mBrowserLogin);
        out.putString("loginUrl", mLoginUrl);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Uri data = intent.getData();
        Log.i(TAG, "Browser callback " + (data == null ? "(none)" : redact(data.toString())));
        if (data != null && !handleUrl(data.toString())) Log.w(TAG, "Unrecognised browser callback");
    }

    private class Client extends WebViewClient {
        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
            Log.w(TAG, "HTTP " + response.getStatusCode() + " " + response.getReasonPhrase()
                    + " for " + request.getUrl() + (request.isForMainFrame() ? " (main frame)" : ""));
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            Log.w(TAG, "Load error " + error.getErrorCode() + " " + error.getDescription() + " for " + request.getUrl());
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            Log.i(TAG, "Loaded " + redact(url));
            view.evaluateJavascript("(document.title||'')+' | '+((document.body&&document.body.innerText)||'').slice(0,300)",
                    text -> {
                        Log.i(TAG, "Page: " + text);
                        if (text != null && !mExchanging && mConsentState == null
                                && text.toLowerCase(java.util.Locale.ROOT).contains("you have been blocked")) {
                            openLoginInBrowser();
                        }
                    });
        }

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
                    // The consent page needs the account.jagex.com session cookie, so it has to run
                    // in the browser that did the login (the WebView is blocked by Cloudflare).
                    if (mBrowserLogin) {
                        Toast.makeText(this, "When Chrome shows \"localhost refused to connect\", tap the "
                                + "green \u2713 at the top to return to RuneLite", Toast.LENGTH_LONG).show();
                        openInBrowser(consentUrl, true);
                    }
                    else if (mWebView != null) mWebView.loadUrl(consentUrl);
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
            // Must match the JVM side, which listens on 127.0.0.1. Android's
            // InetAddress.getLoopbackAddress() is ::1, which was refused.
            try (Socket socket = new Socket(InetAddress.getByName("127.0.0.1"), mPort);
                 OutputStream out = socket.getOutputStream()) {
                out.write(line.getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (Exception e) {
                Log.e(TAG, "Unable to hand the Jagex login result back to RuneLite", e);
            }
        }, "JagexLoginResult").start();
    }

    /** Strip OAuth parameters (code/tokens) from URLs before logging them. */
    private static String redact(String url) {
        if (url == null) return null;
        int cut = url.indexOf('?');
        int hash = url.indexOf('#');
        if (hash >= 0 && (cut < 0 || hash < cut)) cut = hash;
        return cut >= 0 ? url.substring(0, cut) + "?..." : url;
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
