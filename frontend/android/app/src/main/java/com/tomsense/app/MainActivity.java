package com.tomsense.app;

import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.view.View;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.getcapacitor.BridgeActivity;
import com.getcapacitor.JSObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Capacitor host activity. Routes two kinds of inbound intent:
 *
 *  - a "tomsense_path" extra (handoff from the assistant overlay) → navigate the
 *    WebView to that path so a conversation continues here.
 *  - an ACTION_SEND share ("Share → TomSense" from any app) → stash the shared
 *    text/image and open /?share=1; the web app drains it once via
 *    ActionsPlugin.consumeSharedContent().
 */
public class MainActivity extends BridgeActivity {

    // Shared-content stash — set by an ACTION_SEND intent, drained once after
    // the web app loads. Guarded by MainActivity.class (intent handling is on
    // the main thread, plugin calls on a worker thread).
    private static String sShareText;
    private static String sShareImageB64;
    private static String sShareImageMime;

    /** Snapshot the pending shared payload as a JSObject and clear it. */
    static synchronized JSObject takeShared() {
        JSObject o = new JSObject();
        if (sShareText != null) {
            o.put("type", "text");
            o.put("text", sShareText);
        } else if (sShareImageB64 != null) {
            o.put("type", "image");
            o.put("imageBase64", sShareImageB64);
            o.put("mimeType", sShareImageMime != null ? sShareImageMime : "image/jpeg");
        } else {
            o.put("type", "none");
        }
        sShareText = null;
        sShareImageB64 = null;
        sShareImageMime = null;
        return o;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Local plugins aren't auto-discovered — register before super.onCreate
        // so they're available when Capacitor builds the bridge.
        registerPlugin(CalendarPlugin.class);
        registerPlugin(HealthPlugin.class);
        registerPlugin(ActionsPlugin.class);
        registerPlugin(ContactsPlugin.class);
        // Runtime instance URL: a user-saved URL (first-run dialog / in-app
        // setting) overrides the one baked into capacitor.config.json.
        // Must be assigned before super.onCreate — BridgeActivity.load()
        // consumes `this.config` when it builds the bridge.
        String storedUrl = ServerConfig.stored(this);
        if (storedUrl != null) {
            this.config = ServerConfig.capConfig(this, storedUrl);
        }
        super.onCreate(savedInstanceState);

        // targetSdk 35 enforces edge-to-edge on Android 15+, which silently
        // disables adjustResize — the OS falls back to panning the whole
        // window when the keyboard opens, pushing the app's top bar off
        // screen. Handle the IME inset ourselves: pad the WebView's bottom by
        // the keyboard height so the page reflows (flex layout keeps the
        // header pinned and the composer lands just above the keyboard).
        // WebView ignores its own padding, so pad the activity content frame
        // instead — that resizes the WebView child. The strip the keyboard
        // reveals shows the frame's background; match the app theme (--bg)
        // so it isn't a white flash.
        View content = findViewById(android.R.id.content);
        content.setBackgroundColor(Color.parseColor("#0d1015"));
        ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(0, 0, 0, ime.bottom);
            return insets;
        });

        // Stock APK, nothing configured yet → ask where the instance lives.
        if (ServerConfig.needsSetup(this)) {
            promptForInstanceUrl();
        }

        routeIntent(getIntent());
    }

    /**
     * First-run dialog: ask for the TomSense instance URL, save it, and
     * recreate the activity so the bridge reloads against it. Re-prompts on
     * invalid input; "Later" leaves the bundled offline build showing (the
     * setting is also reachable from the in-app Settings once connected).
     */
    private void promptForInstanceUrl() {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("https://tomsense.your-domain.com");
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        new android.app.AlertDialog.Builder(this)
                .setTitle("Connect to TomSense")
                .setMessage("Enter the URL of your TomSense server. "
                        + "You can change it later in Settings.")
                .setView(input)
                .setCancelable(false)
                .setPositiveButton("Connect", (d, w) -> {
                    String url = ServerConfig.normalize(input.getText().toString());
                    if (url != null) {
                        ServerConfig.save(this, url);
                        recreate();
                    } else {
                        promptForInstanceUrl();
                    }
                })
                .setNegativeButton("Later", null)
                .show();
    }

    @Override
    public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        routeIntent(intent);
    }

    private void routeIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        if (stashSharedContent(intent)) {
            navigateTo("/?share=1");
            return;
        }
        String path = intent.getStringExtra("tomsense_path");
        if (path != null && !path.isEmpty() && !path.equals("/")) {
            navigateTo(path);
        }
    }

    /** Pull text or an image out of an ACTION_SEND intent into the stash. */
    private boolean stashSharedContent(Intent intent) {
        if (!Intent.ACTION_SEND.equals(intent.getAction())) {
            return false;
        }
        String type = intent.getType();
        if (type == null) {
            return false;
        }
        if (type.startsWith("text/")) {
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (text == null || text.isEmpty()) {
                return false;
            }
            CharSequence subject = intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT);
            synchronized (MainActivity.class) {
                sShareText = (subject != null && !subject.toString().equals(text))
                        ? subject + "\n" + text
                        : text;
                sShareImageB64 = null;
                sShareImageMime = null;
            }
            return true;
        }
        if (type.startsWith("image/")) {
            Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri == null) {
                return false;
            }
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) {
                    return false;
                }
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                }
                synchronized (MainActivity.class) {
                    sShareImageB64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
                    sShareImageMime = type;
                    sShareText = null;
                }
                return true;
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }

    private void navigateTo(String path) {
        final String url = ServerConfig.effective(this) + path;
        if (bridge != null && bridge.getWebView() != null) {
            // Post so it runs after Capacitor's own initial load of server.url.
            bridge.getWebView().post(() -> bridge.getWebView().loadUrl(url));
        }
    }
}
