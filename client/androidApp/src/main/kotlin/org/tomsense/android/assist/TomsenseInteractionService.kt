package org.tomsense.android.assist

import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.app.assist.AssistStructure.ViewNode
import android.os.Bundle
import android.content.Context
import android.util.Log
import android.view.View
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.ComposeView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.tomsense.android.Launch
import org.tomsense.android.TomsenseApp
import org.tomsense.android.ui.TomsenseTheme
import org.tomsense.android.TurnRunner
import org.tomsense.android.voice.VoiceController
import org.tomsense.sync.WireMessage
import org.tomsense.tools.matchLocalIntent

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
 * An overlay over whatever you were doing, not a launch into the app. That is
 * the whole point of holding the assistant role: an interruption should answer
 * and get out of the way, leaving the screen you were on visible above it.
 *
 * Screen text comes from the ASSIST API rather than an AccessibilityService.
 * That distinction matters: assist context is handed over per invocation, by
 * the user, at the moment they ask. An accessibility service reads everything,
 * always.
 */
class TomsenseSession(context: Context) : VoiceInteractionSession(context) {

    private val host = SessionHost()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val app get() = context.applicationContext as TomsenseApp

    /** Set by onHandleAssist, which can arrive before or after onShow. */
    private var screenText: String? = null
    private var convId: String? = null
    private var turn: Job? = null

    /** The next reply is spoken: the question arrived by voice. */
    private var spokeLast = false
    private var voiceLoaded = false

    // Cheap to construct — the recogniser and TTS engine are only built on
    // first use, so an invocation that stays typed pays nothing for it.
    private val voice: VoiceController = VoiceController(
        context = context,
        scope = scope,
        remoteTts = { text ->
            runCatching { app.edge.speak(text, voice.remoteVoice.ifBlank { null }) }.getOrNull()
        },
        onFinalTranscript = { heard ->
            spokeLast = true
            state.draft = heard
            send()
        },
    )

    // Type stated explicitly: the callbacks below close over `state`, and
    // inferring the type from an initialiser that references itself sends the
    // compiler into a recursion it reports as an unresolved reference.
    private val state: AssistUiState = AssistUiState(
        onSend = { send() },
        onStop = { stop() },
        onDismiss = { hide() },
        onOpenApp = { openApp() },
        onDraftChange = { state.draft = it },
        onMic = { toggleMic() },
        voice = voice,
    )

    override fun onCreate() {
        super.onCreate()
        host.create()
    }

    override fun onCreateContentView(): View {
        val view = ComposeView(context).apply {
            // opaque = false: this floats a card over whatever app the user
            // invoked the assistant from — filling the window would hide it.
            setContent { TomsenseTheme(opaque = false) { AssistOverlay(state) } }
        }
        // Must happen before the view is attached, or Compose throws looking
        // for owners that are not there yet.
        host.attachTo(view)
        host.resume()
        return view
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        // A new chat every time. The system is free to REUSE this session for
        // the next power-button hold rather than building a fresh one, and the
        // conversation id and last reply used to survive in it — so the
        // overlay sometimes opened on the previous exchange and appended the
        // new question to that old conversation.
        turn?.cancel()
        turn = null
        convId = null
        spokeLast = false
        state.reset()
        state.hasScreenContext = screenText != null
        state.canOpenApp = true
    }

    override fun onHandleAssist(assist: AssistState) {
        super.onHandleAssist(assist)
        screenText = runCatching { readScreen(assist) }.getOrNull()
        // Ordering between the two callbacks is not guaranteed, so this
        // reflects whatever arrived. Without it the context is captured and
        // then dropped roughly half the time.
        state.hasScreenContext = screenText != null
        // Fresh screen, fresh default. A new invocation is a new question, so
        // it should not inherit a toggle turned off during the last one.
        state.useScreenContext = true
    }

    override fun onHide() {
        super.onHide()
        // Dismissed means quiet: no mic left open and no reply still
        // talking over whatever app the user went back to.
        voice.stopListening()
        voice.stopSpeaking()
        // Cleared on hide rather than on show: onHandleAssist may arrive
        // BEFORE onShow, so clearing there could throw away this invocation's
        // screen. Clearing here only ever drops the last one's.
        screenText = null
        host.pause()
    }

    override fun onDestroy() {
        turn?.cancel()
        voice.shutdown()
        scope.cancel()
        host.destroy()
        super.onDestroy()
    }

    // ─── the turn ───────────────────────────────────────────────────────────

