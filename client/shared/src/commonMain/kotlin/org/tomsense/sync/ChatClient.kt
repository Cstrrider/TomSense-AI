package org.tomsense.sync

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Streaming chat against the edge control plane.
 *
 * Consumes the same text/reasoning/heartbeat/done contract the Worker emits,
 * which is itself preserved from the FastAPI backend — so this client also
 * works against the old stack during the migration, and the cutover doesn't
 * have to be a big-bang.
 */
class ChatClient(
    private val http: HttpClient,
    private val baseUrl: String,
    private val deviceToken: () -> String?,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun stream(req: ChatRequest): Flow<ChatEvent> = flow {
        http.preparePost("$baseUrl/chat") {
            deviceToken()?.let { header("Authorization", "Bearer $it") }
            contentType(ContentType.Application.Json)
            setBody(req)
        }.execute { response ->
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
)

@Serializable
data class WireMessage(val role: String, val content: String)

@Serializable
data class ChatEvent(
    val type: String,
    val text: String? = null,
    val content: String? = null,
    @SerialName("toolCalls") val toolCalls: List<WireToolCall>? = null,
    val stalled: Boolean? = null,
)

@Serializable
data class WireToolCall(val id: String, val name: String)
