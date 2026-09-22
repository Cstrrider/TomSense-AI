package org.tomsense.voice

/**
 * Turns a reply that is still arriving into sentences that can be spoken now.
 *
 * This is the single biggest win in perceived voice latency, and it costs
 * nothing: waiting for a complete answer before speaking means the user hears
 * silence for as long as the model takes, while speaking sentence one the
 * moment it lands means they hear a voice in a second or so and the rest
 * arrives underneath. Nothing about the model or the network changes — only
 * when the first sound happens.
 *
 * ## What it must not do
 *
 * Split in the wrong place and the speech sounds broken in a way that is worse
 * than a pause. "3.5 million" must not become "three point" … "five million";
 * "Dr. Smith" must not stop after "Dr."; and a code block should not be read
 * as prose at all.
 *
 * ## Usage
 *
 * Feed it the growing text on every token. It returns whatever complete
 * sentences have become available since the last call, and keeps the
 * remainder. Call [drain] at the end for the trailing fragment.
 */
class SentenceChunker {

    private var consumed = 0

    /**
     * Sentences that have completed since the last call.
     *
     * Takes the WHOLE text each time rather than a delta, because that is what
     * the streaming loop already has — reconstructing deltas at the call site
     * is an easy place to drop a token.
     */
    fun feed(fullText: String): List<String> {
        if (fullText.length < consumed) {
            // The text got shorter: a regenerate reused the row. Start over
            // rather than slicing past the end.
            consumed = 0
        }

        val pending = fullText.substring(consumed)
        val out = mutableListOf<String>()
        var cut = 0

        var i = 0
        while (i < pending.length) {
            val c = pending[i]
            if (c == '.' || c == '!' || c == '?' || c == '\n') {
                if (isBoundary(pending, i)) {
                    val piece = pending.substring(cut, i + 1).trim()
                    if (piece.isNotEmpty() && piece.any { it.isLetterOrDigit() }) out += piece
                    cut = i + 1
                }
            }
            i++
        }

        consumed += cut
        return out
    }

    /** Whatever is left when the reply ends — usually a sentence with no period. */
    fun drain(fullText: String): String? {
        if (fullText.length < consumed) return null
        val rest = fullText.substring(consumed).trim()
        consumed = fullText.length
        return rest.takeIf { it.isNotEmpty() && it.any { ch -> ch.isLetterOrDigit() } }
    }

    fun reset() {
        consumed = 0
    }

    /**
     * Is this punctuation actually the end of a sentence?
     *
     * The next character decides it: a sentence end is followed by whitespace
     * or nothing. "3.5" and "e.g." fail that test, which is most of the job.
     */
    private fun isBoundary(text: String, at: Int): Boolean {
        if (text[at] == '\n') return true

        val next = text.getOrNull(at + 1)
        // Mid-stream and nothing after it yet — wait rather than guess, or
        // every decimal point is spoken as a full stop the instant it arrives.
        if (next == null) return false
        if (!next.isWhitespace()) return false

        if (text[at] == '.') {
            val before = text.substring(0, at)
            if (endsWithAbbreviation(before)) return false
            // A single letter before a period is an initial: "J. R. R."
            val lastWord = before.takeLastWhile { !it.isWhitespace() }
            if (lastWord.length == 1 && lastWord[0].isUpperCase()) return false
        }
        return true
    }

    private fun endsWithAbbreviation(before: String): Boolean {
        val word = before.takeLastWhile { !it.isWhitespace() }.lowercase()
        return ABBREVIATIONS.any { word == it || word.endsWith(".$it") }
    }

    private companion object {
        /**
         * Deliberately short. Every entry is a word that genuinely appears
         * mid-sentence followed by a period; a longer list starts swallowing
         * real sentence ends, which is the worse failure — a pause is
         * survivable, a run-on paragraph read as one breath is not.
         */
        val ABBREVIATIONS = setOf(
            "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st",
            "e.g", "i.e", "etc", "vs", "approx", "no", "fig",
        )
    }
}

/**
 * Text that should not be spoken aloud.
 *
 * A model's reply often contains fenced code, tables or long URLs, and reading
 * those out is worse than skipping them — nobody wants to hear backtick
 * backtick backtick kotlin. Stripped rather than summarised, because inventing
 * a spoken description of code is a different feature with different failure
 * modes.
 */
fun speakableText(raw: String): String {
    var text = raw

    // Fenced blocks, with a spoken marker so the omission is not silent.
    text = Regex("```[\\s\\S]*?```").replace(text, " (code omitted) ")
    text = Regex("`[^`\\n]+`").replace(text) { it.value.trim('`') }

    // Markdown emphasis and headers read as noise.
    text = Regex("^#{1,6}\\s*", RegexOption.MULTILINE).replace(text, "")
    text = Regex("\\*\\*([^*]+)\\*\\*").replace(text) { it.groupValues[1] }
    text = Regex("(?<![*\\w])\\*([^*\\n]+)\\*(?![*\\w])").replace(text) { it.groupValues[1] }

    // A link becomes its label; a bare URL becomes a short phrase rather than
    // a letter-by-letter recitation of a path.
    text = Regex("\\[([^\\]]+)]\\([^)]+\\)").replace(text) { it.groupValues[1] }
    text = Regex("https?://\\S+").replace(text, " a link ")

    return text.replace(Regex("[ \\t]+"), " ").trim()
}
