package org.tomsense.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import org.tomsense.sync.UsageToday

/**
 * Today's usage, compact, pinned to the bottom of the chat drawer.
 *
 * The same numbers as the Today card in Settings → AI & models, cut down to
 * what is worth a glance: neurons against the free daily allowance, whether
 * that figure is measured or estimated, and spend. Tapping opens the full card.
 *
 * "Measured" vs "Estimated" is kept here too. Without an analytics token the
 * neuron figure is derived from cost, and a bare number in a drawer would be
 * read as the real one.
 */
@Composable
fun DrawerUsageCard(u: UsageToday, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val fraction = if (u.neuronLimit > 0) u.neurons.toFloat() / u.neuronLimit else 0f
    val pct = (fraction * 100).toInt().coerceAtMost(999)
    // 80% is where budget mode starts downshifting, so that is where the bar
    // changes colour — the point at which the number starts to mean something.
    val barColor = when {
        fraction >= 1f -> MaterialTheme.colorScheme.error
        fraction >= 0.8f -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "%,d neurons · %d%% of %,d".format(u.neurons, pct, u.neuronLimit),
                style = MaterialTheme.typography.bodyMedium,
            )
            LinearProgressIndicator(
                progress = { fraction.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = barColor,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                drawStopIndicator = {},
            )
            Text(
                (if (u.neuronsMeasured) "Measured" else "Estimated") +
                    " · ${u.requests} requests · ${formatUsdDrawer(u.costUsd)} today",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatUsdDrawer(usd: Double): String {
    if (usd <= 0) return "\$0"
    if (usd < 0.0001) return "<\$0.0001"
    val q = (usd * 10000).toInt()
    return "\$" + (q / 10000) + "." + (q % 10000).toString().padStart(4, '0')
}
