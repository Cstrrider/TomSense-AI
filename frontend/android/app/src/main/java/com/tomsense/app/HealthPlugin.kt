package com.tomsense.app

import androidx.activity.result.ActivityResult
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.CervicalMucusRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.IntermenstrualBleedingRecord
import androidx.health.connect.client.records.MenstruationPeriodRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.OvulationTestRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SexualActivityRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.ActivityCallback
import com.getcapacitor.annotation.CapacitorPlugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.reflect.KClass

/**
 * Health Connect bridge for the `get_health` client tool.
 *
 * Registered by MainActivity. Requests a broad read scope — Activity, Body,
 * Vitals, Sleep, Nutrition, Cycle — and returns a comprehensive summary:
 * activity/nutrition aggregates over the requested window, plus the most
 * recent point measurements (weight, blood pressure, etc.) from a wider
 * look-back since those aren't logged daily.
 *
 * The API is coroutine-based, hence Kotlin. Everything degrades gracefully:
 * an unavailable SDK or a declined/partial permission grant resolves with a
 * flag — and each read is independently try/caught — so the model always gets
 * a usable result and missing scopes just drop their fields.
 */
@CapacitorPlugin(name = "Health")
class HealthPlugin : Plugin() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /** Full read scope we ask Health Connect for. The matching
     *  android.permission.health.READ_* entries are in AndroidManifest.xml. */
    private val permissions: Set<String> = setOf(
        // Activity
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(FloorsClimbedRecord::class),
        HealthPermission.getReadPermission(ElevationGainedRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(Vo2MaxRecord::class),
        // Vitals
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
        HealthPermission.getReadPermission(BloodPressureRecord::class),
        HealthPermission.getReadPermission(BloodGlucoseRecord::class),
        HealthPermission.getReadPermission(OxygenSaturationRecord::class),
        HealthPermission.getReadPermission(RespiratoryRateRecord::class),
        HealthPermission.getReadPermission(BodyTemperatureRecord::class),
        // Body measurements
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getReadPermission(HeightRecord::class),
        HealthPermission.getReadPermission(BodyFatRecord::class),
        HealthPermission.getReadPermission(BasalMetabolicRateRecord::class),
        // Sleep
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        // Nutrition
        HealthPermission.getReadPermission(HydrationRecord::class),
        HealthPermission.getReadPermission(NutritionRecord::class),
        // Cycle tracking
        HealthPermission.getReadPermission(MenstruationPeriodRecord::class),
        HealthPermission.getReadPermission(IntermenstrualBleedingRecord::class),
        HealthPermission.getReadPermission(OvulationTestRecord::class),
        HealthPermission.getReadPermission(CervicalMucusRecord::class),
        HealthPermission.getReadPermission(SexualActivityRecord::class)
    )

    private fun daysArg(call: PluginCall): Int {
        val d = call.getInt("days", 1) ?: 1
        return d.coerceIn(1, 14)
    }

    @PluginMethod
    fun getSummary(call: PluginCall) {
        val status = HealthConnectClient.getSdkStatus(context)
        if (status != HealthConnectClient.SDK_AVAILABLE) {
            val ret = JSObject()
            ret.put("available", false)
            ret.put(
                "reason",
                if (status == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED)
                    "Health Connect needs to be updated on this device."
                else
                    "Health Connect is not available on this device."
            )
            call.resolve(ret)
            return
        }

        val client = HealthConnectClient.getOrCreate(context)
        scope.launch {
            try {
                val granted = client.permissionController.getGrantedPermissions()
                // Read whatever is granted; only prompt when nothing is. A
                // partial grant still reads fine — denied scopes just drop out.
                if (granted.intersect(permissions).isEmpty()) {
                    val contract = PermissionController.createRequestPermissionResultContract()
                    val intent = contract.createIntent(context, permissions)
                    startActivityForResult(call, intent, "healthPermResult")
                    return@launch
                }
                call.resolve(readSummary(client, daysArg(call)))
            } catch (e: Exception) {
                call.reject("Health read failed: ${e.message}")
            }
        }
    }

    @ActivityCallback
    fun healthPermResult(call: PluginCall?, result: ActivityResult) {
        if (call == null) return
        val client = HealthConnectClient.getOrCreate(context)
        scope.launch {
            try {
                val granted = client.permissionController.getGrantedPermissions()
                if (granted.intersect(permissions).isEmpty()) {
                    val ret = JSObject()
                    ret.put("available", true)
                    ret.put("granted", false)
                    call.resolve(ret)
                    return@launch
                }
                call.resolve(readSummary(client, daysArg(call)))
            } catch (e: Exception) {
                call.reject("Health read failed: ${e.message}")
            }
        }
    }

    /** Most recent record of a type, or null. Point measurements (weight,
     *  blood pressure…) aren't logged daily, so callers pass a wide window. */
    private suspend fun <T : Record> latest(
        client: HealthConnectClient,
        cls: KClass<T>,
        range: TimeRangeFilter
    ): T? = try {
        client.readRecords(
            ReadRecordsRequest(cls, timeRangeFilter = range, ascendingOrder = false, pageSize = 1)
        ).records.firstOrNull()
    } catch (e: Exception) {
        null
    }

    private suspend fun readSummary(client: HealthConnectClient, days: Int): JSObject {
        val end = Instant.now()
        val start = end.minus(days.toLong(), ChronoUnit.DAYS)
        val range = TimeRangeFilter.between(start, end)
        // Point measurements aren't logged daily — look back further so
        // "what's my weight" returns the most recent reading.
        val wide = TimeRangeFilter.between(end.minus(120, ChronoUnit.DAYS), end)

        val out = JSObject()
        out.put("available", true)
        out.put("granted", true)
        out.put("days", days)

        // ── activity / nutrition / heart-rate aggregates over the window ──
        try {
            val agg = client.aggregate(
                AggregateRequest(
                    metrics = setOf(
                        StepsRecord.COUNT_TOTAL,
                        DistanceRecord.DISTANCE_TOTAL,
                        ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL,
                        TotalCaloriesBurnedRecord.ENERGY_TOTAL,
                        FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL,
                        ElevationGainedRecord.ELEVATION_GAINED_TOTAL,
                        HeartRateRecord.BPM_AVG,
                        HeartRateRecord.BPM_MIN,
                        HeartRateRecord.BPM_MAX,
                        HydrationRecord.VOLUME_TOTAL,
                        NutritionRecord.ENERGY_TOTAL
                    ),
                    timeRangeFilter = range,
                    dataOriginFilter = emptySet()
                )
            )
            agg[StepsRecord.COUNT_TOTAL]?.let { out.put("steps", it) }
            agg[DistanceRecord.DISTANCE_TOTAL]?.let { out.put("distanceMeters", it.inMeters) }
            agg[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.let {
                out.put("activeKcal", it.inKilocalories)
            }
            agg[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.let {
                out.put("totalKcal", it.inKilocalories)
            }
            agg[FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL]?.let { out.put("floors", it) }
            agg[ElevationGainedRecord.ELEVATION_GAINED_TOTAL]?.let {
                out.put("elevationGainMeters", it.inMeters)
            }
            agg[HeartRateRecord.BPM_AVG]?.let { out.put("heartRateAvg", it) }
            agg[HeartRateRecord.BPM_MIN]?.let { out.put("heartRateMin", it) }
            agg[HeartRateRecord.BPM_MAX]?.let { out.put("heartRateMax", it) }
            agg[HydrationRecord.VOLUME_TOTAL]?.let { out.put("hydrationLiters", it.inLiters) }
            agg[NutritionRecord.ENERGY_TOTAL]?.let { out.put("nutritionKcal", it.inKilocalories) }
        } catch (e: Exception) {
            out.put("activityError", e.message ?: "unknown")
        }

        // ── sleep sessions ──
        try {
            val sleep = client.readRecords(
                ReadRecordsRequest(SleepSessionRecord::class, timeRangeFilter = range)
            )
            var minutes = 0L
            for (s in sleep.records) {
                minutes += Duration.between(s.startTime, s.endTime).toMinutes()
            }
            out.put("sleepMinutes", minutes)
            out.put("sleepSessions", sleep.records.size)
        } catch (e: Exception) {
            out.put("sleepError", e.message ?: "unknown")
        }

        // ── exercise sessions ──
        try {
            val ex = client.readRecords(
                ReadRecordsRequest(ExerciseSessionRecord::class, timeRangeFilter = range)
            )
            var minutes = 0L
            for (s in ex.records) {
                minutes += Duration.between(s.startTime, s.endTime).toMinutes()
            }
            out.put("workoutCount", ex.records.size)
            out.put("workoutMinutes", minutes)
        } catch (e: Exception) {
            /* exercise scope optional — ignore */
        }

        // ── latest point measurements (wider look-back) ──
        latest(client, WeightRecord::class, wide)?.let {
            out.put("weightKg", it.weight.inKilograms)
        }
        latest(client, HeightRecord::class, wide)?.let {
            out.put("heightCm", it.height.inMeters * 100.0)
        }
        latest(client, BodyFatRecord::class, wide)?.let {
            out.put("bodyFatPct", it.percentage.value)
        }
        latest(client, BasalMetabolicRateRecord::class, wide)?.let {
            out.put("bmrKcal", it.basalMetabolicRate.inKilocaloriesPerDay)
        }
        latest(client, RestingHeartRateRecord::class, wide)?.let {
            out.put("restingHeartRate", it.beatsPerMinute)
        }
        latest(client, HeartRateVariabilityRmssdRecord::class, wide)?.let {
            out.put("hrvMs", it.heartRateVariabilityMillis)
        }
        latest(client, Vo2MaxRecord::class, wide)?.let {
            out.put("vo2Max", it.vo2MillilitersPerMinuteKilogram)
        }
        latest(client, OxygenSaturationRecord::class, wide)?.let {
            out.put("oxygenSaturationPct", it.percentage.value)
        }
        latest(client, RespiratoryRateRecord::class, wide)?.let {
            out.put("respiratoryRate", it.rate)
        }
        latest(client, BodyTemperatureRecord::class, wide)?.let {
            out.put("bodyTempC", it.temperature.inCelsius)
        }
        latest(client, BloodPressureRecord::class, wide)?.let {
            out.put("bloodPressureSystolic", it.systolic.inMillimetersOfMercury)
            out.put("bloodPressureDiastolic", it.diastolic.inMillimetersOfMercury)
        }
        latest(client, BloodGlucoseRecord::class, wide)?.let {
            out.put("bloodGlucoseMgDl", it.level.inMilligramsPerDeciliter)
        }
        latest(client, MenstruationPeriodRecord::class, wide)?.let {
            out.put("lastPeriodStart", it.startTime.toString())
        }

        return out
    }
}
