package org.tomsense.sync

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

/**
 * Transport to the edge control plane.
 *
 * Deliberately thin. All the interesting behaviour — batching, conflict
 * resolution, retry — belongs in SyncEngine where it can be tested without a
 * network, not smeared into the HTTP layer.
 */
class EdgeApi(
    private val http: HttpClient,
    private val baseUrl: String,
    private val deviceToken: () -> String?,
) {
    suspend fun push(req: PushRequest): PushAck =
        http.post("$baseUrl/sync/push") {
            auth()
            contentType(ContentType.Application.Json)
            setBody(req)
        }.body()

    suspend fun pull(cursor: Long): PullResponse =
        http.get("$baseUrl/sync/pull") {
            auth()
            parameter("cursor", cursor)
        }.body()

    /**
     * Redeem a one-time login code for a device token.
     *
     * Intentionally sends no Authorization header: the caller has no
     * credential yet, which is the whole point. The PKCE verifier is what
     * authorises this call.
     */
    suspend fun exchange(code: String, verifier: String, platform: String): ExchangeResult =
        http.post("$baseUrl/auth/exchange") {
            contentType(ContentType.Application.Json)
            setBody(ExchangeRequest(code, verifier, platform))
        }.body()

    /**
     * Mint or revoke a public link for a conversation.
     *
     * The token is minted at the edge, never here — see the SYNCABLE note in
     * edge/src/sync.ts for why a client cannot choose its own.
     */
    suspend fun setShared(convId: String, shared: Boolean): ShareResult =
        http.post("$baseUrl/chats/$convId/share") {
            auth()
            contentType(ContentType.Application.Json)
            setBody(ShareRequest(shared))
        }.body()

    /**
     * Name a conversation from its opening exchange.
     *
     * Runs on the edge's utility model rather than here: it keeps one warm
     * prompt prefix per user so this is mostly a cache hit, and the device
     * never has to hold a model for a four-word job. Null when the model had
     * nothing useful to say — a chat keeping its placeholder title is
     * cosmetic and must never be worth failing a turn over, which is why this
     * swallows rather than throws.
     */
    suspend fun title(question: String, answer: String?): String? =
        runCatching {
            http.post("$baseUrl/title") {
                auth()
                contentType(ContentType.Application.Json)
                setBody(TitleRequest(question, answer))
            }.body<TitleResponse>().title
        }.getOrNull()

    /**
     * Upload one file, returning its R2 key.
     *
     * Raw body rather than multipart: there is exactly one file per call, and
     * multipart would add a parser on both sides to carry a filename that fits
     * in a header.
     */
    suspend fun uploadFile(bytes: ByteArray, mime: String, name: String): UploadedFile =
        http.post("$baseUrl/files") {
            auth()
            header("content-type", mime)
            header("x-file-name", name)
            setBody(bytes)
        }.body()

    /**
     * Speech for one sentence.
     *
     * Per sentence rather than per reply, so playback can start while the
     * rest of the answer is still being written.
     */
    suspend fun speak(text: String, voice: String?): ByteArray =
        http.post("$baseUrl/voice/tts") {
            auth()
            contentType(ContentType.Application.Json)
            setBody(SpeakRequest(text, voice))
        }.body()

    /** Fetch an attachment's bytes. Authenticated, so it cannot be a plain URL. */
    suspend fun downloadFile(key: String): ByteArray =
        http.get("$baseUrl/files/" + key.split("/").joinToString("/") { encode(it) }) {
            auth()
        }.body()

    private fun encode(segment: String): String =
        segment.map { c ->
            if (c.isLetterOrDigit() || c in "-_.~") c.toString()
            else c.code.let { "%" + it.toString(16).uppercase().padStart(2, '0') }
        }.joinToString("")

    private fun io.ktor.client.request.HttpRequestBuilder.auth() {
        deviceToken()?.let { header("Authorization", "Bearer $it") }
    }
}

@kotlinx.serialization.Serializable
data class SpeakRequest(val text: String, val voice: String? = null)

@kotlinx.serialization.Serializable
data class UploadedFile(val key: String, val mime: String, val bytes: Long)

@kotlinx.serialization.Serializable
data class ShareRequest(val shared: Boolean)

@kotlinx.serialization.Serializable
data class ShareResult(val shareToken: String? = null, val error: String? = null)

@kotlinx.serialization.Serializable
data class TitleRequest(val question: String, val answer: String? = null)

@kotlinx.serialization.Serializable
data class TitleResponse(val title: String? = null)

@kotlinx.serialization.Serializable
data class ExchangeRequest(val code: String, val verifier: String, val platform: String)

@kotlinx.serialization.Serializable
data class ExchangeResult(val deviceId: String, val token: String)

@kotlinx.serialization.Serializable
data class PushAck(val applied: Int, val cursor: Long)
