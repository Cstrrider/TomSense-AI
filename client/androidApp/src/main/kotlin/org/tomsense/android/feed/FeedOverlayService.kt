package org.tomsense.android.feed

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.ComposeView
import com.google.android.libraries.launcherclient.ILauncherOverlay
import com.google.android.libraries.launcherclient.ILauncherOverlayCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.tomsense.android.TomsenseApp
import org.tomsense.android.assist.SessionHost

/**
 * The panel left of the home screen.
 *
 * This is the Launcher3 overlay protocol — the same one the Google app
 * implements to provide Discover, and the reason that slot has only ever had
 * one occupant on most phones. Lawnchair will bind ANY app that exposes it,
 * provided "ignore feed whitelist" is on in the debug menu; without that flag
 * it checks the package against a hardcoded signature list we are not on.
 * Hence a dev build, and hence no fork.
 *
 * ## How the window works, which is the unobvious part
 *
 * The launcher does not host our content. It hands us its own window TOKEN in
 * `windowAttached2`, and we add a window of our own with it. That is why the
 * panel can scroll in perfect lockstep with the workspace: there is no IPC per
 * frame, only a progress float, and each side moves its own surface.
 *
 * It also means the usual Android lifecycle does not apply. There is no
 * Activity, so Compose has to be given owners explicitly — the same problem
 * the assistant overlay solved, so it reuses [SessionHost] rather than
 * inventing a second answer.
 */
class FeedOverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = applicationContext as TomsenseApp

    private var callback: ILauncherOverlayCallback? = null
    private var windowView: View? = null
    private var host: SessionHost? = null
    private var attachedParams: WindowManager.LayoutParams? = null

    /** 0 = closed, 1 = fully open. Driven by the launcher's swipe. */
    private var progress = 0f

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
            attach(lp, cb)
        }

        override fun windowAttached2(bundle: Bundle?, cb: ILauncherOverlayCallback?) {
            @Suppress("DEPRECATION")
            val lp = bundle?.getParcelable<WindowManager.LayoutParams>("layout_params")
            attach(lp, cb)
        }

        override fun windowDetached(isChangingConfigurations: Boolean) {
            runOnMain { detachWindow() }
        }

        override fun startScroll() {
            runOnMain {
                ensureWindow()
                state.visible = true
            }
        }

        override fun onScroll(p: Float) {
            runOnMain {
                progress = p.coerceIn(0f, 1f)
                state.progress = progress
                // Report back so the launcher can fade its own workspace in
                // step with us; without this the two surfaces drift apart
                // visibly during a slow swipe.
                runCatching { callback?.overlayScrollChanged(progress) }
            }
        }

        override fun endScroll() {
            runOnMain { if (progress <= 0.01f) hideWindow() }
        }

        override fun openOverlay(flags: Int) {
            runOnMain {
                ensureWindow()
                progress = 1f
                state.progress = 1f
                state.visible = true
                runCatching { callback?.overlayScrollChanged(1f) }
            }
        }

        override fun closeOverlay(flags: Int) {
            runOnMain { closePanel() }
        }

        override fun onPause() = Unit
        override fun onResume() = Unit

        override fun setActivityState(flags: Int) {
            // Bit 1 is "launcher resumed". Content is refreshed on open rather
            // than here: the launcher resumes constantly, and refetching the
            // feed every time someone returns to the home screen would hammer
            // the worker for a panel nobody opened.
        }

        /**
         * Whether the launcher should offer the panel at all.
         *
         * Answering false makes the swipe do nothing, which is the honest
         * state before the feed is configured — better than a blank page the
         * user has to discover is empty.
         */
        override fun hasOverlayContent(): Boolean = true

        override fun requestVoiceDetection(start: Boolean) = Unit
        override fun getVoiceSearchLanguage(): String = "en"
        override fun isVoiceDetectionRunning(): Boolean = false
        override fun unusedMethod() = Unit
        override fun startSearch(data: ByteArray?, bundle: Bundle?): Boolean = false
    }

    // ─── window plumbing ────────────────────────────────────────────────────

    private fun attach(lp: WindowManager.LayoutParams?, cb: ILauncherOverlayCallback?) {
        callback = cb
        attachedParams = lp
        runOnMain {
            // Status bit 1 tells the launcher the overlay is live and may
            // receive scroll. Without it Lawnchair drops every onScroll on the
            // floor and the panel never moves.
            runCatching { cb?.overlayStatusChanged(STATUS_ATTACHED) }
        }
    }

    private fun ensureWindow() {
        if (windowView != null) return
        val token = attachedParams?.token ?: return

        val sessionHost = SessionHost().also { host = it }
        sessionHost.create()

        val view = ComposeView(this).apply {
            setContent { MaterialTheme { FeedPanel(app, state) } }
        }
        sessionHost.attachTo(view)
        sessionHost.resume()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            // SUB_PANEL rather than an overlay type: we are borrowing the
            // launcher's token, so this is a child of its window and needs no
            // SYSTEM_ALERT_WINDOW permission.
            WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            this.token = token
            gravity = Gravity.START or Gravity.TOP
            windowAnimations = 0
        }

        runCatching {
            getSystemService(WindowManager::class.java).addView(view, params)
            windowView = view
        }.onFailure {
            // A bad token or a launcher that has gone away. Dropping the
            // window rather than crashing keeps the home screen usable — this
            // service runs inside the launcher's swipe gesture.
            host?.destroy()
            host = null
        }
    }

    private fun hideWindow() {
        state.visible = false
    }

    private fun closePanel() {
        progress = 0f
        state.progress = 0f
        state.visible = false
        runCatching { callback?.overlayScrollChanged(0f) }
    }

    private fun detachWindow() {
        windowView?.let { view ->
            runCatching { getSystemService(WindowManager::class.java).removeViewImmediate(view) }
        }
        windowView = null
        host?.destroy()
        host = null
        callback = null
        attachedParams = null
        progress = 0f
    }

    /** Open the full app from a panel tap, and get the panel out of the way. */
    private fun openApp(intent: Intent) {
        runCatching {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        closePanel()
    }

    /**
     * Every AIDL call arrives on a binder thread; windows and Compose are
     * main-thread only. Forgetting this is an intermittent crash rather than a
     * consistent one, which is worse.
     */
    private fun runOnMain(block: () -> Unit) {
        android.os.Handler(mainLooper).post(block)
    }

    private companion object {
        const val STATUS_ATTACHED = 1
    }
}
