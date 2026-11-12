package link.hector.freellee.data

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Temperature
import androidx.health.connect.client.units.TemperatureDelta
import link.hector.freellee.DEFAULT_WATCH_NAME
import link.hector.freellee.TEMPERATURE_SAMPLE_SECONDS
import link.hector.freellee.data.CounterEventWriter
import link.hector.freellee.data.DeviceStore.RecordItem
import link.hector.freellee.data.DeviceStore.StepInterval
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

private const val TAG = "HealthConnectClient"

/**
 * Whether the Health Connect provider is usable on this device.
 *
 * [UNSUPPORTED] means the platform version is too old, or the app is running in a work profile:
 * the app must hide Health Connect integration and keep working locally. [UPDATE_REQUIRED] means
 * the device supports Health Connect but the provider app is missing or outdated; the app should
 * explain this and keep working locally rather than sending the user to a store.
 */
enum class HealthConnectAvailability { AVAILABLE, UPDATE_REQUIRED, UNSUPPORTED }

class HealthConnectClient(private val context: Context) {

    private var client: HealthConnectClient? = null
    private var permissionsGranted: MutableSet<String> = mutableSetOf()

    private fun requireClient(): androidx.health.connect.client.HealthConnectClient {
        return client ?: run {
            try {
                val c = androidx.health.connect.client.HealthConnectClient.getOrCreate(context)
                client = c
                c
            } catch (e: Exception) {
                Log.w(TAG, "Failed to initialize Health Connect client", e)
                throw IllegalStateException("Health Connect not available", e)
            }
        }
    }

    /**
     * The [ActivityResultContract] that shows the Health Connect permission UI.
     *
     * Register this with [androidx.activity.result.registerForActivityResult] (or
     * `rememberLauncherForActivityResult` in Compose) and launch it with the set of permissions
     * returned by [requiredPermissions]. This is the only supported way to request Health Connect
     * access; a generic runtime-permission request will not work.
     */
    fun permissionContract(): ActivityResultContract<Set<String>, Set<String>> =
        PermissionController.createRequestPermissionResultContract()

    fun getGrantedPermissions(): Set<String> {
        requireClient()
        return permissionsGranted
    }

    fun setGrantedPermissions(permissions: Set<String>) {
        permissionsGranted = permissions.toMutableSet()
    }

    /**
     * Whether the Health Connect provider is usable on this device. Never throws.
     */
    fun availability(): HealthConnectAvailability {
        return try {
            when (HealthConnectClient.getSdkStatus(context)) {
                HealthConnectClient.SDK_AVAILABLE -> HealthConnectAvailability.AVAILABLE
                HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                    HealthConnectAvailability.UPDATE_REQUIRED

                else -> HealthConnectAvailability.UNSUPPORTED
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking Health Connect availability", e)
            HealthConnectAvailability.UNSUPPORTED
        }
    }

    /** What Settings shows about Health Connect integration. */
    data class HealthConnectStatus(
        val availability: HealthConnectAvailability,
        val sleepPermission: Boolean,
        val temperaturePermission: Boolean,
        val temperatureSupported: Boolean,
    )

    /** Reads the live permission state; never throws. */
    suspend fun status(): HealthConnectStatus {
        val availability = availability()
        if (availability != HealthConnectAvailability.AVAILABLE) {
            return HealthConnectStatus(availability, false, false, false)
        }
        val granted = try {
            requireClient().permissionController.getGrantedPermissions()
        } catch (e: Exception) {
            Log.w(TAG, "Could not read granted Health Connect permissions", e)
            emptySet()
        }
        return HealthConnectStatus(
            availability = availability,
            sleepPermission = granted.contains(
                HealthPermission.getWritePermission(SleepSessionRecord::class),
            ),
            temperaturePermission = granted.contains(
                HealthPermission.getWritePermission(SkinTemperatureRecord::class),
            ),
            temperatureSupported = isSkinTemperatureAvailable(),
        )
    }

    /**
     * The set of Health Connect permissions the app needs, filtered to the features actually
     * available on this device.
     */
    suspend fun requiredPermissions(): Set<String> = try {
        buildSet {
            add(HealthPermission.getWritePermission(StepsRecord::class))
            if (isHeartRateAvailable()) {
                add(HealthPermission.getWritePermission(HeartRateRecord::class))
            }
            if (isSkinTemperatureAvailable()) {
                add(HealthPermission.getWritePermission(SkinTemperatureRecord::class))
            }
            add(HealthPermission.getWritePermission(ExerciseSessionRecord::class))
            add(HealthPermission.getWritePermission(SleepSessionRecord::class))
            // Counter events
            addAll(CounterEventWriter.counterEventPermissions)
        }
    } catch (e: Exception) {
        Log.w(TAG, "Error computing required permissions", e)
        // Fall back to the core permissions so the caller still has something to request.
        setOf(
            HealthPermission.getWritePermission(StepsRecord::class),
            HealthPermission.getWritePermission(ExerciseSessionRecord::class),
        )
    }

    /**
     * Checks whether skin temperature feature is available on this device.
     */
    fun isSkinTemperatureAvailable(): Boolean {
        return try {
            val hc = requireClient()
            hc.features.getFeatureStatus(HealthConnectFeatures.FEATURE_SKIN_TEMPERATURE) ==
                HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        } catch (e: Exception) {
            Log.w(TAG, "Error checking skin temperature availability", e)
            false
        }
    }

    /**
     * Opens the system Health Connect settings where users can grant/revoke permissions.
     */
    fun openHealthConnectSettings(context: Context) {
        val intent = Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open Health Connect settings", e)
        }
    }

