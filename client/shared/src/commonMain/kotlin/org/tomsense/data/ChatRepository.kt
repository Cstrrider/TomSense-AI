package org.tomsense.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
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
    /** Needed for the FTS5 statements SQLDelight cannot generate; see [MessageSearch]. */
    driver: SqlDriver,
) {
    private val q = db.schemaQueries
    private val fts = MessageSearch(driver)

    /**
     * Build the search index for history that predates it.
     *
     * Call once at startup from a background scope — never on the main
     * thread. Cheap and idempotent after the first run; see
     * [MessageSearch.backfillIfNeeded].
     */
    suspend fun prepareSearchIndex() = withContext(Dispatchers.Default) {
        fts.backfillIfNeeded()
    }

    fun conversations(): Flow<List<Conversation>> =
        q.conversationList().asFlow().mapToList(Dispatchers.Default)

    fun messages(convId: String): Flow<List<Message>> =
        q.messagesFor(convId).asFlow().mapToList(Dispatchers.Default)

    fun conversation(convId: String): Flow<Conversation?> =
        q.conversationById(convId).asFlow().mapToOneOrNull(Dispatchers.Default)

    suspend fun createConversation(title: String = "", model: String = ""): String =
        withContext(Dispatchers.Default) {
            val id = randomId()
            val now = nowMillis()
            db.transaction {
                q.upsertConversation(
                    id = id,
                    project_id = null,
                    title = title,
                    model = model,
                    system_prompt = null,
                    pinned = 0,
                    share_token = null,
                    created_at = now,
                    updated_at = now,
                    deleted = 0,
                    lamport = nextLamport(),
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
        /** R2 keys, stored as a JSON array; see the attachments column. */
        attachments: List<String> = emptyList(),
    ): String = withContext(Dispatchers.Default) {
        val id = randomId()
        val now = nowMillis()
        db.transaction {
            q.upsertMessage(
                id = id,
                conv_id = convId,
                role = role,
                content = content,
                encrypted = 0,
                reasoning = reasoning,
                tool_calls = null,
                attachments = attachments.takeIf { it.isNotEmpty() }?.let(::encodeKeys),
                created_at = now,
                deleted = 0,
                lamport = nextLamport(),
                device_id = deviceId,
                dirty = 1,
            )
            q.upsertConversationTimestamp(now, convId)
            // A user turn is complete the moment it is written, so it is
            // searchable immediately. Assistant turns start empty and are
            // indexed by finishStreaming instead.
            if (content.isNotEmpty()) fts.index(id, convId, content)
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
        db.transaction {
            q.resetMessage(msgId)
            // Drop the superseded answer from search straight away. Leaving it
            // would make a discarded reply findable, and tapping the result
            // would open a conversation that no longer contains those words.
            fts.unindex(msgId)
        }
    }

    /**
     * Mark a finished answer for sync — and index it for search.
     *
     * This is the single point where a streamed answer becomes "real", which
     * is why both the dirty flag and the search index are set here rather than
     * anywhere in the token loop.
     */
    suspend fun finishStreaming(msgId: String) = withContext(Dispatchers.Default) {
        db.transaction {
            q.markMessageDirty(nextLamport(), msgId)
            val msg = q.messageById(msgId).executeAsOneOrNull()
            if (msg != null && msg.content.isNotEmpty()) {
                fts.unindex(msgId)
                fts.index(msgId, msg.conv_id, msg.content)
            }
        }
    }

    // ─── chat management (migration doc phase D) ────────────────────────────

    suspend fun rename(convId: String, title: String) = withContext(Dispatchers.Default) {
        db.transaction { q.renameConversation(title, nowMillis(), nextLamport(), convId) }
    }

    suspend fun setPinned(convId: String, pinned: Boolean) = withContext(Dispatchers.Default) {
        db.transaction { q.setConversationPinned(if (pinned) 1 else 0, nextLamport(), convId) }
    }

    suspend fun setProject(convId: String, projectId: String?) =
        withContext(Dispatchers.Default) {
            db.transaction {
                q.setConversationProject(projectId, nowMillis(), nextLamport(), convId)
            }
        }

    /** Pass null to fall back to the global prompt; '' means a deliberately empty one. */
    suspend fun setSystemPrompt(convId: String, prompt: String?) =
        withContext(Dispatchers.Default) {
            db.transaction {
                q.setConversationSystemPrompt(prompt, nowMillis(), nextLamport(), convId)
            }
        }

    suspend fun setModel(convId: String, model: String) = withContext(Dispatchers.Default) {
        db.transaction { q.setConversationModel(model, nowMillis(), nextLamport(), convId) }
    }

    suspend fun setShareToken(convId: String, token: String?) =
        withContext(Dispatchers.Default) {
            db.transaction { q.setConversationShareToken(token, nextLamport(), convId) }
        }

    /**
     * Delete a conversation and everything in it.
     *
     * Tombstones rather than DELETEs, so the removal actually propagates —
     * see the note on `tombstoneConversation`. Messages are tombstoned too;
     * otherwise another device keeps their bodies and only learns that the
     * parent is gone.
     *
     * Every message shares ONE lamport here. They are a single user action,
     * and spending a thousand ticks on a thousand-message chat would inflate
     * the device's clock far ahead of its peers for no benefit.
     */
    suspend fun deleteConversation(convId: String) = withContext(Dispatchers.Default) {
        db.transaction {
            val lamport = nextLamport()
            q.tombstoneMessagesFor(lamport, convId)
            q.tombstoneConversation(lamport, convId)
            fts.unindexConversation(convId)
        }
    }

    /**
     * Fork a conversation, keeping everything up to and including [throughMsgId].
     *
     * Returns the new conversation's id, or null if the message does not
     * belong to that conversation.
     *
     * Every copied row gets a FRESH id and a FRESH lamport. Reusing either is
     * the trap the migration doc calls out (risk §9.2): a copied message that
     * keeps its id is not a copy at all under last-writer-wins — it is a
     * competing version of the original, and whichever lamport is higher wins
     * on every other device. The fork would silently overwrite its own source.
     *
     * `created_at` IS preserved, so the fork reads in its original order and
     * timestamps still mean when the thing was actually said.
     */
    suspend fun branchConversation(convId: String, throughMsgId: String): String? =
        withContext(Dispatchers.Default) {
            val source = q.conversationById(convId).executeAsOneOrNull()
                ?: return@withContext null
            val history = q.messagesFor(convId).executeAsList()
            val cut = history.indexOfFirst { it.id == throughMsgId }
            if (cut < 0) return@withContext null

            val newConvId = randomId()
            val now = nowMillis()
            db.transaction {
                q.upsertConversation(
                    id = newConvId,
                    project_id = source.project_id,
                    title = branchTitle(source.title),
                    model = source.model,
                    system_prompt = source.system_prompt,
                    // A fork starts unpinned and unshared. Inheriting the
                    // share token would be a data leak: the original's public
                    // link would resolve to a conversation the reader was
                    // never given.
                    pinned = 0,
                    share_token = null,
                    created_at = now,
                    updated_at = now,
                    deleted = 0,
                    lamport = nextLamport(),
                    device_id = deviceId,
                    dirty = 1,
                )
                for (msg in history.take(cut + 1)) {
                    val newMsgId = randomId()
                    q.upsertMessage(
                        id = newMsgId,
                        conv_id = newConvId,
                        role = msg.role,
                        content = msg.content,
                        encrypted = msg.encrypted,
                        reasoning = msg.reasoning,
                        tool_calls = msg.tool_calls,
                        attachments = msg.attachments,
                        created_at = msg.created_at,
                        deleted = 0,
                        lamport = nextLamport(),
                        device_id = deviceId,
                        dirty = 1,
                    )
                    if (msg.content.isNotEmpty()) {
                        fts.index(newMsgId, newConvId, msg.content)
                    }
                }
            }
            newConvId
        }

    private fun branchTitle(original: String): String =
        if (original.isBlank()) "Branch" else "$original (branch)"

    // ─── search ─────────────────────────────────────────────────────────────

    /**
     * Search message bodies and conversation titles.
     *
     * Runs entirely against local SQLite, so it is instant and works offline —
     * the reason the migration doc says not to port stable's `/chats/search`
     * endpoint at all.
     */
    suspend fun search(query: String, limit: Long = 50): SearchResults =
        withContext(Dispatchers.Default) {
            SearchResults(
                messages = searchMessages(query, limit),
                conversations = q.searchConversationTitles(query, limit).executeAsList(),
            )
        }

    /**
     * Message-body search, by whichever means this device supports.
     *
     * Every path is wrapped: this runs on each keystroke, and a malformed FTS
     * expression THROWS rather than returning nothing. `ftsMatchExpression`
     * should make that impossible, but the cost of being wrong is a crash
     * while the user is typing and the cost of the guard is nothing.
     */
    private fun searchMessages(query: String, limit: Long): List<MessageHit> {
        if (query.isBlank()) return emptyList()

        if (fts.isAvailable) {
            val match = ftsMatchExpression(query) ?: return emptyList()
            return runCatching { fts.search(match, limit) }.getOrDefault(emptyList())
        }

        // No FTS module on this device — scan instead. The excerpt has to be
        // built here because there is no snippet() to do it.
        return runCatching {
            q.searchMessagesLike(query, limit).executeAsList().map { row ->
                MessageHit(
                    msgId = row.msgId,
                    convId = row.convId,
                    title = row.title,
                    role = row.role,
                    createdAt = row.createdAt,
                    excerpt = excerptAround(row.content, query),
                )
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Attach a file to an existing message — used when the edge reports one it
     * generated mid-run, which is after the assistant row already exists.
     */
    suspend fun addAttachment(msgId: String, key: String) = withContext(Dispatchers.Default) {
        db.transaction {
            val msg = q.messageById(msgId).executeAsOneOrNull() ?: return@transaction
            val existing = decodeKeys(msg.attachments)
            if (key in existing) return@transaction
            q.updateMessageAttachments(encodeKeys(existing + key), msgId)
        }
    }

    /**
     * A readable fragment around the first match, mimicking `snippet()`.
     *
     * Case-insensitive, because the FTS path matches that way and results
     * should not change character depending on which engine answered.
     */
    private fun excerptAround(content: String, query: String, window: Int = 60): String {
        val at = content.indexOf(query, ignoreCase = true)
        if (at < 0) return content.take(window * 2)

        val start = (at - window).coerceAtLeast(0)
        val end = (at + query.length + window).coerceAtMost(content.length)
        return buildString {
            if (start > 0) append("...")
            append(content, start, at)
            append('[').append(content, at, at + query.length).append(']')
            append(content, at + query.length, end)
            if (end < content.length) append("...")
        }
    }

    /**
     * Bump this device's logical clock and return the new value.
     *
     * MUST be called inside a `db.transaction` — read-modify-write on the
     * lamport is only atomic within one. Every mutation goes through here so
     * that no write can reach the sync engine with a stale clock and lose to
     * the row it was meant to replace.
     */
    private fun nextLamport(): Long {
        q.bumpLamport()
        return q.syncState().executeAsOne().lamport
    }
}

/** Attachments are a JSON array of R2 keys — the shape the UI also reads. */
private fun encodeKeys(keys: List<String>): String =
    JsonArray(keys.map { JsonPrimitive(it) }).toString()

private fun decodeKeys(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        Json.parseToJsonElement(raw).jsonArray.mapNotNull { (it as? JsonPrimitive)?.content }
    }.getOrDefault(emptyList())
}

/** Message hits and title hits are ranked differently, so they stay separate. */
data class SearchResults(
    val messages: List<MessageHit>,
    val conversations: List<Conversation>,
) {
    val isEmpty: Boolean get() = messages.isEmpty() && conversations.isEmpty()
}

/** Platform clock and id source — trivial, but they differ per target. */
expect fun nowMillis(): Long

expect fun randomId(): String
