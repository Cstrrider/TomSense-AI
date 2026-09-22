package org.tomsense.android.feed

import android.app.Dialog
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.util.Log
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.ComposeView
import com.google.android.libraries.launcherclient.ILauncherOverlay
import com.google.android.libraries.launcherclient.ILauncherOverlayCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.tomsense.android.R
import org.tomsense.android.TomsenseApp
import org.tomsense.android.assist.SessionHost

/**
 * The panel left of the home screen.
 *
 * Launcher3's overlay protocol — the same one the Google app implements for
 * Discover. Lawnchair binds any app exposing it once "ignore feed whitelist"
 * is on in its debug menu, which is why this is an addon and not a fork.
 *
 * ## The window, which is the whole difficulty
 *
 * The launcher does not host the content. It hands over its own window TOKEN,
 * and the provider builds a window of its own parented to it. The first
 * attempt here did that by calling `WindowManager.addView` with the token
 * copied onto some fresh LayoutParams, which produced a panel that was either
 * absent or invisible — indistinguishable from each other, and both looking
 * like "the home screen with nothing on it".
 *
 * The sequence below follows the one every working implementation uses:
 *
 *   1. Borrow a real [Window] from a [Dialog] that is never shown. There is no
 *      public way to construct one otherwise, and a raw View added to the
 *      WindowManager is not the same thing — it has no decor view, no theme
 *      and no attributes of its own to animate.
 *   2. `setWindowManager(null, token, …)` reparents it onto the launcher.
 *   3. Mutate the launcher's OWN LayoutParams rather than building new ones,
 *      so every field it set that we do not know about survives.
 *   4. Drive visibility with WINDOW alpha, never view alpha. A Compose
 *      `Modifier.alpha(progress)` starting at zero renders a fully
 *      transparent panel if the launcher never delivers a scroll event, which
 *      is exactly the blank screen it looks like.
 *
 * Touchability is toggled with visibility for the same reason: the window is
 * created NOT_TOUCHABLE and NOT_FOCUSABLE so it cannot eat home-screen
 * gestures while closed, and both flags clear when it opens — otherwise the
 * panel renders and ignores every tap.
 */
class FeedOverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = applicationContext as TomsenseApp
    private val main by lazy { Handler(mainLooper) }

    private var callback: ILauncherOverlayCallback? = null
    private var window: Window? = null
    private var windowView: View? = null
    private var windowManager: WindowManager? = null
    private var host: SessionHost? = null

    private var progress = 0f
    private var visible = false

    private val state = FeedPanelState(
        onOpenApp = { openApp(it) },
        onDismiss = { closePanel() },
    )

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        detachWindow()
        scope.cancel()
        super.onDestroy()
    }

    private val binder = object : ILauncherOverlay.Stub() {

        override fun windowAttached(lp: WindowManager.LayoutParams?, cb: ILauncherOverlayCallback?, flags: Int) {
            onMain { attach(lp, cb) }
        }

        override fun windowAttached2(bundle: Bundle?, cb: ILauncherOverlayCallback?) {
            @Suppress("DEPRECATION")
            val lp = bundle?.getParcelable<WindowManager.LayoutParams>("layout_params")
            onMain { attach(lp, cb) }
        }

        override fun windowDetached(isChangingConfigurations: Boolean) = onMain { detachWindow() }

        override fun startScroll() = onMain { setVisible(true) }

        override fun onScroll(p: Float) = onMain {
            progress = p.coerceIn(0f, 1f)
            state.progress = progress
            setWindowAlpha(progress)
            runCatching { callback?.overlayScrollChanged(progress) }
        }

        override fun endScroll() = onMain {
            if (progress <= 0.01f) setVisible(false)
        }

        override fun openOverlay(flags: Int) = onMain {
            progress = 1f
            state.progress = 1f
            setVisible(true)
            runCatching { callback?.overlayScrollChanged(1f) }
        }

        override fun closeOverlay(flags: Int) = onMain { closePanel() }

        override fun onPause() = Unit
        override fun onResume() = Unit

        override fun setActivityState(flags: Int) = Unit

        /**
         * Whether the launcher offers the panel at all.
         *
         * Always true: the panel has local content (recent chats, the next
         * calendar event, the ask box) even with no news source configured, so
         * there is never a state where opening it is pointless.
         */
        override fun hasOverlayContent(): Boolean = true

        override fun requestVoiceDetection(start: Boolean) = Unit
        override fun getVoiceSearchLanguage(): String = "en"
        override fun isVoiceDetectionRunning(): Boolean = false
        override fun unusedMethod() = Unit
        override fun startSearch(data: ByteArray?, bundle: Bundle?): Boolean = false
    }

    // ─── window ─────────────────────────────────────────────────────────────

    private fun attach(lp: WindowManager.LayoutParams?, cb: ILauncherOverlayCallback?) {
        callback = cb

        if (lp?.token == null) {
            // Loud, because the alternative is a blank panel and no clue why.
            Log.e(TAG, "windowAttached with no window token — cannot build the panel")
            return
        }

        detachWindow()
        if (!buildWindow(lp)) return

        // Bit 1 is what makes the launcher forward scroll at all: Lawnchair
        // checks (mServiceState & 1) before delivering onScroll, so without
        // this the panel attaches and then never moves.
        runCatching { cb?.overlayStatusChanged(STATUS_ATTACHED) }
            .onFailure { Log.e(TAG, "overlayStatusChanged failed", it) }
    }

    private fun buildWindow(lp: WindowManager.LayoutParams): Boolean {
        try {
            // Never shown. It exists only to hand over a real Window, which
            // has no public constructor.
            val dialog = Dialog(this, R.style.Theme_Tomsense)
            val win = dialog.window ?: run {
                Log.e(TAG, "dialog produced no window")
                return false
            }

            win.setWindowManager(
                null,
                lp.token,
                ComponentName(this, javaClass).flattenToShortString(),
                true,
            )
            val wm = win.windowManager ?: run {
                Log.e(TAG, "window manager missing after reparenting")
                return false
            }

            val sessionHost = SessionHost().also { host = it }
            sessionHost.create()

            val content = ComposeView(this).apply {
                setContent { MaterialTheme { FeedPanel(app, state) } }
            }
            sessionHost.attachTo(content)
            sessionHost.resume()

            // The launcher's own params, mutated — not replaced. It sets
            // fields we do not know about, and discarding them is how a panel
            // ends up positioned or sized wrongly on one device only.
            lp.width = WindowManager.LayoutParams.MATCH_PARENT
            lp.height = WindowManager.LayoutParams.MATCH_PARENT
            lp.type = TYPE_DRAWN_APPLICATION
            lp.flags = lp.flags or LAUNCHER_OVERLAY_FLAGS or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            lp.alpha = 0f
            lp.dimAmount = 0f
            lp.gravity = android.view.Gravity.START
            lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED

            win.attributes = lp
            win.setContentView(content)

            val decor = win.decorView
            wm.addView(decor, win.attributes)

            window = win
            windowView = decor
            windowManager = wm
            visible = false
            Log.i(TAG, "panel window attached")
            return true
        } catch (e: Throwable) {
            // Swallowing this is what made the first version undiagnosable:
            // a failed addView and a transparent window look identical from
            // the home screen.
            Log.e(TAG, "failed to build the panel window", e)
            detachWindow()
            return false
        }
    }

    /**
     * Visible AND interactive, together.
     *
     * They have to move as one: a panel that is drawn but not touchable takes
     * taps nowhere, and one that is touchable while closed steals home-screen
     * gestures.
     */
    private fun setVisible(show: Boolean) {
        if (visible == show) return
        visible = show
        state.visible = show
        setFocusable(show)
        setWindowAlpha(if (show) 1f else 0f)
    }

    private fun setFocusable(focusable: Boolean) {
        val win = window ?: return
        val attrs = win.attributes
        val mask = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE

        val changed = if (focusable) {
            (attrs.flags and mask) != 0
        } else {
            (attrs.flags and mask) != mask
        }
        if (!changed) return

        attrs.flags = if (focusable) attrs.flags and mask.inv() else attrs.flags or mask
        win.attributes = attrs
        if (focusable) windowView?.requestFocus()
    }

    private fun setWindowAlpha(alpha: Float) {
        val win = window ?: return
        val attrs = win.attributes
        val target = alpha.coerceIn(0f, 1f)
        if (attrs.alpha == target) return
        attrs.alpha = target
        win.attributes = attrs
    }

    private fun closePanel() {
        progress = 0f
        state.progress = 0f
        setVisible(false)
        runCatching { callback?.overlayScrollChanged(0f) }
    }

    private fun detachWindow() {
        windowView?.let { view ->
            runCatching { windowManager?.removeViewImmediate(view) }
                .onFailure { Log.w(TAG, "removing the panel window failed", it) }
        }
        windowView = null
        window = null
        windowManager = null
        host?.destroy()
        host = null
        visible = false
        progress = 0f
    }

    private fun openApp(intent: Intent) {
        runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Log.w(TAG, "could not open the app", it) }
        closePanel()
    }

    /**
     * Every AIDL call arrives on a binder thread; windows and Compose are
     * main-thread only. Getting this wrong is an intermittent crash, which is
     * worse than a consistent one.
     */
    private fun onMain(block: () -> Unit) {
        main.post(block)
    }

    private companion object {
        const val TAG = "TomSenseFeed"
        const val STATUS_ATTACHED = 1

        /**
         * TYPE_DRAWN_APPLICATION. Not public API, and not a sub-panel: the
         * window is parented by the launcher's token rather than being a
         * child of one of its views.
         */
        const val TYPE_DRAWN_APPLICATION = 4

        /**
         * The flag set every overlay provider uses (0x840000): split-touch
         * plus hardware acceleration. Kept as a literal because that is how it
         * appears in the implementations this was derived from, and guessing
         * at the decomposition risks dropping one.
         */
        const val LAUNCHER_OVERLAY_FLAGS = 8650752
    }
}
