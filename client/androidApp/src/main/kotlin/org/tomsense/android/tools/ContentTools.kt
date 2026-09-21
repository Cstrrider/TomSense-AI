package org.tomsense.android.tools

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.provider.CalendarContract
import android.provider.ContactsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.tomsense.tools.DeviceTool
import org.tomsense.tools.ToolCatalog
import org.tomsense.tools.errorJson
import org.tomsense.tools.okJson
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.coroutines.resume

/** Tools that read or write the user's personal data. All permission-gated. */
internal fun contentTools(ctx: Context): List<DeviceTool> = listOf(

    Tool(ToolCatalog.GET_LOCATION) {
        val granted = PermissionGate.require(
            ctx,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        if (!granted) return@Tool errorJson("location permission not granted")

        val location = currentLocation(ctx) ?: return@Tool errorJson("no location fix available")
        val address = withContext(Dispatchers.IO) {
            runCatching {
                @Suppress("DEPRECATION")
                Geocoder(ctx).getFromLocation(location.latitude, location.longitude, 1)
                    ?.firstOrNull()
                    ?.getAddressLine(0)
            }.getOrNull()
        }

        okJson {
            put("latitude", location.latitude)
            put("longitude", location.longitude)
            put("accuracy_m", location.accuracy.toInt())
            put("address", address ?: "unknown")
            put("age_seconds", (System.currentTimeMillis() - location.time) / 1000)
        }
    },

    Tool(ToolCatalog.GET_CALENDAR) { args ->
        if (!PermissionGate.require(ctx, Manifest.permission.READ_CALENDAR)) {
            return@Tool errorJson("calendar permission not granted")
        }

        val days = (args.int("days") ?: 1).coerceIn(1, 60)
        val max = (args.int("max") ?: 20).coerceIn(1, 100)
        val from = System.currentTimeMillis()
        val to = from + days * 86_400_000L

        // Instances rather than Events: a weekly recurring meeting is ONE
        // Events row, and querying that table returns the series definition
        // instead of "the 9am standup tomorrow".
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(from.toString())
            .appendPath(to.toString())
            .build()

        val columns = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.ALL_DAY,
        )

        val events = withContext(Dispatchers.IO) {
            buildList {
                ctx.contentResolver.query(
                    uri, columns, null, null, "${CalendarContract.Instances.BEGIN} ASC",
                )?.use { cursor ->
                    while (cursor.moveToNext() && size < max) {
                        add(
                            CalendarEntry(
                                title = cursor.getString(0) ?: "(no title)",
                                begin = cursor.getLong(1),
                                end = cursor.getLong(2),
                                location = cursor.getString(3),
                                allDay = cursor.getInt(4) == 1,
                            ),
                        )
                    }
                }
            }
        }

        okJson {
            put("count", events.size)
            putJsonArray("events") {
                for (event in events) addJsonObject {
                    put("title", event.title)
                    put("start", isoOf(event.begin))
                    put("end", isoOf(event.end))
                    put("all_day", event.allDay)
                    event.location?.takeIf { it.isNotBlank() }?.let { put("location", it) }
                }
            }
        }
    },

    Tool(ToolCatalog.CREATE_CALENDAR_EVENT) { args ->
        if (!PermissionGate.require(
                ctx,
                Manifest.permission.READ_CALENDAR,
                Manifest.permission.WRITE_CALENDAR,
            )
        ) {
            return@Tool errorJson("calendar permission not granted")
        }

        val start = parseLocalTime(args.require("start"))
            ?: return@Tool errorJson("could not parse 'start'; use ISO 8601 like 2026-09-21T14:30")
        val minutes = (args.int("duration_minutes") ?: 60).coerceIn(1, 24 * 60)
        val calendarId = defaultCalendarId(ctx)
            ?: return@Tool errorJson("no writable calendar on this device")

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, args.require("title"))
            put(CalendarContract.Events.DTSTART, start)
            put(CalendarContract.Events.DTEND, start + minutes * 60_000L)
            put(CalendarContract.Events.EVENT_TIMEZONE, ZoneId.systemDefault().id)
            args.str("location")?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            args.str("description")?.let { put(CalendarContract.Events.DESCRIPTION, it) }
        }

        val uri = withContext(Dispatchers.IO) {
            ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
        } ?: return@Tool errorJson("calendar rejected the event")

        okJson {
            put("event_id", ContentUris.parseId(uri))
            put("start", isoOf(start))
            put("duration_minutes", minutes)
        }
    },

    Tool(ToolCatalog.SET_REMINDER) { args ->
        if (!PermissionGate.require(
                ctx,
                Manifest.permission.READ_CALENDAR,
                Manifest.permission.WRITE_CALENDAR,
            )
        ) {
            return@Tool errorJson("calendar permission not granted")
        }

        val at = parseLocalTime(args.require("at"))
            ?: return@Tool errorJson("could not parse 'at'; use ISO 8601 like 2026-09-21T14:30")
        val calendarId = defaultCalendarId(ctx)
            ?: return@Tool errorJson("no writable calendar on this device")

        // Android has no general-purpose reminder store — Google Tasks and
        // Keep are proprietary. A zero-length calendar event with an alarm at
        // T-0 is the one mechanism that is guaranteed present and actually
        // notifies, so a "reminder" is that.
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, args.require("text"))
            put(CalendarContract.Events.DTSTART, at)
            put(CalendarContract.Events.DTEND, at)
            put(CalendarContract.Events.EVENT_TIMEZONE, ZoneId.systemDefault().id)
            put(CalendarContract.Events.HAS_ALARM, 1)
        }

        val uri = withContext(Dispatchers.IO) {
            ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
        } ?: return@Tool errorJson("calendar rejected the reminder")

        val eventId = ContentUris.parseId(uri)
        withContext(Dispatchers.IO) {
            ctx.contentResolver.insert(
                CalendarContract.Reminders.CONTENT_URI,
                ContentValues().apply {
                    put(CalendarContract.Reminders.EVENT_ID, eventId)
                    put(CalendarContract.Reminders.MINUTES, 0)
                    put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                },
            )
        }

        okJson { put("reminder_id", eventId); put("at", isoOf(at)) }
    },

    Tool(ToolCatalog.GET_CONTACTS) { args ->
        if (!PermissionGate.require(ctx, Manifest.permission.READ_CONTACTS)) {
            return@Tool errorJson("contacts permission not granted")
        }

        val name = args.require("name")
        val max = (args.int("max") ?: 5).coerceIn(1, 25)

        // The FILTER uri matches on name AND number and is what the dialer
        // itself uses, so results rank the way the user expects.
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI
            .buildUpon().appendPath(name).build()

        val found = withContext(Dispatchers.IO) {
            buildList {
                ctx.contentResolver.query(
                    uri,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER,
                        ContactsContract.CommonDataKinds.Phone.TYPE,
                    ),
                    null, null,
                    "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
                )?.use { cursor ->
                    while (cursor.moveToNext() && size < max) {
                        add(Triple(cursor.getString(0), cursor.getString(1), cursor.getInt(2)))
                    }
                }
            }
        }

        if (found.isEmpty()) return@Tool errorJson("no contact matching '$name'")

        okJson {
            put("count", found.size)
            putJsonArray("contacts") {
                for ((display, number, type) in found) addJsonObject {
                    put("name", display ?: "(unnamed)")
                    put("number", number ?: "")
                    put("type", phoneTypeLabel(ctx, type))
                }
            }
        }
    },
)

