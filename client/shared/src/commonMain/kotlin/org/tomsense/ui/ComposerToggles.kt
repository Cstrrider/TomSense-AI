package org.tomsense.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer

/*
 * The composer's two toggles, shared by every surface that has a composer —
 * the chat screen, the assistant overlay and the feed panel. One definition so
 * "on" cannot look different depending on where you asked.
 *
 * Both icons show STATE, not the action a tap would take: accent colour and
 * plain glyph when on, grey and crossed out when off.
 */

/**
 * @param speaking a spoken reply is playing. The mic is not listening then, so
 * it stays crossed out, but keeps the accent colour: voice is still the mode
 * you are in, and tapping it barges in.
 */
@Composable
fun MicToggle(listening: Boolean, speaking: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            // A crossed-out mic while listening read as "muted" at exactly
            // the moment it was hearing you.
            if (listening) Icons.Filled.Mic else Icons.Filled.MicOff,
            contentDescription = if (listening) "Stop listening" else "Speak",
            tint = if (listening || speaking) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/**
 * Think mode. A toggle rather than a per-send choice: "think about this one"
 * is usually a mode you stay in for a few turns.
 */
@Composable
fun ThinkToggle(on: Boolean, onChange: (Boolean) -> Unit) {
    val off = MaterialTheme.colorScheme.onSurfaceVariant
    IconButton(onClick = { onChange(!on) }) {
        Icon(
            Icons.Filled.Lightbulb,
            contentDescription = if (on) "Think mode on" else "Think mode off",
            // Material has no LightbulbOff, so draw the same slash MicOff
            // uses — off has to look off, not just greyer, beside the mic.
            modifier = if (on) Modifier else Modifier.slashed(off),
            tint = if (on) MaterialTheme.colorScheme.primary else off,
        )
    }
}

/**
 * A diagonal slash across an icon, drawn like Material's *Off icons: a
 * transparent gap cut through the glyph, then the stroke itself in [color],
 * which must be the icon's tint (a modifier can't see it). The gap is a real
 * cut (BlendMode.Clear on an offscreen layer), so it works on any background.
 */
private fun Modifier.slashed(color: Color): Modifier = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()
        // Same geometry as MicOff's slash in the 24-unit viewport.
        val u = size.width / 24f
        val start = Offset(3.5f * u, 3.5f * u)
        val end = Offset(20.5f * u, 20.5f * u)
        drawLine(Color.Black, start, end, strokeWidth = 5f * u, cap = StrokeCap.Round, blendMode = BlendMode.Clear)
        drawLine(color, start, end, strokeWidth = 2f * u, cap = StrokeCap.Round)
    }
