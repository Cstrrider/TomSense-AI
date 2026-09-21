package org.tomsense.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Tools the device itself can run.
 *
 * On the old stack every one of these round-tripped model → backend → SSE →
 * WebView → JS plugin → Android API and all the way back. Here the client
 * holds the implementation, so a tool call is a direct Kotlin call: lower
 * latency, no server involvement, and the local ones keep working with no
 * network at all.
 *
 * The edge never learns what these do. It forwards the schemas the client
 * advertises and parks the run until the client answers, which means adding a
 * tool is a client-only change — no deploy.
 *
 * WHAT THE MODEL SEES ([ToolSpec], in [ToolCatalog]) IS SEPARATE FROM WHAT
 * RUNS IT. The contract is platform-neutral and has no Android types, so it
 * can be dumped and tested against a real model on its own — see
 * `./gradlew :shared:dumpToolSchemas`. Every prompt-visible word is decided in
 * one file rather than scattered across implementations.
 */
class ToolSpec(
    val name: String,
    val description: String,
    /** JSON Schema for the arguments object. */
    val parameters: JsonObject,
)

interface DeviceTool {
    val spec: ToolSpec

    /**
     * Run it. The return value is JSON handed straight back to the model.
     *
     * Implementations should not throw: a failure is a result the model can
     * reason about and recover from, while an exception is a wedged run.
     * The toolset enforces this, but returning a useful `error` string beats
     * being wrapped in a generic one.
     */
    suspend fun execute(args: JsonObject): String
}

val DeviceTool.name: String get() = spec.name

/** The set of tools a platform offers. Desktop has none yet; see [NoTools]. */
interface Toolset {
    val tools: List<DeviceTool>

    /** Dispatch by name. Never throws — see [DeviceTool.execute]. */
    suspend fun call(name: String, args: JsonObject): String
}

/** Platforms with no device integration. Advertises nothing, so nothing calls it. */
object NoTools : Toolset {
    override val tools: List<DeviceTool> = emptyList()
    override suspend fun call(name: String, args: JsonObject): String =
        errorJson("no device tools on this platform")
}

/**
 * Schemas in the OpenAI `{type:"function", function:{…}}` shape.
 *
 * Verified to be accepted by both Cloudflare Workers AI and OpenAI-compatible
 * providers, so one advertisement serves every provider the user might add.
 */
fun toolSchemas(specs: List<ToolSpec>): List<JsonElement> = specs.map { spec ->
    buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", spec.name)
            put("description", spec.description)
            put("parameters", spec.parameters)
        }
    }
}

fun Toolset.schemas(): List<JsonElement> = toolSchemas(tools.map { it.spec })

// ─── small helpers, so 19 tool definitions stay readable ─────────────────────

/** Build an object schema. Anything in [required] must also be in [props]. */
fun objectSchema(
    required: List<String> = emptyList(),
    props: Map<String, JsonObject> = emptyMap(),
): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        for ((key, value) in props) put(key, value)
    }
    put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
}

fun stringProp(description: String, enum: List<String>? = null): JsonObject =
    buildJsonObject {
        put("type", "string")
        put("description", description)
        if (enum != null) put("enum", buildJsonArray { enum.forEach { add(JsonPrimitive(it)) } })
    }

fun numberProp(description: String): JsonObject = buildJsonObject {
    put("type", "integer")
    put("description", description)
}

fun errorJson(message: String): String =
    buildJsonObject { put("error", message) }.toString()

fun okJson(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): String =
    buildJsonObject { put("ok", true); build() }.toString()
