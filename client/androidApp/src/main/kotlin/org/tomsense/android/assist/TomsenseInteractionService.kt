package org.tomsense.android.assist

import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.os.Bundle
import android.content.Context
import android.util.Log

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
 * The overlay shown on assistant invocation.
 *
 * Two things must be true for this to beat Gemini, and both are latency, not
 * features:
 *
 *   1. It draws IMMEDIATELY. The UI reads from local SQLite, so there is no
 *      network on the path to first paint. This is the local-first payoff
 *      made visible — the old WebView could not do it at any price.
 *   2. Simple requests are answered by the on-device tier-0 model without
 *      ever opening a socket.
 */
class TomsenseSession(context: Context) : VoiceInteractionSession(context) {

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        // M5: inflate the Compose overlay, start the duplex voice session,
        // and hand any assist context (screen text, screenshot) to the model.
    }

    override fun onHandleAssist(state: AssistState) {
        super.onHandleAssist(state)
        // Screen content arrives here when the user has granted assist access.
        // "What does this say?" / "Summarise this page" without a screenshot
        // round-trip is a thing the big three do and a self-hosted WebView
        // fundamentally cannot.
    }
}