    /**
     * Checks if all required permissions are already granted.
     * Only considers permissions for features available on this device.
     */
    suspend fun hasAllPermissions(): Boolean {
        return try {
            val hc = requireClient()
            val granted = hc.permissionController.getGrantedPermissions()
            permissionsGranted = granted.toMutableSet()
            granted.containsAll(requiredPermissions())
        } catch (e: Exception) {
            Log.w(TAG, "Error checking permissions", e)
            false
        }
    }

    private fun isHeartRateAvailable(): Boolean {
        return true // Heart rate is a core Health Connect feature, always available
    }

    /**
     * Writes step intervals to Health Connect.
     */
    suspend fun writeStepIntervals(intervals: List<StepInterval>) {
        try {
            val hc = requireClient()

            if (!hc.permissionController.getGrantedPermissions().contains(
                HealthPermission.getWritePermission(StepsRecord::class)
            )) {
                Log.w(TAG, "Health Connect permissions not granted — skipping write")
                return
            }

            val records = intervals.map { interval ->
                val startInstant = Instant.ofEpochSecond(interval.tStart)
                val endInstant = Instant.ofEpochSecond(interval.tEnd)
                val metadata = createMetadata(startInstant, endInstant)

                StepsRecord(
                    count = interval.steps.toLong(),
                    startTime = startInstant,
                    endTime = endInstant,
                    startZoneOffset = null,
                    endZoneOffset = null,
                    metadata = metadata
                )
            }

            hc.insertRecords(records)
            Log.d(TAG, "Wrote ${records.size} step intervals to Health Connect")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write step intervals to Health Connect", e)
        }
    }

    /**
     * Writes heart rate records to Health Connect.
     */
    suspend fun writeHeartRateRecords(records: List<RecordItem>) {
        try {
            val hc = requireClient()

            if (!hc.permissionController.getGrantedPermissions().contains(
                HealthPermission.getWritePermission(HeartRateRecord::class)
            )) {
                Log.w(TAG, "Heart Write permission not granted — skipping heart rate write")
                return
            }

            if (records.isEmpty()) return

            val healthRecords = records.map { item ->
                val startInstant = Instant.ofEpochSecond(item.tStart)
                val metadata = createMetadata(startInstant, startInstant)

                HeartRateRecord(
                    startTime = startInstant,
                    startZoneOffset = null,
                    endTime = startInstant,
                    endZoneOffset = null,
                    samples = listOf(
                        HeartRateRecord.Sample(
                            time = startInstant,
                            beatsPerMinute = item.value.toLong()
                        )
                    ),
                    metadata = metadata
                )
            }

            hc.insertRecords(healthRecords)
            Log.d(TAG, "Wrote ${healthRecords.size} heart rate records to Health Connect")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write heart rate records to Health Connect", e)
        }
    }

