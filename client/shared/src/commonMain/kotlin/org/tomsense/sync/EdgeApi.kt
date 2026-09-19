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

    private fun io.ktor.client.request.HttpRequestBuilder.auth() {
        deviceToken()?.let { header("Authorization", "Bearer $it") }
    }
}

@kotlinx.serialization.Serializable
data class ExchangeRequest(val code: String, val verifier: String, val platform: String)

@kotlinx.serialization.Serializable
data class ExchangeResult(val deviceId: String, val token: String)

@kotlinx.serialization.Serializable
data class PushAck(val applied: Int, val cursor: Long)
