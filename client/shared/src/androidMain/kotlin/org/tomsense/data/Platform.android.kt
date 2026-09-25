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

actual fun messageTime(millis: Long): String {
    val zone = java.time.ZoneId.systemDefault()
    val at = java.time.Instant.ofEpochMilli(millis).atZone(zone)
    val today = java.time.LocalDate.now(zone)
    val date = at.toLocalDate()
    // Locale-driven, never a fixed pattern: 12/24-hour and day/month order
    // come from the device, not from whoever wrote this.
    val time = at.format(
        java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT),
    )
    return when {
        date == today -> time
        date == today.minusDays(1) -> "Yesterday $time"
        date.isAfter(today.minusDays(7)) ->
            "${at.format(java.time.format.DateTimeFormatter.ofPattern("EEE"))} $time"
        else -> "${at.format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM))}, $time"
    }
}
