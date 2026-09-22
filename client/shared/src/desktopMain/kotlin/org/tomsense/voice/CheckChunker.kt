package org.tomsense.voice

/**
 * `./gradlew -q :shared:checkChunker`
 *
 * Streams each input one character at a time, the way tokens actually arrive,
 * because the interesting failures only happen mid-stream: a decimal point
 * with no following character yet looks exactly like a sentence end.
 */
fun main() {
    var failures = 0

    fun streamed(text: String): List<String> {
        val chunker = SentenceChunker()
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        for (ch in text) {
            sb.append(ch)
            out += chunker.feed(sb.toString())
        }
        chunker.drain(sb.toString())?.let { out += it }
        return out
    }

    fun check(label: String, input: String, expected: List<String>) {
        val got = streamed(input)
        val ok = got == expected
        if (!ok) failures++
        println("${if (ok) "  ok  " else "  FAIL"} $label")
        if (!ok) {
            println("        want: $expected")
            println("        got : $got")
        }
    }

    println("-- splitting --")
    check("two sentences", "Hello there. How are you?", listOf("Hello there.", "How are you?"))
    check(
        "decimal survives",
        "It grew by 3.5 million last year. That is a lot.",
        listOf("It grew by 3.5 million last year.", "That is a lot."),
    )
    check(
        "abbreviation survives",
        "Dr. Smith will see you now. Please wait.",
        listOf("Dr. Smith will see you now.", "Please wait."),
    )
    check(
        "initials survive",
        "The author is J. R. R. Tolkien. He wrote a lot.",
        listOf("The author is J. R. R. Tolkien.", "He wrote a lot."),
    )
    check("newline splits", "First line\nSecond line.", listOf("First line", "Second line."))
    check("trailing fragment drains", "No period here", listOf("No period here"))
    check("e.g. survives", "Use a tool, e.g. the timer. Then stop.", listOf("Use a tool, e.g. the timer.", "Then stop."))

    println()
    println("-- speakable text --")
    val cases = listOf(
        "```kotlin\nval x = 1\n```" to "(code omitted)",
        "See **this** and *that*." to "See this and that.",
        "## Heading\nBody." to "Heading\nBody.",
        "Read [the docs](https://example.com/a/b)." to "Read the docs.",
        "Go to https://example.com/very/long/path now." to "Go to a link now.",
    )
    for ((input, want) in cases) {
        val got = speakableText(input)
        val ok = got == want
        if (!ok) failures++
        println("${if (ok) "  ok  " else "  FAIL"} ${input.replace("\n", "\\n")}")
        if (!ok) {
            println("        want: \"$want\"")
            println("        got : \"$got\"")
        }
    }

    println()
    println(if (failures == 0) "all clear" else "$failures failure(s)")
    if (failures > 0) kotlin.system.exitProcess(1)
}
