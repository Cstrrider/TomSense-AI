package com.tomsense.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import com.getcapacitor.CapConfig;

/**
 * Where this app's TomSense instance lives. Resolution order:
 *
 *   1. the user-set value (SharedPreferences) — written by the first-run
 *      dialog in MainActivity or the in-app Settings field
 *      (ActionsPlugin.setServerUrl)
 *   2. the URL baked into assets/capacitor.config.json at `npx cap sync`
 *      (TOMSENSE_SERVER_URL) — a stock APK falls back to the compose
 *      localhost placeholder, which triggers the first-run dialog.
 */
public final class ServerConfig {

    private static final String PREFS = "tomsense";
    private static final String KEY_URL = "server_url";

    private ServerConfig() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The user-set instance URL, or null when none has been saved. */
    public static String stored(Context ctx) {
        String v = prefs(ctx).getString(KEY_URL, null);
        return (v == null || v.isEmpty()) ? null : v;
    }

    /** The URL baked into the bundled capacitor config at build time. */
    public static String baked(Context ctx) {
        String v = CapConfig.loadDefault(ctx).getServerUrl();
        return v == null ? "" : v;
    }

    /** Effective instance URL: stored value wins over the baked default. */
    public static String effective(Context ctx) {
        String s = stored(ctx);
        return s != null ? s : baked(ctx);
    }

    /** Bare hostname of the effective URL ("" if somehow unset). */
    public static String effectiveHost(Context ctx) {
        String h = Uri.parse(effective(ctx)).getHost();
        return h == null ? "" : h;
    }

    /**
     * True on first run of a stock APK: nothing user-set and the baked
     * default is the localhost placeholder (or empty) — i.e. there is no
     * real instance to load, so MainActivity should ask for one.
     */
    public static boolean needsSetup(Context ctx) {
        if (stored(ctx) != null) {
            return false;
        }
        String b = baked(ctx);
        return b.isEmpty() || b.contains("localhost") || b.contains("10.0.2.2");
    }

    /**
     * Normalize user input to a usable base URL: trims, defaults the scheme
     * to https, strips trailing slashes. Returns null when unusable.
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.trim();
        if (v.isEmpty()) {
            return null;
        }
        if (!v.startsWith("http://") && !v.startsWith("https://")) {
            v = "https://" + v;
        }
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        String host = Uri.parse(v).getHost();
        if (host == null || host.isEmpty()) {
            return null;
        }
        return v;
    }

    public static void save(Context ctx, String url) {
        prefs(ctx).edit().putString(KEY_URL, url).apply();
    }

    /**
     * Bridge config for `url`: server.url plus the navigation allowlist that
     * keeps the Cloudflare Access + Google sign-in redirects inside the
     * WebView (bouncing to an external browser would drop the auth cookie).
     * Everything else stays at Capacitor defaults, matching
     * capacitor.config.ts (https scheme, no mixed content).
     */
    public static CapConfig capConfig(Context ctx, String url) {
        String host = Uri.parse(url).getHost();
        return new CapConfig.Builder(ctx)
                .setServerUrl(url)
                .setAllowNavigation(new String[] {
                        host,
                        "*.cloudflareaccess.com",
                        "accounts.google.com",
                })
                .create();
    }
}
