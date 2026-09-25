package org.tomsense.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Code
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.launch
import org.tomsense.db.Message

/**
 * The conversation view, shared by Android and desktop.
 *
 * It renders from a list the caller has already collected out of local
 * SQLite. That is the whole local-first payoff made concrete: first paint
 * needs no network, so the assistant overlay can appear instantly on a
 * power-button hold instead of showing a spinner.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    messages: List<Message>,
    syncLabel: String,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** Null on platforms with no settings surface yet (desktop). */
    onOpenSettings: (() -> Unit)? = null,
    /** A generation is in flight — the send button becomes stop. */
    isGenerating: Boolean = false,
    onStop: () -> Unit = {},
    onRegenerate: (() -> Unit)? = null,
    /** Shown in the bar so the open chat is identifiable without the drawer. */
    title: String = "TomSense",
    /** Null hides the menu button — used where there is no drawer to open. */
    onOpenDrawer: (() -> Unit)? = null,
    /** Fork from the last turn. Null while there is nothing to fork. */
    onBranch: (() -> Unit)? = null,
    onExport: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    /**
     * Routing overrides for the turn in flight — "answering with your Vision
     * model", "budget mode". Shown above the input so the reason for an
     * unexpected model is visible at the moment it applies.
     */
    notices: List<String> = emptyList(),
    /** Think mode. Null hides the control entirely. */
    thinkEnabled: Boolean? = null,
    onThinkChange: (Boolean) -> Unit = {},
    /**
     * Fetch an attachment's bytes by R2 key. Null disables image rendering —
     * the desktop app has no uploader yet, and a broken image is worse than
     * an honest placeholder.
     */
    loadAttachment: (suspend (String) -> ByteArray?)? = null,
    /** Null hides the attach button on platforms with no picker. */
    onAttach: (() -> Unit)? = null,
    /** Keys staged for the next send, shown as removable chips. */
    pendingAttachments: List<String> = emptyList(),
    onRemoveAttachment: (String) -> Unit = {},
    /** Text handed in from outside — share sheet, ASK intent, assistant. */
    prefill: String = "",
    onPrefillConsumed: () -> Unit = {},
    /** Null hides the mic on platforms with no speech engine. */
    onMic: (() -> Unit)? = null,
    listening: Boolean = false,
    speaking: Boolean = false,
    /** Live transcript while listening, shown in place of the draft. */
    partialTranscript: String = "",
    /** Suggestions on an empty chat; tapping one sends it. */
    starters: List<String> = emptyList(),
    /** Suggested next messages under the latest reply. */
    followups: List<String> = emptyList(),
    /** This chat's own instructions; null hides the menu entry. */
    chatInstructions: String? = null,
    onSetChatInstructions: (String) -> Unit = {},
    /** (id, name) of every project, for "Move to project". Empty hides it. */
    projects: List<Pair<String, String>> = emptyList(),
    currentProjectId: String? = null,
    onMoveToProject: (String?) -> Unit = {},
    /**
     * Rewind to just before a message of yours: it and everything after are
     * removed and its text returns to the composer for editing. Deleting is
     * what keeps this safe with sync — see ChatRepository.rewindTo.
     */
    onRewindTo: ((Message) -> Unit)? = null,
    /** Load an artifact for its card and viewer. Null hides artifact cards. */
    loadArtifact: (suspend (String) -> org.tomsense.sync.Artifact?)? = null,
    onShareText: ((String) -> Unit)? = null,
) {
    var draft by remember { mutableStateOf("") }
    var showInstructions by remember { mutableStateOf(false) }
    var showProjects by remember { mutableStateOf(false) }
    var openArtifact by remember { mutableStateOf<String?>(null) }

    // Dropped into the composer UNSENT, on purpose: something arriving from a
    // share sheet should be reviewable before it is asked, not fired off.
    // Appended rather than replacing, so a half-typed message is not lost.
    LaunchedEffect(prefill) {
        if (prefill.isNotBlank()) {
            draft = if (draft.isBlank()) prefill else draft.trimEnd() + " " + prefill
            onPrefillConsumed()
        }
    }
    val listState = rememberLazyListState()

    /**
     * Whether the view follows the stream. Decided by the USER'S scrolling,
     * never by the content.
     *
     * The previous rule — "follow while the last message is visible" — was
     * always true inside a long reply, because you are reading the last
     * message. And it scrolled to the TOP of that message, so once a reply
     * outgrew the screen every token yanked the view back to its first line
     * and you could not scroll it at all while it streamed.
     *
     * Content growth can't be the signal either: every token leaves the list
     * momentarily not-at-bottom, which would switch following off by itself.
     * So: touching the list stops following; letting go resumes it only if
     * you left it at the very bottom.
     */
    var following by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect {
            if (it is DragInteraction.Start) following = false
        }
    }
    LaunchedEffect(listState) {
        // Fires after flings too, so a fling that lands at the bottom
        // re-engages following just like a drag that does.
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) following = !listState.canScrollForward
        }
    }

    // Sending (or opening a chat) always goes to the bottom, even if you had
    // scrolled up: the thing you just did is down there.
    LaunchedEffect(messages.size) {
        if (messages.takeLast(2).any { it.role == "user" }) following = true
    }

    // Follow the tail as tokens stream in. Keyed on the last message's length
    // as well as the count, or the view freezes mid-answer while a single
    // message grows. Not animated: an animation per token is still running
    // when the next token lands, and the two fight.
    LaunchedEffect(messages.size, messages.lastOrNull()?.content?.length) {
        if (messages.isNotEmpty() && following) listState.scrollToEnd(messages.lastIndex)
    }

    // The bar gets out of the way while you read: scrolling down slides it
    // off, any upward scroll brings it straight back. enterAlways rather than
    // exitUntilCollapsed so the menu is never more than a flick away.
    // Only USER scrolls reach it — nestedScroll sees gestures, not the
    // programmatic scrollBy that follows a streaming reply — so auto-follow
    // never hides the bar on its own.
    val barScroll = TopAppBarDefaults.enterAlwaysScrollBehavior()

    Scaffold(
        modifier = modifier.nestedScroll(barScroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                scrollBehavior = barScroll,
                title = {
                    Text(
                        title.ifBlank { "New chat" },
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    onOpenDrawer?.let {
                        IconButton(onClick = it) {
                            Icon(Icons.Filled.Menu, contentDescription = "Chats")
                        }
                    }
                },
                actions = {
                    // Sync state is ambient, not a blocking dialog. Being
                    // offline is a normal condition here, not an error.
                    Text(
                        syncLabel,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    // Branch, export and share act on the OPEN conversation,
                    // so they live here rather than in the drawer's per-row
                    // menu — that menu acts on whichever row was long-pressed,
                    // which is not necessarily the one on screen.
                    if (onBranch != null || onExport != null || onShare != null) {
                        var menuOpen by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "Chat actions")
                            }
                            DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false },
                            ) {
                                onBranch?.let { action ->
                                    DropdownMenuItem(
                                        text = { Text("Branch from here") },
                                        onClick = { menuOpen = false; action() },
                                    )
                                }
                                onExport?.let { action ->
                                    DropdownMenuItem(
                                        text = { Text("Export as markdown") },
                                        onClick = { menuOpen = false; action() },
                                    )
                                }
                                onShare?.let { action ->
                                    DropdownMenuItem(
                                        text = { Text("Share link") },
                                        onClick = { menuOpen = false; action() },
                                    )
                                }
                                if (chatInstructions != null) {
                                    DropdownMenuItem(
                                        text = { Text(if (chatInstructions.isBlank()) "Chat instructions…" else "Edit chat instructions") },
                                        onClick = { menuOpen = false; showInstructions = true },
                                    )
                                }
                                if (projects.isNotEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("Move to project…") },
                                        onClick = { menuOpen = false; showProjects = true },
                                    )
                                }
                            }
                        }
                    }
                    onOpenSettings?.let {
                        IconButton(onClick = it) {
                            Icon(Icons.Filled.Settings, contentDescription = "Providers and models")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (messages.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(
                        Modifier.padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            "What can I help with?",
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center,
                        )
                        // Tapping sends immediately: a starter is a complete
                        // question, and making you press send again after
                        // choosing it is one step too many.
                        starters.take(6).forEach { s ->
                            SuggestionPill(s) { onSend(s) }
                        }
                    }
                }
            } else {
                // Boxed so the jump-to-latest control can float over the list.
                // Now that scrolling up stops the auto-follow, there has to be
                // a way back down that is not a manual fling.
                Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(messages, key = { it.id }) {
                        MessageBubble(
                            it,
                            loadAttachment,
                            loadArtifact = loadArtifact,
                            onOpenArtifact = { id -> openArtifact = id },
                            onRewind = onRewindTo?.takeIf { _ -> !isGenerating }?.let { rw ->
                                { m: Message ->
                                    draft = m.content
                                    rw(m)
                                }
                            },
                        )
                    }

                    // Suggested next messages, only under a settled reply.
                    if (!isGenerating && followups.isNotEmpty() && messages.lastOrNull()?.role == "assistant") {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                followups.forEach { f -> SuggestionPill(f) { onSend(f) } }
                            }
                        }
                    }

                    // Offered only on the settled tail of the conversation:
                    // regenerating anything earlier would orphan every turn
                    // that followed it.
                    if (!isGenerating && onRegenerate != null &&
                        messages.lastOrNull()?.role == "assistant"
                    ) {
                        item {
                            TextButton(onClick = onRegenerate) {
                                Icon(
                                    Icons.Filled.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.padding(end = 4.dp),
                                )
                                Text("Regenerate", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }

                // Only while the tail is off screen — it is both the way back
                // and the signal that the view has stopped following.
                if (!following) {
                    val scope = rememberCoroutineScope()
                    FilledTonalIconButton(
                        onClick = {
                            following = true
                            scope.launch { listState.scrollToEnd(messages.lastIndex) }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 12.dp)
                            // 48dp: below that this is a thumb-sized target
                            // sitting directly above the composer, which is
                            // the worst place to need a precise tap.
                            .size(48.dp),
                    ) {
                        Icon(
                            Icons.Filled.ExpandMore,
                            contentDescription = "Jump to latest",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                }
            }

            // Routing notices sit directly above the composer, where the eye
            // already is when a reply arrives.
            notices.forEach { notice ->
                Text(
                    notice,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                )
            }

            // Staged attachments, above the composer so they are visibly
            // part of the message about to be sent rather than of the last one.
            if (pendingAttachments.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    pendingAttachments.forEach { key ->
                        AssistChip(
                            onClick = { onRemoveAttachment(key) },
                            label = { Text(shortFileName(key), style = MaterialTheme.typography.labelSmall) },
                            trailingIcon = {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "Remove attachment",
                                    modifier = Modifier.size(14.dp),
                                )
                            },
                        )
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                onAttach?.let { attach ->
                    IconButton(onClick = attach) {
                        Icon(Icons.Filled.AttachFile, contentDescription = "Attach a file")
                    }
                }
                onMic?.let { mic -> MicToggle(listening, speaking, mic) }
                thinkEnabled?.let { on -> ThinkToggle(on, onThinkChange) }
                OutlinedTextField(
                    // While listening, the field shows what is being heard.
                    // Writing it into `draft` instead would leave a half-heard
                    // sentence behind when recognition is cancelled.
                    value = if (listening && partialTranscript.isNotEmpty()) partialTranscript else draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    readOnly = listening,
                    placeholder = { Text(if (listening) "Listening…" else "Message") },
                    maxLines = 6,
                )
                // One button, two jobs. Stop has to be exactly where send was,
                // because the moment you want it is the moment you just
                // pressed send — a separate control somewhere else is a
                // control you hunt for while the model keeps going.
                if (isGenerating) {
                    IconButton(onClick = onStop) {
                        Icon(Icons.Filled.Stop, contentDescription = "Stop generating")
                    }
                } else {
                    IconButton(
                        onClick = {
                            val text = draft.trim()
                            if (text.isNotEmpty()) {
                                draft = ""
                                onSend(text)
                            }
                        },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                    }
                }
            }
        }
    }
    if (showInstructions) {
        var text by remember { mutableStateOf(chatInstructions.orEmpty()) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showInstructions = false },
            title = { Text("Chat instructions") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Applies to this chat only, on top of your persona and project.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        minLines = 4,
                        placeholder = { Text("e.g. Answer in Spanish. Keep replies under 100 words.") },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showInstructions = false; onSetChatInstructions(text.trim()) }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showInstructions = false }) { Text("Cancel") } },
        )
    }

    if (showProjects) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showProjects = false },
            title = { Text("Move to project") },
            text = {
                Column {
                    (listOf<Pair<String?, String>>(null to "No project") + projects).forEach { (id, name) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { showProjects = false; onMoveToProject(id) }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.RadioButton(
                                selected = id == currentProjectId,
                                onClick = { showProjects = false; onMoveToProject(id) },
                            )
                            Text(name, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showProjects = false }) { Text("Close") } },
        )
    }

    openArtifact?.let { id ->
        ArtifactViewer(id, loadArtifact, onShareText, onClose = { openArtifact = null })
    }
}