    /**
     * Writes an exercise session record to Health Connect.
     */
    suspend fun writeExerciseSessionWithType(
        tStart: Long,
        tEnd: Long,
        exerciseType: Int,
    ): Boolean {
        try {
            val hc = requireClient()

            if (!hc.permissionController.getGrantedPermissions().contains(
                HealthPermission.getWritePermission(ExerciseSessionRecord::class)
            )) {
                Log.w(TAG, "Exercise Write permission not granted — skipping exercise write")
                return false
            }

            val startInstant = Instant.ofEpochSecond(tStart)
            val endInstant = Instant.ofEpochSecond(tEnd)
            val metadata = createMetadata(startInstant, endInstant)

            val record = ExerciseSessionRecord(
                startTime = startInstant,
                startZoneOffset = null,
                endTime = endInstant,
                endZoneOffset = null,
                exerciseType = exerciseType,
                title = null,
                metadata = metadata
            )

            hc.insertRecords(listOf(record))
            Log.d(TAG, "Wrote exercise session: $exerciseType [$tStart – $tEnd]")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write exercise session to Health Connect", e)
            return false
        }
    }

    /**
     * Writes a sleep session record to Health Connect.
     *
     * The session is split into two stages:
     * 1. [SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED] for the first 30 minutes
     * 2. [SleepSessionRecord.STAGE_TYPE_SLEEPING] for the remainder
     *
     * Returns false if the interval is empty or the permission is missing.
     */
    suspend fun writeSleepSession(
        tStart: Long,
        tEnd: Long,
    ): Boolean {
        try {
            if (tEnd <= tStart) {
                Log.w(TAG, "Skipping sleep session with invalid interval [$tStart – $tEnd]")
                return false
            }

            val hc = requireClient()

            if (!hc.permissionController.getGrantedPermissions().contains(
                HealthPermission.getWritePermission(SleepSessionRecord::class)
            )) {
                Log.w(TAG, "Sleep Write permission not granted — skipping sleep write")
                return false
            }

            val startInstant = Instant.ofEpochSecond(tStart)
            val endInstant = Instant.ofEpochSecond(tEnd)
            val metadata = createMetadata(startInstant, endInstant)

            // The watch's stopwatch starts with the wearer awake in bed; the first 30 minutes
            // are treated as awake-in-bed, and the rest as generic sleep.
            val awakeStageEnd = minOf(startInstant.plus(Duration.ofMinutes(30)), endInstant)
            val stages = buildList {
                add(
                    SleepSessionRecord.Stage(
                        startInstant,
                        awakeStageEnd,
                        SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
                    )
                )
                if (endInstant > awakeStageEnd) {
                    add(
                        SleepSessionRecord.Stage(
                            awakeStageEnd,
                            endInstant,
                            SleepSessionRecord.STAGE_TYPE_SLEEPING,
                        )
                    )
                }
            }

            val record = SleepSessionRecord(
                startTime = startInstant,
                startZoneOffset = null,
                endTime = endInstant,
                endZoneOffset = null,
                stages = stages,
                metadata = metadata,
            )

            hc.insertRecords(listOf(record))
            Log.d(TAG, "Wrote sleep session [$tStart – $tEnd]")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write sleep session to Health Connect", e)
            return false
        }
    }

