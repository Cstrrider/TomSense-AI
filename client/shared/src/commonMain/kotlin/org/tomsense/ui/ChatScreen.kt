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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
) {
    var draft by remember { mutableStateOf("") }

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
     * Whether the tail is on screen.
     *
     * This is what stops the auto-scroll below from being a nuisance: it used
     * to jump to the bottom on EVERY token, so scrolling up to re-read
     * something during a reply dragged you straight back down again, once per
     * token. Following only while already at the tail is what people expect.
     */
    val atTail by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()
                ?: return@derivedStateOf true
            last.index >= listState.layoutInfo.totalItemsCount - 2
        }
    }

    // Follow the tail as tokens stream in. Keyed on the last message's length
    // as well as the count, or the view freezes mid-answer while a single
    // message grows.
    LaunchedEffect(messages.size, messages.lastOrNull()?.content?.length) {
        if (messages.isNotEmpty() && atTail) listState.animateScrollToItem(messages.lastIndex)
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
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
                    Text(
                        "Ask anything.\nWorks offline for simple things.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
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
                    items(messages, key = { it.id }) { MessageBubble(it, loadAttachment) }

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
                if (!atTail) {
                    val scope = rememberCoroutineScope()
                    FilledTonalIconButton(
                        onClick = { scope.launch { listState.animateScrollToItem(messages.lastIndex) } },
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
                onMic?.let { mic ->
                    IconButton(onClick = mic) {
                        Icon(
                            if (listening) Icons.Filled.MicOff else Icons.Filled.Mic,
                            contentDescription = if (listening) "Stop listening" else "Speak",
                            // Tinted while active so the mic state is readable
                            // at a glance rather than from the icon shape.
                            tint = if (listening || speaking) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
                thinkEnabled?.let { on ->
                    // A toggle rather than a per-send choice: "think about
                    // this one" is usually a mode you stay in for a few turns.
                    IconButton(onClick = { onThinkChange(!on) }) {
                        Icon(
                            Icons.Filled.Lightbulb,
                            contentDescription = if (on) "Think mode on" else "Think mode off",
                            tint = if (on) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
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
}

@Composable
private fun MessageBubble(
    message: Message,
    loadAttachment: (suspend (String) -> ByteArray?)? = null,
) {
    val isUser = message.role == "user"
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Card(
            modifier = Modifier.widthIn(max = 520.dp),
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
                attachmentKeys(message.attachments).forEach { key ->
                    AttachmentImage(key, loadAttachment)
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

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    UsageFooter(message.model, message.usage)
                    Spacer(Modifier.weight(1f))
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
private fun UsageFooter(model: String?, usageJson: String?) {
    if (model.isNullOrBlank() && usageJson.isNullOrBlank()) return

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
                add(if (cached > 0) "${tin} in (${cached} cached) · ${tout} out" else "${tin} in · ${tout} out")
            }
            cost?.takeIf { it > 0 }?.let {
                add(formatUsd(it))
                add("~${neuronsFromUsd(it)} neurons")
            }
        }
    }
    if (parts.isEmpty()) return

    Text(
        parts.joinToString(" · "),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
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
