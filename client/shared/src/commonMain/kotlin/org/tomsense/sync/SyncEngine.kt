package org.tomsense.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.Serializable
import org.tomsense.db.TomsenseDb

/**
 * The client half of sync (spec §5).
 *
 * The contract that matters: **nothing in the UI path ever awaits this class.**
 * Writes go to SQLite and return; the engine drains them in the background.
 * If that invariant is broken anywhere, the app stops being local-first and
 * becomes the WebView-on-server.url design again, just with more code.
 */
class SyncEngine(
    private val db: TomsenseDb,
    private val api: EdgeApi,
    private val scope: CoroutineScope,
) {
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val status: StateFlow<SyncStatus> = _status

    /** Push batch size. Large enough to be efficient, small enough that a
     *  failed batch on a flaky connection isn't an expensive retry. */
    private val batchSize = 200

    fun start() {
        scope.launch(Dispatchers.Default) {
            var backoffMs = 1_000L
            while (true) {
                val ok = runCatching { syncOnce() }
                    .onFailure { _status.value = SyncStatus.Error(it.message ?: "sync failed") }
                    .isSuccess

                if (ok) {
                    backoffMs = 1_000L
                    delay(15_000)
                } else {
                    // Offline is the NORMAL case, not an error state to shout
                    // about. Back off quietly and keep serving local data.
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(5 * 60_000L)
                }
            }
        }
    }

    suspend fun syncOnce() = withContext(Dispatchers.Default) {
        _status.value = SyncStatus.Syncing
        pushDirty()
        pullRemote()
        _status.value = SyncStatus.Idle
    }

    private suspend fun pushDirty() {
        val q = db.schemaQueries
        val state = q.syncState().executeAsOneOrNull() ?: return

        while (true) {
            val convs = q.dirtyConversations(batchSize.toLong()).executeAsList()
            val msgs = q.dirtyMessages(batchSize.toLong()).executeAsList()
            if (convs.isEmpty() && msgs.isEmpty()) return

            val rows = buildList {
                convs.forEach { add(PushRow("conversations", it.toJson(state.device_id))) }
                msgs.forEach { add(PushRow("messages", it.toJson(state.device_id))) }
            }

            api.push(PushRequest(deviceId = state.device_id, rows = rows))

            // Clear the dirty flag only for the lamport we actually sent. If
            // the user edited the row while the request was in flight, its
            // lamport has advanced and the guard leaves it dirty — so the
            // newer edit is not silently dropped.
            db.transaction {
                convs.forEach { q.clearConversationDirty(it.id, it.lamport) }
                msgs.forEach { q.clearMessageDirty(it.id, it.lamport) }
            }
        }
    }

    private suspend fun pullRemote() {
        val q = db.schemaQueries
        var cursor = q.syncState().executeAsOneOrNull()?.cursor ?: 0L

        while (true) {
            val page = api.pull(cursor)
            if (page.rows.isEmpty()) {
                if (page.cursor > cursor) q.setCursor(page.cursor)
                return
            }

            db.transaction {
                for (entry in page.rows) applyRemote(entry)
                q.setCursor(page.cursor)
            }
            cursor = page.cursor

            if (!page.more) return
        }
    }

    /**
     * Apply one remote row.
     *
     * Remote rows land with `dirty = 0` — they came FROM the server, so
     * echoing them straight back would be a pointless round trip and, worse,
     * would make two devices ping-pong the same row forever.
     */
    private fun applyRemote(entry: PullRow) {
        val q = db.schemaQueries
        val o = entry.row
        val lamport = o.long("lamport")
        q.raiseLamport(lamport)

        when (entry.table) {
            "conversations" -> q.upsertConversation(
                id = o.string("id"),
                project_id = o.stringOrNull("project_id"),
                title = o.string("title"),
                model = o.string("model"),
                created_at = o.long("created_at"),
                updated_at = o.long("updated_at"),
                deleted = o.long("deleted"),
                lamport = lamport,
                device_id = o.string("device_id"),
                dirty = 0,
            )
            "messages" -> q.upsertMessage(
                id = o.string("id"),
                conv_id = o.string("conv_id"),
                role = o.string("role"),
                content = o.string("content"),
                encrypted = o.long("encrypted"),
                reasoning = o.stringOrNull("reasoning"),
                tool_calls = o.stringOrNull("tool_calls"),
                attachments = o.stringOrNull("attachments"),
                created_at = o.long("created_at"),
                deleted = o.long("deleted"),
                lamport = lamport,
                device_id = o.string("device_id"),
                dirty = 0,
            )
            // projects / memories handled once their local tables land (M7).
        }
    }
}

sealed interface SyncStatus {
    data object Idle : SyncStatus
    data object Syncing : SyncStatus
    data class Error(val message: String) : SyncStatus
}

@Serializable
data class PushRow(val table: String, val row: JsonObject)

@Serializable
data class PushRequest(val deviceId: String, val rows: List<PushRow>)

@Serializable
data class PullRow(val table: String, val row: JsonObject)

@Serializable
data class PullResponse(val cursor: Long, val rows: List<PullRow>, val more: Boolean)
