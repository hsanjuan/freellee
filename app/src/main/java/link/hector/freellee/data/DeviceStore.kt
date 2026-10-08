package link.hector.freellee.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "DeviceStore"

private val Context.deviceStore: DataStore<Preferences> by preferencesDataStore(name = "devices")

data class PairedDevice(
    val macAddress: String,
    val name: String,
    val firmwareVersion: String?,
    val isActive: Boolean,
    val lastSyncedStepCount: Long?,
    val voltageMv: Int? = null,
)

/**
 * Persistent store for paired Ollee watches.
 * Uses DataStore preferences for simple key-value storage.
 */
class DeviceStore(private val context: Context) {

    companion object {
        private val ACTIVE_KEY = stringPreferencesKey("active_device")
        private val TEMP_RECORDS_KEY = stringPreferencesKey("temp_records")
        private val HR_RECORDS_KEY = stringPreferencesKey("hr_records")
        private val COUNTER_RECORDS_KEY = stringPreferencesKey("counter_records")
        private val STOPWATCH_RECORDS_KEY = stringPreferencesKey("stopwatch_records")
        private val PENDING_ACTIVITIES_KEY = stringPreferencesKey("pending_activities")
        private val LAST_EXERCISE_TYPE_KEY = intPreferencesKey("last_exercise_type")
        private val LAST_SYNC_TIME_KEY = longPreferencesKey("last_sync_time")
        private const val VOLTAGE_PREFIX = "voltage_"
    }

    /** Get the currently active device's MAC address. */
    val activeDeviceMac: Flow<String?> = context.deviceStore.data
        .map { prefs -> prefs[ACTIVE_KEY] }

    /** Get the stored name for a specific device by MAC address. */
    fun getDeviceName(macAddress: String): Flow<String?> = context.deviceStore.data
        .map { prefs -> prefs[prefKey("name", macAddress)] }

    /** Add or update a paired device, and mark it as active. */
    suspend fun addOrUpdate(
        macAddress: String,
        name: String,
        firmwareVersion: String?,
        lastSyncedStepCount: Long?,
        voltageMv: Int? = null,
    ) {
        context.deviceStore.edit { prefs ->
            prefs[prefKey("name", macAddress)] = name
            if (firmwareVersion != null) {
                prefs[prefKey("firmware", macAddress)] = firmwareVersion
            } else {
                prefs.remove(prefKey("firmware", macAddress))
            }
            prefs[ACTIVE_KEY] = macAddress
            if (lastSyncedStepCount != null) {
                prefs[prefLongKey("steps", macAddress)] = lastSyncedStepCount
            } else {
                prefs.remove(prefLongKey("steps", macAddress))
            }
            if (voltageMv != null) {
                prefs[stringPreferencesKey(VOLTAGE_PREFIX + macAddress)] = voltageMv.toString()
            } else {
                prefs.remove(stringPreferencesKey(VOLTAGE_PREFIX + macAddress))
            }
        }
    }

    /** Remove a device by MAC address. If it was active, clear the active flag. */
    suspend fun remove(macAddress: String): Boolean {
        var wasActive = false
        context.deviceStore.edit { prefs ->
            if (prefs[ACTIVE_KEY] == macAddress) {
                prefs.remove(ACTIVE_KEY)
                wasActive = true
            }
            prefs.remove(prefKey("name", macAddress))
            prefs.remove(prefKey("firmware", macAddress))
            prefs.remove(prefLongKey("steps", macAddress))
            prefs.remove(stringPreferencesKey(VOLTAGE_PREFIX + macAddress))
        }
        return wasActive
    }

    /** Get all paired devices (active device only). */
    fun getAllPairedDevices(): Flow<List<PairedDevice>> = context.deviceStore.data
        .map { prefs ->
            val activeMac = prefs[ACTIVE_KEY]
            if (activeMac == null) emptyList() else listOf(
                PairedDevice(
                    macAddress = activeMac,
                    name = prefs[prefKey("name", activeMac)] ?: "Unknown",
                    firmwareVersion = prefs[prefKey("firmware", activeMac)],
                    isActive = true,
                    lastSyncedStepCount = try {
                        prefs[prefLongKey("steps", activeMac)]
                    } catch (_: Exception) {
                        null
                    },
                    voltageMv = try {
                        prefs[stringPreferencesKey(VOLTAGE_PREFIX + activeMac)]?.toIntOrNull()
                    } catch (_: Exception) {
                        null
                    },
                )
            )
        }
        .flowOn(Dispatchers.Default)

