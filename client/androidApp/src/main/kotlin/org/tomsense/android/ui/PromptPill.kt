package org.tomsense.android.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.tomsense.ui.MicToggle
import org.tomsense.ui.ThinkToggle

/**
 * The Pixel-style prompt bar: one rounded pill, controls inside it.
 *
 * Shared by the assistant overlay and the feed panel, the two surfaces that
 * sit on top of the system rather than inside the app — which is exactly where
 * matching how the phone's own assistant and feed look matters most.
 *
 * The trailing slot does ONE job at a time, like the Pixel's: mic while the
 * box is empty, send once there is text, stop while a reply is generating.
 * Three buttons side by side made the field too narrow to type into on a
 * panel that is already inset from the screen edge.
 */
@Composable
fun PromptPill(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    think: Boolean,
    onThinkChange: (Boolean) -> Unit,
    listening: Boolean,
    speaking: Boolean,
    partial: String,
    onMic: () -> Unit,
    generating: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    maxLines: Int = 4,
    color: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
) {
    Surface(
        modifier = modifier.heightIn(min = 56.dp),
        // Fully round while it is one line; a multi-line draft would make a
        // CircleShape a lozenge, so cap the radius instead.
        shape = RoundedCornerShape(28.dp),
        color = color,
    ) {
        Row(
            Modifier.padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ThinkToggle(think, onThinkChange)
            TextField(
                // Show what is being heard without writing a half-heard
                // sentence into the draft — same as the chat screen.
                value = if (listening && partial.isNotEmpty()) partial else value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                readOnly = listening,
                placeholder = { Text(if (listening) "Listening…" else placeholder) },
                maxLines = maxLines,
                textStyle = MaterialTheme.typography.bodyLarge,
                // Borderless: the pill IS the field. An underline or outline
                // inside a rounded container is the stock-Compose look this
                // is replacing.
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
            )
            when {
                generating -> FilledTonalIconButton(onClick = onStop) {
                    Icon(Icons.Filled.Stop, contentDescription = "Stop")
                }
                value.isNotBlank() && !listening -> FilledIconButton(onClick = onSend, shape = CircleShape) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
                else -> MicToggle(listening, speaking, onMic)
            }
        }
    }
}
