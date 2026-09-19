package org.tomsense.sync

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tomsense.db.Conversation
import org.tomsense.db.Message

/**
 * Row (de)serialisation for the sync wire format.
 *
 * Accessors are LENIENT on read and strict on write. A row arriving with a
 * missing optional column should not crash sync for every other row in the
 * batch — one bad row would otherwise wedge the cursor permanently and stop
 * all history from ever arriving again.
 */

internal fun JsonObject.string(key: String): String =
    (this[key] as? JsonPrimitive)?.contentOrNullSafe() ?: ""

internal fun JsonObject.stringOrNull(key: String): String? {
    val v = this[key] ?: return null
    if (v is JsonNull) return null
    return (v as? JsonPrimitive)?.contentOrNullSafe()
}

internal fun JsonObject.long(key: String): Long =
    (this[key] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L

private fun JsonPrimitive.contentOrNullSafe(): String? =
    if (this is JsonNull) null else content

internal fun Conversation.toJson(deviceId: String): JsonObject = buildJsonObject {
    put("id", JsonPrimitive(id))
    put("project_id", project_id?.let { JsonPrimitive(it) } ?: JsonNull)
    put("title", JsonPrimitive(title))
    put("model", JsonPrimitive(model))
    put("created_at", JsonPrimitive(created_at))
    put("updated_at", JsonPrimitive(updated_at))
    put("deleted", JsonPrimitive(deleted))
    put("lamport", JsonPrimitive(lamport))
    put("device_id", JsonPrimitive(deviceId))
}

internal fun Message.toJson(deviceId: String): JsonObject = buildJsonObject {
    put("id", JsonPrimitive(id))
    put("conv_id", JsonPrimitive(conv_id))
    put("role", JsonPrimitive(role))
    put("content", JsonPrimitive(content))
    put("encrypted", JsonPrimitive(encrypted))
    put("reasoning", reasoning?.let { JsonPrimitive(it) } ?: JsonNull)
    put("tool_calls", tool_calls?.let { JsonPrimitive(it) } ?: JsonNull)
    put("attachments", attachments?.let { JsonPrimitive(it) } ?: JsonNull)
    put("created_at", JsonPrimitive(created_at))
    put("deleted", JsonPrimitive(deleted))
    put("lamport", JsonPrimitive(lamport))
    put("device_id", JsonPrimitive(deviceId))
}
