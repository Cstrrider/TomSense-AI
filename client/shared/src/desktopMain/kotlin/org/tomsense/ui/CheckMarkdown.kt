package org.tomsense.ui

/**
 * Exercise the Markdown parser. `./gradlew -q :shared:checkMarkdown`
 *
 * Worth having as a runnable check rather than eyeballing the rendered result,
 * because the failure modes are all silent: a fence that swallows the rest of
 * the reply, emphasis that eats a bold marker, or a code comment promoted to a
 * heading. None of those throw — they just render something plausible and
 * wrong.
 */
private data class Case(val name: String, val source: String, val expect: (List<MdBlock>) -> Boolean)

private val CASES = listOf(
    Case(
        "heading levels",
        "# One\n## Two\n### Three",
    ) { b ->
        b.size == 3 && (b[0] as MdBlock.Heading).level == 1 &&
            (b[1] as MdBlock.Heading).level == 2 && (b[2] as MdBlock.Heading).level == 3
    },

    Case(
        "fence beats every other marker",
        "```sh\n# not a heading\n- not a bullet\n> not a quote\n```",
    ) { b ->
        b.size == 1 && b[0] is MdBlock.Code &&
            (b[0] as MdBlock.Code).lang == "sh" &&
            (b[0] as MdBlock.Code).code.lines().size == 3
    },

    Case(
        "unterminated fence still renders",
        "text\n\n```\nstuck open",
    ) { b -> b.any { it is MdBlock.Code && (it as MdBlock.Code).code.contains("stuck open") } },

    Case(
        "ordered and unordered lists",
        "1. first\n2. second\n\n- a\n* b\n+ c",
    ) { b ->
        b.count { it is MdBlock.Bullet } == 5 &&
            (b[0] as MdBlock.Bullet).ordinal == "1." &&
            (b[2] as MdBlock.Bullet).ordinal == null
    },

    Case(
        "bold is not eaten by italic",
        "**bold** and *it*",
    ) { b -> (b[0] as MdBlock.Paragraph).text.text == "bold and it" },

    Case(
        "inline code suppresses markers inside it",
        "use `a * b ** c` here",
    ) { b -> (b[0] as MdBlock.Paragraph).text.text == "use a * b ** c here" },

    Case(
        "link shows its label, drops the url",
        "see [the docs](https://example.com) now",
    ) { b -> (b[0] as MdBlock.Paragraph).text.text == "see the docs now" },

    Case(
        "soft-wrapped lines join into one paragraph",
        "line one\nline two\n\nsecond para",
    ) { b -> b.size == 2 && (b[0] as MdBlock.Paragraph).text.text == "line one line two" },

    Case("horizontal rule", "a\n\n---\n\nb") { b -> b.any { it is MdBlock.Rule } },

    Case("blockquote", "> quoted") { b -> b.size == 1 && b[0] is MdBlock.Quote },

    Case(
        "plain prose is untouched",
        "Just a sentence with no markup at all.",
    ) { b -> b.size == 1 && (b[0] as MdBlock.Paragraph).text.text == "Just a sentence with no markup at all." },

    Case(
        "unmatched markers stay literal",
        "2 * 3 = 6 and a_b_c",
    ) { b -> (b[0] as MdBlock.Paragraph).text.text.startsWith("2 * 3 = 6") },
)

/** Highlighter checks: the kinds a reader relies on, and that copy stays exact. */
private fun kindsAt(code: String, lang: String?) = highlight(code, lang).associate { code.substring(it.start, it.end) to it.kind }

private val HIGHLIGHT_CASES: List<Pair<String, () -> Boolean>> = listOf(
    "kotlin: keyword, string, call, comment" to {
        val k = kindsAt("val x = greet(\"hi\") // say it", "kotlin")
        k["val"] == TokenKind.Keyword && k["\"hi\""] == TokenKind.String &&
            k["greet"] == TokenKind.Function && k["// say it"] == TokenKind.Comment
    },
    "python: # is a comment, // is not" to {
        val k = kindsAt("x = 7 // 2  # floor div", "python")
        k["# floor div"] == TokenKind.Comment && k.keys.none { it.startsWith("//") } && k["7"] == TokenKind.Number
    },
    "c: #include is not a comment when labelled" to {
        kindsAt("#include <stdio.h>", "c").values.none { it == TokenKind.Comment }
    },
    "shell apostrophe does not swallow the rest" to {
        val code = "echo don't\nls -la"
        highlight(code, "bash").none { it.kind == TokenKind.String && code.substring(it.start, it.end).contains("ls") }
    },
    "unterminated block comment ends at EOF, no crash" to {
        highlight("a /* never closed", "js").last().kind == TokenKind.Comment
    },
    "spans stay inside the source" to {
        val code = "fn main() { println!(\"{}\", 42); }"
        highlight(code, "rust").all { it.start >= 0 && it.end <= code.length && it.start < it.end }
    },
    "json values get no keyword colour" to {
        kindsAt("{\"a\": true, \"b\": null}", "json").values.none { it == TokenKind.Keyword }
    },
)

fun main() {
    var failed = 0
    for (case in CASES) {
        val blocks = runCatching { parseMarkdown(case.source) }.getOrNull()
        val ok = blocks != null && runCatching { case.expect(blocks) }.getOrDefault(false)
        println("  ${if (ok) "PASS" else "FAIL"}  ${case.name}")
        if (!ok) {
            failed++
            blocks?.forEach { println("        $it") }
        }
    }
    for ((name, check) in HIGHLIGHT_CASES) {
        val ok = runCatching { check() }.getOrDefault(false)
        println("  ${if (ok) "PASS" else "FAIL"}  highlight: $name")
        if (!ok) failed++
    }
    val total = CASES.size + HIGHLIGHT_CASES.size
    println()
    println("== ${total - failed} passed, $failed failed ==")
    if (failed > 0) kotlin.system.exitProcess(1)
}
