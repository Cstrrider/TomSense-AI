package org.tomsense.sync

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * BYO-key provider management.
 *
 * Note what is absent: any way to read a stored API key back. The server
 * returns `hasKey` only, so the UI can show "key set" but can never display
 * or re-send the secret. Editing a provider without touching the key field
 * leaves it untouched server-side.
 */
class ProvidersApi(
    private val http: HttpClient,
    private val baseUrl: String,
    private val deviceToken: () -> String?,
) {
    suspend fun list(): List<ProviderView> =
        http.get("$baseUrl/providers") { auth() }.body()

    suspend fun models(): ModelsResponse =
        http.get("$baseUrl/models") { auth() }.body()

    suspend fun create(req: CreateProvider): CreatedProvider =
        http.post("$baseUrl/providers") {
            auth(); contentType(ContentType.Application.Json); setBody(req)
        }.body()

    suspend fun update(id: String, req: UpdateProvider) {
        http.patch("$baseUrl/providers/$id") {
            auth(); contentType(ContentType.Application.Json); setBody(req)
        }
    }

    suspend fun delete(id: String) {
        http.delete("$baseUrl/providers/$id") { auth() }
    }

    /**
     * Ask a provider what it serves. Best-effort: the server returns an empty
     * list rather than an error when a provider has no /models endpoint, so
     * the form falls back to manual entry instead of blocking.
     */
    suspend fun discover(req: DiscoverRequest): DiscoverResponse =
        http.post("$baseUrl/providers/discover") {
            auth(); contentType(ContentType.Application.Json); setBody(req)
        }.body()

    /** Routing preferences: model slots, auto-route, analytics key state. */
    suspend fun prefs(): UserPrefs =
        http.get("$baseUrl/me/prefs") { auth() }.body()

    /**
     * Patch preferences. Omitted fields are left alone, and an omitted slot is
     * NOT cleared — only an explicit empty string clears one.
     */
    suspend fun setPrefs(req: PrefsPatch): UserPrefs =
        http.put("$baseUrl/me/prefs") {
            auth(); contentType(ContentType.Application.Json); setBody(req)
        }.body()

    suspend fun setDefaultModel(model: String) {
        http.put("$baseUrl/me/default-model") {
            auth(); contentType(ContentType.Application.Json); setBody(DefaultModel(model))
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.auth() {
        deviceToken()?.let { header("Authorization", "Bearer $it") }
    }
}

@Serializable
data class ProviderView(
    val id: String,
    val name: String,
    val kind: String,
    val baseUrl: String,
    val hasKey: Boolean,
    val keyless: Boolean,
    val models: List<WireModel> = emptyList(),
    val enabled: Boolean,
    val builtin: Boolean,
)

@Serializable
data class WireModel(
    val id: String,
    val vision: Boolean = false,
    val reasoning: Boolean = false,
    val context: Int? = null,
)

@Serializable
data class ModelOption(
    val value: String,
    val label: String,
    val provider: String,
    val vision: Boolean = false,
    val reasoning: Boolean = false,
    val context: Int? = null,
)

@Serializable
data class Preset(val kind: String, val name: String, val baseUrl: String)

@Serializable
data class ModelsResponse(
    val models: List<ModelOption> = emptyList(),
    val defaultModel: String = "",
    val presets: List<Preset> = emptyList(),
)

@Serializable
data class CreateProvider(
    val name: String,
    val kind: String,
    val baseUrl: String,
    val apiKey: String,
    val models: List<WireModel> = emptyList(),
)

@Serializable
data class CreatedProvider(val id: String)

@Serializable
data class DiscoverRequest(
    /** Discover against a saved provider using its stored key… */
    val providerId: String? = null,
    /** …or against an in-progress form before it has been saved. */
    val baseUrl: String? = null,
    /** Supplying a key for a saved provider discovers with the NEW key,
     *  so a rotation can be validated before it is committed. */
    val apiKey: String? = null,
)

@Serializable
data class DiscoverResponse(val models: List<String> = emptyList())

@Serializable
data class UpdateProvider(
    val name: String? = null,
    val baseUrl: String? = null,
    /** null means "leave the stored key alone" — NOT "clear it". */
    val apiKey: String? = null,
    val models: List<WireModel>? = null,
    val enabled: Boolean? = null,
)

@Serializable
private data class DefaultModel(val model: String)

/**
 * Model slots. Each holds a full "provider::model" string, so a slot can point
 * at any configured provider rather than being limited to Cloudflare.
 */
@Serializable
data class ToolModels(
    /** Owns image turns outright, even over a vision-capable chat model. */
    val vision: String? = null,
    /** Think mode. */
    val research: String? = null,
    /** Utility tier: titles, follow-ups, the auto-route classifier. */
    val title: String? = null,
    @SerialName("chat_fallback") val chatFallback: String? = null,
    @SerialName("vision_fallback") val visionFallback: String? = null,
    @SerialName("title_fallback") val titleFallback: String? = null,
)

@Serializable
data class UserPrefs(
    @SerialName("tool_models") val toolModels: ToolModels = ToolModels(),
    @SerialName("auto_route") val autoRoute: Boolean = true,
    /** Whether an analytics key is SET. The key itself is never returned. */
    val hasAnalyticsKey: Boolean = false,
)

/** Slots are sent as a plain map so one can be cleared with an empty string. */
@Serializable
data class PrefsPatch(
    @SerialName("tool_models") val toolModels: Map<String, String>? = null,
    @SerialName("auto_route") val autoRoute: Boolean? = null,
    val cfAnalyticsKey: String? = null,
    val cfAccountId: String? = null,
)
