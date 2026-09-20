package org.tomsense.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.tomsense.db.Conversation
import org.tomsense.db.Message
import org.tomsense.db.TomsenseDb

/**
 * The only thing the UI talks to.
 *
 * Every read is a Flow over local SQLite and every write commits locally and
 * returns. There is deliberately no suspend-on-network call anywhere in this
 * class: if the UI could ever block on the edge, the app would stop being
 * local-first, which is the entire justification for the native rewrite.
 *
 * Sending a message is therefore two separate things — persist the user's
 * turn (instant, offline-safe) and start a generation (may fail, may be
 * retried, may complete while the app is dead). Conflating them is what makes
 * chat apps lose messages when the network drops mid-send.
 */
class ChatRepository(
    private val db: TomsenseDb,
    private val deviceId: String,
) {
    private val q = db.schemaQueries

    fun conversations(): Flow<List<Conversation>> =
        q.conversationList().asFlow().mapToList(Dispatchers.Default)

    fun messages(convId: String): Flow<List<Message>> =
        q.messagesFor(convId).asFlow().mapToList(Dispatchers.Default)

    suspend fun createConversation(title: String = "", model: String = ""): String =
        withContext(Dispatchers.Default) {
            val id = randomId()
            val now = nowMillis()
            db.transaction {
                q.bumpLamport()
                val lamport = q.syncState().executeAsOne().lamport
                q.upsertConversation(
                    id = id,
                    project_id = null,
                    title = title,
                    model = model,
                    created_at = now,
                    updated_at = now,
                    deleted = 0,
                    lamport = lamport,
                    device_id = deviceId,
                    dirty = 1,
                )
            }
            id
        }

    /**
     * Persist a turn locally. Returns immediately; the sync engine drains it.
     *
     * `dirty = 1` is what guarantees the message survives — if the process is
     * killed the instant after this returns, the row is already committed and
     * will push on next launch.
     */
    suspend fun appendMessage(
        convId: String,
        role: String,
        content: String,
        reasoning: String? = null,
    ): String = withContext(Dispatchers.Default) {
        val id = randomId()
        val now = nowMillis()
        db.transaction {
            q.bumpLamport()
            val lamport = q.syncState().executeAsOne().lamport
            q.upsertMessage(
                id = id,
                conv_id = convId,
                role = role,
                content = content,
                encrypted = 0,
                reasoning = reasoning,
                tool_calls = null,
                attachments = null,
                created_at = now,
                deleted = 0,
                lamport = lamport,
                device_id = deviceId,
                dirty = 1,
            )
            q.upsertConversationTimestamp(now, convId)
        }
        id
    }

    /**
     * Replace an assistant message's body as tokens stream in.
     *
     * Marked dirty = 0 while streaming ON PURPOSE. Pushing a partial answer on
     * every token would flood the edge with hundreds of writes per reply and
     * leave other devices rendering half-sentences. The row is marked dirty
     * once, at completion, by [finishStreaming].
     */
    suspend fun updateStreamingContent(msgId: String, content: String) =
        withContext(Dispatchers.Default) {
            q.updateMessageContent(content, msgId)
        }

    /** Same deal as [updateStreamingContent] — not dirtied until completion. */
    suspend fun updateStreamingReasoning(msgId: String, reasoning: String) =
        withContext(Dispatchers.Default) {
            q.updateMessageReasoning(reasoning, msgId)
        }

    /**
     * Empty an assistant turn so it can be generated again.
     *
     * Reuses the row instead of inserting a replacement: under last-writer-wins
     * sync, two rows for the same answer means every other device ends up
     * showing both.
     */
    suspend fun resetMessage(msgId: String) = withContext(Dispatchers.Default) {
        q.resetMessage(msgId)
    }

    suspend fun finishStreaming(msgId: String) = withContext(Dispatchers.Default) {
        db.transaction {
            q.bumpLamport()
            val lamport = q.syncState().executeAsOne().lamport
            q.markMessageDirty(lamport, msgId)
        }
    }
}

/** Platform clock and id source — trivial, but they differ per target. */
expect fun nowMillis(): Long

expect fun randomId(): String
