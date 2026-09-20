package org.tomsense.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import org.tomsense.android.auth.Login
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.tomsense.db.Message
import org.tomsense.sync.ChatRequest
import org.tomsense.sync.SyncStatus
import org.tomsense.sync.WireMessage
import org.tomsense.ui.ChatScreen

class MainActivity : ComponentActivity() {

    private val app by lazy { application as TomsenseApp }
    private lateinit var convId: String

    /** Drives the send/stop button. Compose observes it; no event bus needed. */
    private var generating by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                var ready by remember { mutableStateOf(false) }
                // Re-read on every composition after a resume: the token
                // arrives via a different activity, so this screen has no
                // event telling it sign-in finished.
                var signedIn by remember { mutableStateOf(Login.hasToken(this@MainActivity)) }

                androidx.compose.runtime.DisposableEffect(Unit) {
                    val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                        if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                            signedIn = Login.hasToken(this@MainActivity)
                        }
                    }
                    lifecycle.addObserver(observer)
                    onDispose { lifecycle.removeObserver(observer) }
                }
                val messagesFlow = remember { MutableStateFlow<List<Message>>(emptyList()) }
                val messages by messagesFlow.collectAsState()
                val syncStatus by app.sync.status.collectAsState()

                androidx.compose.runtime.LaunchedEffect(Unit) {
                    // Reuse the most recent conversation, or start one. Both
                    // paths are local — opening the app never waits on a
                    // network round trip.
                    val existing = app.db.schemaQueries.conversationList().executeAsList()
                    convId = existing.firstOrNull()?.id ?: app.repo.createConversation()
                    ready = true
                    // Before rendering steady state: a reply may still be
                    // being written on the edge from a previous launch.
                    resumeActiveRun()
                    app.repo.messages(convId).collect { messagesFlow.value = it }
                }

                // Chat renders regardless of sign-in — history is local and
                // must be readable offline and signed-out. The banner only
                // appears when a reply would fail for lack of a credential.
                if (ready) {
                    // targetSdk 35 means Android 15 draws edge-to-edge with no
                    // opt-out, so anything above the Scaffold must apply the
                    // status-bar inset itself or it renders under the clock.
                    //
                    // statusBarsPadding() CONSUMES the inset, so the Scaffold
                    // and TopAppBar inside ChatScreen see zero and don't pad a
                    // second time. Padding the banner alone would leave the
                    // signed-in case double-spaced.
                    // imePadding for the same reason: edge-to-edge means the
                    // keyboard overlaps content, and adjustResize alone no
                    // longer lifts the input row on Android 15.
                    Column(Modifier.statusBarsPadding().imePadding()) {
                        if (!signedIn) {
                            SignInBanner(onSignIn = { Login.start(this@MainActivity, app.baseUrl) })
                        }
                        ChatScreen(
                            messages = messages,
                            syncLabel = syncStatus.label(),
                            onSend = ::send,
                            isGenerating = generating,
                            onStop = ::stop,
                            onRegenerate = ::regenerate,
                            onOpenSettings = {
                                startActivity(
                                    android.content.Intent(
                                        this@MainActivity,
                                        SettingsActivity::class.java,
                                    ),
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    /**
     * Persist first, then generate.
     *
     * The user's turn is committed to SQLite before any network call, so
     * pressing send with no connection still records the message and it
     * syncs later. The generation is a separate concern that may fail.
     */
    private fun send(text: String) {
        lifecycleScope.launch {
            app.repo.appendMessage(convId, "user", text)
            val assistantId = app.repo.appendMessage(convId, "assistant", "")
            val history = historyForModel()
            consume(assistantId) { app.chat.stream(ChatRequest(convId, history)) }
        }
    }

    /**
     * Answer the last user turn again, into the same message row.
     *
     * The stale answer is dropped from the history first, or the model reads
     * its own previous attempt as context and tends to simply agree with it.
     */
    private fun regenerate() {
        lifecycleScope.launch {
            val last = app.db.schemaQueries.messagesFor(convId).executeAsList().lastOrNull()
            if (last == null || last.role != "assistant") return@launch

            app.repo.resetMessage(last.id)
            val history = historyForModel(exclude = last.id)
            consume(last.id) { app.chat.stream(ChatRequest(convId, history)) }
        }
    }

    /** Stop the generation in flight, on the edge — not just locally. */
    private fun stop() {
        val runId = activeRun()?.first ?: return
        lifecycleScope.launch { runCatching { app.chat.cancel(runId) } }
    }

    /**
     * Rejoin a run left behind by a previous launch.
     *
     * This is the visible payoff of moving generation onto the edge: force-quit
     * the app mid-reply, reopen it, and the answer is still being written.
     */
    private fun resumeActiveRun() {
        val (runId, msgId) = activeRun() ?: return
        lifecycleScope.launch {
            val stillGoing = app.chat.activeRuns().any { it.id == runId }
            if (!stillGoing) {
                clearActiveRun()
                return@launch
            }
            consume(msgId, runId) { app.chat.attach(runId) }
        }
    }

    private fun historyForModel(exclude: String? = null): List<WireMessage> =
        app.db.schemaQueries.messagesFor(convId).executeAsList()
            .filter { it.id != exclude && it.content.isNotBlank() }
            .map { WireMessage(it.role, it.content) }

    /**
     * Drive one generation into [assistantId], whether newly started or rejoined.
     *
     * Content and reasoning are rebuilt from scratch on every event rather than
     * appended to what is on screen, because a reattach replays the answer so
     * far — appending would print the first half twice.
     */
    private suspend fun consume(
        assistantId: String,
        knownRun: String? = null,
        source: () -> kotlinx.coroutines.flow.Flow<org.tomsense.sync.ChatEvent>,
    ) {
        generating = true
        var runId = knownRun
        val buffer = StringBuilder()
        val thinking = StringBuilder()

        runCatching {
            source().collect { ev ->
                when (ev.type) {
                    "run" -> ev.runId?.let {
                        runId = it
                        rememberActiveRun(it, assistantId)
                    }
                    "text" -> {
                        buffer.append(ev.text.orEmpty())
                        app.repo.updateStreamingContent(assistantId, buffer.toString())
                    }
                    "reasoning" -> {
                        thinking.append(ev.text.orEmpty())
                        app.repo.updateStreamingReasoning(assistantId, thinking.toString())
                    }
                    // heartbeat carries no payload; it exists so a long
                    // silent reasoning stretch isn't mistaken for a dead
                    // connection. Nothing to render.
                    "heartbeat" -> Unit

                    // End of a ROUND, not of the run. Tool calls here mean the
                    // edge is parked waiting for results, and no device tools
                    // exist yet (migration phase B) — so answer honestly and
                    // let the model recover, rather than leaving the run
                    // parked until it is swept away.
                    "done" -> {
                        val calls = ev.toolCalls.orEmpty()
                        val id = runId
                        if (calls.isNotEmpty() && id != null) {
                            app.chat.sendToolResults(
                                id,
                                calls.map {
                                    org.tomsense.sync.ToolResult(
                                        id = it.id,
                                        name = it.name,
                                        content = """{"error":"tool ${it.name} is not available on this device"}""",
                                    )
                                },
                            )
                        }
                    }
                    "end" -> app.repo.finishStreaming(assistantId)
                }
            }
        }.onFailure {
            app.repo.updateStreamingContent(
                assistantId,
                buffer.toString().ifEmpty { "[offline — will retry]" },
            )
            app.repo.finishStreaming(assistantId)
        }

        generating = false
        clearActiveRun()
    }

    // ─── active run, device-local ────────────────────────────────────────────
    //
    // Kept in prefs rather than on the message row on purpose. A run id is
    // meaningful only to the device that started it; putting it in the synced
    // message table would push device-local scratch state to every other
    // device and to D1.

    private fun prefs() = getSharedPreferences("tomsense", MODE_PRIVATE)

    private fun rememberActiveRun(runId: String, msgId: String) {
        prefs().edit().putString(ACTIVE_RUN, "$runId|$msgId").apply()
    }

    private fun activeRun(): Pair<String, String>? {
        val raw = prefs().getString(ACTIVE_RUN, null) ?: return null
        val parts = raw.split("|")
        return if (parts.size == 2) parts[0] to parts[1] else null
    }

    private fun clearActiveRun() {
        prefs().edit().remove(ACTIVE_RUN).apply()
    }

    private companion object {
        const val ACTIVE_RUN = "active_run"
    }
}

@androidx.compose.runtime.Composable
private fun SignInBanner(onSignIn: () -> Unit) {
    androidx.compose.material3.Surface(
        color = androidx.compose.material3.MaterialTheme.colorScheme.tertiaryContainer,
        modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = androidx.compose.ui.Modifier.padding(12.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            androidx.compose.material3.Text(
                "Not signed in — replies need Cloudflare Access.",
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                modifier = androidx.compose.ui.Modifier.weight(1f),
            )
            androidx.compose.material3.TextButton(onClick = onSignIn) {
                androidx.compose.material3.Text("Sign in")
            }
        }
    }
}

private fun SyncStatus.label(): String = when (this) {
    SyncStatus.Idle -> ""
    SyncStatus.Syncing -> "syncing"
    is SyncStatus.Error -> "offline"
}