    /** Set which paired device is currently active. */
    suspend fun setActiveDevice(macAddress: String) {
        context.deviceStore.edit { prefs ->
            prefs[ACTIVE_KEY] = macAddress
        }
    }

    /** Persist temperature, heart rate, counter, and stopwatch records for the active device. */
    suspend fun saveRecentRecords(
        temperature: List<RecordItem>,
        heartRate: List<RecordItem>,
        counter: List<RecordItem> = emptyList(),
        stopwatch: List<RecordItem> = emptyList(),
    ) {
        val uniqueTemp = temperature.distinctBy { it.tStart }
        val uniqueHr = heartRate.distinctBy { it.tStart }
        val uniqueCounter = counter.distinctBy { it.tStart }
        val uniqueStopwatch = stopwatch.distinctBy { it.tStart }

        context.deviceStore.edit { prefs ->
            val existingTempRecords = parseRecordItems(prefs[TEMP_RECORDS_KEY])
            val existingHrRecords = parseRecordItems(prefs[HR_RECORDS_KEY])
            val existingCounterRecords = parseRecordItems(prefs[COUNTER_RECORDS_KEY])
            val existingStopwatchRecords = parseRecordItems(prefs[STOPWATCH_RECORDS_KEY])

            val mergedTemp = existingTempRecords.mergeWith(uniqueTemp)
            val mergedHr = existingHrRecords.mergeWith(uniqueHr)
            val mergedCounter = existingCounterRecords.mergeWith(uniqueCounter)
            val mergedStopwatch = existingStopwatchRecords.mergeWith(uniqueStopwatch)

            Log.d(TAG, "saveRecentRecords: temp ${existingTempRecords.size} + ${uniqueTemp.size} -> ${mergedTemp.size}, hr ${existingHrRecords.size} + ${uniqueHr.size} -> ${mergedHr.size}, counter ${existingCounterRecords.size} + ${uniqueCounter.size} -> ${mergedCounter.size}, stopwatch ${existingStopwatchRecords.size} + ${uniqueStopwatch.size} -> ${mergedStopwatch.size}")
            if (uniqueCounter.isNotEmpty()) {
                Log.d(TAG, "saveRecentRecords: counter items: ${uniqueCounter.map { "type=${it.type} tStart=${it.tStart} value=${it.value}" }}")
            }
            if (uniqueStopwatch.isNotEmpty()) {
                Log.d(TAG, "saveRecentRecords: stopwatch items: ${uniqueStopwatch.map { "type=${it.type} tStart=${it.tStart} tEnd=${it.tEnd} value=${it.value}" }}")
            }

            prefs[TEMP_RECORDS_KEY] = recordsToJson(mergedTemp)
            prefs[HR_RECORDS_KEY] = recordsToJson(mergedHr)
            prefs[COUNTER_RECORDS_KEY] = recordsToJson(mergedCounter)
            prefs[STOPWATCH_RECORDS_KEY] = recordsToJson(mergedStopwatch)
        }
    }

    /** Keep [this] records and append [incoming] ones whose start time is not already present. */
    private fun List<RecordItem>.mergeWith(incoming: List<RecordItem>): List<RecordItem> {
        val existingKeys = map { it.tStart }.toSet()
        return this + incoming.filter { it.tStart !in existingKeys }
    }

    private fun recordsToJson(items: List<RecordItem>): String = listToJson(items) { item, obj ->
        obj.put("type", item.type)
        obj.put("tStart", item.tStart)
        obj.put("tEnd", item.tEnd)
        obj.put("value", item.value)
        obj.put("syncTime", item.syncTime)
    }

    private fun parseRecordItems(json: String?): List<RecordItem> {
        if (json == null) return emptyList()
        return parseJsonArray(json) { obj ->
            RecordItem(
                type = obj.getInt("type"),
                tStart = obj.getLong("tStart"),
                tEnd = obj.getLong("tEnd"),
                value = obj.getInt("value"),
                syncTime = if (obj.has("syncTime")) obj.getLong("syncTime") else 0L
            )
        }
    }

