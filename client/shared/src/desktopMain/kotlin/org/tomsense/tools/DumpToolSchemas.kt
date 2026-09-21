package org.tomsense.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/**
 * Print the tool schemas exactly as the client advertises them.
 *
 * Exists so the contract can be pointed at a real model without an emulator:
 *
 *     ./gradlew -q :shared:dumpToolSchemas > /tmp/tools.json
 *
 * Testing hand-copied schemas would prove nothing — the value here is that
 * this is the same [ToolCatalog] the app ships, so a routing failure found
 * this way is a real one and a fix is a real fix.
 */
fun main() {
    val pretty = Json { prettyPrint = true }
    println(pretty.encodeToString(JsonArray.serializer(), JsonArray(toolSchemas(ToolCatalog.ALL))))
}
