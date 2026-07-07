package com.tomsense.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.provider.Settings;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.List;
import java.util.Locale;

/**
 * The assistant overlay: a translucent activity hosting a full-screen,
 * TRANSPARENT WebView of the live /assist page. /assist draws only a floating
 * bubble + input pill, so the homescreen shows through around them — the
 * Gemini-style overlay look. Launched by TomSenseVoiceSession on the assist
 * gesture.
 *
 * The WebView shares the app-global CookieManager, so the Cloudflare Access
 * session established by MainActivity carries over with no extra login.
 */
public class AssistActivity extends Activity {

    /** Live /assist route — see frontend/src/routes/assist/+page.svelte. */
    private static final String ASSIST_URL = "https://tomsense.example.com/assist";
    private static final String ASSIST_HOST = "tomsense.example.com";
    private static final int REQ_RECORD_AUDIO = 7001;

    private WebView webView;
    private boolean firstResume = true;

    /**
     * Set by TomSenseVoiceSession when the assist gesture fires. If the overlay's
     * activity is still alive (e.g. it was dismissed earlier with the Home
     * button, which stops but doesn't finish it), onResume sees this and tells
     * /assist to start a fresh chat — so each gesture invocation is its own
     * conversation. Returning to a still-open overlay any other way (e.g. Back
     * from a tapped link) leaves the in-progress chat intact.
     */
    private static volatile boolean sNewChatPending = false;

    /** Called from TomSenseVoiceSession.onShow when the assist gesture fires. */
    static void requestNewChat() {
        sNewChatPending = true;
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_assist);

