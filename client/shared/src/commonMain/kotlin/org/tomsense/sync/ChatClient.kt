package org.tomsense.sync

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpStatement
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Streaming chat against the edge control plane.
 *
 * A generation is a *run* that lives on the edge, not a response to this
 * request. [stream] starts one and [attach] rejoins it, which is what lets the
 * phone close the socket, sleep or change networks without losing the reply.
 * Both consume the identical event contract, so the caller does not care which
 * one it is reading from.
 *
 *   run        first frame — the id needed to stop, rejoin, or answer tools
 *   text       incremental content
 *   reasoning  incremental thinking trace
 *   heartbeat  liveness; no payload
 *   done       end of one model ROUND; carries tool calls, if any
 *   end        the run is finished
 *
 * `done` is not the end. A generation that calls tools emits one per round,
 * and only `end` is terminal — treating `done` as final is the mistake that
 * makes a tool-using reply look truncated.
 */
class ChatClient(
    private val http: HttpClient,
    private val baseUrl: String,
    private val deviceToken: () -> String?,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Start a new generation. */
    fun stream(req: ChatRequest): Flow<ChatEvent> = sse {
        http.preparePost("$baseUrl/chat") {
            auth()
            contentType(ContentType.Application.Json)
            setBody(req)
        }
    }

    /**
     * Rejoin a run already in progress.
     *
     * The edge replays what was produced while we were away before switching
     * to live events, so a reconnecting client never sees a reply that starts
     * mid-sentence.
     */
    fun attach(runId: String): Flow<ChatEvent> = sse {
        http.prepareGet("$baseUrl/run/$runId/attach") { auth() }
    }

    /** Stop a generation. Safe to call on a run that has already finished. */
    suspend fun cancel(runId: String) {
        http.post("$baseUrl/run/$runId/cancel") { auth() }
    }

    /**
     * Hand back what the tools produced, which resumes the parked run.
     *
     * A run that asked for tools waits indefinitely for this, so it must be
     * sent even when every tool failed — an error result is an answer, silence
     * is a wedged run.
     */
    suspend fun sendToolResults(runId: String, results: List<ToolResult>) {
        http.post("$baseUrl/run/$runId/tool_result") {
            auth()
            contentType(ContentType.Application.Json)
            setBody(ToolResultRequest(results))
        }
    }

    /** Runs still going on the edge — how a cold start finds work to rejoin. */
    suspend fun activeRuns(): List<RunInfo> =
        runCatching { http.get("$baseUrl/runs") { auth() }.body<RunList>().runs }
            .getOrDefault(emptyList())

    private fun io.ktor.client.request.HttpRequestBuilder.auth() {
        deviceToken()?.let { header("Authorization", "Bearer $it") }
    }

    /**
     * Shared SSE reader — the only place the wire format is parsed.
     *
     * The request is built inside the flow, not passed in: `preparePost` is
     * itself a suspend function, so building it eagerly would fire the request
     * at the moment the Flow is constructed rather than when it is collected.
     */
    private fun sse(open: suspend () -> HttpStatement): Flow<ChatEvent> = flow {
        open().execute { response ->
            val channel = response.bodyAsChannel()
            while (true) {
                val line = channel.readUTF8Line() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload == "[DONE]") break

                // A malformed frame must not kill the stream — the rest of
                // the answer is still coming, and dropping it would look
                // like the model stopped mid-sentence.
                val event = runCatching { json.decodeFromString<ChatEvent>(payload) }.getOrNull()
                if (event != null) emit(event)
            }
        }
    }
}

@Serializable
data class ChatRequest(
    val conversationId: String,
    val messages: List<WireMessage>,
    val model: String? = null,
    /** Omitted entirely when empty, so a model is never told about no tools. */
    val tools: List<JsonElement>? = null,
    /** Route to the reasoning model and raise its effort. */
    val think: Boolean? = null,
    /** Skip the edge's persona/profile/memory context — utility calls only. */
    val bare: Boolean? = null,
)

@Serializable
data class WireMessage(
    val role: String,
    val content: String,
    /**
     * R2 keys, not bytes. The edge turns them into image parts at request
     * time — inlining base64 here would put megabytes into every sync payload
     * and into every replay of the conversation.
     */
    val attachments: List<String>? = null,
)

@Serializable
data class ChatEvent(
    val type: String,
    val text: String? = null,
    val content: String? = null,
    @SerialName("runId") val runId: String? = null,
    val status: String? = null,
    @SerialName("toolCalls") val toolCalls: List<WireToolCall>? = null,
    val stalled: Boolean? = null,
    val error: String? = null,
    /** On a "attachment" event: the R2 key of a file the run produced. */
    val key: String? = null,
    val mime: String? = null,
    /** On "done": what the round consumed, and which model served it. */
    val usage: WireUsage? = null,
    val model: String? = null,
    val costUsd: Double? = null,
)

@Serializable
data class WireUsage(
    @SerialName("in") val tokensIn: Int = 0,
    val out: Int = 0,
    @SerialName("cache_read") val cacheRead: Int = 0,
    @SerialName("cache_write") val cacheWrite: Int = 0,
)

@Serializable
data class WireToolCall(
    val id: String,
    val name: String,
    val arguments: JsonElement? = null,
)

@Serializable
data class ToolResult(val id: String, val name: String? = null, val content: String)

@Serializable
private data class ToolResultRequest(val results: List<ToolResult>)

@Serializable
data class RunInfo(
    val id: String,
    @SerialName("conv_id") val convId: String,
    val status: String,
    val model: String = "",
)

@Serializable
private data class RunList(val runs: List<RunInfo>)
