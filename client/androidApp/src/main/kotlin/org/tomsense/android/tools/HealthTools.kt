package org.tomsense.android.tools

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.tomsense.tools.DeviceTool
import org.tomsense.tools.ToolCatalog
import org.tomsense.tools.errorJson
import kotlin.math.roundToInt

/**
 * get_health — the 20th device tool, deferred until now because it needed
 * Health Connect (a dependency and its own permission model).
 *
 * Read-only, and summarised per day before it reaches the model: raw heart
 * rate samples run to thousands a day, and a model needs "resting 58, peak 162
 * during a 40-minute run", not the samples.
 *
 * Permission can't be requested from a tool call (Health Connect's dialog
 * needs an Activity and its own contract), so a missing grant comes back as
 * an error that names where to grant it: Settings → Health.
 */
object Health {
    val PERMISSIONS: Set<String> = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
    )

    fun available(ctx: Context): Boolean =
        HealthConnectClient.getSdkStatus(ctx) == HealthConnectClient.SDK_AVAILABLE

    suspend fun granted(ctx: Context): Set<String> =
        if (!available(ctx)) emptySet()
        else runCatching { HealthConnectClient.getOrCreate(ctx).permissionController.getGrantedPermissions() }
            .getOrDefault(emptySet())
}

internal fun healthTools(ctx: Context): List<DeviceTool> = listOf(
    Tool(ToolCatalog.GET_HEALTH) { args ->
        if (!Health.available(ctx)) {
            return@Tool errorJson("Health Connect is not available on this phone (install or update it from the Play Store).")
        }
        val granted = Health.granted(ctx)
        if (granted.isEmpty()) {
            return@Tool errorJson("Health access not granted. Ask the user to open TomSense → Settings → Health and allow access.")
        }
        val client = HealthConnectClient.getOrCreate(ctx)
        val days = (args.int("days") ?: 1).coerceIn(1, 30)
        val only = args.str("metric")
        val zone = ZoneId.systemDefault()
        val startDay = LocalDate.now().minusDays(days - 1L)
        val start = startDay.atStartOfDay()
        val end = LocalDateTime.now()
        val range = TimeRangeFilter.between(start.atZone(zone).toInstant(), end.atZone(zone).toInstant())
        fun want(m: String) = only == null || only == m
        fun has(cls: kotlin.reflect.KClass<out androidx.health.connect.client.records.Record>) =
            HealthPermission.getReadPermission(cls) in granted

        // buildJsonObject, not okJson: it is INLINE, so the Health Connect
        // reads below (suspend calls) are allowed inside the builder.
        buildJsonObject {
            put("ok", true)
            put("from", startDay.toString())
            put("to", LocalDate.now().toString())

            if ((want("steps") && has(StepsRecord::class)) || (want("calories") && has(ActiveCaloriesBurnedRecord::class))) {
                val metrics = buildSet {
                    if (want("steps") && has(StepsRecord::class)) add(StepsRecord.COUNT_TOTAL)
                    if (want("calories") && has(ActiveCaloriesBurnedRecord::class)) add(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
                }
                val perDay = client.aggregateGroupByPeriod(
                    AggregateGroupByPeriodRequest(metrics, TimeRangeFilter.between(start, end), Period.ofDays(1)),
                )
                put("daily", JsonArray(perDay.map { g ->
                    buildJsonObject {
                        put("date", g.startTime.toLocalDate().toString())
                        g.result[StepsRecord.COUNT_TOTAL]?.let { put("steps", it) }
                        g.result[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.let { put("active_kcal", it.inKilocalories.roundToInt()) }
                    }
                }))
            }

            if (want("exercise") && has(ExerciseSessionRecord::class)) {
                val sessions = client.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, range)).records
                put("exercise", JsonArray(sessions.takeLast(20).map { s ->
                    buildJsonObject {
                        put("start", s.startTime.atZone(zone).toLocalDateTime().toString().take(16))
                        put("minutes", Duration.between(s.startTime, s.endTime).toMinutes())
                        put("type", s.exerciseType)
                        s.title?.let { put("title", it) }
                    }
                }))
            }

            if (want("sleep") && has(SleepSessionRecord::class)) {
                // Sleep that ENDED in the window: last night's sleep started yesterday.
                val sleepRange = TimeRangeFilter.between(start.minusHours(12).atZone(zone).toInstant(), end.atZone(zone).toInstant())
                val sleeps = client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, sleepRange)).records
                put("sleep", JsonArray(sleeps.takeLast(14).map { s ->
                    val stages = s.stages.groupBy { it.stage }.mapValues { (_, v) ->
                        v.sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }
                    }
                    buildJsonObject {
                        put("night_of", s.startTime.atZone(zone).toLocalDate().toString())
                        put("bed", s.startTime.atZone(zone).toLocalTime().toString().take(5))
                        put("wake", s.endTime.atZone(zone).toLocalTime().toString().take(5))
                        put("hours", (Duration.between(s.startTime, s.endTime).toMinutes() / 6.0).roundToInt() / 10.0)
                        if (stages.isNotEmpty()) {
                            put("deep_min", stages[SleepSessionRecord.STAGE_TYPE_DEEP] ?: 0)
                            put("rem_min", stages[SleepSessionRecord.STAGE_TYPE_REM] ?: 0)
                            put("light_min", stages[SleepSessionRecord.STAGE_TYPE_LIGHT] ?: 0)
                            put("awake_min", stages[SleepSessionRecord.STAGE_TYPE_AWAKE] ?: 0)
                        }
                    }
                }))
            }

            if (want("heart_rate") && has(HeartRateRecord::class)) {
                val samples = client.readRecords(ReadRecordsRequest(HeartRateRecord::class, range)).records
                    .flatMap { r -> r.samples }
                val byDay = samples.groupBy { it.time.atZone(zone).toLocalDate() }
                put("heart_rate", JsonArray(byDay.toSortedMap().map { (day, s) ->
                    buildJsonObject {
                        put("date", day.toString())
                        put("min", s.minOf { it.beatsPerMinute })
                        put("avg", s.map { it.beatsPerMinute }.average().roundToInt())
                        put("max", s.maxOf { it.beatsPerMinute })
                        put("samples", s.size)
                    }
                }))
            }

            if (want("resting_heart_rate") && has(RestingHeartRateRecord::class)) {
                val rhr = client.readRecords(ReadRecordsRequest(RestingHeartRateRecord::class, range)).records
                put("resting_heart_rate", JsonArray(rhr.takeLast(30).map { r ->
                    buildJsonObject {
                        put("date", r.time.atZone(zone).toLocalDate().toString())
                        put("bpm", r.beatsPerMinute)
                    }
                }))
            }

            if (want("weight") && has(WeightRecord::class)) {
                // Weight is sparse — look back further than the window for the latest.
                val wRange = TimeRangeFilter.between(end.minusDays(90).atZone(zone).toInstant(), end.atZone(zone).toInstant())
                client.readRecords(ReadRecordsRequest(WeightRecord::class, wRange)).records.lastOrNull()?.let { w ->
                    put("latest_weight", buildJsonObject {
                        put("kg", (w.weight.inKilograms * 10).roundToInt() / 10.0)
                        put("date", w.time.atZone(zone).toLocalDate().toString())
                    })
                }
            }

            val missing = Health.PERMISSIONS - granted
            if (missing.isNotEmpty()) {
                put("not_granted", JsonArray(missing.map { JsonPrimitive(it.substringAfterLast('.')) }))
            }
        }.toString()
    },
)