    /**
     * Writes automatically detected or user-confirmed sleep sessions.
     *
     * Unlike [writeSleepSession], which tags the first 30 minutes awake-in-bed for stopwatch
     * recordings, detected sessions are already trimmed to the sleep window, so the whole
     * interval is written as sleeping. The session id becomes the Health Connect client record
     * id, so re-confirming or adjusting a session updates the existing record instead of
     * adding a duplicate.
     *
     * Returns the ids that were written successfully.
     */
    suspend fun writeSleepSessions(sessions: List<SleepSession>): List<String> {
        if (sessions.isEmpty()) return emptyList()
        return try {
            val hc = requireClient()
            if (!hasPermission(SleepSessionRecord::class)) {
                Log.w(TAG, "Sleep Write permission not granted — skipping sleep session write")
                return emptyList()
            }

            val records = sessions.map { session ->
                val startInstant = Instant.ofEpochSecond(session.startEpochSecond)
                val endInstant = Instant.ofEpochSecond(session.endEpochSecond)
                SleepSessionRecord(
                    startTime = startInstant,
                    startZoneOffset = null,
                    endTime = endInstant,
                    endZoneOffset = null,
                    stages = listOf(
                        SleepSessionRecord.Stage(
                            startInstant,
                            endInstant,
                            SleepSessionRecord.STAGE_TYPE_SLEEPING,
                        ),
                    ),
                    metadata = createMetadata(
                        startInstant,
                        endInstant,
                        clientRecordIdFor(session.id),
                    ),
                )
            }
            hc.insertRecords(records)
            Log.d(TAG, "Wrote ${records.size} sleep sessions to Health Connect")
            sessions.map { it.id }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write sleep sessions to Health Connect", e)
            emptyList()
        }
    }

    /** Removes sleep records previously written for these session ids. */
    suspend fun deleteSleepSessions(sessionIds: List<String>) {
        if (sessionIds.isEmpty()) return
        try {
            val hc = requireClient()
            if (!hasPermission(SleepSessionRecord::class)) return
            hc.deleteRecords(
                SleepSessionRecord::class,
                sessionIds.map { clientRecordIdFor(it) },
                emptyList(),
            )
            Log.d(TAG, "Deleted ${sessionIds.size} stale sleep sessions from Health Connect")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete stale sleep sessions from Health Connect", e)
        }
    }

    /**
     * Health Connect requires client record ids to be UUIDs when deleting; a readable string is
     * only accepted on insert. Derive a stable UUID from the session id so updates and deletes
     * always address the same record.
     */
    private fun clientRecordIdFor(sessionId: String): String =
        UUID.nameUUIDFromBytes("freellee_sleep_$sessionId".toByteArray()).toString()

    /**
     * One-time cleanup for sleep records written before ids became UUIDs. Those records cannot
     * be addressed individually any more, so all sleep records previously written by this app
     * are removed; the current sessions are written again right after.
     *
     * Returns false when the server could not be reached or the permission is missing, so the
     * caller can retry instead of marking the migration done and leaving duplicates behind.
     */
    suspend fun deleteAllOwnSleepSessions(): Boolean {
        return try {
            val hc = requireClient()
            if (!hasPermission(SleepSessionRecord::class)) return false
            hc.deleteRecords(
                SleepSessionRecord::class,
                emptyList(),
                listOf(context.packageName),
            )
            Log.d(TAG, "Deleted all sleep records written by this app")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete legacy sleep records", e)
            false
        }
    }

    private suspend fun hasPermission(recordClass: kotlin.reflect.KClass<out androidx.health.connect.client.records.Record>): Boolean =
        requireClient().permissionController.getGrantedPermissions()
            .contains(HealthPermission.getWritePermission(recordClass))

    /**
     * Reads recent exercise sessions from Health Connect and returns the exercise types
     * ordered by most-recent first (deduplicated).
     * Returns empty list if the API is not available in this version.
     */
    suspend fun getRecentExerciseTypes(limit: Int = 8): List<Int> {
        // Health Connect 1.1.0 does not expose queryExerciseSessions.
        // This is a nice-to-have; the bottom sheet has all types available.
        return emptyList()
    }

