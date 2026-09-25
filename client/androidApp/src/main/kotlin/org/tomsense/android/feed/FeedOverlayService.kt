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
import org.tomsense.android.ui.TomsenseTheme

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
 *   5. SUBMIT every params change with `updateViewLayout`. Assigning
 *      `window.attributes` is not enough when the decor view was added by
 *      hand — see [updateParams]. Without it the window attaches at alpha 0
 *      and stays there, logging success the whole time.
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
    /** The panel content inside the drag layer — what actually slides. */
    private var slider: View? = null
    private var settle: android.animation.ValueAnimator? = null
    private var windowManager: WindowManager? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var host: SessionHost? = null

    private var progress = 0f
    private var visible = false

    private val state = FeedPanelState(
        onOpenApp = { openApp(it) },
        onOpenFirst = { openFirst(it) },
        onDismiss = { closePanel() },
    )

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        state.shutdownVoice()
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

        override fun startScroll() = onMain {
            settle?.cancel()
            setVisible(true)
        }

        override fun onScroll(p: Float) = onMain {
            settle?.cancel()
            applyProgress(p)
            runCatching { callback?.overlayScrollChanged(progress) }
        }

        override fun endScroll() = onMain {
            // Settle explicitly either way. The launcher does not reliably
            // send a final onScroll(1f), so a panel left to the last progress
            // it heard would sit half-slid (formerly: half-faded) forever.
            if (progress <= 0.01f) closePanel() else openFully()
        }

        override fun openOverlay(flags: Int) = onMain {
            openFully()
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

            val content = ComposeView(this).also { slider = it }.apply {
                // opaque = false: the panel paints its own surface and the
                // service animates the WINDOW alpha, so a second full-screen
                // Surface here would fight both.
                setContent { TomsenseTheme(opaque = false) { FeedPanel(app, state) } }
            }

            // Dismissal lives in the view layer, above Compose — see
            // DismissFrameLayout. The back key stays as a SECOND, independent
            // way out: the open panel covers the launcher, so a bug in one
            // dismissal path must not make the home screen unreachable.
            val root = DismissFrameLayout(this).apply {
                addView(content)
                onDragTo = { p ->
                    settle?.cancel()
                    applyProgress(p)
                    runCatching { callback?.overlayScrollChanged(p) }
                }
                onDragSettled = { p, velocityX ->
                    // Either a deliberate drag OR a flick. Distance alone made
                    // this feel stuck: a quick leftward flick barely moves the
                    // finger before it lifts, so it kept snapping back open.
                    val flicked = velocityX <= FLING_DISMISS
                    if (flicked || p < SETTLE_CLOSED) closePanel() else openFully()
                }
                isFocusableInTouchMode = true
                setOnKeyListener { _, keyCode, event ->
                    if (keyCode == android.view.KeyEvent.KEYCODE_BACK &&
                        event.action == android.view.KeyEvent.ACTION_UP
                    ) {
                        closePanel()
                        true
                    } else {
                        false
                    }
                }
            }
            sessionHost.attachTo(root)
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
            // Transparent, or the slide shows nothing: the theme's solid
            // window background would cover the home screen the moment the
            // window turns visible, and the panel would appear to pop in
            // behind a wall. The alpha fade used to hide this. FeedPanel paints
            // its own opaque Surface, so the open panel looks the same.
            win.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            win.setContentView(root)
            // Parked off the left edge until the first scroll, so the window
            // turning visible on startScroll cannot flash a full panel.
            content.translationX = -panelWidth()

            // setAttributes COPIES into the window's own params, so hold on to
            // the object that actually gets added — that is the one every
            // later update has to mutate and re-submit.
            val decor = win.decorView
            val attached = win.attributes
            wm.addView(decor, attached)

            window = win
            windowView = decor
            windowManager = wm
            layoutParams = attached
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
        if (!show) state.silence()

        // Alpha and focusability in ONE submission: they belong to the same
        // params object, so pushing them separately would round-trip the
        // window twice for a single state change.
        val mask = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val pushed = updateParams { p ->
            p.flags = if (show) p.flags and mask.inv() else p.flags or mask
            p.alpha = if (show) 1f else 0f
        }
        if (pushed && show) windowView?.requestFocus()
        Log.d(TAG, "panel ${if (show) "open" else "closed"} (pushed=$pushed)")
    }

    /**
     * Place the panel for a progress of 0 (closed) … 1 (open).
     *
     * A SLIDE: the content moves in from the left edge, tracking the finger,
     * with the home screen visible beside it. It used to be a fade of the
     * window alpha. Translation is a RenderThread property, so this costs no
     * window relayout per frame the way moving the window itself would.
     *
     * The window alpha stays 1 while visible (setVisible owns it). The
     * content is moved, not the DismissFrameLayout around it: that layer
     * reads touch positions in its own coordinates, and translating it would
     * shift the finger's position under itself as it dragged.
     */
    private fun applyProgress(p: Float) {
        progress = p.coerceIn(0f, 1f)
        state.progress = progress
        slider?.translationX = -(1f - progress) * panelWidth()
    }

    /** Width before first layout too — a 0 width would park the panel ON screen. */
    private fun panelWidth(): Float =
        (windowView?.width?.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels).toFloat()

    /**
     * Animate to fully open or fully closed from wherever the finger left it.
     *
     * Jumping straight to the end state was invisible under a fade and looks
     * broken under a slide. Duration scales with the distance left, so a
     * nearly-complete swipe finishes quickly instead of taking the full time.
     * Echoes every frame to the launcher so its workspace moves in step.
     */
    private fun settleTo(target: Float, onEnd: () -> Unit = {}) {
        settle?.cancel()
        val from = progress
        if (from == target) {
            onEnd()
            return
        }
        settle = android.animation.ValueAnimator.ofFloat(from, target).apply {
            duration = (SETTLE_MS * kotlin.math.abs(target - from)).toLong().coerceAtLeast(90L)
            interpolator = android.view.animation.DecelerateInterpolator(1.6f)
            addUpdateListener {
                applyProgress(it.animatedValue as Float)
                runCatching { callback?.overlayScrollChanged(progress) }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    cancelled = true
                }
                // Only a COMPLETED settle finishes the job: a cancelled one
                // was interrupted by a new drag, which now owns the panel.
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (!cancelled) onEnd()
                }
            })
            start()
        }
    }

    /**
     * Mutate the live window params and hand them back to the WindowManager.
     *
     * The submission is the part that was missing. `window.attributes = …`
     * changes nothing on screen here: Window dispatches the change to its
     * Callback, which is the Dialog, and Dialog only forwards it to
     * updateViewLayout once `mDecor` has been set — which happens in show().
     * This dialog is deliberately never shown, so nothing forwards anything.
     * The decor view was added to the WindowManager here, so it has to be
     * updated here, or the window keeps the alpha 0 it was created with and
     * the panel is invisible forever while every log line says it attached.
     */
    private fun updateParams(block: (WindowManager.LayoutParams) -> Unit): Boolean {
        val wm = windowManager ?: return false
        val view = windowView ?: return false
        val params = layoutParams ?: return false

        block(params)
        window?.attributes = params
        return runCatching { wm.updateViewLayout(view, params); true }
            .onFailure { Log.e(TAG, "updating the panel window failed", it) }
            .getOrDefault(false)
    }

    private fun openFully() {
        setVisible(true)
        settleTo(1f)
    }

    /** Slides out, THEN hides — hiding first would cut the animation off. */
    private fun closePanel() {
        settleTo(0f) { setVisible(false) }
    }

    private fun detachWindow() {
        // Before the views go: a settle still running would keep writing
        // translation into a detached view and echoing to a dead callback.
        settle?.cancel()
        settle = null
        slider = null
        windowView?.let { view ->
            runCatching { windowManager?.removeViewImmediate(view) }
                .onFailure { Log.w(TAG, "removing the panel window failed", it) }
        }
        windowView = null
        window = null
        windowManager = null
        layoutParams = null
        host?.destroy()
        host = null
        visible = false
        progress = 0f
    }

    /**
     * First intent that starts wins. startActivity rather than resolving
     * first: resolving needs package visibility (<queries>) for every app we
     * might hand off to, while starting only needs the app to exist.
     */
    private fun openFirst(intents: List<Intent>) {
        val started = intents.any { intent ->
            runCatching { startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        }
        if (!started) Log.w(TAG, "nothing could open ${intents.firstOrNull()}")
        closePanel()
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
         * Below this, a released drag closes rather than snapping back open.
         *
         * Raised from 0.65, which demanded a drag across 35% of the screen
         * before the panel would let go — far more travel than the gesture
         * people actually make, so it usually snapped back. 0.85 is about a
         * sixth of the width, and a flick closes at any distance.
         */
        const val SETTLE_CLOSED = 0.85f

        /** Full-width slide duration; shorter settles take a share of it. */
        const val SETTLE_MS = 260f

        /**
         * Leftward px/s that dismisses regardless of how far the finger got.
         * Roughly a brisk flick; a slow deliberate drag stays under it and is
         * judged on distance instead.
         */
        const val FLING_DISMISS = -700f

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
