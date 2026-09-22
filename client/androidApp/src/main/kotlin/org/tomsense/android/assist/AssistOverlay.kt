package org.tomsense.android.assist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The assistant surface: a card over whatever you were doing.
 *
 * Deliberately NOT the chat screen. A power-button hold is an interruption —
 * you want one answer and your app back — so there is no history, no drawer,
 * no settings. Anything that wants those is one tap from opening the full app,
 * which continues the same conversation rather than starting over.
 *
 * Bottom-aligned because that is where the thumb and the keyboard are, and
 * because it leaves the screen you were looking at visible above it, which is
 * usually what the question is about.
 */
@Composable
fun AssistOverlay(state: AssistUiState) {
    Box(
        Modifier.fillMaxSize().imePadding().navigationBarsPadding(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Card(Modifier.fillMaxWidth().padding(12.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "TomSense",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f),
                    )
                    if (state.canOpenApp) {
                        IconButton(onClick = state.onOpenApp) {
                            Icon(
                                Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = "Open in TomSense",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    IconButton(onClick = state.onDismiss) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Close",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                // Shown only when the system actually offered screen text, and
                // removable — answering about the wrong thing is worse than
                // answering with no context, and the user can see which they
                // are getting.
                if (state.hasScreenContext) {
                    AssistChip(
                        onClick = state.onDropScreenContext,
                        label = { Text("Using what's on screen", style = MaterialTheme.typography.labelSmall) },
                        trailingIcon = {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Ignore screen",
                                modifier = Modifier.size(14.dp),
                            )
                        },
                    )
                }

                state.notices.forEach {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (state.reply.isNotBlank() || state.generating) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        if (state.reply.isBlank()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                Text(
                                    "Thinking…",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                        } else {
                            Text(state.reply, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                // Surfaced rather than swallowed: a tool that needs a
                // permission cannot prompt from here, because a permission
                // dialog needs an Activity. Saying so — with the way to fix
                // it — beats "I couldn't access your calendar" and no recourse.
                if (state.needsPermission) {
                    TextButton(onClick = state.onOpenApp) {
                        Text("Open TomSense to grant permission")
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = state.draft,
                        onValueChange = state.onDraftChange,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Ask anything") },
                        maxLines = 4,
                    )
                    if (state.generating) {
                        IconButton(onClick = state.onStop) {
                            Icon(Icons.Filled.Stop, contentDescription = "Stop")
                        }
                    } else {
                        IconButton(
                            onClick = state.onSend,
                            enabled = state.draft.isNotBlank(),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Everything the overlay renders and calls.
 *
 * A plain holder rather than a ViewModel: the session owns the lifetime, it is
 * measured in seconds, and there is nothing to survive a configuration change.
 */
class AssistUiState(
    val onSend: () -> Unit,
    val onStop: () -> Unit,
    val onDismiss: () -> Unit,
    val onOpenApp: () -> Unit,
    val onDropScreenContext: () -> Unit,
    val onDraftChange: (String) -> Unit,
) {
    var draft by mutableStateOf("")
    var reply by mutableStateOf("")
    var generating by mutableStateOf(false)
    var notices by mutableStateOf<List<String>>(emptyList())
    var hasScreenContext by mutableStateOf(false)
    var needsPermission by mutableStateOf(false)
    var canOpenApp by mutableStateOf(false)
}
