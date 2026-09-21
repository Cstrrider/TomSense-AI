package org.tomsense.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver

/**
 * Full-text search over message bodies, on the device (migration doc §3).
 *
 * ## Why this file is hand-written SQL
 *
 * SQLDelight 2.0.2 cannot generate code against an FTS5 table. Its SQLite
 * dialect ships no fts5 module, so a virtual table's columns carry no type:
 * selecting one fails to compile, and merely referencing the table inside a
 * subquery sends the generator into infinite recursion. The table is still
 * created normally in `1.sqm` — migrations are passed through without being
 * typed — so the schema is fine and only the queries have to come here.
 *
 * Every FTS5 statement in the app is therefore in this one class, deliberately.
 * These are the only queries in the codebase the compiler does not check, and
 * keeping them together means there is exactly one file to look at when search
 * misbehaves, instead of raw SQL scattered through the repository.
 *
 * Writes go through the same driver the rest of the app uses, so calling these
 * inside a `db.transaction { }` block enrolls them in that transaction — which
 * is what keeps the index and the `message` table from disagreeing when a
 * write fails halfway.
 */
internal class MessageSearch(private val driver: SqlDriver) {

    fun index(msgId: String, convId: String, content: String) {
        driver.execute(
            identifier = null,
            sql = "INSERT INTO message_fts(msg_id, conv_id, content) VALUES (?, ?, ?)",
            parameters = 3,
        ) {
            bindString(0, msgId)
            bindString(1, convId)
            bindString(2, content)
        }
    }

    /** FTS5 has no upsert, so re-indexing is always delete-then-insert. */
    fun unindex(msgId: String) {
        driver.execute(
            identifier = null,
            sql = "DELETE FROM message_fts WHERE msg_id = ?",
            parameters = 1,
        ) {
            bindString(0, msgId)
        }
    }

    fun unindexConversation(convId: String) {
        driver.execute(
            identifier = null,
            sql = "DELETE FROM message_fts WHERE conv_id = ?",
            parameters = 1,
        ) {
            bindString(0, convId)
        }
    }

    /**
     * Search, ranked by relevance.
     *
     * `bm25()` rather than the `rank` shorthand — same function, but spelled
     * in a way that does not depend on FTS5's hidden-column sugar. Lower is
     * better, hence ASC. `snippet()` returns the matched fragment with the hit
     * wrapped in the markers, which is what makes a result readable without
     * opening the conversation.
     *
     * The join back to `message` and `conversation` is what filters out
     * tombstoned rows: the index is maintained explicitly, and a row deleted
     * while the app was closed could otherwise still be in it.
     */
    fun search(match: String, limit: Long): List<MessageHit> {
        val sql = """
            SELECT m.id, m.conv_id, c.title, m.role, m.created_at,
                   snippet(message_fts, 2, '[', ']', '...', 12)
            FROM message_fts
            JOIN message m      ON m.id = message_fts.msg_id
            JOIN conversation c ON c.id = m.conv_id
            WHERE message_fts MATCH ?
              AND m.deleted = 0
              AND c.deleted = 0
            ORDER BY bm25(message_fts) ASC
            LIMIT ?
        """.trimIndent()

        return driver.executeQuery(
            identifier = null,
            sql = sql,
            parameters = 2,
            binders = {
                bindString(0, match)
                bindLong(1, limit)
            },
            mapper = { cursor ->
                val hits = mutableListOf<MessageHit>()
                while (cursor.next().value) {
                    hits += MessageHit(
                        msgId = cursor.getString(0).orEmpty(),
                        convId = cursor.getString(1).orEmpty(),
                        title = cursor.getString(2).orEmpty(),
                        role = cursor.getString(3).orEmpty(),
                        createdAt = cursor.getLong(4) ?: 0L,
                        excerpt = cursor.getString(5).orEmpty(),
                    )
                }
                QueryResult.Value(hits.toList())
            },
        ).value
    }
}

/** One search result: enough to render a row and open the right conversation. */
data class MessageHit(
    val msgId: String,
    val convId: String,
    val title: String,
    val role: String,
    val createdAt: Long,
    /** The matched fragment, with the hit wrapped in `[` and `]`. */
    val excerpt: String,
)

/**
 * Turn what the user typed into an FTS5 MATCH expression, or null if there is
 * nothing to search for.
 *
 * Each term is quoted and given a trailing `*`, so results appear while a word
 * is still being typed. The quoting is the important part: without it, FTS5
 * reads `-`, `"`, `OR` and `NEAR` as operators, and searching for a hyphenated
 * word or an apostrophe is a syntax error rather than a search. Quoting turns
 * the whole term back into literal text.
 */
internal fun ftsMatchExpression(raw: String): String? {
    val terms = raw.split(' ', '\t', '\n', ',')
        .map { it.replace("\"", "").trim() }
        .filter { it.isNotEmpty() }
    if (terms.isEmpty()) return null
    return terms.joinToString(" ") { "\"$it\"*" }
}