        // Translucent overlay: draw the system-bar backgrounds ourselves and
        // keep them transparent, so the homescreen shows through the status
        // and navigation bars too — without this the parent Translucent theme
        // leaves an opaque black status bar framing the panel.
        Window window = getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);

        // The /assist mic button uses getUserMedia inside the WebView, which
        // needs the app to hold RECORD_AUDIO. Ask once, up front.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this, new String[]{Manifest.permission.RECORD_AUDIO}, REQ_RECORD_AUDIO);
        }

        webView = findViewById(R.id.assist_webview);
        // Transparent: /assist paints a floating bubble, the rest shows the
        // (dimmed) homescreen behind the activity.
        webView.setBackgroundColor(Color.TRANSPARENT);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        // The /assist page plays TTS audio; allow it without a user gesture.
        settings.setMediaPlaybackRequiresUserGesture(false);

        // Cookies are app-global, so the Cloudflare Access session from
        // MainActivity carries over. Third-party cookies are enabled for the
        // CF Access / Google sign-in redirect bounce on a cold session.
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        // Lets the /assist page drive the native shell (swipe-up / dismiss).
        webView.addJavascriptInterface(new NativeBridge(), "TomSenseNative");
        webView.setWebViewClient(new AssistWebViewClient());
        webView.setWebChromeClient(new AssistChromeClient());

        if (savedInstanceState == null) {
            webView.loadUrl(ASSIST_URL);
        } else {
            webView.restoreState(savedInstanceState);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean pending = sNewChatPending;
        sNewChatPending = false;
        if (firstResume) {
            // A fresh onCreate already loaded /assist clean — nothing to reset.
            firstResume = false;
            return;
        }
        if (pending && webView != null) {
            // A new assist gesture re-opened an overlay that was still alive.
            webView.evaluateJavascript(
                    "window.__assistNewChat && window.__assistNewChat();", null);
        }
    }

    /**
     * Bridge exposed to the /assist page as window.TomSenseNative. Both methods
     * are harmless (open the app / dismiss), so no origin check is needed.
     */
    private final class NativeBridge {
        /** Swipe-up gesture on the bubble → open the full TomSense app, landing
         *  on the given path (e.g. /c/&lt;id&gt;) so the conversation continues. */
        @JavascriptInterface
        public void openApp(String path) {
            runOnUiThread(() -> openFullApp(path));
        }

        /** Backdrop tap → dismiss the overlay. */
        @JavascriptInterface
        public void close() {
            runOnUiThread(AssistActivity.this::finish);
        }

        /** True once Android has delivered a screenshot for this invocation. */
        @JavascriptInterface
        public boolean hasScreen() {
            return ScreenCapture.get() != null;
        }

        /** The captured screen as a base64 JPEG, or null if none is available. */
        @JavascriptInterface
        public String getScreen() {
            byte[] bytes = ScreenCapture.get();
            return bytes == null ? null : Base64.encodeToString(bytes, Base64.NO_WRAP);
        }

        // ── Device-action methods (mirror of ActionsPlugin for overlay context) ──
        // Returns "ok" on success or "error: <message>" on failure.
        // Called from clienttools.ts when the Capacitor bridge isn't available.

        @JavascriptInterface
        public String launchApp(String appName) {
            if (appName == null || appName.isEmpty()) return "error: no app name";
            PackageManager pm = getPackageManager();
            String lower = appName.toLowerCase(Locale.US).trim();
            String pkg = ActionsPlugin.COMMON_PACKAGES.get(lower);
            if (pkg == null) {
                Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> apps = pm.queryIntentActivities(main, 0);
                for (ResolveInfo info : apps) {
                    String label = info.loadLabel(pm).toString().toLowerCase(Locale.US);
                    if (label.equals(lower) || label.contains(lower)) {
                        pkg = info.activityInfo.packageName;
                        break;
                    }
                }
            }
            if (pkg == null) return "error: app not found";
            Intent launch = pm.getLaunchIntentForPackage(pkg);
            if (launch == null) return "error: no launch intent";
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { startActivity(launch); return "ok"; }
            catch (Exception e) { return "error: " + e.getMessage(); }
        }

        @JavascriptInterface
        public String makeCall(String phoneNumber) {
            if (phoneNumber == null || phoneNumber.isEmpty()) return "error: no number";
            Intent intent = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(phoneNumber)));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { startActivity(intent); return "ok"; }
            catch (Exception e) { return "error: " + e.getMessage(); }
        }

        @JavascriptInterface
        public String sendSms(String phoneNumber, String message) {
            if (phoneNumber == null || phoneNumber.isEmpty()) return "error: no number";
            Intent intent = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(phoneNumber)));
            if (message != null && !message.isEmpty()) intent.putExtra("sms_body", message);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { startActivity(intent); return "ok"; }
            catch (Exception e) { return "error: " + e.getMessage(); }
        }

        @JavascriptInterface
        public String openMaps(String destination, String mode) {
            if (destination == null || destination.isEmpty()) return "error: no destination";
            if (mode != null && !mode.isEmpty()) {
                String gm = mode.equals("transit") ? "r" : mode.equals("walking") ? "w"
                          : mode.equals("cycling") ? "b" : "d";
                Intent nav = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("google.navigation:q=" + Uri.encode(destination) + "&mode=" + gm));
                nav.setPackage("com.google.android.apps.maps");
                nav.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { startActivity(nav); return "ok"; } catch (Exception ignored) {}
            }
            Intent intent = new Intent(Intent.ACTION_VIEW,
                Uri.parse("geo:0,0?q=" + Uri.encode(destination)));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { startActivity(intent); return "ok"; }
            catch (Exception e) { return "error: " + e.getMessage(); }
        }

        @JavascriptInterface
        public String openUrl(String url) {
            if (url == null || url.isEmpty()) return "error: no url";
            if (!url.matches("^[a-z][a-z0-9+\\-.]*:.*")) url = "https://" + url;
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { startActivity(intent); return "ok"; }
            catch (Exception e) { return "error: " + e.getMessage(); }
        }

        @JavascriptInterface
        public String shareText(String text, String title) {
            if (text == null || text.isEmpty()) return "error: no text";
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TEXT, text);
            if (title != null && !title.isEmpty()) intent.putExtra(Intent.EXTRA_SUBJECT, title);
            Intent chooser = Intent.createChooser(intent, "Share via");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { startActivity(chooser); return "ok"; }
            catch (Exception e) { return "error: " + e.getMessage(); }
        }

        @JavascriptInterface
        public String setVolume(int level, String action) {
            return ActionsPlugin.doSetVolume(AssistActivity.this, level, action);
        }

        @JavascriptInterface
        public String setBrightness(int level) {
            return ActionsPlugin.doSetBrightness(AssistActivity.this, level);
        }

        @JavascriptInterface
        public String mediaControl(String action) {
            return ActionsPlugin.doMediaControl(AssistActivity.this, action);
        }

        @JavascriptInterface
        public String getDeviceStatus() {
            return ActionsPlugin.doGetDeviceStatus(AssistActivity.this);
        }

        @JavascriptInterface
        public String playMusic(String query, String kind, String app) {
            return ActionsPlugin.doPlayMusic(AssistActivity.this, query, kind, app);
        }

        @JavascriptInterface
        public String openSettings(String panel) {
            String action = (panel != null && !panel.isEmpty())
                ? ActionsPlugin.SETTINGS_ACTIONS.getOrDefault(
                    panel.toLowerCase(Locale.US), Settings.ACTION_SETTINGS)
                : Settings.ACTION_SETTINGS;
            Intent intent = new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { startActivity(intent); return "ok"; }
            catch (Exception e) {
                try { startActivity(new Intent(Settings.ACTION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return "ok"; }
                catch (Exception e2) { return "error: " + e2.getMessage(); }
            }
        }
    }

    /** Keeps /assist navigation in-WebView; sends real links to the browser
     *  so a tapped link never replaces the tiny overlay. */
    private final class AssistWebViewClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri url = request.getUrl();
            String host = url != null ? url.getHost() : null;
            if (host != null && host.equals(ASSIST_HOST)) {
                return false; // same site — load inside the overlay
            }
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, url));
            } catch (Exception ignored) {
                // no app can handle the link — just drop it
            }
            return true;
        }
    }

    /** Grants the WebView's getUserMedia mic request — provided the app holds
     *  RECORD_AUDIO (requested in onCreate). */
    private final class AssistChromeClient extends WebChromeClient {
        /** The /assist page's get_location tool calls navigator.geolocation
         *  (the Capacitor geolocation plugin's web path — this plain WebView
         *  has no Capacitor bridge). Grant it iff the app already holds a
         *  location permission; otherwise deny quietly. */
        @Override
        public void onGeolocationPermissionsShowPrompt(
                String origin, GeolocationPermissions.Callback callback) {
            boolean has = ContextCompat.checkSelfPermission(
                    AssistActivity.this, Manifest.permission.ACCESS_COARSE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
            callback.invoke(origin, has, false);
        }

        @Override
        public void onPermissionRequest(final PermissionRequest request) {
            runOnUiThread(() -> {
                for (String res : request.getResources()) {
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(res)) {
                        if (ContextCompat.checkSelfPermission(
                                AssistActivity.this, Manifest.permission.RECORD_AUDIO)
                                == PackageManager.PERMISSION_GRANTED) {
                            request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                        } else {
                            request.deny();
                        }
                        return;
                    }
                }
                request.deny();
            });
        }
    }

    private void openFullApp(String path) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (path != null && !path.isEmpty()) {
            intent.putExtra("tomsense_path", path);
        }
        startActivity(intent);
        finish();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView != null) {
            webView.saveState(outState);
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
