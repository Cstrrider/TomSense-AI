package org.tomsense.android.assist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import org.tomsense.android.ui.PromptPill
import org.tomsense.ui.MarkdownText
import androidx.compose.material3.FilterChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import org.tomsense.android.voice.VoiceController

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
    // NO imePadding here. This view is hosted by the VoiceInteractionSession's
    // own window, and the framework already moves that window for the
    // keyboard. Insetting on top of it is additive — the window shifts up and
    // then the content is inset by another keyboard's height again, which is
    // why the card shot to the top of the screen the moment the composer took
    // focus.
    Box(
        Modifier.fillMaxSize().navigationBarsPadding(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        // Modelled on the Pixel's own assistant sheet: one floating rounded
        // surface, tonal rather than outlined, hugging the bottom edge with a
        // small margin so the app underneath still reads as "behind" it.
        Surface(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            shape = RoundedCornerShape(32.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 8.dp,
        ) {
            Column(
                Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 6.dp).size(20.dp),
                    )
                    Text(
                        "TomSense",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 8.dp).weight(1f),
                    )
                    if (state.canOpenApp) {
                        IconButton(onClick = state.onOpenApp) {
                            Icon(
                                Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = "Open in TomSense",
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    IconButton(onClick = state.onDismiss) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Close",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                if (state.asked.isNotBlank() || state.reply.isNotBlank() || state.generating) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // Your question, echoed back as a bubble: without it a
                        // spoken question vanishes the moment it is sent, and
                        // the answer arrives with nothing to say what it is to.
                        if (state.asked.isNotBlank()) {
                            Surface(
                                Modifier.align(Alignment.End).widthIn(max = 300.dp),
                                shape = RoundedCornerShape(20.dp, 4.dp, 20.dp, 20.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                            ) {
                                Text(
                                    state.asked,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                )
                            }
                        }
                        if (state.reply.isBlank()) {
                            if (state.generating) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Text(
                                        if (state.think) "Thinking it through…" else "Thinking…",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 10.dp),
                                    )
                                }
                            }
                        } else {
                            // Rendered markdown, same as the chat screen — a
                            // reply full of literal asterisks looks broken on
                            // the surface people see most.
                            MarkdownText(state.reply)
                        }
                    }
                }

                state.notices.forEach {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
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

                state.voice.error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }

                // A TOGGLE, not a dismiss — both states visible, either one
                // tap away. Worded and placed like the Pixel's "Ask about
                // screen" chip, directly above the prompt it applies to.
                if (state.hasScreenContext) {
                    FilterChip(
                        selected = state.useScreenContext,
                        onClick = { state.useScreenContext = !state.useScreenContext },
                        shape = CircleShape,
                        label = { Text("Ask about screen") },
                        leadingIcon = {
                            Icon(
                                if (state.useScreenContext) Icons.Filled.Check else Icons.Filled.Smartphone,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    )
                }

                PromptPill(
                    value = state.draft,
                    onValueChange = state.onDraftChange,
                    placeholder = if (state.asked.isBlank()) "Ask TomSense" else "Ask a follow-up",
                    think = state.think,
                    onThinkChange = { state.think = it },
                    listening = state.voice.phase == VoiceController.Phase.Listening,
                    speaking = state.voice.phase == VoiceController.Phase.Speaking,
                    partial = state.voice.partial,
                    onMic = state.onMic,
                    generating = state.generating,
                    onSend = state.onSend,
                    onStop = state.onStop,
                    modifier = Modifier.fillMaxWidth(),
                )
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
    val onDraftChange: (String) -> Unit,
    val onMic: () -> Unit,
    /** Owned by the session; read here for phase, partial text and errors. */
    val voice: VoiceController,
) {
    /** Think mode for this invocation. Starts off: a power-button question wants a fast answer. */
    var think by mutableStateOf(false)
    var draft by mutableStateOf("")
    var reply by mutableStateOf("")

    /** The question the current reply answers, echoed above it. */
    var asked by mutableStateOf("")
    var generating by mutableStateOf(false)
    var notices by mutableStateOf<List<String>>(emptyList())

    /** Screen text was offered by the system and is available to use. */
    var hasScreenContext by mutableStateOf(false)

    /**
     * Whether to actually send it. Separate from availability so the choice
     * can be changed back — the old drop-it-forever action could not be.
     */
    var useScreenContext by mutableStateOf(true)
    var needsPermission by mutableStateOf(false)
    var canOpenApp by mutableStateOf(false)

    /**
     * Back to a blank sheet. Every invocation is a new question: the system
     * can hand back the SAME session object for the next power-button hold,
     * and without this it reopened showing the previous answer.
     */
    fun reset() {
        draft = ""
        reply = ""
        asked = ""
        generating = false
        notices = emptyList()
        needsPermission = false
        think = false
    }
}