private class CalendarEntry(
    val title: String,
    val begin: Long,
    val end: Long,
    val location: String?,
    val allDay: Boolean,
)

/**
 * A usable fix, preferring a fresh one.
 *
 * `getCurrentLocation` actively asks the hardware, which is what you want when
 * someone says "where am I" — but it is API 31+ on LocationManager and can
 * take seconds. The last known fix is the fallback, and also the answer when
 * the request times out, since a slightly stale position beats none.
 */
private suspend fun currentLocation(ctx: Context): Location? {
    val manager = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }

    val lastKnown = providers
        .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
        .maxByOrNull { it.time }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val provider = providers.firstOrNull() ?: return lastKnown
        val fresh = withTimeoutOrNull(10_000) {
            suspendCancellableCoroutine { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                runCatching {
                    manager.getCurrentLocation(
                        provider,
                        signal,
                        ctx.mainExecutor,
                    ) { location -> if (cont.isActive) cont.resume(location) }
                }.onFailure { if (cont.isActive) cont.resume(null) }
            }
        }
        if (fresh != null) return fresh
    }
    return lastKnown
}

/**
 * The calendar to write to.
 *
 * Preference order is deliberate: the one marked primary, else any the account
 * owner can write to. Picking the first row returned instead lands events in
 * read-only subscriptions like "Holidays in United States", where the insert
 * appears to succeed and the event is never seen again.
 */
private suspend fun defaultCalendarId(ctx: Context): Long? = withContext(Dispatchers.IO) {
    ctx.contentResolver.query(
        CalendarContract.Calendars.CONTENT_URI,
        arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
        ),
        null, null, null,
    )?.use { cursor ->
        var writable: Long? = null
        while (cursor.moveToNext()) {
            val id = cursor.getLong(0)
            val access = cursor.getInt(2)
            if (access < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) continue
            if (cursor.getInt(1) == 1) return@use id
            if (writable == null) writable = id
        }
        writable
    }
}

/**
 * Parse what a model actually emits for a time.
 *
 * Models are inconsistent about the seconds field and the `T`, and a rejected
 * event because of a missing `:00` is a bad reason to fail.
 */
private fun parseLocalTime(raw: String): Long? {
    val text = raw.trim().replace(' ', 'T')
    val patterns = listOf(
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd'T'HH:mm",
    )
    for (pattern in patterns) {
        val parsed = runCatching {
            LocalDateTime.parse(text, DateTimeFormatter.ofPattern(pattern))
        }.getOrNull()
        if (parsed != null) {
            return parsed.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
    }
    // Also accept a full instant with an offset, which some models prefer.
    return runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
}

private fun isoOf(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))

private fun phoneTypeLabel(ctx: Context, type: Int): String =
    ContactsContract.CommonDataKinds.Phone
        .getTypeLabel(ctx.resources, type, "other").toString()
