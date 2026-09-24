package org.tomsense.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

/**
 * Just enough Markdown for a chat reply.
 *
 * Hand-written rather than a dependency. The subset a model actually emits is
 * small and stable — headings, lists, emphasis, inline code and fenced blocks —
 * and a full CommonMark implementation would bring a parser, a renderer and a
 * theme layer to render text that is mostly paragraphs. What matters here is
 * that FENCED CODE gets a monospace block with a copy button, because that is
 * the content people came to copy and the reason plain Text was unacceptable.
 *
 * Deliberately NOT supported: tables, images, footnotes, nested blockquotes,
 * reference links. Anything unrecognised falls through as literal text, which
 * is exactly what happens today — so this can only be an improvement, never a
 * regression into blank space.
 */

sealed interface MdBlock {
    data class Paragraph(val text: AnnotatedString) : MdBlock
    data class Heading(val text: AnnotatedString, val level: Int) : MdBlock
    data class Bullet(val text: AnnotatedString, val ordinal: String?) : MdBlock
    data class Quote(val text: AnnotatedString) : MdBlock
    data class Code(val code: String, val lang: String?) : MdBlock
    data object Rule : MdBlock
}

private val FENCE = Regex("^\\s*```\\s*([A-Za-z0-9+#_-]*)\\s*$")
private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
private val BULLET = Regex("^\\s*[-*+]\\s+(.*)$")
private val ORDERED = Regex("^\\s*(\\d{1,3})[.)]\\s+(.*)$")
private val QUOTE = Regex("^\\s*>\\s?(.*)$")
private val RULE = Regex("^\\s*([-*_])\\1{2,}\\s*$")

/**
 * Split into blocks.
 *
 * Fences are handled first and greedily: everything between them is literal,
 * including lines that look like headings or lists. Getting that precedence
 * wrong is how a shell script full of `# comments` renders as a stack of h1s.
 */
fun parseMarkdown(source: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val lines = source.replace("\r\n", "\n").split('\n')
    val paragraph = StringBuilder()

    fun flushParagraph() {
        if (paragraph.isNotBlank()) out += MdBlock.Paragraph(inlineMarkdown(paragraph.toString().trim()))
        paragraph.setLength(0)
    }

    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val fence = FENCE.find(line)

        if (fence != null) {
            flushParagraph()
            val lang = fence.groupValues[1].takeIf { it.isNotBlank() }
            val body = StringBuilder()
            i++
            // An unterminated fence runs to the end rather than being
            // abandoned — a reply cut off mid-block still has to render.
            while (i < lines.size && FENCE.find(lines[i]) == null) {
                body.appendLine(lines[i])
                i++
            }
            i++ // closing fence, or past the end
            out += MdBlock.Code(body.toString().trimEnd('\n'), lang)
            continue
        }

        when {
            line.isBlank() -> flushParagraph()

            RULE.matches(line) -> {
                flushParagraph()
                out += MdBlock.Rule
            }

            HEADING.matches(line) -> {
                flushParagraph()
                val (hashes, text) = HEADING.find(line)!!.destructured
                out += MdBlock.Heading(inlineMarkdown(text.trim()), hashes.length)
            }

            ORDERED.matches(line) -> {
                flushParagraph()
                val (num, text) = ORDERED.find(line)!!.destructured
                out += MdBlock.Bullet(inlineMarkdown(text.trim()), "$num.")
            }

            BULLET.matches(line) -> {
                flushParagraph()
                out += MdBlock.Bullet(inlineMarkdown(BULLET.find(line)!!.groupValues[1].trim()), null)
            }

            QUOTE.matches(line) -> {
                flushParagraph()
                out += MdBlock.Quote(inlineMarkdown(QUOTE.find(line)!!.groupValues[1].trim()))
            }

            // Soft-wrapped: consecutive non-blank lines are one paragraph,
            // which is how models emit prose.
            else -> {
                if (paragraph.isNotEmpty()) paragraph.append(' ')
                paragraph.append(line.trim())
            }
        }
        i++
    }
    flushParagraph()
    return out
}

/**
 * Inline spans: `code`, **bold**, *italic*, ~~strike~~, [text](url).
 *
 * One left-to-right pass rather than nested regex replacement, because the
 * delimiters overlap — `**` contains `*`, and a regex pass for italics will
 * happily eat half of a bold marker. Code spans win outright: backticks
 * suppress every other marker inside them, which is the whole point of them.
 */
fun inlineMarkdown(text: String): AnnotatedString = buildAnnotatedStringCompat { push ->
    var i = 0
    while (i < text.length) {
        val rest = text.substring(i)

        // Code first, and it consumes everything to the closing backtick.
        if (rest.startsWith("`")) {
            val end = rest.indexOf('`', 1)
            if (end > 0) {
                push(rest.substring(1, end), CODE_SPAN)
                i += end + 1
                continue
            }
        }

        if (rest.startsWith("**") || rest.startsWith("__")) {
            val marker = rest.take(2)
            val end = rest.indexOf(marker, 2)
            if (end > 1) {
                push(rest.substring(2, end), SpanStyle(fontWeight = FontWeight.Bold))
                i += end + 2
                continue
            }
        }

        if (rest.startsWith("~~")) {
            val end = rest.indexOf("~~", 2)
            if (end > 1) {
                push(rest.substring(2, end), SpanStyle(textDecoration = TextDecoration.LineThrough))
                i += end + 2
                continue
            }
        }

        if ((rest.startsWith("*") || rest.startsWith("_")) && rest.length > 1) {
            val marker = rest.take(1)
            val end = rest.indexOf(marker, 1)
            if (end > 0) {
                push(rest.substring(1, end), SpanStyle(fontStyle = FontStyle.Italic))
                i += end + 1
                continue
            }
        }

        // [label](url) — the label is shown, underlined. The URL is dropped
        // rather than made tappable: a link handler needs a UriHandler and a
        // decision about opening untrusted URLs, and showing the label beats
        // showing raw markdown either way.
        if (rest.startsWith("[")) {
            val close = rest.indexOf(']')
            if (close > 0 && close + 1 < rest.length && rest[close + 1] == '(') {
                val paren = rest.indexOf(')', close)
                if (paren > 0) {
                    push(
                        rest.substring(1, close),
                        SpanStyle(textDecoration = TextDecoration.Underline),
                    )
                    i += paren + 1
                    continue
                }
            }
        }

        push(text[i].toString(), null)
        i++
    }
}

// Tinted as well as monospaced: a font change alone is hard to spot in a
// short span. Translucent grey reads on light and dark bubbles alike.
private val CODE_SPAN = SpanStyle(
    fontFamily = FontFamily.Monospace,
    background = androidx.compose.ui.graphics.Color(0x33808080),
)

/** Tiny shim so the pass above reads as "emit this run with this style". */
private fun buildAnnotatedStringCompat(
    build: ((String, SpanStyle?) -> Unit) -> Unit,
): AnnotatedString {
    val builder = AnnotatedString.Builder()
    build { chunk, style ->
        if (style == null) {
            builder.append(chunk)
        } else {
            builder.pushStyle(style)
            builder.append(chunk)
            builder.pop()
        }
    }
    return builder.toAnnotatedString()
}