    /** Load cached temperature records. */
    fun getCachedTempRecords(): Flow<List<RecordItem>> = preferencesFlow(TEMP_RECORDS_KEY, ::parseRecordItems)

    /** Load cached heart rate records. */
    fun getCachedHRRecords(): Flow<List<RecordItem>> = preferencesFlow(HR_RECORDS_KEY, ::parseRecordItems)

    /** Load cached counter (type 3) records. */
    fun getCachedCounterRecords(): Flow<List<RecordItem>> = preferencesFlow(COUNTER_RECORDS_KEY, ::parseRecordItems)

    /** Load cached stopwatch (type 4) records. */
    fun getCachedStopwatchRecords(): Flow<List<RecordItem>> = preferencesFlow(STOPWATCH_RECORDS_KEY, ::parseRecordItems)

    data class RecordItem(
        val type: Int,
        val tStart: Long,
        val tEnd: Long,
        val value: Int,
        val syncTime: Long = 0L
    )

    /** Persist step intervals for a device on a specific date. */
    suspend fun saveStepIntervals(macAddress: String, dateKey: String, intervals: List<StepInterval>) {
        Log.d(TAG, "saveStepIntervals: mac=$macAddress, date=$dateKey, count=${intervals.size}")
        intervals.forEachIndexed { i, it ->
            Log.d(TAG, "  save interval[$i]: tStart=${it.tStart}, steps=${it.steps}")
        }
        context.deviceStore.edit { prefs ->
            val existingJson = prefs[prefKey("step_intervals", macAddress)]?.let { json ->
                parseJsonArray(json) { obj ->
                    StepInterval(
                        tStart = obj.getLong("tStart"),
                        tEnd = obj.getLong("tEnd"),
                        steps = obj.getInt("steps"),
                        syncTimestamp = obj.optLong("syncTimestamp", 0)
                    )
                }
            } ?: emptyList()

            Log.d(TAG, "saveStepIntervals: existing=${existingJson.size}, merging ${intervals.size} new")
            val merged = (existingJson + intervals).distinctBy { it.tStart to it.tEnd to it.steps }
            Log.d(TAG, "saveStepIntervals: after dedup -> total=${merged.size}")
            prefs[prefKey("step_intervals", macAddress)] = listToJson(merged) { interval, obj ->
                obj.put("tStart", interval.tStart)
                obj.put("tEnd", interval.tEnd)
                obj.put("steps", interval.steps)
                if (interval.syncTimestamp > 0) {
                    obj.put("syncTimestamp", interval.syncTimestamp)
                }
            }
        }
    }

    /** Get all step intervals for a device. */
    fun getStepIntervalsForDevice(macAddress: String): Flow<List<StepInterval>> =
        preferencesFlow(prefKey("step_intervals", macAddress)) { json ->
            Log.d(TAG, "getStepIntervalsForDevice: mac=$macAddress, jsonLen=${json?.length ?: 0}")
            if (json == null) {
                Log.d(TAG, "getStepIntervalsForDevice: no data for mac=$macAddress")
                emptyList()
            } else {
                val result = parseJsonArray(json) { obj ->
                    StepInterval(
                        tStart = obj.getLong("tStart"),
                        tEnd = obj.getLong("tEnd"),
                        steps = obj.getInt("steps"),
                        syncTimestamp = obj.optLong("syncTimestamp", 0)
                    )
                }
                Log.d(TAG, "getStepIntervalsForDevice: mac=$macAddress, parsed ${result.size} intervals, first=${result.firstOrNull()}, last=${result.lastOrNull()}")
                result
            }
        }

