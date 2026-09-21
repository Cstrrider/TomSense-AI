package org.tomsense.data

import java.util.UUID

actual fun nowMillis(): Long = System.currentTimeMillis()

actual fun randomId(): String = UUID.randomUUID().toString()

/**
 * Formatted with the JVM's own locale and zone, so a user who has their phone
 * in another language sees the model reason in their own terms.
 */
actual fun deviceClock(): String {
    val now = java.time.ZonedDateTime.now()
    val formatted = now.format(
        java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm"),
    )
    return "$formatted (${now.zone.id})"
}
