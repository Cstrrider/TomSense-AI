package org.tomsense.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
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
import org.tomsense.data.SearchResults
import org.tomsense.data.deviceSystemPrompt
import org.tomsense.data.exportFileName
import org.tomsense.data.exportMarkdown
import org.tomsense.db.Message
import org.tomsense.sync.ChatRequest
import org.tomsense.sync.SyncStatus
import org.tomsense.sync.WireMessage
import org.tomsense.android.tools.PermissionGate
import org.tomsense.tools.schemas
import org.tomsense.android.ui.TomsenseTheme
import org.tomsense.ui.ChatScreen
import org.tomsense.ui.ConversationDrawer

/**
 * The two top-level surfaces.
 *
 * Kept as a plain enum rather than a nav graph: there are two destinations,
 * neither takes arguments, and the chat screen's state has to survive a switch
 * to News and back — which a NavHost would tear down by default.
 */
enum class HomeTab { Chat, News }

class MainActivity : ComponentActivity() {

    private val app by lazy { application as TomsenseApp }

    /**
     * The turn loop, shared with the assistant overlay.
     *
     * Both entry points drive the same wire protocol, and it is subtle enough
     * — `done` is a round boundary, tool results must go back even when they
     * all failed, a reattach replays — that a second copy would drift.
     */
    private val runner by lazy {
        TurnRunner(
            app = app,
            memory = object : TurnRunner.RunMemory {
                override fun remember(runId: String, msgId: String) = rememberActiveRun(runId, msgId)
                override fun clear() = clearActiveRun()
            },
            onGenerating = { generating = it },
            onNotices = { notices = it },
        )
    }

    /**
     * The open conversation.
     *
     * Compose state rather than a plain field: switching chats from the drawer
     * has to recompose the message list, and it is null only for the instant
     * before the first one is resolved at startup.
     */
    private var convId by mutableStateOf<String?>(null)

    /** Drives the send/stop button. Compose observes it; no event bus needed. */
    private var generating by mutableStateOf(false)

    /** Routing overrides for the current turn, surfaced by the edge. */
    private var notices by mutableStateOf<List<String>>(emptyList())

    /** Think mode. Sticky across turns until switched off. */
    private var think by mutableStateOf(false)

    /** R2 keys uploaded and staged for the next send. */
    private var pending by mutableStateOf<List<String>>(emptyList())

    /** Text handed in from outside — share sheet, ASK intent, assistant. */
    private var prefill by mutableStateOf("")

    /**
     * Speech in and out.
     *
     * Created lazily so the recogniser and TTS engine are not built for a
     * session that never uses voice — both are surprisingly expensive to
     * initialise, and most turns are typed.
     */
    private val voice by lazy {
        org.tomsense.android.voice.VoiceController(
            context = this,
            scope = lifecycleScope,
            remoteTts = { text ->
                runCatching { app.edge.speak(text, voicePref.ifBlank { null }) }.getOrNull()
            },
            onFinalTranscript = { heard ->
                spokeLast = true
                send(heard)
            },
        )
    }

    /** aura-2 speaker, or empty for the device engine. Loaded from prefs. */
    private var voicePref by mutableStateOf("")

    /**
     * What was on screen when the assistant was invoked.
     *
     * Sent as context on the next turn and then cleared. Deliberately NOT put
     * in the composer: it is background, not something the user typed, and
     * showing it as their words would be both confusing and wrong.
     */
    private var screenContext: String? = null

    /** Registered in onCreate — a picker launcher created later than that throws. */
    private lateinit var picker: androidx.activity.result.ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Must happen before RESUMED — registerForActivityResult throws if it
        // is called later. This is what lets a tool ask for a permission at
        // the moment the model needs it.
        PermissionGate.attach(this)
        applyIncoming(intent)

