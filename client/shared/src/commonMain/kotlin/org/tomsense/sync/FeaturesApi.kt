package org.tomsense.sync

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The edge's user-level features: memory, personas, projects, starters,
 * follow-ups, API keys, schedules, notifications, MCP servers, artifacts and
 * documents (edge/src/features.ts).
 *
 * Every call checks the status and throws with the SERVER's message, so a
 * settings screen can show "Couldn't connect: HTTP 401" rather than a JSON
 * parse error about an error body.
 */
class FeaturesApi(
    private val http: HttpClient,
    private val baseUrl: String,
    private val deviceToken: () -> String?,
) {
    private fun HttpRequestBuilder.auth() {
        deviceToken()?.let { header("Authorization", "Bearer $it") }
    }

    private fun HttpRequestBuilder.jsonBody(body: Any) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun HttpResponse.ok(): HttpResponse {
        if (status.isSuccess()) return this
        val text = runCatching { bodyAsText() }.getOrDefault("")
        val msg = runCatching { Json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
        throw IllegalStateException(msg ?: "HTTP ${status.value}")
    }

    // ─── memory ─────────────────────────────────────────────────────────────
    suspend fun profile(): String = http.get("$baseUrl/me/profile") { auth() }.ok().body<TextBody>().text
    suspend fun setProfile(text: String) { http.put("$baseUrl/me/profile") { auth(); jsonBody(TextBody(text)) }.ok() }
    suspend fun memories(): MemoriesResponse = http.get("$baseUrl/memories") { auth() }.ok().body()
    suspend fun addMemory(text: String, pinned: Boolean = false) {
        http.post("$baseUrl/memories") { auth(); jsonBody(NewMemory(text, pinned)) }.ok()
    }
    suspend fun pinMemory(id: String, pinned: Boolean) {
        http.patch("$baseUrl/memories/$id") { auth(); jsonBody(PinPatch(pinned)) }.ok()
    }
    suspend fun deleteMemory(id: String) { http.delete("$baseUrl/memories/$id") { auth() }.ok() }
    suspend fun setAutoMemory(on: Boolean) {
        http.put("$baseUrl/me/prefs") { auth(); jsonBody(AutoMemoryPatch(on)) }.ok()
    }

    // ─── personas ───────────────────────────────────────────────────────────
    suspend fun personas(): PersonasResponse = http.get("$baseUrl/personas") { auth() }.ok().body()
    suspend fun addPersona(name: String, prompt: String) {
        http.post("$baseUrl/personas") { auth(); jsonBody(PersonaBody(name, prompt)) }.ok()
    }
    suspend fun updatePersona(id: String, name: String, prompt: String) {
        http.put("$baseUrl/personas/$id") { auth(); jsonBody(PersonaBody(name, prompt)) }.ok()
    }
    suspend fun deletePersona(id: String) { http.delete("$baseUrl/personas/$id") { auth() }.ok() }
    suspend fun setActivePersona(id: String) {
        http.put("$baseUrl/personas/active") { auth(); jsonBody(IdBody(id)) }.ok()
    }

    // ─── projects ───────────────────────────────────────────────────────────
    suspend fun projects(): List<Project> = http.get("$baseUrl/projects") { auth() }.ok().body<ProjectsResponse>().projects
    suspend fun addProject(name: String, instructions: String): String =
        http.post("$baseUrl/projects") { auth(); jsonBody(ProjectBody(name, instructions)) }.ok().body<IdBody>().id
    suspend fun updateProject(id: String, name: String, instructions: String) {
        http.put("$baseUrl/projects/$id") { auth(); jsonBody(ProjectBody(name, instructions)) }.ok()
    }
    suspend fun deleteProject(id: String) { http.delete("$baseUrl/projects/$id") { auth() }.ok() }

    // ─── starters & follow-ups ──────────────────────────────────────────────
    suspend fun starters(): Starters = http.get("$baseUrl/starters") { auth() }.ok().body()
    suspend fun setStarters(list: List<String>): Starters =
        http.put("$baseUrl/starters") { auth(); jsonBody(StartersBody(list)) }.ok().body()
    suspend fun followups(question: String, answer: String): List<String> =
        http.post("$baseUrl/followups") { auth(); jsonBody(FollowupsRequest(question, answer)) }.ok()
            .body<FollowupsResponse>().suggestions

    // ─── API keys ───────────────────────────────────────────────────────────
    suspend fun secrets(): List<SecretView> = http.get("$baseUrl/secrets") { auth() }.ok().body<SecretsResponse>().secrets
    suspend fun setSecret(name: String, value: String) {
        http.put("$baseUrl/secrets/$name") { auth(); jsonBody(ValueBody(value)) }.ok()
    }

    // ─── schedules & notifications ──────────────────────────────────────────
    suspend fun schedules(): List<Schedule> = http.get("$baseUrl/schedules") { auth() }.ok().body<SchedulesResponse>().schedules
    suspend fun addSchedule(s: NewSchedule) { http.post("$baseUrl/schedules") { auth(); jsonBody(s) }.ok() }
    suspend fun setScheduleEnabled(id: String, on: Boolean) {
        http.put("$baseUrl/schedules/$id") { auth(); jsonBody(EnabledBody(on)) }.ok()
    }
    suspend fun runScheduleNow(id: String) { http.post("$baseUrl/schedules/$id/run") { auth() }.ok() }
    suspend fun deleteSchedule(id: String) { http.delete("$baseUrl/schedules/$id") { auth() }.ok() }
    suspend fun notifications(): List<AppNotification> =
        http.get("$baseUrl/notifications") { auth() }.ok().body<NotificationsResponse>().notifications

    // ─── MCP ────────────────────────────────────────────────────────────────
    suspend fun mcpServers(): List<McpServer> = http.get("$baseUrl/mcp/servers") { auth() }.ok().body<McpServersResponse>().servers
    suspend fun addMcpServer(name: String, url: String, token: String): List<String> =
        http.post("$baseUrl/mcp/servers") { auth(); jsonBody(NewMcpServer(name, url, token)) }.ok().body<McpAdded>().tools
    suspend fun setMcpEnabled(id: String, on: Boolean) {
        http.put("$baseUrl/mcp/servers/$id") { auth(); jsonBody(EnabledBody(on)) }.ok()
    }
    suspend fun deleteMcpServer(id: String) { http.delete("$baseUrl/mcp/servers/$id") { auth() }.ok() }
    suspend fun createMcpToken(): McpToken = http.post("$baseUrl/mcp/token") { auth() }.ok().body()

    // ─── artifacts & documents ──────────────────────────────────────────────
    suspend fun artifact(id: String): Artifact = http.get("$baseUrl/artifacts/$id") { auth() }.ok().body()
    suspend fun documents(): DocumentsResponse = http.get("$baseUrl/documents") { auth() }.ok().body()
    suspend fun uploadDocument(bytes: ByteArray, mime: String, name: String) {
        http.post("$baseUrl/documents") {
            auth()
            url { parameters.append("name", name) }
            contentType(ContentType.parse(mime.ifBlank { "application/octet-stream" }))
            setBody(bytes)
        }.ok()
    }
    suspend fun deleteDocument(id: String) { http.delete("$baseUrl/documents/$id") { auth() }.ok() }
}

@Serializable data class TextBody(val text: String = "")
@Serializable data class ValueBody(val value: String)
@Serializable data class IdBody(val id: String = "")
@Serializable data class EnabledBody(val enabled: Boolean)
@Serializable data class PinPatch(val pinned: Boolean)
@Serializable data class AutoMemoryPatch(@SerialName("auto_memory") val autoMemory: Boolean)
@Serializable data class NewMemory(val text: String, val pinned: Boolean = false)

@Serializable
data class MemoryItem(
    val id: String,
    val text: String,
    val pinned: Int = 0,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("use_count") val useCount: Int = 0,
)

@Serializable data class MemoriesResponse(val memories: List<MemoryItem> = emptyList(), val auto: Boolean = true)

@Serializable data class PersonaBody(val name: String, val prompt: String)
@Serializable data class Persona(val id: String, val name: String, val prompt: String)
@Serializable data class PersonasResponse(val personas: List<Persona> = emptyList(), val active: String = "")

@Serializable data class ProjectBody(val name: String, val instructions: String)
@Serializable data class Project(val id: String, val name: String, val instructions: String = "")
@Serializable data class ProjectsResponse(val projects: List<Project> = emptyList())

@Serializable data class Starters(val starters: List<String> = emptyList(), val custom: Boolean = false)
@Serializable data class StartersBody(val starters: List<String>)
@Serializable data class FollowupsRequest(val question: String, val answer: String)
@Serializable data class FollowupsResponse(val suggestions: List<String> = emptyList())

@Serializable data class SecretView(val name: String, val set: Boolean, val description: String = "")
@Serializable data class SecretsResponse(val secrets: List<SecretView> = emptyList())

@Serializable
data class Schedule(
    val id: String,
    val title: String,
    val prompt: String,
    val kind: String,
    @SerialName("time_local") val timeLocal: String = "08:00",
    val days: String = "",
    val tz: String = "UTC",
    val enabled: Int = 1,
    @SerialName("next_run_at") val nextRunAt: Long? = null,
    @SerialName("last_run_at") val lastRunAt: Long? = null,
    @SerialName("last_error") val lastError: String? = null,
    @SerialName("conv_id") val convId: String? = null,
)

@Serializable
data class NewSchedule(
    val title: String,
    val prompt: String,
    val kind: String,
    @SerialName("time_local") val timeLocal: String,
    val days: String,
    val tz: String,
    @SerialName("run_at") val runAt: Long? = null,
)

@Serializable data class SchedulesResponse(val schedules: List<Schedule> = emptyList())

@Serializable
data class AppNotification(
    val id: String,
    val title: String,
    val body: String = "",
    @SerialName("conv_id") val convId: String? = null,
)

@Serializable data class NotificationsResponse(val notifications: List<AppNotification> = emptyList())

@Serializable
data class McpServer(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Int = 1,
    @SerialName("has_auth") val hasAuth: Int = 0,
)

@Serializable data class McpServersResponse(val servers: List<McpServer> = emptyList())
@Serializable data class NewMcpServer(val name: String, val url: String, val token: String)
@Serializable data class McpAdded(val id: String = "", val tools: List<String> = emptyList())
@Serializable data class McpToken(val url: String, val token: String)

@Serializable
data class Artifact(
    val id: String,
    val title: String,
    val kind: String = "markdown",
    val language: String? = null,
    val content: String = "",
    val version: Int = 1,
)

@Serializable
data class DocumentItem(
    val id: String,
    val name: String,
    val size: Long = 0,
    val status: String = "",
    val chunks: Int = 0,
    val error: String? = null,
)

@Serializable data class DocumentsResponse(val documents: List<DocumentItem> = emptyList(), val vector: Boolean = false)
