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
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Temperature
import androidx.health.connect.client.units.TemperatureDelta
import link.hector.freellee.DEFAULT_WATCH_NAME
import link.hector.freellee.data.DeviceStore.RecordItem
import link.hector.freellee.data.DeviceStore.StepInterval
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
            
            for (record in records) {
                Log.d(TAG, "  [${formatTime(record.startTime)} – ${formatTime(record.endTime)}] ${record.count} steps")
            }
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
            
            for (record in healthRecords) {
                val bpm = record.samples.firstOrNull()?.beatsPerMinute ?: 0
                Log.d(TAG, "  [${formatTime(record.startTime)}] $bpm bpm")
            }
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
     * Temperature values from the watch are in centi-degrees Celsius.
     * We use baseline to store the absolute temperature reading.
     * Deltas are calculated from consecutive records in the list.
     */
    suspend fun writeSkinTemperatureRecords(
        records: List<RecordItem>,
    ) {
        try {
            val hc = requireClient()

            if (!isSkinTemperatureAvailable()) {
                Log.w(TAG, "Skin temperature feature not available on this device")
                return
            }

            if (!hc.permissionController.getGrantedPermissions().contains(
                HealthPermission.getWritePermission(SkinTemperatureRecord::class)
            )) {
                Log.w(TAG, "Skin Temperature Write permission not granted — skipping temperature write")
                return
            }

            if (records.isEmpty()) return

            // Sort records by tStart to ensure proper delta calculation
            val sortedRecords = records.sortedBy { it.tStart }

            // Compute PER-GROUP baselines from records strictly before each group's time range
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val yesterdayEnd = today.minusDays(1).atTime(23, 59, 59).atZone(zone).toInstant()

            val baselineRecords = sortedRecords.filter { it.tStart <= yesterdayEnd.epochSecond }

            if (baselineRecords.isEmpty()) {
                Log.d(TAG, "No records available for baseline calculation, skipping temperature write")
                return
            }

            // Group records into night/day buckets
            val groups = groupTemperatureRecords(sortedRecords)
            Log.d(TAG, "Temperature groups after trimming boundaries: ${groups.size}")

            // Create a SkinTemperatureRecord for each group, using the period-specific baseline
            val healthRecords = groups.flatMap { group ->
                // Baseline = median of records strictly before this group, for the same period
                val periodRecords = baselineRecords.filter { record ->
                    record.tStart < group.tStart &&
                        Period.fromHour(
                            Instant.ofEpochSecond(record.tStart)
                                .atZone(zone).toLocalDateTime().toLocalTime().hour
                        ) == group.period
                }
                val baseline = if (periodRecords.isEmpty()) {
                    null
                } else {
                    val values = periodRecords.map { it.value / 100.0 }.sorted()
                    val mid = values.size / 2
                    if (values.size % 2 == 0) {
                        (values[mid - 1] + values[mid]) / 2.0
                    } else {
                        values[mid]
                    }
                }

                if (baseline == null) {
                    Log.w(TAG, "No baseline for ${group.period} ${group.tStart}, skipping group")
                    emptyList()
                } else {
                    Log.d(TAG, "${group.period} baseline: ${"%.2f".format(baseline)}°C from ${periodRecords.size} records")
                    createSkinTemperatureRecord(group, baseline)
                }
            }

            if (healthRecords.isEmpty()) {
                Log.d(TAG, "No complete temperature groups to write")
                return
            }

            Log.d(TAG, "insertRecords: ${healthRecords.size} skin temperature records")
            try {
                hc.insertRecords(healthRecords)
                Log.d(TAG, "Wrote ${healthRecords.size} skin temperature records to Health Connect")

                for (record in healthRecords) {
                    val temp = record.baseline?.inCelsius ?: 0.0
                    Log.d(TAG, "  [${formatTime(record.startTime)}] $temp°C baseline, ${record.deltas.size} deltas")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to insert ${healthRecords.size} skin temperature records: ${e.message}", e)
                for ((i, record) in healthRecords.withIndex()) {
                    Log.e(TAG, "  Record $i: startTime=${record.startTime}, endTime=${record.endTime}, baseline=${record.baseline}, deltas=${record.deltas}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write skin temperature records to Health Connect", e)
        }
    }


    enum class Period(val order: Int) {
        NIGHT(0), MORNING(1), AFTERNOON(2), EVENING(3);

        fun getBoundaries(date: LocalDate, zone: ZoneId): Pair<Instant, Instant> {
            val start = when (this) {
                NIGHT -> date.atTime(0, 0).atZone(zone).toInstant()
                MORNING -> date.atTime(6, 0).atZone(zone).toInstant()
                AFTERNOON -> date.atTime(12, 0).atZone(zone).toInstant()
                EVENING -> date.atTime(18, 0).atZone(zone).toInstant()
            }
            val end = when (this) {
                NIGHT -> date.atTime(6, 0).atZone(zone).toInstant()
                MORNING -> date.atTime(12, 0).atZone(zone).toInstant()
                AFTERNOON -> date.atTime(18, 0).atZone(zone).toInstant()
                EVENING -> date.plusDays(1).atTime(0, 0).atZone(zone).toInstant()
            }
            return start to end
        }

        companion object {
            fun fromHour(hour: Int): Period = when (hour) {
                in 0..5 -> NIGHT
                in 6..11 -> MORNING
                in 12..17 -> AFTERNOON
                in 18..23 -> EVENING
                else -> throw IllegalArgumentException("Invalid hour: $hour")
            }
        }
    }

    data class GroupedTempRecord(
        val tStart: Long,
        val tEnd: Long,
        val period: Period,
        val readings: List<RecordItem>,
        val stableId: String,
    )

    private fun createSkinTemperatureRecord(
        group: GroupedTempRecord,
        periodBaseline: Double,
    ): List<SkinTemperatureRecord> {
        val sorted = group.readings.sortedBy { it.tStart }
        val startInstant = Instant.ofEpochSecond(group.tStart)
        val endInstant = Instant.ofEpochSecond(group.tEnd)
        val metadata = createMetadata(startInstant, endInstant, group.stableId)

        // Calculate deltas as deviation from period baseline
        val deltaValues = mutableListOf<Double>()
        val deltas = sorted.map { record ->
            val watchTempCelsius = record.value / 100.0
            val deltaCelsius = watchTempCelsius - periodBaseline
            deltaValues.add(deltaCelsius)

            // Clamp to be strictly inside the parent time range
            val deltaEnd = Instant.ofEpochSecond(record.tStart)
                .coerceIn(startInstant.plusNanos(1), endInstant.minusNanos(1))

            SkinTemperatureRecord.Delta(
                deltaEnd,
                TemperatureDelta.celsius(deltaCelsius)
            )
        }

        val mainRecord = SkinTemperatureRecord(
            startTime = startInstant,
            startZoneOffset = null,
            endTime = endInstant,
            endZoneOffset = null,
            metadata = metadata,
            deltas = deltas,
            baseline = Temperature.celsius(periodBaseline),
            measurementLocation = SkinTemperatureRecord.MEASUREMENT_LOCATION_WRIST
        )

        // For evening groups, add bridge and copy records for smooth graph transition
        if (group.period == Period.EVENING) {
            val avgDelta = deltaValues.average()
            Log.d(TAG, "Evening→Night bridge: avgDelta=$avgDelta°C, tEnd=${formatTime(endInstant)}")

            val bridgeStart = endInstant.minusSeconds(60)
            val bridgeEnd = endInstant.minusSeconds(1)
            val bridgeId = stableIdForGroup(bridgeStart.epochSecond, bridgeEnd.epochSecond, group.period)
            val bridgeRecord = SkinTemperatureRecord(
                startTime = bridgeStart,
                startZoneOffset = null,
                endTime = bridgeEnd,
                endZoneOffset = null,
                metadata = createMetadata(bridgeStart, bridgeEnd, bridgeId),
                deltas = listOf(
                    SkinTemperatureRecord.Delta(
                        bridgeStart,
                        TemperatureDelta.celsius(avgDelta)
                    )
                ),
                baseline = Temperature.celsius(periodBaseline),
                measurementLocation = SkinTemperatureRecord.MEASUREMENT_LOCATION_WRIST
            )

            val copyStart = endInstant
            val copyEnd = endInstant.plusSeconds(1)
            val copyId = stableIdForGroup(copyStart.epochSecond, copyEnd.epochSecond, group.period)
            val copyRecord = SkinTemperatureRecord(
                startTime = copyStart,
                startZoneOffset = null,
                endTime = copyEnd,
                endZoneOffset = null,
                metadata = createMetadata(copyStart, copyEnd, copyId),
                deltas = listOf(
                    SkinTemperatureRecord.Delta(
                        copyStart,
                        TemperatureDelta.celsius(avgDelta)
                    )
                ),
                baseline = Temperature.celsius(periodBaseline),
                measurementLocation = SkinTemperatureRecord.MEASUREMENT_LOCATION_WRIST
            )

            return listOf(mainRecord, bridgeRecord, copyRecord)
        }

        return listOf(mainRecord)
    }

    private fun groupTemperatureRecords(
        sortedRecords: List<RecordItem>,
    ): List<GroupedTempRecord> {
        val zone = ZoneId.systemDefault()

        val allGroups = sortedRecords
            .groupBy { record ->
                val localDateTime = Instant.ofEpochSecond(record.tStart)
                    .atZone(zone).toLocalDateTime()
                val date = localDateTime.toLocalDate()
                val hour = localDateTime.toLocalTime().hour
                val period = Period.fromHour(hour)
                date to period
            }
            .map { (key, records) ->
                val (date, period) = key
                val (startInstant, endInstant) = period.getBoundaries(date, zone)
                val stableId = stableIdForGroup(startInstant.epochSecond, endInstant.epochSecond, period)
                GroupedTempRecord(
                    tStart = startInstant.epochSecond,
                    tEnd = endInstant.epochSecond,
                    period = period,
                    readings = records,
                    stableId = stableId,
                )
            }
            .sortedBy { it.tStart }

        // Skip first group (truncated by 15-day cutoff) and last group (still being built)
        return if (allGroups.size > 2) allGroups.drop(1).dropLast(1) else emptyList()
    }

    /**
     * Generates a stable client record ID based on the time range and period.
     * This is used as `clientRecordId` in Metadata for Health Connect deduplication.
     */
    private fun stableIdForGroup(tStart: Long, tEnd: Long, period: Period): String {
        return "$tStart:$tEnd:$period"
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

    private fun formatTime(instant: Instant): String {
        val formatter = DateTimeFormatter.ofPattern("HH:mm")
            .withZone(ZoneId.systemDefault())
        return formatter.format(instant)
    }
}