    /** Get all step intervals across all paired devices. */
    fun getAllStepIntervalsAcrossDevices(): Flow<List<StepInterval>> = context.deviceStore.data
        .map { prefs ->
            val list = mutableListOf<StepInterval>()
            var scanCount = 0
            for ((key, value) in prefs.asMap()) {
                if (key.name.startsWith("step_intervals_")) {
                    scanCount++
                    val strValue = value as? String
                    Log.d(TAG, "getAllStepIntervalsAcrossDevices: found key=$key, valLen=${strValue?.length ?: 0}")
                    if (strValue == null) {
                        Log.w(TAG, "getAllStepIntervalsAcrossDevices: skipping non-string value for key=$key")
                        continue
                    }
                    try {
                        parseJsonArray(strValue) { obj ->
                            StepInterval(
                                tStart = obj.getLong("tStart"),
                                tEnd = obj.getLong("tEnd"),
                                steps = obj.getInt("steps"),
                                syncTimestamp = obj.optLong("syncTimestamp", 0)
                            )
                        }.let { list.addAll(it) }
                    } catch (e: Exception) {
                        Log.w(TAG, "getAllStepIntervalsAcrossDevices: failed to parse key=$key: ${e.message}")
                    }
                }
            }
            val distinct = list.distinctBy { it.tStart to it.tEnd to it.steps }
            Log.d(TAG, "getAllStepIntervalsAcrossDevices: scanned $scanCount keys, got ${list.size} raw -> ${distinct.size} distinct")
            distinct
        }
        .flowOn(Dispatchers.Default)

    data class StepInterval(
        val tStart: Long,
        val tEnd: Long,
        val steps: Int,
        val syncTimestamp: Long = 0
    )

    /** Get processed interval IDs for a device on a specific date. */
    fun getProcessedIntervalKeys(macAddress: String, dateKey: String): Flow<Set<String>> =
        preferencesFlow(stringPreferencesKey("processed_steps_${macAddress}_$dateKey")) { json ->
            if (json == null) emptySet() else parseJsonArray(json) { obj ->
                obj.getString("value")
            }.toSet()
        }

    /** Mark intervals as processed (append to existing set). */
    suspend fun markIntervalsAsProcessed(macAddress: String, dateKey: String, keys: Set<String>) {
        context.deviceStore.edit { prefs ->
            val keyName = "processed_steps_${macAddress}_${dateKey}"
            val existingJson = prefs[stringPreferencesKey(keyName)]?.let { json ->
                parseJsonArray(json) { obj ->
                    obj.getString("value")
                }.toSet()
            } ?: emptySet()

            val merged = existingJson + keys
            prefs[stringPreferencesKey(keyName)] = listToJson(merged.toList()) { key, obj ->
                obj.put("value", key)
            }
        }
    }

    /** Clear all paired devices (factory reset). */
    suspend fun clearAll() {
        context.deviceStore.edit { prefs ->
            prefs.clear()
        }
    }

    // -- Last used exercise type --

    /** Persist the most recently used exercise type, used for quick swipe-tagging. */
    suspend fun setLastExerciseType(type: Int) {
        context.deviceStore.edit { prefs -> prefs[LAST_EXERCISE_TYPE_KEY] = type }
    }

    /** The most recently used exercise type, or null if none has been chosen yet. */
    val lastExerciseType: Flow<Int?> = context.deviceStore.data
        .map { prefs -> prefs[LAST_EXERCISE_TYPE_KEY] }

    // -- Pending Activities --

    data class PendingActivity(
        val id: String,
        val tStart: Long,
        val tEnd: Long,
        val durationMillis: Int,
        val syncTime: Long = 0L,
        val assignedExerciseType: Int? = null,
    )

    /** Get all pending activities, ordered newest first. Deduplicates by ID. */
    fun getPendingActivities(): Flow<List<PendingActivity>> = preferencesFlow(PENDING_ACTIVITIES_KEY) { json ->
        Log.d(TAG, "getPendingActivities: json=${json?.take(100)}")
        parsePendingActivities(json).distinctBy { it.id }.sortedByDescending { it.tStart }
    }

    /** Add a pending activity (newest first). Skips if ID already exists. */
    suspend fun addPendingActivity(activity: PendingActivity) {
        Log.d(TAG, "addPendingActivity: id=${activity.id}, tStart=${activity.tStart}, tEnd=${activity.tEnd}")
        context.deviceStore.edit { prefs ->
            val existing = parsePendingActivities(prefs[PENDING_ACTIVITIES_KEY])

            // Skip if ID already exists
            if (existing.any { it.id == activity.id }) {
                Log.d(TAG, "addPendingActivity: skipping duplicate id=${activity.id}")
                return@edit
            }

            prefs[PENDING_ACTIVITIES_KEY] = pendingActivitiesToJson(existing + activity)
        }
    }

