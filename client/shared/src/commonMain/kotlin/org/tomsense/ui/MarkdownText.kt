package org.tomsense.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Render a model reply as Markdown.
 *
 * Replies were rendered with a plain Text, so every heading, list and fenced
 * code block arrived as literal characters — and a code block, which is the
 * thing most worth reading carefully and copying, was the worst affected:
 * wrapped, proportional, and indistinguishable from prose.
 *
 * Parsing is remembered on the source string. It is cheap, but this recomposes
 * on every streamed token, and re-parsing a long answer per token is the kind
 * of cost that only shows up on the longest and most valuable replies.
 */
@Composable
fun MarkdownText(source: String, modifier: Modifier = Modifier) {
    val blocks = remember(source) { parseMarkdown(source) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (block in blocks) {
            when (block) {
                is MdBlock.Paragraph ->
                    Text(block.text, style = MaterialTheme.typography.bodyMedium)

                is MdBlock.Heading -> Text(
                    block.text,
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 4.dp),
                )

                is MdBlock.Bullet -> Row(Modifier.fillMaxWidth()) {
                    Text(
                        block.ordinal ?: "•",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Text(block.text, style = MaterialTheme.typography.bodyMedium)
                }

                is MdBlock.Quote -> Row(Modifier.fillMaxWidth()) {
                    // A tinted rule rather than a ">" character, which is what
                    // the markup meant rather than what it said.
                    Surface(
                        Modifier.padding(end = 8.dp).size(width = 3.dp, height = 20.dp),
                        color = MaterialTheme.colorScheme.primary,
                    ) {}
                    Text(
                        block.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is MdBlock.Code -> CodeBlock(block)

                MdBlock.Rule -> HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
    }
}

/**
 * A fenced code block: monospace, scrollable sideways, copyable.
 *
 * Horizontal scroll rather than wrapping, because wrapped code is misleading —
 * a line break that is not in the source reads as one that is. Copy sits on
 * the block itself rather than only on the message, since copying one command
 * out of an explanation is the common case.
 */
@Composable
private fun CodeBlock(block: MdBlock.Code) {
    val clipboard = LocalClipboardManager.current

    Surface(
        Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(Modifier.padding(start = 10.dp, top = 4.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    block.lang ?: "code",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { clipboard.setText(AnnotatedString(block.code)) }) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = "Copy code",
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Text(
                block.code,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(end = 10.dp),
            )
        }
    }
}
