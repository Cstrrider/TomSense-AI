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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.sp

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
 * A fenced code block, drawn as its own editor panel.
 *
 * It used to share the reply bubble's surfaceVariant colour, so a block of
 * code inside an answer looked like more of the answer. It now has its own
 * editor palette (dark on dark theme, light on light), a header bar naming the
 * language, syntax colour, and a line-wrap toggle for long lines.
 *
 * Horizontal scroll is the default rather than wrapping: a line break that is
 * not in the source reads as one that is. Copy always copies the SOURCE.
 */
@Composable
private fun CodeBlock(block: MdBlock.Code) {
    val clipboard = LocalClipboardManager.current
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val palette = if (dark) DarkCode else LightCode
    var wrap by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1_500)
            copied = false
        }
    }
    val styled = remember(block.code, block.lang, dark) { styledCode(block.code, block.lang, palette) }

    Surface(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        color = palette.background,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, palette.border),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().background(palette.header).padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    block.lang?.takeIf { it.isNotBlank() } ?: "code",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = palette.muted,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { wrap = !wrap }, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.AutoMirrored.Filled.WrapText,
                        contentDescription = if (wrap) "Scroll long lines" else "Wrap long lines",
                        modifier = Modifier.size(18.dp),
                        tint = if (wrap) palette.keyword else palette.muted,
                    )
                }
                TextButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(block.code))
                        copied = true
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp),
                ) {
                    Icon(
                        if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = palette.muted,
                    )
                    Text(
                        if (copied) "Copied" else "Copy",
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.muted,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
            val scroll = rememberScrollState()
            Text(
                styled,
                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                fontFamily = FontFamily.Monospace,
                color = palette.text,
                softWrap = wrap,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (wrap) Modifier else Modifier.horizontalScroll(scroll))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}

/** Editor colours, independent of the Material scheme — code needs its own contrast. */
private data class CodePalette(
    val background: Color,
    val header: Color,
    val border: Color,
    val text: Color,
    val muted: Color,
    val comment: Color,
    val string: Color,
    val number: Color,
    val keyword: Color,
    val function: Color,
    val type: Color,
)

private val DarkCode = CodePalette(
    background = Color(0xFF16181D), header = Color(0xFF1F2229), border = Color(0xFF2C3039),
    text = Color(0xFFE3E6EC), muted = Color(0xFF9AA3B2), comment = Color(0xFF7F8A9C),
    string = Color(0xFFA5D6A7), number = Color(0xFFF9C38A), keyword = Color(0xFFC792EA),
    function = Color(0xFF82AAFF), type = Color(0xFF80CBC4),
)

private val LightCode = CodePalette(
    background = Color(0xFFF7F8FA), header = Color(0xFFECEEF2), border = Color(0xFFDADDE3),
    text = Color(0xFF1F2328), muted = Color(0xFF5B6270), comment = Color(0xFF6E7781),
    string = Color(0xFF0A7A3E), number = Color(0xFFB35900), keyword = Color(0xFF8250DF),
    function = Color(0xFF0550AE), type = Color(0xFF116B6B),
)

private fun styledCode(code: String, lang: String?, p: CodePalette): AnnotatedString {
    val b = AnnotatedString.Builder(code)
    for (t in highlight(code, lang)) {
        val color = when (t.kind) {
            TokenKind.Comment -> p.comment
            TokenKind.String -> p.string
            TokenKind.Number -> p.number
            TokenKind.Keyword -> p.keyword
            TokenKind.Function -> p.function
            TokenKind.Type -> p.type
            TokenKind.Plain -> continue
        }
        b.addStyle(
            SpanStyle(color = color, fontStyle = if (t.kind == TokenKind.Comment) FontStyle.Italic else null),
            t.start,
            t.end,
        )
    }
    return b.toAnnotatedString()
}