/** A tappable suggestion — starters on an empty chat, follow-ups under a reply. */
@Composable
private fun SuggestionPill(text: String, onClick: () -> Unit) {
    androidx.compose.material3.Surface(
        onClick = onClick,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

/**
 * An artifact the model made, as a card in the reply. The content is NOT
 * inlined — that is the point of an artifact — so the card is how you get to
 * it: tap to open the full document with copy and share.
 */
@Composable
private fun ArtifactCard(id: String, load: (suspend (String) -> org.tomsense.sync.Artifact?)?, onOpen: () -> Unit) {
    var art by remember(id) { mutableStateOf<org.tomsense.sync.Artifact?>(null) }
    LaunchedEffect(id) { art = runCatching { load?.invoke(id) }.getOrNull() }
    androidx.compose.material3.Surface(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (art?.kind == "code" || art?.kind == "html") Icons.Filled.Code else Icons.Filled.Description,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(art?.title ?: "Artifact", style = MaterialTheme.typography.titleSmall)
                Text(
                    listOfNotNull(art?.language ?: art?.kind, art?.let { "v${it.version}" }).joinToString(" · ").ifBlank { "Loading…" },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("Open", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** Full-screen artifact view. Code renders through the code-block panel. */
@Composable
private fun ArtifactViewer(
    id: String,
    load: (suspend (String) -> org.tomsense.sync.Artifact?)?,
    onShareText: ((String) -> Unit)?,
    onClose: () -> Unit,
) {
    var art by remember(id) { mutableStateOf<org.tomsense.sync.Artifact?>(null) }
    var failed by remember(id) { mutableStateOf(false) }
    LaunchedEffect(id) {
        art = runCatching { load?.invoke(id) }.getOrNull()
        failed = art == null
    }
    val clipboard = LocalClipboardManager.current
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close") }
                    Text(
                        art?.title ?: "Artifact",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    art?.let { a ->
                        IconButton(onClick = { clipboard.setText(AnnotatedString(a.content)) }) {
                            Icon(Icons.Filled.ContentCopy, "Copy")
                        }
                        onShareText?.let { share ->
                            IconButton(onClick = { share(a.content) }) { Icon(Icons.Filled.Share, "Share") }
                        }
                    }
                }
                val a = art
                when {
                    a != null -> {
                        val source = when (a.kind) {
                            "code", "html" -> "```" + (a.language ?: if (a.kind == "html") "html" else "") + "\n" + a.content + "\n```"
                            "text" -> "```text\n" + a.content + "\n```"
                            else -> a.content
                        }
                        Column(
                            Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(16.dp),
                        ) { MarkdownText(source) }
                    }
                    failed -> Text("Couldn't load this artifact.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                    else -> Text("Loading…", Modifier.padding(16.dp))
                }
            }
        }
    }
}


@Composable
private fun MessageBubble(
    message: Message,
    loadAttachment: (suspend (String) -> ByteArray?)? = null,
    loadArtifact: (suspend (String) -> org.tomsense.sync.Artifact?)? = null,
    onOpenArtifact: (String) -> Unit = {},
    onRewind: ((Message) -> Unit)? = null,
) {
    val isUser = message.role == "user"
    var confirmRewind by remember { mutableStateOf(false) }
    if (confirmRewind) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmRewind = false },
            title = { Text("Edit from here?") },
            text = { Text("This message and everything after it are removed, and it goes back into the message box so you can change it and send again.") },
            confirmButton = { TextButton(onClick = { confirmRewind = false; onRewind?.invoke(message) }) { Text("Edit from here") } },
            dismissButton = { TextButton(onClick = { confirmRewind = false }) { Text("Cancel") } },
        )
    }
    Column(
        // Your messages sit to the right with a gutter on the left, like any
        // chat app — so whose turn it is reads from position, not by
        // comparing two shades of card.
        Modifier.fillMaxWidth().padding(start = if (isUser) 56.dp else 0.dp),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        Card(
            modifier = Modifier.widthIn(max = 520.dp),
            // The flattened corner points at the speaker's side.
            shape = if (isUser) {
                androidx.compose.foundation.shape.RoundedCornerShape(20.dp, 4.dp, 20.dp, 20.dp)
            } else {
                CardDefaults.shape
            },
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ),
        ) {
            Column(Modifier.padding(12.dp)) {
                // Collapsed by default but never hidden — the point of
                // self-hosting is that nothing is opaque. It was rendered
                // inline above the answer, which on a reasoning model meant
                // paragraphs of working-out pushing the actual reply off
                // screen.
                message.reasoning?.takeIf { it.isNotBlank() }?.let {
                    Reasoning(it, streaming = message.content.isBlank())
                }

                // Above the text: for a generated image the picture IS the
                // answer, and for an attached one it is the question.
                attachmentKeys(message.attachments).distinct().forEach { key ->
                    if (key.startsWith("artifact:")) {
                        if (loadArtifact != null) {
                            val id = key.removePrefix("artifact:")
                            ArtifactCard(id, loadArtifact) { onOpenArtifact(id) }
                        }
                    } else {
                        AttachmentImage(key, loadAttachment)
                    }
                }

                if (message.content.isNotBlank()) {
                    // Markdown, not plain text. A model's reply is full of
                    // headings, lists and fenced code, and rendering it raw
                    // put literal ``` and ** on screen while making code
                    // indistinguishable from prose.
                    //
                    // The user's own messages stay plain: they typed them, and
                    // reinterpreting someone's asterisks as emphasis is wrong.
                    if (isUser) {
                        Text(message.content, style = MaterialTheme.typography.bodyMedium)
                    } else {
                        MarkdownText(message.content)
                    }
                }

                // fillMaxWidth only for replies: on a user message it
                // stretched every bubble to full width, however short, which
                // is what kept them from sitting to the right.
                Row(
                    if (isUser) Modifier.align(Alignment.End) else Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!isUser) {
                        UsageFooter(message.model, message.usage)
                    }
                    if (isUser && onRewind != null) {
                        IconButton(onClick = { confirmRewind = true }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.Filled.Edit,
                                contentDescription = "Edit from here",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    // Copy the SOURCE, not the rendered text — someone copying
                    // a reply usually wants to paste it somewhere that
                    // understands markdown, and the formatting is information.
                    if (message.content.isNotBlank()) {
                        val clipboard = LocalClipboardManager.current
                        IconButton(
                            onClick = { clipboard.setText(AnnotatedString(message.content)) },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                Icons.Filled.ContentCopy,
                                contentDescription = "Copy message",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        // Under the bubble rather than in it, on the speaker's side.
        FooterText(
            remember(message.created_at) { org.tomsense.data.messageTime(message.created_at) },
            Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/**
 * The model's working-out, behind a disclosure.
 *
 * Collapsed by default: on a reasoning model this is routinely longer than the
 * answer, and rendering it inline pushed the actual reply off screen. Still
 * one tap away, because hiding it entirely would defeat the point of running
 * your own stack.
 *
 * While a reply is still streaming, its reasoning IS the only sign of life —
 * so an answer that has not started yet shows a live label rather than a
 * finished one.
 */
@Composable
private fun Reasoning(text: String, streaming: Boolean = false) {
    var open by remember { mutableStateOf(false) }

    Column(Modifier.padding(bottom = 6.dp)) {
        Row(
            Modifier.clickable { open = !open },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (streaming) "Thinking…" else "Thought process",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        if (open) {
            Text(
                text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}


/** Stored as a JSON array of R2 keys; tolerant of anything malformed. */
private fun attachmentKeys(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        Json.parseToJsonElement(raw).jsonArray.mapNotNull {
            (it as? JsonPrimitive)?.content
        }
    }.getOrDefault(emptyList())
}

private fun shortFileName(key: String): String = key.substringAfterLast('-').ifBlank { "file" }

/**
 * One attachment, fetched and decoded on demand.
 *
 * The fetch is authenticated, so this cannot be a plain image URL — the bytes
 * come back through the same client that holds the device token. Keyed on the
 * R2 key so a recomposition does not re-download, and a failure renders a
 * placeholder rather than throwing inside the list.
 */
@Composable
private fun AttachmentImage(key: String, load: (suspend (String) -> ByteArray?)?) {
    if (load == null) return
    var bitmap by remember(key) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(key) { mutableStateOf(false) }

    LaunchedEffect(key) {
        val bytes = runCatching { load(key) }.getOrNull()
        val decoded = bytes?.let { decodeImageBytes(it) }
        if (decoded == null) failed = true else bitmap = decoded
    }

    val image = bitmap
    when {
        image != null -> Image(
            bitmap = image,
            contentDescription = "Attached image",
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            contentScale = ContentScale.FillWidth,
        )
        failed -> Text(
            "[image unavailable]",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        else -> Text(
            "Loading image…",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(bottom = 6.dp),
        )
    }
}


/**
 * What this reply cost, and which model produced it.
 *
 * The model is the point as much as the numbers: the router and the stall
 * fallback both mean the model that answered is often not the one configured,
 * and until this footer existed nothing in the transcript said which had run.
 *
 * Neurons are DERIVED from cost at Cloudflare's published rate, because no
 * per-request neuron figure exists — they are an account-level analytics
 * number. Shown with a tilde so it never reads as measured.
 */
@Composable
private fun RowScope.UsageFooter(model: String?, usageJson: String?) {
    val parts = buildList {
        model?.takeIf { it.isNotBlank() }?.let { add(shortModel(it)) }
        usageJson?.let { raw ->
            val u = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
            val tin = u?.get("in")?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            val tout = u?.get("out")?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            val cached = u?.get("cache_read")?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            val cost = u?.get("usd")?.jsonPrimitive?.content?.toDoubleOrNull()

            if (tin > 0 || tout > 0) {
                // Cached input is called out because it is billed at a lower
                // rate — on a long conversation it is most of the input.
                add(if (cached > 0) "${compact(tin)} in (${compact(cached)} cached) · ${compact(tout)} out" else "${compact(tin)} in · ${compact(tout)} out")
            }
            cost?.takeIf { it > 0 }?.let {
                add(formatUsd(it))
                add("~${neuronsFromUsd(it)} neurons")
            }
        }
    }
    if (parts.isEmpty()) return

    // weight(1f): all the room left of the buttons (no spacer — two weighted
    // children would split it and cut the text at half width). When the
    // line is too long it is cut with an ellipsis rather than pushing the
    // copy button off the card. Least important last (neurons), so that is
    // what gets cut.
    FooterText(parts.joinToString(" · "), Modifier.weight(1f))
}

/** 12,480 → "12.5k": keeps the usage line short enough for one line. */
private fun compact(n: Int): String = when {
    n < 1_000 -> n.toString()
    n < 100_000 -> "${n / 1000}.${(n % 1000) / 100}k"
    else -> "${n / 1000}k"
}

/** The footer's type: a notch under labelSmall, and always a single line. */
@Composable
private fun FooterText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 13.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        softWrap = false,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

private fun shortModel(spec: String): String =
    spec.substringAfter("::").substringAfterLast('/')

/** Cloudflare's published rate: $0.011 per 1,000 neurons. */
internal fun neuronsFromUsd(usd: Double): Int = ((usd / 0.011) * 1000).toInt()

/** Sub-cent costs are the normal case, so four decimals rather than two. */
internal fun formatUsd(usd: Double): String {
    if (usd <= 0) return "\$0"
    if (usd < 0.0001) return "<\$0.0001"
    val cents = (usd * 10000).toInt()
    return "\$" + (cents / 10000) + "." + (cents % 10000).toString().padStart(4, '0')
}

/**
 * Scroll so the BOTTOM of item [lastIndex] sits at the bottom of the viewport.
 *
 * scrollToItem alone aligns an item's top, which for a reply taller than the
 * screen means showing its first line. Bring the item into the layout first,
 * then scroll forward as far as the list allows — scrollBy is clamped to the
 * content, so the large delta just means "all the way".
 */
private suspend fun LazyListState.scrollToEnd(lastIndex: Int) {
    if (layoutInfo.visibleItemsInfo.none { it.index == lastIndex }) scrollToItem(lastIndex)
    scrollBy(100_000f)
}
