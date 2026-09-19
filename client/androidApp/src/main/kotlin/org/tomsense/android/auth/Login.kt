package org.tomsense.android.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import androidx.browser.customtabs.CustomTabsIntent
import org.tomsense.android.MainActivity
import org.tomsense.android.TomsenseApp
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Cloudflare Access login for the native client.
 *
 * The browser leg has to happen in a real browser: Access issues its session
 * as a cookie on the protected domain, and doing it inside an in-app WebView
 * both breaks some identity providers and trains the user to type credentials
 * into a window that the app could read. Custom Tabs keeps it in the system
 * browser with the app's look.
 *
 *   app  ──► Custom Tab: /auth/mobile?challenge=…&state=…
 *              │  (Cloudflare Access authenticates the user here)
 *              ▼
 *            Worker mints a one-time CODE, redirects tomsense://auth?code=…
 *              │
 *   app  ◄─────┘  then POSTs {code, verifier} to /auth/exchange → device token
 *
 * The token never travels through the redirect. Custom schemes on Android are
 * first-come-first-served and any installed app can claim `tomsense://`, so
 * the redirect carries only a code that is useless without the verifier —
 * which never leaves this process.
 */
object Login {

    private const val PREFS = "tomsense_auth"
    private const val KEY_VERIFIER = "pkce_verifier"
    private const val KEY_STATE = "pkce_state"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun randomUrlSafe(bytes: Int): String {
        val b = ByteArray(bytes)
        SecureRandom().nextBytes(b)
        return Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    /** base64url(SHA-256(verifier)) — must match the Worker's derivation. */
    private fun challengeOf(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    /** Open the system browser at the Access-protected login URL. */
    fun start(ctx: Context, baseUrl: String) {
        val verifier = randomUrlSafe(48)
        val state = randomUrlSafe(16)

        // Persisted because the browser leg may outlive this process — the
        // user can background the app, or the OS can kill it, while they are
        // completing an SSO challenge.
        prefs(ctx).edit()
            .putString(KEY_VERIFIER, verifier)
            .putString(KEY_STATE, state)
            .apply()

        val uri = Uri.parse("$baseUrl/auth/mobile").buildUpon()
            .appendQueryParameter("challenge", challengeOf(verifier))
            .appendQueryParameter("state", state)
            .appendQueryParameter("name", android.os.Build.MODEL ?: "android")
            .build()

        CustomTabsIntent.Builder().build().launchUrl(ctx, uri)
    }

    /**
     * Handle the `tomsense://auth` redirect.
     *
     * Returns the verifier to exchange, or null if the callback should be
     * ignored. A mismatched `state` means this callback did not originate
     * from our request — the case a hostile app registering the same scheme
     * would produce — so it is dropped rather than exchanged.
     */
    fun consumeCallback(ctx: Context, uri: Uri): Pair<String, String>? {
        val code = uri.getQueryParameter("code") ?: return null
        val state = uri.getQueryParameter("state") ?: return null

        val p = prefs(ctx)
        val expectedState = p.getString(KEY_STATE, null) ?: return null
        val verifier = p.getString(KEY_VERIFIER, null) ?: return null

        // Single-use: clear immediately so a replayed callback cannot be
        // exchanged a second time.
        p.edit().remove(KEY_STATE).remove(KEY_VERIFIER).apply()

        if (state != expectedState) return null
        return code to verifier
    }

    fun storeToken(ctx: Context, token: String) {
        ctx.getSharedPreferences("tomsense", Context.MODE_PRIVATE)
            .edit()
            .putString(TomsenseApp.KEY_DEVICE_TOKEN, token)
            .apply()
    }

    fun hasToken(ctx: Context): Boolean =
        ctx.getSharedPreferences("tomsense", Context.MODE_PRIVATE)
            .getString(TomsenseApp.KEY_DEVICE_TOKEN, null) != null
}

/**
 * Receives the `tomsense://auth` deep link, exchanges the code, and returns
 * to the app. Kept separate from MainActivity so the callback works whether
 * or not the app is already running.
 */
class AuthCallbackActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val data = intent?.data
        val app = application as TomsenseApp

        if (data == null) {
            finish()
            return
        }

        val pair = Login.consumeCallback(this, data)
        if (pair == null) {
            // Either a replay, a stale callback, or a state mismatch. Say
            // nothing useful — this path is reachable by other apps.
            finish()
            return
        }

        val (code, verifier) = pair
        app.exchangeAuthCode(code, verifier) { ok ->
            runOnUiThread {
                if (ok) {
                    startActivity(
                        Intent(this, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    )
                }
                finish()
            }
        }
    }
}
