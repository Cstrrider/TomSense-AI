package org.tomsense.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver

/**
 * Full-text search over message bodies, on the device (migration doc §3).
 *
 * ## Why this is raw SQL, built at runtime
 *
 * Two separate constraints land in the same place.
 *
 * **SQLDelight cannot generate code against a virtual table.** Its 2.0.2
 * SQLite dialect has no FTS module, so the columns have no type: selecting one
 * fails to compile, and merely referencing the table in a subquery sends the
 * generator into infinite recursion. So these statements are hand-written and
 * gathered here rather than scattered — they are the only queries in the
 * client the compiler does not check.
 *
 * **The index must not be created by a migration.** beta7 did exactly that and
 * the app could not start: migrations run from `Application.onCreate`, so a
 * statement that throws there means the database never opens. Building the
 * index at runtime is what lets a failure be caught, recorded, and degraded
 * around instead of being fatal.
 *
 * ## Why FTS4 rather than FTS5
 *
 * Android's system SQLite is not compiled with FTS5 — this is why Room itself
 * supports only FTS3/FTS4 and recommends FTS4. FTS5 works on the desktop JDBC
 * driver, which bundles its own SQLite, and that discrepancy is exactly what
 * made beta7 pass every test here and die on the phone.
 *
 * The cost is relevance ranking: FTS4 has no `bm25()`, so results come back
 * newest-first. For searching one's own chat history that is arguably the
 * better order anyway — the usual question is "the recent conversation where I
 * mentioned X", not "the most lexically relevant".
 *
 * ## If even FTS4 is missing
 *
 * Some AOSP-derived builds strip more than others. [isAvailable] reports
 * honestly, indexing becomes a no-op, and the repository falls back to a LIKE
 * scan. Slower on a large history, but it always works and it never crashes.
 */
internal class MessageSearch(private val driver: SqlDriver) {

    /**
     * False when this device's SQLite has no usable FTS module.
     *
     * Checked by the repository to pick a search strategy. It is set once, at
     * construction, so the answer cannot change midway through a session and
     * leave the index half-populated.
     */
    var isAvailable: Boolean = false
        private set

    init {
        // Creating the table is cheap and has to happen before anything can
        // index into it, so it runs here. Populating it does NOT — see
        // [backfillIfNeeded].
        //
        // `runCatching` is load-bearing rather than defensive habit: on a
        // device with no FTS module this throws, and this constructor runs
        // inside Application.onCreate. Letting it propagate is precisely the
        // beta7 crash, one layer up.
        isAvailable = runCatching {
            exec(
                """
                CREATE VIRTUAL TABLE IF NOT EXISTS message_index USING fts4(
                  msg_id, conv_id, content, notindexed=msg_id, notindexed=conv_id
                )
                """.trimIndent(),
            )
        }.isSuccess
    }

    /**
     * Populate the index from existing messages, if it is empty and history
     * is not.
     *
     * **Call this off the main thread.** It is separated from the constructor
     * for exactly that reason: the constructor runs in `Application.onCreate`,
     * and an `INSERT ... SELECT` across a long history there is an ANR on a
     * cold start — a slower repeat of the mistake that made the index fatal in
     * the first place.
     *
     * Guarded by the row counts rather than a "did I just create it" flag, so
     * it is idempotent and self-healing: an index that was wiped or half-built
     * repairs itself on the next launch instead of staying empty forever.
     *
     * Without it, search returns nothing for the entire history predating the
     * index — indistinguishable, to the user, from search being broken.
     */
    fun backfillIfNeeded() {
        if (!isAvailable) return
        runCatching {
            if (countOf("message_index") == 0L && countOf("message") > 0L) {
                exec(
                    """
                    INSERT INTO message_index(msg_id, conv_id, content)
                    SELECT id, conv_id, content FROM message
                     WHERE deleted = 0 AND content <> ''
                    """.trimIndent(),
                )
            }
        }
    }

    fun index(msgId: String, convId: String, content: String) {
        if (!isAvailable) return
        driver.execute(
            identifier = null,
            sql = "INSERT INTO message_index(msg_id, conv_id, content) VALUES (?, ?, ?)",
            parameters = 3,
        ) {
            bindString(0, msgId)
            bindString(1, convId)
            bindString(2, content)
        }
    }

    /** FTS has no upsert, so re-indexing is always delete-then-insert. */
    fun unindex(msgId: String) {
        if (!isAvailable) return
        driver.execute(
            identifier = null,
            sql = "DELETE FROM message_index WHERE msg_id = ?",
            parameters = 1,
        ) {
            bindString(0, msgId)
        }
    }

    fun unindexConversation(convId: String) {
        if (!isAvailable) return
        driver.execute(
            identifier = null,
            sql = "DELETE FROM message_index WHERE conv_id = ?",
            parameters = 1,
        ) {
            bindString(0, convId)
        }
    }

    /**
     * Search, newest first.
     *
     * The join back to `message` and `conversation` is what filters out
     * tombstoned rows: the index is maintained explicitly, so a row deleted
     * while the app was closed could otherwise still be in it.
     *
     * `snippet()` here uses the FTS3/4 argument order —
     * `(table, start, end, ellipsis, column, tokens)` — which is NOT the FTS5
     * order. Getting these two confused produces plausible-looking output
     * rather than an error.
     */
    fun search(match: String, limit: Long): List<MessageHit> {
        if (!isAvailable) return emptyList()

        val sql = """
            SELECT m.id, m.conv_id, c.title, m.role, m.created_at,
                   snippet(message_index, '[', ']', '...', 2, 12)
              FROM message_index
              JOIN message m      ON m.id = message_index.msg_id
              JOIN conversation c ON c.id = m.conv_id
             WHERE message_index MATCH ?
               AND m.deleted = 0
               AND c.deleted = 0
             ORDER BY m.created_at DESC
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
            mapper = ::readHits,
        ).value
    }

    private fun readHits(cursor: app.cash.sqldelight.db.SqlCursor): QueryResult.Value<List<MessageHit>> {
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
        return QueryResult.Value(hits.toList())
    }

    private fun exec(sql: String) =
        driver.execute(identifier = null, sql = sql, parameters = 0)

    private fun countOf(table: String): Long =
        driver.executeQuery(
            identifier = null,
            sql = "SELECT COUNT(*) FROM $table",
            parameters = 0,
            mapper = { c ->
                QueryResult.Value(if (c.next().value) c.getLong(0) ?: 0L else 0L)
            },
        ).value
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
 * Turn what the user typed into an FTS4 MATCH expression, or null if there is
 * nothing searchable in it.
 *
 * Only letters and digits survive, and each resulting token gets a trailing
 * `*` so results appear while a word is still being typed. Emitting nothing
 * else is what makes this safe: FTS treats `-`, `"`, `*`, `(`, `OR` and `NEAR`
 * as operators, and a malformed expression THROWS rather than returning no
 * rows — on a search-as-you-type field that is a crash per keystroke.
 *
 * Splitting on non-alphanumerics also matches how the default tokenizer
 * indexed the text, so "it's" becomes `it* s*` and still finds the original.
 */
internal fun ftsMatchExpression(raw: String): String? {
    val tokens = raw
        .map { if (it.isLetterOrDigit()) it else ' ' }
        .joinToString("")
        .split(' ')
        .filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return null
    return tokens.joinToString(" ") { "$it*" }
}