    private fun send() {
        val text = state.draft.trim()
        if (text.isEmpty() || state.generating) return

        state.draft = ""
        state.asked = text
        state.reply = ""
        state.needsPermission = false

        turn = scope.launch {
            val id = convId ?: newConversation(text).also { convId = it }

            // Tier 0 first: "set a timer for ten minutes" from a power-button
            // hold should never reach the network. This is the invocation that
            // benefits most from it.
            // Gated on whether the screen will actually be SENT, not on
            // whether it was captured: with the toggle off this is a plain
            // "set a timer" again and should stay off the network.
            if (screenText == null || !state.useScreenContext) {
                matchLocalIntent(text)?.let { intent ->
                    val args = buildJsonObject {
                        intent.args.forEach { (k, v) ->
                            when (v) {
                                is Int -> put(k, JsonPrimitive(v))
                                is Boolean -> put(k, JsonPrimitive(v))
                                else -> put(k, JsonPrimitive(v.toString()))
                            }
                        }
                    }
                    val ok = runCatching { app.tools.call(intent.tool, args) }.getOrNull()
                    if (ok != null) {
                        app.repo.appendMessage(id, "user", text)
                        val replyId = app.repo.appendMessage(id, "assistant", intent.summary)
                        app.repo.finishStreaming(replyId)
                        state.reply = intent.summary
                        return@launch
                    }
                }
            }

            val runner = TurnRunner(
                app = app,
                onGenerating = { state.generating = it },
                onNotices = { state.notices = it },
            )

            // Honour the toggle. The text stays captured either way, so the
            // user can change their mind before sending — turning it off no
            // longer destroys it.
            val context = screenText?.takeIf { state.useScreenContext }?.let {
                listOf(
                    WireMessage(
                        "system",
                        "The user invoked the assistant while this was on their screen. " +
                            "Use it only if their message refers to it:\n\n" + it,
                    ),
                )
            } ?: emptyList()

            val speakThis = spokeLast
            spokeLast = false
            if (speakThis) voice.beginReply()
            val assistantId = runner.send(
                id,
                text,
                think = state.think,
                extraContext = context,
                onText = if (speakThis) voice::speakStreaming else null,
            )
            // Spent: a follow-up in the same overlay is about the conversation,
            // not still about the screen they have since left.
            screenText = null
            state.hasScreenContext = false

            val reply = app.db.schemaQueries.messageById(assistantId).executeAsOneOrNull()
            state.reply = reply?.content.orEmpty()
            if (speakThis) voice.endReply(state.reply)
            state.needsPermission = state.reply.contains("permission not granted", ignoreCase = true)
        }
    }

    /**
     * The mic. A session has no Activity, so it cannot show the permission
     * dialog — without the grant it sends you to the app, which asks the
     * first time its own mic is tapped.
     */
    private fun toggleMic() {
        if (!VoiceController.hasMicPermission(context)) {
            state.needsPermission = true
            return
        }
        if (!voiceLoaded) {
            voiceLoaded = true
            scope.launch {
                runCatching { app.providers.prefs() }.getOrNull()?.let { voice.remoteVoice = it.ttsVoice }
            }
        }
        voice.toggleListening()
    }

    private fun stop() {
        turn?.cancel()
        voice.stopSpeaking()
        state.generating = false
    }

    /**
     * A conversation per invocation.
     *
     * Left UNTITLED on purpose now: TurnRunner names it from the whole
     * exchange once the reply lands. This used to truncate the question to 48
     * characters, which was better than a drawer full of "New chat" but is
     * strictly worse than a real title — and since a non-blank title is what
     * tells the namer to leave a conversation alone, keeping it would have
     * suppressed the model on exactly the surface that needs it most.
     */
    @Suppress("UNUSED_PARAMETER")
    private suspend fun newConversation(firstMessage: String): String =
        app.repo.createConversation()

    /** Hand the conversation to the full app and get out of the way. */
    private fun openApp() {
        // THIS conversation, not whichever one the app had open last — that
        // was the other way the overlay led back to an old chat.
        Launch.openWith(
            context,
            prefill = state.draft.takeIf { it.isNotBlank() },
            conversationId = convId,
        )
        hide()
    }

    // ─── screen context ─────────────────────────────────────────────────────

    /**
     * Flatten the assist structure into readable text.
     *
     * Capped, because a long article yields kilobytes of view text and the
     * point is a fast answer — not paying for a page of markup nobody asked
     * about.
     */
    private fun readScreen(assist: AssistState): String? {
        val structure = assist.assistStructure ?: return null
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
