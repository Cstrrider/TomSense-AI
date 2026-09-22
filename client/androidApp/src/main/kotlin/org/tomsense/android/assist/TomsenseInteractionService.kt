package org.tomsense.android.assist

import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.app.assist.AssistStructure.ViewNode
import android.os.Bundle
import android.content.Context
import android.util.Log
import org.tomsense.android.Launch

/**
 * Assistant role (spec §12) — the single highest-value thing a native client
 * unlocks, and the reason this rewrite exists at all.
 *
 * Once the user selects TomSense in Settings > Apps > Default apps > Digital
 * assistant app, this receives the power-button hold and the lock-screen
 * invocation. The role CANNOT be claimed programmatically; the app can only
 * send the user to the picker.
 *
 * Wake-word detection also lives here rather than in the UI process, because
 * a VoiceInteractionService is allowed to keep an always-on hotword pipeline
 * where a normal app is not.
 */
class TomsenseInteractionService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
        Log.i(TAG, "assistant role active")
        // Wake word (Porcupine / openWakeWord) starts here — M5.
        // It stays off until the user explicitly enables it: an always-on mic
        // is exactly the kind of thing that should never be a silent default.
    }

    companion object {
        const val TAG = "TomsenseAssist"
    }
}

/** Hosts the session that appears when the assistant is invoked. */
class TomsenseSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        TomsenseSession(this)
}

/**
 * What happens on a power-button hold.
 *
 * Previously `onShow` was a comment, so holding the power button with
 * TomSense set as assistant produced a blank overlay — the role was granted
 * and delivered nothing.
 *
 * This opens a turn instead, carrying whatever the system offered about the
 * current screen. That uses the ASSIST API rather than an AccessibilityService,
 * which matters: assist context is handed over per-invocation, by the user, at
 * the moment they ask for it. An accessibility service reads everything,
 * always, and is the wrong trade for this.
 *
 * Not yet the in-place overlay the spec describes — that needs a Compose
 * surface hosted in the session window, and voice with it. This is the honest
 * intermediate: the role does something real and the screen text is not lost.
 */
class TomsenseSession(context: Context) : VoiceInteractionSession(context) {

    /** Set by onHandleAssist, which can arrive before or after onShow. */
    private var screenText: String? = null
    private var shown = false

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        shown = true
        open()
    }

    override fun onHandleAssist(state: AssistState) {
        super.onHandleAssist(state)
        screenText = runCatching { readScreen(state) }.getOrNull()
        // Ordering between the two callbacks is not guaranteed, so whichever
        // arrives second does the work. Without this the context is captured
        // and then dropped roughly half the time.
        if (shown) open()
    }

    private fun open() {
        Launch.openWith(context, screenContext = screenText)
        hide()
    }

    /**
     * Flatten the assist structure into readable text.
     *
     * Capped, because a long article yields kilobytes of view text and the
     * whole point is a fast turn — not paying for a page of markup the user
     * did not ask about.
     */
    private fun readScreen(state: AssistState): String? {
        val structure = state.assistStructure ?: return null
        val out = StringBuilder()

        for (i in 0 until structure.windowNodeCount) {
            appendNode(structure.getWindowNodeAt(i).rootViewNode, out)
            if (out.length >= MAX_SCREEN_CHARS) break
        }
        return out.toString().trim().takeIf { it.isNotEmpty() }?.take(MAX_SCREEN_CHARS)
    }

    private fun appendNode(node: ViewNode?, out: StringBuilder) {
        if (node == null || out.length >= MAX_SCREEN_CHARS) return
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            out.append(it).append('\n')
        }
        for (i in 0 until node.childCount) appendNode(node.getChildAt(i), out)
    }

    private companion object {
        const val MAX_SCREEN_CHARS = 4000
    }
}
