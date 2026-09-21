package org.tomsense.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
) {
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Follow the tail as tokens stream in. Keyed on the last message's length
    // as well as the count, or the view freezes mid-answer while a single
    // message grows.
    LaunchedEffect(messages.size, messages.lastOrNull()?.content?.length) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
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
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(messages, key = { it.id }) { MessageBubble(it) }

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
            }

            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message") },
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
private fun MessageBubble(message: Message) {
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
                // Reasoning is collapsed by default but never hidden — the
                // point of self-hosting is that nothing is opaque.
                message.reasoning?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                Text(message.content, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