    /** Remove a pending activity by ID. */
    suspend fun removePendingActivity(id: String) {
        Log.d(TAG, "removePendingActivity: id=$id")
        context.deviceStore.edit { prefs ->
            val existing = parsePendingActivities(prefs[PENDING_ACTIVITIES_KEY])
            prefs[PENDING_ACTIVITIES_KEY] = pendingActivitiesToJson(existing.filter { it.id != id })
        }
    }

    /** Update an existing pending activity in place (by ID). */
    suspend fun updatePendingActivity(activity: PendingActivity) {
        Log.d(TAG, "updatePendingActivity: id=${activity.id}")
        context.deviceStore.edit { prefs ->
            val existing = parsePendingActivities(prefs[PENDING_ACTIVITIES_KEY])
            val updated = existing.map { if (it.id == activity.id) activity else it }
            prefs[PENDING_ACTIVITIES_KEY] = pendingActivitiesToJson(updated)
        }
    }

    private fun parsePendingActivities(json: String?): List<PendingActivity> {
        if (json == null) return emptyList()
        return parseJsonArray(json) { obj ->
            PendingActivity(
                id = obj.getString("id"),
                tStart = obj.getLong("tStart"),
                tEnd = obj.getLong("tEnd"),
                durationMillis = obj.getInt("durationMillis"),
                syncTime = if (obj.has("syncTime")) obj.getLong("syncTime") else 0L,
                assignedExerciseType = if (obj.has("assignedExerciseType")) obj.getInt("assignedExerciseType") else null,
            )
        }
    }

    private fun pendingActivitiesToJson(items: List<PendingActivity>): String = listToJson(items) { act, obj ->
        obj.put("id", act.id)
        obj.put("tStart", act.tStart)
        obj.put("tEnd", act.tEnd)
        obj.put("durationMillis", act.durationMillis)
        obj.put("syncTime", act.syncTime)
        if (act.assignedExerciseType != null) {
            obj.put("assignedExerciseType", act.assignedExerciseType)
        }
    }

    /** Clear all pending activities. */
    suspend fun clearPendingActivities() {
        Log.d(TAG, "clearPendingActivities")
        context.deviceStore.edit { prefs ->
            prefs.remove(PENDING_ACTIVITIES_KEY)
        }
    }

    /** Persist the timestamp of the last successful sync. */
    suspend fun saveLastSyncTime(epochSeconds: Long) {
        context.deviceStore.edit { prefs ->
            prefs[LAST_SYNC_TIME_KEY] = epochSeconds
        }
    }

    /** Load the timestamp of the last successful sync (epoch seconds). */
    fun getLastSyncTime(): Flow<Long?> = context.deviceStore.data
        .map { prefs -> prefs[LAST_SYNC_TIME_KEY] }

    // -- Internal helpers --

    /**
     * Read a single string preference and decode it, running [parse] only when the raw
     * value actually changes.
     *
     * DataStore re-emits the whole preferences map on every write, so a change to one key
     * would otherwise cause every other key's JSON to be parsed again. Comparing the raw
     * (immutable) string first avoids that redundant work.
     */
    private fun <T> preferencesFlow(
        key: Preferences.Key<String>,
        parse: (String?) -> T,
    ): Flow<T> = context.deviceStore.data
        .map { prefs -> prefs[key] }
        .distinctUntilChanged()
        .map(parse)
        .flowOn(Dispatchers.Default)

    private fun prefKey(prefix: String, mac: String) = stringPreferencesKey("$prefix$mac")
    private fun prefLongKey(prefix: String, mac: String) = longPreferencesKey("$prefix$mac")

    private inline fun <T> parseJsonArray(json: String, crossinline factory: (JSONObject) -> T): List<T> {
        return try {
            val array = JSONArray(json)
            val list = mutableListOf<T>()
            for (i in 0 until array.length()) {
                val value = array.get(i)
                if (value == JSONObject.NULL || value !is JSONObject) continue
                list.add(factory(value))
            }
            list
        } catch (e: Exception) {
            Log.w(TAG, "parseJsonArray failed: ${e.message}", e)
            emptyList()
        }
    }

    private inline fun <T> listToJson(items: List<T>, crossinline mapper: (T, JSONObject) -> Unit): String {
        val array = JSONArray()
        for (item in items) {
            val obj = JSONObject()
            mapper(item, obj)
            array.put(obj)
        }
        return array.toString()
    }
}