    /**
     * Writes skin temperature records to Health Connect.
     *
     * Each record covers one local day part and uses the baseline frozen when that day was first
     * aggregated. Re-syncing a day therefore replaces its deltas without moving the baseline.
     * The interval spans the first and last valid reading, and every valid reading contributes
     * exactly one delta, so the delta count always matches the measurements in the interval.
     */
    suspend fun writeSkinTemperatureRecords(
        records: List<RecordItem>,
        aggregates: List<TemperatureDayAggregate>,
    ): Int {
        return try {
            val hc = requireClient()

            if (!isSkinTemperatureAvailable()) {
                Log.w(TAG, "Skin temperature feature not available on this device")
                return 0
            }

            if (!hc.permissionController.getGrantedPermissions().contains(
                HealthPermission.getWritePermission(SkinTemperatureRecord::class)
            )) {
                Log.w(TAG, "Skin Temperature Write permission not granted — skipping temperature write")
                return 0
            }

            if (records.isEmpty() || aggregates.isEmpty()) return 0

            val zone = ZoneId.systemDefault()
            val aggregatesByDate = aggregates.associateBy { it.date }
            val groups = records
                .asSequence()
                .filter { it.value >= MIN_VALID_TEMPERATURE }
                .distinctBy { it.tStart }
                .sortedBy { it.tStart }
                .groupBy { record ->
                    val local = Instant.ofEpochSecond(record.tStart).atZone(zone)
                    TemperatureGroupKey(
                        date = local.toLocalDate(),
                        period = TemperaturePeriod.fromHour(local.hour),
                    )
                }

            val healthRecords = groups.mapNotNull { (key, readings) ->
                val aggregate = aggregatesByDate[key.date]
                val baseline = aggregate?.baselineCelsius(key.period)
                if (baseline == null) {
                    Log.w(TAG, "No frozen baseline for ${key.date} ${key.period} — skipping")
                    return@mapNotNull null
                }
                val sorted = readings.sortedBy { it.tStart }
                // The oldest raw day may be truncated by the retention cutoff. Never replace a
                // previously written record with fewer deltas than the aggregate already holds.
                val aggregateCount = key.period.hours.sumOf { aggregate.hourCount(it) }
                if (sorted.size < aggregateCount) {
                    Log.d(
                        TAG,
                        "Skipping truncated ${key.date} ${key.period}: " +
                            "${sorted.size}/$aggregateCount readings",
                    )
                    return@mapNotNull null
                }
                Log.d(
                    TAG,
                    "Skin temperature ${key.date} ${key.period}: " +
                        "baseline=${"%.2f".format(baseline)}°C, deltas=${sorted.size}",
                )
                createSkinTemperatureRecord(key, sorted, baseline)
            }

            if (healthRecords.isEmpty()) {
                Log.d(TAG, "No complete temperature groups to write")
                return 0
            }

            Log.d(TAG, "insertRecords: ${healthRecords.size} skin temperature records")
            return try {
                hc.insertRecords(healthRecords)
                Log.d(TAG, "Wrote ${healthRecords.size} skin temperature records to Health Connect")
                healthRecords.size
            } catch (e: Exception) {
                Log.e(TAG, "Failed to insert ${healthRecords.size} skin temperature records: ${e.message}", e)
                for ((i, record) in healthRecords.withIndex()) {
                    Log.e(
                        TAG,
                        "  Record $i: startTime=${record.startTime}, " +
                            "endTime=${record.endTime}, baseline=${record.baseline}, " +
                            "deltas=${record.deltas.size}",
                    )
                }
                0
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write skin temperature records to Health Connect", e)
            0
        }
    }

    private data class TemperatureGroupKey(
        val date: LocalDate,
        val period: TemperaturePeriod,
    )

    private fun createSkinTemperatureRecord(
        key: TemperatureGroupKey,
        readings: List<RecordItem>,
        periodBaseline: Double,
    ): SkinTemperatureRecord {
        val startEpochSecond = readings.first().tStart
        val lastDeltaEpochSecond = readings.last().tStart
        // Health Connect requires the interval to end strictly after the final delta. The final
        // reading's tEnd normally provides that, but fall back to one second past the timestamp
        // for records with a zero-length end.
        val endEpochSecond = maxOf(
            readings.maxOf { maxOf(it.tEnd, it.tStart + TEMPERATURE_SAMPLE_SECONDS) },
            lastDeltaEpochSecond + 1,
        )
        val startInstant = Instant.ofEpochSecond(startEpochSecond)
        val endInstant = Instant.ofEpochSecond(endEpochSecond)
        val metadata = createMetadata(startInstant, endInstant, temperatureRecordIdFor(key))

        val deltas = readings.map { record ->
            val watchTempCelsius = record.value / 100.0
            val deltaCelsius = watchTempCelsius - periodBaseline
            // Health Connect requires deltas to fall strictly inside the record interval.
            val deltaTime = Instant.ofEpochSecond(record.tStart)
                .coerceIn(startInstant.plusNanos(1), endInstant.minusNanos(1))
            SkinTemperatureRecord.Delta(
                deltaTime,
                TemperatureDelta.celsius(deltaCelsius),
            )
        }

        return SkinTemperatureRecord(
            startTime = startInstant,
            startZoneOffset = null,
            endTime = endInstant,
            endZoneOffset = null,
            metadata = metadata,
            deltas = deltas,
            baseline = Temperature.celsius(periodBaseline),
            measurementLocation = SkinTemperatureRecord.MEASUREMENT_LOCATION_WRIST,
        )
    }

    private fun temperatureRecordIdFor(key: TemperatureGroupKey): String =
        UUID.nameUUIDFromBytes("freellee_temp_${key.date}:${key.period}".toByteArray()).toString()

    /**
     * One-time cleanup for skin temperature records written before ids became UUIDs. Those
     * records cannot be addressed individually, so every skin temperature record previously
     * written by this app is removed before the retained readings are written again.
     */
    suspend fun deleteAllOwnSkinTemperatureRecords(): Boolean {
        return try {
            val hc = requireClient()
            if (!isSkinTemperatureAvailable()) return false
            if (!hasPermission(SkinTemperatureRecord::class)) return false
            Log.d(TAG, "Deleting legacy skin temperature records")
            hc.deleteRecords(
                SkinTemperatureRecord::class,
                emptyList(),
                listOf(context.packageName),
            )
            Log.d(TAG, "Deleted all skin temperature records written by this app")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete legacy skin temperature records", e)
            false
        }
    }

    private fun createMetadata(
        startTime: Instant,
        endTime: Instant,
        clientRecordId: String? = null,
    ): Metadata {
        val device = Device(
            type = Device.TYPE_WATCH,
            manufacturer = "Ollee Watch",
            model = DEFAULT_WATCH_NAME,
        )
        return if (clientRecordId != null) {
            Metadata.activelyRecorded(
                clientRecordId = clientRecordId,
                clientRecordVersion = 1L,
                device = device,
            )
        } else {
            Metadata.activelyRecorded(device = device)
        }
    }

    /**
     * Writes a counter event to Health Connect.
     *
     * Converts the counter event using [CounterEventWriter.convert] and inserts
     * the resulting record into Health Connect.
     */
    suspend fun writeCounterEvent(
        tStart: Long,
        value: Int,
        meaningType: String,
    ): Boolean {
        try {
            val hc = requireClient()

            val record = CounterEventWriter.convert(value, tStart, meaningType)
                ?: run {
                    Log.w(TAG, "writeCounterEvent: unknown meaning type $meaningType")
                    return false
                }

            // Only the permission for this specific record type is required.
            val writePermission = HealthPermission.getWritePermission(record::class)
            if (writePermission !in hc.permissionController.getGrantedPermissions()) {
                Log.w(TAG, "Counter event write permission not granted ($writePermission) — skipping")
                return false
            }

            hc.insertRecords(listOf(record))
            Log.d(TAG, "Wrote counter event: type=$meaningType, value=$value, tStart=$tStart")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write counter event to Health Connect", e)
            return false
        }
    }
}
