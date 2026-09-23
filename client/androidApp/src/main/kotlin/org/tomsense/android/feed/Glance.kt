package org.tomsense.android.feed

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tomsense.android.TomsenseApp
import kotlin.math.roundToInt

/** One thing happening, calendar event or reminder alike. */
data class CalendarEntry(
    val title: String,
    val start: String,
    val allDay: Boolean,
    val location: String? = null,
)

/**
 * The at-a-glance facts, each independently optional.
 *
 * Every field is nullable on purpose: these come from four unrelated sources —
 * a device tool, a location fix, a public weather API and the news worker —
 * and any of them can be missing a permission, a fix or a network. A card with
 * two of four lines is useful; one that refuses to render because the fourth
 * failed is not.
 */
data class Insights(
    val device: String? = null,
    val weather: String? = null,
    val feed: String? = null,
    val summary: String = "",
) {
    val lines: List<String> get() = listOfNotNull(device, weather, feed)
    val isEmpty: Boolean get() = lines.isEmpty() && summary.isBlank()
}

/**
 * Today's calendar, reminders included.
 *
 * Reminders set through `set_reminder` land in the calendar provider, so they
 * come back from the same tool and need no separate read.
 */
suspend fun readCalendar(app: TomsenseApp, limit: Int = 4): List<CalendarEntry> = runCatching {
    val raw = app.tools.call(
        "get_calendar",
        buildJsonObject { put("days", JsonPrimitive(1)) },
    )
    // The tool reports a missing permission as an error object rather than
    // throwing, so this is a content check, not a failure check.
    if (raw.contains("\"error\"", ignoreCase = true)) return emptyList()

    Json.parseToJsonElement(raw).jsonObject["events"]?.jsonArray.orEmpty()
        .mapNotNull { element ->
            val event = element.jsonObject
            val title = event["title"]?.jsonPrimitive?.contentOrNull().orEmpty()
            if (title.isBlank()) {
                null
            } else {
                CalendarEntry(
                    title = title,
                    start = event["start"]?.jsonPrimitive?.contentOrNull().orEmpty(),
                    allDay = event["all_day"]?.jsonPrimitive?.contentOrNull() == "true",
                    location = event["location"]?.jsonPrimitive?.contentOrNull(),
                )
            }
        }
        .take(limit)
}.getOrDefault(emptyList())

/** Battery, network and nothing else — the things a glance actually answers. */
suspend fun readDevice(app: TomsenseApp): String? = runCatching {
    val raw = app.tools.call("get_device_status", buildJsonObject { })
    val status = Json.parseToJsonElement(raw).jsonObject
    val battery = status["battery_percent"]?.jsonPrimitive?.contentOrNull()?.toIntOrNull()
    val charging = status["charging"]?.jsonPrimitive?.contentOrNull() == "true"
    val network = status["network"]?.jsonPrimitive?.contentOrNull()

    val parts = buildList {
        battery?.let { add("$it%" + if (charging) " charging" else "") }
        network?.takeIf { it != "other" }?.let { add(it) }
    }
    parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}.getOrNull()

/**
 * Current conditions from Open-Meteo.
 *
 * No API key and no account, which is why it is preferred to the services that
 * need one: the panel should not depend on a credential the user has to go and
 * create. Location comes from the device tool that already exists, so a denied
 * location permission degrades to no weather line rather than an error.
 */
suspend fun readWeather(app: TomsenseApp): String? = runCatching {
    val fix = app.tools.call("get_location", buildJsonObject { })
    if (fix.contains("\"error\"", ignoreCase = true)) return null

    val location = Json.parseToJsonElement(fix).jsonObject
    val lat = location["latitude"]?.jsonPrimitive?.contentOrNull()?.toDoubleOrNull() ?: return null
    val lon = location["longitude"]?.jsonPrimitive?.contentOrNull()?.toDoubleOrNull() ?: return null

    val response = app.httpClient.get("https://api.open-meteo.com/v1/forecast") {
        parameter("latitude", lat)
        parameter("longitude", lon)
        parameter("current", "temperature_2m,weather_code")
        parameter("daily", "temperature_2m_max,temperature_2m_min")
        parameter("temperature_unit", "fahrenheit")
        parameter("timezone", "auto")
        parameter("forecast_days", 1)
    }
    if (!response.status.isSuccess()) return null

    val forecast: OpenMeteo = response.body()
    val now = forecast.current?.temperature?.roundToInt() ?: return null
    val sky = wmoDescription(forecast.current.weatherCode)
    val high = forecast.daily?.max?.firstOrNull()?.roundToInt()
    val low = forecast.daily?.min?.firstOrNull()?.roundToInt()

    buildString {
        append("$now°")
        if (sky.isNotBlank()) append(", $sky")
        if (high != null && low != null) append(" · $low–$high°")
        location["place"]?.jsonPrimitive?.contentOrNull()?.let { append(" · $it") }
    }
}.getOrNull()

/**
 * What the worker already decided, stated plainly.
 *
 * The balance counts are the whole point of the ranker's lean machinery, and
 * they are invisible in a list of headlines — this is the only place the user
 * can see that it is working. Bias is OUTLET lean, never article analysis.
 */
fun feedStats(items: List<NewsClient.Item>): String? {
    if (items.isEmpty()) return null
    val left = items.count { (it.outletBias ?: 0f) <= -0.5f }
    val right = items.count { (it.outletBias ?: 0f) >= 0.5f }
    val centre = items.size - left - right
    return "${items.size} stories · $left left / $centre centre / $right right"
}

/** Cheap, dependency-free WMO code names. Blank for anything unmapped. */
private fun wmoDescription(code: Int?): String = when (code) {
    0 -> "clear"
    1 -> "mostly clear"
    2 -> "partly cloudy"
    3 -> "overcast"
    45, 48 -> "fog"
    51, 53, 55 -> "drizzle"
    56, 57 -> "freezing drizzle"
    61, 63, 65 -> "rain"
    66, 67 -> "freezing rain"
    71, 73, 75, 77 -> "snow"
    80, 81, 82 -> "showers"
    85, 86 -> "snow showers"
    95 -> "thunderstorms"
    96, 99 -> "thunderstorms with hail"
    else -> ""
}

@Serializable
private data class OpenMeteo(
    val current: Current? = null,
    val daily: Daily? = null,
) {
    @Serializable
    data class Current(
        @SerialName("temperature_2m") val temperature: Double? = null,
        @SerialName("weather_code") val weatherCode: Int? = null,
    )

    @Serializable
    data class Daily(
        @SerialName("temperature_2m_max") val max: List<Double> = emptyList(),
        @SerialName("temperature_2m_min") val min: List<Double> = emptyList(),
    )
}

/** Null rather than the literal string "null", which JsonPrimitive returns. */
private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? =
    content.takeIf { it != "null" && it.isNotBlank() }