        // Registered before RESUMED for the same reason as PermissionGate.
        // GetContent rather than a storage permission: the picker grants
        // access to the one file chosen, so the app never asks to read
        // everything in order to send one photo.
        picker = registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.GetContent(),
        ) { uri -> uri?.let(::attach) }

        setContent {
            TomsenseTheme {
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
                val conversations by app.repo.conversations()
                    .collectAsState(initial = emptyList())

                // Loaded once: which engine speaks is a setting, and asking
                // the edge for it on every utterance would add a round trip to
                // the one path that is trying to avoid them.
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    runCatching { app.providers.prefs() }.getOrNull()?.let {
                        voicePref = it.ttsVoice
                        voice.remoteVoice = it.ttsVoice
                    }
                }

                androidx.compose.runtime.LaunchedEffect(Unit) {
                    // Reopen whatever was last open, falling back to the most
                    // recent chat and then to a new one. All three paths are
                    // local — opening the app never waits on the network.
                    //
                    // The remembered id is checked against the table rather
                    // than trusted: the chat may have been deleted on another
                    // device, and a dangling id would open an empty screen
                    // with no way back to a real conversation.
                    val existing = app.db.schemaQueries.conversationList().executeAsList()
                    val remembered = prefs().getString(LAST_CONV, null)
                    convId = existing.firstOrNull { it.id == remembered }?.id
                        ?: existing.firstOrNull()?.id
                        ?: app.repo.createConversation()
                    ready = true
                    // Before rendering steady state: a reply may still be
                    // being written on the edge from a previous launch.
                    resumeActiveRun()
                }

                // Re-subscribed whenever the open chat changes. Keyed on
                // convId so switching chats swaps the message list instead of
                // leaving the previous conversation's collector running.
                androidx.compose.runtime.LaunchedEffect(convId) {
                    val id = convId ?: return@LaunchedEffect
                    prefs().edit().putString(LAST_CONV, id).apply()
                    messagesFlow.value = emptyList()
                    app.repo.messages(id).collect { messagesFlow.value = it }
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
                    val drawerState = rememberDrawerState(DrawerValue.Closed)
                    val scope = rememberCoroutineScope()
                    var query by remember { mutableStateOf("") }
                    var results by remember { mutableStateOf<SearchResults?>(null) }

                    var tab by remember { mutableStateOf(HomeTab.Chat) }

                    // The SAME state class the home-screen panel uses, so the
                    // taste vector, the refetch cooldown and the feedback calls
                    // behave identically on both surfaces. Remembered here so
                    // switching tabs does not refetch.
                    val feedState = remember {
                        org.tomsense.android.feed.FeedPanelState(
                            onOpenApp = { startActivity(it) },
                            onDismiss = { tab = HomeTab.Chat },
                        )
                    }

                    // Debounced so a fast typist does not run a query per
                    // keystroke. Restarting on every change cancels the
                    // previous wait, so only the last pause actually searches.
                    androidx.compose.runtime.LaunchedEffect(query) {
                        val q = query.trim()
                        if (q.isEmpty()) {
                            results = null
                        } else {
                            kotlinx.coroutines.delay(150)
                            results = app.repo.search(q)
                        }
                    }

                    ModalNavigationDrawer(
                        drawerState = drawerState,
                        drawerContent = {
                            ModalDrawerSheet {
                                ConversationDrawer(
                                    conversations = conversations,
                                    selectedId = convId,
                                    query = query,
                                    onQueryChange = { query = it },
                                    results = results,
                                    onSelect = { id ->
                                        convId = id
                                        query = ""
                                        scope.launch { drawerState.close() }
                                    },
                                    // Opening a hit only opens its chat for
                                    // now; scrolling to the exact message
                                    // needs the list to expose a scroll
                                    // target, which it does not yet.
                                    onOpenMessage = { cid, _ ->
                                        convId = cid
                                        query = ""
                                        scope.launch { drawerState.close() }
                                    },
                                    onNew = {
                                        scope.launch {
                                            convId = app.repo.createConversation()
                                            query = ""
                                            drawerState.close()
                                        }
                                    },
                                    onRename = { id, title ->
                                        scope.launch { app.repo.rename(id, title) }
                                    },
                                    onPin = { id, pinned ->
                                        scope.launch { app.repo.setPinned(id, pinned) }
                                    },
                                    onDelete = ::deleteConversation,
                                )
                            }
                        },
                    ) {
                        Column(Modifier.statusBarsPadding().imePadding()) {
                            if (!signedIn) {
                                SignInBanner(
                                    onSignIn = { Login.start(this@MainActivity, app.baseUrl) },
                                )
                            }
                            Box(Modifier.weight(1f)) {
                                if (tab == HomeTab.News) {
                                    org.tomsense.android.feed.NewsScreen(app, feedState)
                                } else {
                                    ChatScreen(
                                    messages = messages,
                                    syncLabel = syncStatus.label(),
                                    onSend = ::send,
                                    isGenerating = generating,
                                    onStop = ::stop,
                                    onRegenerate = ::regenerate,
                                    title = conversations.firstOrNull { it.id == convId }
                                        ?.title.orEmpty(),
                                    onOpenDrawer = { scope.launch { drawerState.open() } },
                                    prefill = prefill,
                                    onPrefillConsumed = { prefill = "" },
                                    notices = notices,
                                    thinkEnabled = think,
                                    onThinkChange = { think = it },
                                    onAttach = { picker.launch("*/*") },
                                    onMic = ::toggleMic,
                                    listening = voice.phase == org.tomsense.android.voice.VoiceController.Phase.Listening,
                                    speaking = voice.phase == org.tomsense.android.voice.VoiceController.Phase.Speaking,
                                    partialTranscript = voice.partial,
                                    pendingAttachments = pending,
                                    onRemoveAttachment = { pending = pending - it },
                                    loadAttachment = { key ->
                                        runCatching { app.edge.downloadFile(key) }.getOrNull()
                                    },
                                    // Branching needs something to branch FROM,
                                    // and exporting an empty chat produces a file
                                    // with a heading and nothing under it.
                                    onBranch = if (messages.isNotEmpty()) ::branchHere else null,
                                    onExport = if (messages.isNotEmpty()) ::exportChat else null,
                                    onShare = if (messages.isNotEmpty()) ::shareChat else null,
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

                            NavigationBar {
                                NavigationBarItem(
                                    selected = tab == HomeTab.Chat,
                                    onClick = { tab = HomeTab.Chat },
                                    icon = {
                                        Icon(
                                            Icons.AutoMirrored.Filled.Chat,
                                            contentDescription = null,
                                        )
                                    },
                                    label = { Text("Chat") },
                                )
                                NavigationBarItem(
                                    selected = tab == HomeTab.News,
                                    onClick = { tab = HomeTab.News },
                                    icon = {
                                        Icon(
                                            Icons.Filled.Newspaper,
                                            contentDescription = null,
                                        )
                                    },
                                    label = { Text("News") },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * A second invocation while already running.
     *
     * singleTop plus CLEAR_TOP means the share sheet reuses this instance
     * rather than stacking another, so without this the second share would be
     * silently ignored — the same bug as before, just harder to notice.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyIncoming(intent)
    }

    /** Apply whatever an incoming intent carried. */
    private fun applyIncoming(intent: Intent?) {
        val incoming = Launch.read(intent)
        if (incoming.isEmpty) return

        // Before the rest: the others act on whatever chat is open, so the
        // chat has to be the right one first.
        incoming.conversationId?.takeIf { it.isNotBlank() }?.let { id ->
            convId = id
            prefs().edit().putString(LAST_CONV, id).apply()
        }

        incoming.prefill?.let { prefill = it }
        incoming.screenContext?.let { screenContext = it }
        incoming.imageUri?.let { attach(it) }
    }

    /** True when the turn in flight arrived through the microphone. */
    private var spokeLast = false

    override fun onDestroy() {
        voice.shutdown()
        PermissionGate.detach(this)
        super.onDestroy()
    }

    /**
     * Persist first, then generate.
     *
     * The user's turn is committed to SQLite before any network call, so
     * pressing send with no connection still records the message and it
     * syncs later. The generation is a separate concern that may fail.
     */
    private fun send(text: String) {
        val id = convId ?: return

        // Tier 0. Only when the turn is plain text — an attachment or screen
        // context means the user is asking ABOUT something, which no pattern
        // here can answer.
        if (pending.isEmpty() && screenContext == null) {
            org.tomsense.tools.matchLocalIntent(text)?.let { return handleLocally(id, text, it) }
        }

        sendToModel(id, text)
    }

    /**
     * Open or close the microphone.
     *
     * The permission is requested HERE, on first use, rather than at launch —
     * a prompt that arrives the moment you tap a mic explains itself.
     */
    private fun toggleMic() {
        val v = voice
        when (v.phase) {
            org.tomsense.android.voice.VoiceController.Phase.Listening -> v.stopListening()
            // Tapping the mic mid-reply is barge-in: stop talking and listen.
            org.tomsense.android.voice.VoiceController.Phase.Speaking -> {
                v.stopSpeaking()
                requestMicThen { v.startListening() }
            }
            else -> requestMicThen { v.startListening() }
        }
    }

    private fun requestMicThen(action: () -> Unit) {
        lifecycleScope.launch {
            val granted = org.tomsense.android.tools.PermissionGate.require(
                this@MainActivity,
                org.tomsense.android.voice.VoiceController.MIC_PERMISSION,
            )
            if (granted) action() else toast("Microphone permission needed to speak.")
        }
    }

    /** The ordinary path: persist the turn, then ask the model. */
    private fun sendToModel(id: String, text: String) {
        val attached = pending
        // Cleared here rather than after the send completes: they belong to
        // the message being sent, and leaving them staged would silently
        // attach them to the NEXT one too.
        pending = emptyList()
        val speakThis = spokeLast
        spokeLast = false
        lifecycleScope.launch {
            if (speakThis) voice.beginReply()
            val assistantId = runner.send(
                convId = id,
                text = text,
                think = think,
                attachments = attached,
                extraContext = screenContextMessages(),
                onText = if (speakThis) voice::speakStreaming else null,
            )
            if (speakThis) {
                val full = app.db.schemaQueries.messageById(assistantId)
                    .executeAsOneOrNull()?.content.orEmpty()
                voice.endReply(full)
            }
            // Spent: a later turn is about the conversation, not still about a
            // screen the user has since left.
            screenContext = null
        }
    }

    /**
     * Fork the open conversation at its last turn.
     *
     * The fork is opened immediately — branching and then staying in the
     * original is a reliable way to type the next message into the wrong one.
     */
    private fun branchHere() {
        val id = convId ?: return
        lifecycleScope.launch {
            val last = app.db.schemaQueries.messagesFor(id).executeAsList().lastOrNull()
                ?: return@launch
            app.repo.branchConversation(id, last.id)?.let { convId = it }
        }
    }

    /**
     * Export the open conversation as markdown, via the system share sheet.
     *
     * Shared as EXTRA_TEXT rather than written to a file: a chat export is
     * usually on its way into a note, a message or an issue, and going through
     * a file would mean a FileProvider, a cache directory and a cleanup story
     * for something the user wants to paste.
     */
    private fun exportChat() {
        val id = convId ?: return
        lifecycleScope.launch {
            val conv = app.db.schemaQueries.conversationById(id).executeAsOneOrNull()
                ?: return@launch
            val msgs = app.db.schemaQueries.messagesFor(id).executeAsList()
            val markdown = exportMarkdown(conv, msgs)

            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_TITLE, exportFileName(conv))
                putExtra(android.content.Intent.EXTRA_SUBJECT, conv.title.ifBlank { "Conversation" })
                putExtra(android.content.Intent.EXTRA_TEXT, markdown)
            }
            startActivity(android.content.Intent.createChooser(send, "Export chat"))
        }
    }

    /**
     * Publish the open conversation and hand the link to the share sheet.
     *
     * Unlike everything else in this class this one REQUIRES the network —
     * the token is minted at the edge, because a client that could choose its
     * own could choose a guessable one. So it reports failure rather than
     * optimistically showing a link that resolves to nothing.
     */
    private fun shareChat() {
        val id = convId ?: return
        lifecycleScope.launch {
            val result = runCatching { app.edge.setShared(id, shared = true) }.getOrNull()
            val token = result?.shareToken
            if (token == null) {
                android.widget.Toast.makeText(
                    this@MainActivity,
                    result?.error ?: "Couldn't create a link — check your connection.",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
                return@launch
            }

            // Mirror it locally so the chat shows as shared while offline.
            app.repo.setShareToken(id, token)

            val link = "${app.baseUrl}/share/$token"
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_TEXT, link)
            }
            startActivity(android.content.Intent.createChooser(send, "Share chat"))
        }
    }

    /**
     * Delete a conversation, and leave the user somewhere valid.
     *
     * Deleting the OPEN chat has to move them off it — otherwise the screen
     * keeps rendering a conversation that no longer exists and the next
     * message would be written into a tombstoned row.
     */
    private fun deleteConversation(id: String) {
        lifecycleScope.launch {
            app.repo.deleteConversation(id)
            if (convId == id) {
                val remaining = app.db.schemaQueries.conversationList().executeAsList()
                convId = remaining.firstOrNull()?.id ?: app.repo.createConversation()
            }
        }
    }

    /**
     * Answer the last user turn again, into the same message row.
     *
     * The stale answer is dropped from the history first, or the model reads
     * its own previous attempt as context and tends to simply agree with it.
     */
    private fun regenerate() {
        val id = convId ?: return
        lifecycleScope.launch {
            val last = app.db.schemaQueries.messagesFor(id).executeAsList().lastOrNull()
            if (last == null || last.role != "assistant") return@launch

            app.repo.resetMessage(last.id)
            val history = runner.historyFor(id, exclude = last.id)
            runner.consume(last.id) {
                app.chat.stream(
                    ChatRequest(id, history, tools = app.tools.schemas(), think = think),
                )
            }
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
            runner.consume(msgId, runId) { app.chat.attach(runId) }
        }
    }

    /**
     * Answer without the network.
     *
     * The device tool runs directly and the reply is written straight into the
     * conversation, so it syncs and reads like any other turn — with a footer
     * saying it was handled on device, because a reply that cost nothing and
     * involved no model should not be silently indistinguishable from one that
     * did.
     *
     * A tool failure falls back to the normal path rather than reporting an
     * error: if the shortcut cannot do it, the model may still be able to.
     */
    private fun handleLocally(convId: String, text: String, intent: org.tomsense.tools.LocalIntent) {
        lifecycleScope.launch {
            val args = kotlinx.serialization.json.buildJsonObject {
                intent.args.forEach { (k, v) ->
                    when (v) {
                        is Int -> put(k, kotlinx.serialization.json.JsonPrimitive(v))
                        is Boolean -> put(k, kotlinx.serialization.json.JsonPrimitive(v))
                        else -> put(k, kotlinx.serialization.json.JsonPrimitive(v.toString()))
                    }
                }
            }

            val ok = runCatching { app.tools.call(intent.tool, args) }.getOrNull()
            if (ok == null) {
                sendToModel(convId, text)
                return@launch
            }

            app.repo.appendMessage(convId, "user", text)
            val replyId = app.repo.appendMessage(convId, "assistant", intent.summary)
            app.repo.recordUsage(replyId, LOCAL_USAGE, LOCAL_MODEL)
            app.repo.finishStreaming(replyId)
            // Spoken as well, when the turn arrived by voice: a timer set by
            // speaking should answer by speaking.
            if (spokeLast) {
                voice.beginReply()
                voice.endReply(intent.summary)
            }
        }
    }

    /** Screen context as a single-use system message, or nothing. */
    private fun screenContextMessages(): List<WireMessage> =
        screenContext?.let {
            listOf(
                WireMessage(
                    "system",
                    "The user invoked the assistant while this was on their screen. " +
                        "Use it only if their message refers to it:\n\n" + it,
                ),
            )
        } ?: emptyList()

    /**
     * Prepare and upload one picked file, then stage its key.
     *
     * Upload happens at PICK time, not at send time: it is the slow part, and
     * doing it here means pressing send is still instant and the failure — a
     * dead network, a file too large — surfaces while the user is still
     * thinking about the attachment rather than about their message.
     */
    private fun attach(uri: android.net.Uri) {
        lifecycleScope.launch {
            val prepared = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                Attachments.prepare(this@MainActivity, uri)
            }
            if (prepared == null) {
                toast("Couldn't read that file.")
                return@launch
            }
            val result = runCatching {
                app.edge.uploadFile(prepared.bytes, prepared.mime, prepared.name)
            }.getOrNull()
            if (result == null) {
                toast("Upload failed — check your connection.")
                return@launch
            }
            pending = pending + result.key
        }
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    /** Attachments are stored as a JSON array of R2 keys. */
    private fun attachmentKeys(raw: String?): List<String>? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(raw)
                .let { it as kotlinx.serialization.json.JsonArray }
                .mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

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
        /** Marks a turn answered on device; rendered by the usage footer. */
        const val LOCAL_MODEL = "on-device"
        const val LOCAL_USAGE = """{"in":0,"out":0,"cache_read":0,"usd":0}"""

        const val ACTIVE_RUN = "active_run"
        const val LAST_CONV = "last_conv"
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
