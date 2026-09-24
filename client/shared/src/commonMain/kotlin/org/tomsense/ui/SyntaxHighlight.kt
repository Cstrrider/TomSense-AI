package org.tomsense.ui

/**
 * A small, language-tolerant syntax highlighter for code blocks in replies.
 *
 * Not a parser. Models emit a few dozen languages and a real grammar per
 * language is a dependency the offline build can't take and a correctness
 * burden nobody reading a reply needs. What makes code READABLE at a glance is
 * five things — comments, strings, numbers, keywords, and calls — and those
 * look nearly the same across every language a model is likely to write.
 *
 * Output is spans over the source, never a rewritten string: copy must always
 * copy exactly what the model wrote, so highlighting can only ever colour it.
 */
enum class TokenKind { Comment, String, Number, Keyword, Function, Type, Plain }

data class Token(val start: Int, val end: Int, val kind: TokenKind)

private val KEYWORDS = setOf(
    // C family, JVM, JS/TS, Go, Rust, Swift
    "if", "else", "for", "while", "do", "switch", "case", "default", "break", "continue", "return",
    "function", "fun", "fn", "func", "def", "class", "interface", "object", "struct", "enum", "trait",
    "impl", "type", "typealias", "val", "var", "let", "const", "static", "final", "public", "private",
    "protected", "internal", "override", "abstract", "open", "sealed", "data", "suspend", "async",
    "await", "yield", "import", "from", "export", "package", "module", "use", "extends", "implements",
    "new", "this", "self", "super", "try", "catch", "finally", "throw", "throws", "in", "is", "as",
    "when", "match", "where", "with", "lambda", "pass", "raise", "except", "elif", "not", "and", "or",
    "true", "false", "null", "nil", "None", "True", "False", "undefined", "void", "mut", "pub", "crate",
    "go", "defer", "chan", "select", "range", "echo", "then", "fi", "esac", "done", "local", "readonly",
    "SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE", "CREATE", "TABLE",
    "JOIN", "LEFT", "RIGHT", "INNER", "ON", "GROUP", "BY", "ORDER", "LIMIT", "AND", "OR", "NOT", "NULL",
    "AS", "INDEX", "PRIMARY", "KEY", "ALTER", "DROP", "IF", "EXISTS",
)

/** Languages whose line comment is `#` rather than `//`. */
private val HASH_COMMENT = setOf(
    "python", "py", "bash", "sh", "shell", "zsh", "console", "yaml", "yml", "toml", "ruby", "rb",
    "perl", "r", "dockerfile", "makefile", "make", "ini", "conf", "nginx", "powershell", "ps1",
)
private val DASH_COMMENT = setOf("sql", "lua", "haskell", "hs", "elm")

fun highlight(code: String, lang: String?): List<Token> {
    val l = lang?.lowercase()?.trim().orEmpty()
    // Unlabelled blocks allow every comment style: a false comment is far
    // less damaging to readability than a real one rendered as code.
    val hash = l.isEmpty() || l in HASH_COMMENT
    val slash = l.isEmpty() || l !in HASH_COMMENT
    val dash = l in DASH_COMMENT
    // Markup and data formats have no keywords worth colouring.
    val plainWords = l in setOf("json", "xml", "html", "markdown", "md", "text", "txt", "csv")

    val out = ArrayList<Token>()
    var i = 0
    val n = code.length
    while (i < n) {
        val c = code[i]
        when {
            slash && c == '/' && i + 1 < n && code[i + 1] == '/' -> {
                val end = code.indexOf('\n', i).let { if (it < 0) n else it }
                out += Token(i, end, TokenKind.Comment); i = end
            }
            slash && c == '/' && i + 1 < n && code[i + 1] == '*' -> {
                val close = code.indexOf("*/", i + 2)
                val end = if (close < 0) n else close + 2
                out += Token(i, end, TokenKind.Comment); i = end
            }
            hash && c == '#' && (i == 0 || code[i - 1].isWhitespace()) -> {
                val end = code.indexOf('\n', i).let { if (it < 0) n else it }
                out += Token(i, end, TokenKind.Comment); i = end
            }
            dash && c == '-' && i + 1 < n && code[i + 1] == '-' -> {
                val end = code.indexOf('\n', i).let { if (it < 0) n else it }
                out += Token(i, end, TokenKind.Comment); i = end
            }
            c == '"' || c == '\'' || c == '`' -> {
                // Triple quotes (Python, Kotlin raw strings) close on a triple.
                val triple = i + 2 < n && code[i + 1] == c && code[i + 2] == c
                var j = if (triple) i + 3 else i + 1
                while (j < n) {
                    if (code[j] == '\\' && !triple) { j += 2; continue }
                    if (triple) {
                        if (j + 2 < n && code[j] == c && code[j + 1] == c && code[j + 2] == c) { j += 3; break }
                    } else if (code[j] == c) { j++; break }
                    // An unclosed ' is usually an apostrophe in a comment-less
                    // language (shell prose, markdown) — stop at the line end.
                    else if (code[j] == '\n' && c != '`') break
                    j++
                }
                val end = j.coerceAtMost(n)
                out += Token(i, end, TokenKind.String); i = end
            }
            c.isDigit() && (i == 0 || !code[i - 1].isLetterOrDigit() && code[i - 1] != '_') -> {
                var j = i + 1
                while (j < n && (code[j].isLetterOrDigit() || code[j] == '.' || code[j] == '_')) j++
                out += Token(i, j, TokenKind.Number); i = j
            }
            c.isLetter() || c == '_' || c == '@' -> {
                var j = i + 1
                while (j < n && (code[j].isLetterOrDigit() || code[j] == '_')) j++
                val word = code.substring(i, j)
                val kind = when {
                    plainWords -> TokenKind.Plain
                    word in KEYWORDS -> TokenKind.Keyword
                    word.startsWith("@") -> TokenKind.Keyword
                    j < n && code[j] == '(' -> TokenKind.Function
                    word[0].isUpperCase() && word.length > 1 && word.any { it.isLowerCase() } -> TokenKind.Type
                    else -> TokenKind.Plain
                }
                if (kind != TokenKind.Plain) out += Token(i, j, kind)
                i = j
            }
            else -> i++
        }
    }
    return out
}
