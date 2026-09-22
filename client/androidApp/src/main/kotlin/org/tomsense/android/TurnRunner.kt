package org.tomsense.android

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.tomsense.sync.ChatEvent
import org.tomsense.sync.ChatRequest
import org.tomsense.sync.ToolResult
import org.tomsense.sync.WireMessage
import org.tomsense.sync.WireToolCall
import org.tomsense.tools.schemas

/**
 * One turn, from "send" to a finished reply.
 *
 * Extracted because there are now two places a turn can start — the chat
 * screen and the assistant overlay — and the wire protocol is subtle enough
 * that a second copy would drift. `done` is a ROUND boundary rather than the
 * end of a generation, tool results must go back even when every one of them
 * failed, and a reattach replays what was already sent. Getting any of those
 * wrong in one copy and not the other is the kind of bug that only shows up
 * from one entry point.
 */
class TurnRunner(
    private val app: TomsenseApp,
    private val memory: RunMemory = RunMemory.None,
    private val onGenerating: (Boolean) -> Unit = {},
    private val onNotices: (List<String>) -> Unit = {},
) {

    /**
     * Where an in-flight run id is remembered so it can be rejoined.
     *
     * Only the full app implements this: the overlay is dismissed the moment
     * the user looks away, and a run it started is picked up by the chat
     * screen, which is where a reconnect belongs.
     */
    interface RunMemory {
        fun remember(runId: String, msgId: String)
        fun clear()

        object None : RunMemory {
            override fun remember(runId: String, msgId: String) = Unit
            override fun clear() = Unit
        }
    }

    /**
     * Persist the user's turn, then generate.
     *
     * Two separate things on purpose: the message is committed locally before
     * any network call, so pressing send with no connection still records it.
     *
     * Returns the assistant row's id.
     */
    suspend fun send(
        convId: String,
        text: String,
        think: Boolean = false,
        attachments: List<String> = emptyList(),
        extraContext: List<WireMessage> = emptyList(),
        /**
         * Called with the reply so far on every token.
         *
         * Exists for speech: the sentence chunker needs the growing text to
         * start speaking before the answer finishes. Null for typed turns, so
         * nothing pays for it.
         */
        onText: ((String) -> Unit)? = null,
    ): String {
        app.repo.appendMessage(convId, "user", text, attachments = attachments)
        val assistantId = app.repo.appendMessage(convId, "assistant", "")
        val history = historyFor(convId, extraContext = extraContext)

        consume(assistantId, onText = onText) {
            app.chat.stream(
                ChatRequest(convId, history, tools = app.tools.schemas(), think = think),
            )
        }
        return assistantId
    }

    /** The turns to send, with the device's own context in front. */
    fun historyFor(
        convId: String,
        exclude: String? = null,
        extraContext: List<WireMessage> = emptyList(),
    ): List<WireMessage> =
        listOf(WireMessage("system", org.tomsense.data.deviceSystemPrompt())) +
            extraContext +
            app.db.schemaQueries.messagesFor(convId).executeAsList()
                .filter { it.id != exclude && (it.content.isNotBlank() || it.attachments != null) }
                .map { WireMessage(it.role, it.content, attachmentKeys(it.attachments)) }

    /**
     * Drive one generation into [assistantId], newly started or rejoined.
     *
     * Content is rebuilt from scratch on every event rather than appended to
     * what is on screen, because a reattach replays the answer so far —
     * appending would print the first half twice.
     */
    suspend fun consume(
        assistantId: String,
        knownRun: String? = null,
        onText: ((String) -> Unit)? = null,
        source: () -> Flow<ChatEvent>,
    ) {
        onGenerating(true)
        // Cleared per turn: a notice explains THIS reply, and leaving the last
        // one up would attribute it to the wrong answer.
        var notices = emptyList<String>()
        onNotices(notices)

        var runId = knownRun
        val buffer = StringBuilder()
        val thinking = StringBuilder()

        runCatching {
            source().collect { ev ->
                when (ev.type) {
                    "run" -> ev.runId?.let {
                        runId = it
                        memory.remember(it, assistantId)
                    }
                    "text" -> {
                        buffer.append(ev.text.orEmpty())
                        val soFar = buffer.toString()
                        app.repo.updateStreamingContent(assistantId, soFar)
                        onText?.invoke(soFar)
                    }
                    "reasoning" -> {
                        thinking.append(ev.text.orEmpty())
                        app.repo.updateStreamingReasoning(assistantId, thinking.toString())
                    }
                    // A routing override the edge wants the user to see.
                    "notice" -> ev.text?.let {
                        notices = notices + it
                        onNotices(notices)
                    }
                    // A file the run produced — currently a generated image.
                    "attachment" -> ev.key?.let { app.repo.addAttachment(assistantId, it) }

                    // Carries no payload; exists so a long silent reasoning
                    // stretch isn't mistaken for a dead connection.
                    "heartbeat" -> Unit

                    // End of a ROUND, not of the run. Tool calls here mean the
                    // edge is parked waiting on this device, so results have to
                    // go back even when every one of them failed — silence
                    // leaves the run parked until it is swept away.
                    "done" -> {
                        ev.usage?.let { u ->
                            app.repo.recordUsage(assistantId, usageJson(u, ev.costUsd), ev.model)
                        }
                        val calls = ev.toolCalls.orEmpty()
                        val id = runId
                        if (calls.isNotEmpty() && id != null) {
                            app.chat.sendToolResults(id, calls.map { runTool(it) })
                        }
                    }
                    "end" -> app.repo.finishStreaming(assistantId)
                }
            }
        }.onFailure {
            app.repo.updateStreamingContent(
                assistantId,
                buffer.toString().ifEmpty { "[offline — will retry]" },
            )
            app.repo.finishStreaming(assistantId)
        }

        onGenerating(false)
        memory.clear()
    }

    /**
     * Run one tool call on this device.
     *
     * Arguments are normalised to an object because models occasionally send
     * `"{}"` as a string, or nothing at all, and a tool that takes no
     * arguments is the most common case.
     */
    suspend fun runTool(call: WireToolCall): ToolResult {
        val args = when (val raw = call.arguments) {
            is JsonObject -> raw
            is JsonPrimitive ->
                runCatching { Json.parseToJsonElement(raw.content) as? JsonObject }.getOrNull()
                    ?: JsonObject(emptyMap())
            else -> JsonObject(emptyMap())
        }
        return ToolResult(id = call.id, name = call.name, content = app.tools.call(call.name, args))
    }

    private fun usageJson(u: org.tomsense.sync.WireUsage, costUsd: Double?): String =
        buildJsonObject {
            put("in", JsonPrimitive(u.tokensIn))
            put("out", JsonPrimitive(u.out))
            put("cache_read", JsonPrimitive(u.cacheRead))
            costUsd?.let { put("usd", JsonPrimitive(it)) }
        }.toString()

    /** Attachments are stored as a JSON array of R2 keys. */
    private fun attachmentKeys(raw: String?): List<String>? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            (Json.parseToJsonElement(raw) as kotlinx.serialization.json.JsonArray)
                .mapNotNull { (it as? JsonPrimitive)?.content }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }
}
