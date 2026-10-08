package link.hector.freellee.viewmodel

import android.bluetooth.BluetoothManager
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import link.hector.freellee.ble.ActivityRecord
import link.hector.freellee.ble.ActivityType
import link.hector.freellee.ble.ConnectionState
import link.hector.freellee.ble.WatchRepository
import link.hector.freellee.util.formatDuration
import link.hector.freellee.util.intervalId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import java.util.Locale
import link.hector.freellee.BLE_CONNECT_TIMEOUT_MS
import link.hector.freellee.DEFAULT_WATCH_NAME
import link.hector.freellee.data.DeviceStore
import link.hector.freellee.data.DeviceStore.RecordItem
import link.hector.freellee.data.DeviceStore.StepInterval
import link.hector.freellee.data.HealthConnectClient
import link.hector.freellee.data.PairedDevice

private const val VM_TAG = "OlleeViewModel"

sealed class DashboardUiState {
    object Disconnected : DashboardUiState()
    object Connecting : DashboardUiState()
    object Connected : DashboardUiState()
    data class Syncing(val step: String = "Syncing") : DashboardUiState()
    data class Ready(
        val steps: Int?,
        val lastSyncTime: Instant?,
        val stepIntervals: List<StepInterval> = emptyList(),
        val weeklySteps: Int? = null,
        val connected: Boolean = false,
        val temperatureRecords: List<HistoryEntry> = emptyList(),
        val heartRateRecords: List<HistoryEntry> = emptyList(),
        val counterRecords: List<HistoryEntry> = emptyList(),
        val stopwatchRecords: List<HistoryEntry> = emptyList(),
        val firmwareVersion: String? = null,
    ) : DashboardUiState()
    object SyncSuccessful : DashboardUiState()
    data class Error(val message: String) : DashboardUiState()
}

data class HistoryEntry(
    val value: Int,
    val watchTimestampStart: Long,
    val watchTimestampEnd: Long,
    val syncTimestamp: Long,
    val isInstantaneous: Boolean = false,
)

class OlleeViewModel(
    private val repository: WatchRepository,
    private val deviceStore: DeviceStore,
    private val healthConnectClient: HealthConnectClient,
    private val bluetoothManager: BluetoothManager,
    bluetoothEnabledFlow: kotlinx.coroutines.flow.Flow<Boolean>,
) : ViewModel() {

    private val _bluetoothEnabled = bluetoothEnabledFlow.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        true,
    )

    private val _uiState = MutableStateFlow<DashboardUiState>(DashboardUiState.Disconnected)
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    val connectionState: StateFlow<ConnectionState> = repository.connectionState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ConnectionState.DISCONNECTED)

    val isConnected: StateFlow<Boolean> = connectionState
        .map { it == ConnectionState.READY }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val hasPairedDevice: StateFlow<Boolean> = deviceStore.getAllPairedDevices()
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** Preserves the last successful Ready state so the UI can fall back to it
     *  when the current uiState is transient (Connecting/Syncing/Error) or
     *  when the composable is recreated by a tab switch. */
    @Volatile
    var lastReadyState: DashboardUiState.Ready? = null
        private set

    init {
        // Health Connect access is optional: always load locally cached data so the app stays
        // usable when permissions are missing, denied or Health Connect is unavailable. Writes
        // to Health Connect are skipped individually when their permission is not granted.
        // Running on IO keeps cache decoding (large JSON blobs) off the main thread.
        viewModelScope.launch(Dispatchers.IO) { loadCachedData() }
    }

    suspend fun loadCachedData() {
        val devicesFlow = deviceStore.getAllPairedDevices()
        val tempFlow = deviceStore.getCachedTempRecords()
        val hrFlow = deviceStore.getCachedHRRecords()
        val counterFlow = deviceStore.getCachedCounterRecords()
        val stopwatchFlow = deviceStore.getCachedStopwatchRecords()
        val syncTimeFlow = deviceStore.getLastSyncTime()

        data class CachedData(
            val devices: List<PairedDevice>,
            val tempRecords: List<RecordItem>,
            val hrRecords: List<RecordItem>,
            val counterRecords: List<RecordItem>,
            val stopwatchRecords: List<RecordItem>,
            val lastSyncEpoch: Long?,
        )

        combine(devicesFlow, tempFlow, hrFlow, counterFlow, stopwatchFlow) { devices, tempRecords, hrRecords, counterRecords, stopwatchRecords ->
            CachedData(devices, tempRecords, hrRecords, counterRecords, stopwatchRecords, null)
        }.combine(syncTimeFlow) { cached, lastSyncEpoch ->
            cached.copy(lastSyncEpoch = lastSyncEpoch)
        }.collect { data ->
            val devices = data.devices
            val tempRecords = data.tempRecords
            val hrRecords = data.hrRecords
            val counterRecords = data.counterRecords
            val stopwatchRecords = data.stopwatchRecords
            val lastSyncEpoch = data.lastSyncEpoch

            Log.d(VM_TAG, "loadCachedData: devices=${devices.size}, temp=${tempRecords.size}, hr=${hrRecords.size}, syncTime=$lastSyncEpoch")
            if (tempRecords.isNotEmpty()) {
                Log.d(VM_TAG, "  temp_records[0]: type=${tempRecords[0].type}, tStart=${tempRecords[0].tStart}, value=${tempRecords[0].value}")
            }
            if (hrRecords.isNotEmpty()) {
                Log.d(VM_TAG, "  hr_records[0]: type=${hrRecords[0].type}, tStart=${hrRecords[0].tStart}, value=${hrRecords[0].value}")
            }

            // Debug: check step intervals for all devices when no active device
            if (devices.isEmpty()) {
                val allIntervals = deviceStore.getAllStepIntervalsAcrossDevices().first()
                Log.d(VM_TAG, "loadCachedData(no device): allStepIntervals=${allIntervals.size}, first=${allIntervals.firstOrNull()}, last=${allIntervals.lastOrNull()}")
                allIntervals.take(5).forEachIndexed { i, it ->
                    Log.d(VM_TAG, "  interval[$i]: tStart=${it.tStart}, tEnd=${it.tEnd}, steps=${it.steps}")
                }
            }

            if (_uiState.value is DashboardUiState.Connecting || _uiState.value is DashboardUiState.Syncing) return@collect

            if (devices.isEmpty()) {
                val allIntervals = deviceStore.getAllStepIntervalsAcrossDevices().first()
                Log.d(VM_TAG, "loadCachedData(no active device): read ${allIntervals.size} step intervals from store")
                val weekStartEpoch = weekStartEpoch(LocalDate.now())
                val weeklySteps = allIntervals.filter { it.tStart >= weekStartEpoch }.sumOf { it.steps }
                Log.d(VM_TAG, "loadCachedData(no active device): weeklySteps=$weeklySteps from ${allIntervals.size} intervals")

                if (allIntervals.isNotEmpty() || tempRecords.isNotEmpty() || hrRecords.isNotEmpty() || counterRecords.isNotEmpty() || stopwatchRecords.isNotEmpty()) {
                    _uiState.value = DashboardUiState.Ready(
                        steps = null,
                        lastSyncTime = lastSyncEpoch?.let { Instant.ofEpochSecond(it) },
                        stepIntervals = allIntervals,
                        weeklySteps = if (weeklySteps > 0) weeklySteps else null,
                        connected = false,
                        temperatureRecords = tempRecords.map { HistoryEntry(it.value, it.tStart, it.tEnd, it.syncTime) },
                        heartRateRecords = hrRecords.map { HistoryEntry(it.value, it.tStart, it.tEnd, it.syncTime, isInstantaneous = true) },
                        counterRecords = counterRecords.map { HistoryEntry(it.value, it.tStart, it.tEnd, it.syncTime, isInstantaneous = true) },
                        stopwatchRecords = stopwatchRecords.map { HistoryEntry(it.value, it.tStart, it.tEnd, it.syncTime) },
                    )
                } else if (_uiState.value !is DashboardUiState.Ready && _uiState.value !is DashboardUiState.Connecting && _uiState.value !is DashboardUiState.Syncing) {
                    _uiState.value = DashboardUiState.Disconnected
                }
                return@collect
            }

            val device = devices.first()
            val macAddress = device.macAddress
            loadDeviceState(macAddress, tempRecords, hrRecords, counterRecords, stopwatchRecords, lastSyncEpoch)
        }
    }

    private suspend fun loadDeviceState(
        macAddress: String,
        tempRecords: List<RecordItem>,
        hrRecords: List<RecordItem>,
        counterRecs: List<RecordItem>,
        stopwatchRecs: List<RecordItem>,
        lastSyncEpoch: Long?,
    ) {
        val intervals = deviceStore.getStepIntervalsForDevice(macAddress).first()
        Log.d(VM_TAG, "loadDeviceState: mac=$macAddress, read ${intervals.size} step intervals from store")
        intervals.take(10).forEachIndexed { i, it ->
            Log.d(VM_TAG, "  interval[$i]: tStart=${it.tStart}, tEnd=${it.tEnd}, steps=${it.steps}")
        }
        val todayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toEpochSecond()
        Log.d(VM_TAG, "loadDeviceState: todayStart=$todayStart (${LocalDate.now()})")
        val todayIntervals = intervals.filter { it.tStart >= todayStart }
        Log.d(VM_TAG, "loadDeviceState: todayIntervals=${todayIntervals.size} (filtered from ${intervals.size})")
        val dailySteps = todayIntervals.sumOf { it.steps }
        val weekStartEpoch = weekStartEpoch(LocalDate.now())
        val weeklySteps = intervals.filter { it.tStart >= weekStartEpoch }.sumOf { it.steps }

        Log.d(VM_TAG, "loadDeviceState: mac=$macAddress, dailySteps=$dailySteps, weeklySteps=$weeklySteps (from ${intervals.size} total, ${todayIntervals.size} today)")
        Log.d(VM_TAG, "loadDeviceState: counterRecords=${counterRecs.size}, stopwatchRecords=${stopwatchRecs.size}")

        val readyState = DashboardUiState.Ready(
            steps = if (dailySteps > 0) dailySteps else null,
            lastSyncTime = lastSyncEpoch?.let { Instant.ofEpochSecond(it) },
            stepIntervals = intervals,
            weeklySteps = if (weeklySteps > 0) weeklySteps else null,
            connected = repository.connectionState.value == ConnectionState.READY,
            temperatureRecords = tempRecords.map { HistoryEntry(it.value, it.tStart, it.tEnd, it.syncTime) },
            heartRateRecords = hrRecords.map { HistoryEntry(it.value, it.tStart, it.tEnd, it.syncTime, isInstantaneous = true) },
            counterRecords = counterRecs.map { HistoryEntry(it.value, it.tStart, it.tEnd, it.syncTime, isInstantaneous = true) },
            stopwatchRecords = stopwatchRecs.map { HistoryEntry(it.value, it.tStart, it.tEnd, it.syncTime) },
        )
        lastReadyState = readyState
        _uiState.value = readyState
    }

    private suspend fun loadNoDeviceState(
        tempRecords: List<RecordItem>,
        hrRecords: List<RecordItem>,
        counterRecs: List<RecordItem>,
        stopwatchRecs: List<RecordItem>,
        lastSyncEpoch: Long?,
    ) {
        val allIntervals = deviceStore.getAllStepIntervalsAcrossDevices().first()
        Log.d(VM_TAG, "loadNoDeviceState: read ${allIntervals.size} step intervals from store")
        val weekStartEpoch = weekStartEpoch(LocalDate.now())
        val weeklySteps = allIntervals.filter { it.tStart >= weekStartEpoch }.sumOf { it.steps }

        if (allIntervals.isNotEmpty() || tempRecords.isNotEmpty() || hrRecords.isNotEmpty() || counterRecs.isNotEmpty() || stopwatchRecs.isNotEmpty()) {
            val readyState = DashboardUiState.Ready(
                steps = null,
                lastSyncTime = lastSyncEpoch?.let { Instant.ofEpochSecond(it) },
                stepIntervals = allIntervals,
                weeklySteps = if (weeklySteps > 0) weeklySteps else null,
                connected = false,
                temperatureRecords = tempRecords.map { HistoryEntry(it.value, it.tStart, it.tEnd, it.syncTime) },
                heartRateRecords = hrRecords.map { HistoryEntry(it.value, it.tStart, it.tEnd, it.syncTime, isInstantaneous = true) },
                counterRecords = counterRecs.map { HistoryEntry(it.value, it.tStart, it.tEnd, it.syncTime, isInstantaneous = true) },
                stopwatchRecords = stopwatchRecs.map { HistoryEntry(it.value, it.tStart, it.tEnd, it.syncTime) },
            )
            lastReadyState = readyState
            _uiState.value = readyState
        }
    }

    private fun weekStartEpoch(today: LocalDate): Long {
        val weekFields = WeekFields.of(Locale.getDefault())
        val dayOfWeekInWeekFields = weekFields.dayOfWeek().getFrom(today)
        val daysSinceWeekStart = dayOfWeekInWeekFields - 1
        val weekStart = today.minus(daysSinceWeekStart, ChronoUnit.DAYS)
        return weekStart.atStartOfDay(ZoneId.systemDefault()).toEpochSecond()
    }

    fun sync(address: String? = null) = viewModelScope.launch(Dispatchers.IO) {
        try {
            // Authoritative Bluetooth check — uses the system adapter directly
            if (!bluetoothManager.adapter.isEnabled) {
                _uiState.value = DashboardUiState.Error("Bluetooth is off. Please enable Bluetooth and try again.")
                return@launch
            }

            val targetAddress = address ?: deviceStore.activeDeviceMac.first() ?: run {
                _uiState.value = DashboardUiState.Error("Pair a watch to sync")
                return@launch
            }

            val wasConnected = repository.connectionState.value == ConnectionState.READY
            _uiState.value = if (!wasConnected) {
                DashboardUiState.Connecting
            } else {
                DashboardUiState.Syncing("Syncing")
            }

            if (repository.connectionState.value != ConnectionState.READY) {
                repository.connect(targetAddress, timeoutMs = BLE_CONNECT_TIMEOUT_MS)
                _uiState.value = DashboardUiState.Connected
            }

            _uiState.value = DashboardUiState.Syncing("Reading watch info")
            val bleDeviceName = repository.deviceName ?: DEFAULT_WATCH_NAME
            val watchName = try {
                repository.name()
            } catch (e: Exception) {
                Log.w(VM_TAG, "Failed to read watch name: ${e.message}")
                null
            }
            val deviceInfo = try {
                repository.deviceInfo()
            } catch (e: Exception) {
                Log.w(VM_TAG, "Failed to read device info: ${e.message}")
                null
            }
            val firmwareVersion = deviceInfo?.firmware
            val voltageMv = deviceInfo?.voltageMv

            // Update stored device info on every sync so the name is always fresh
            _uiState.value = DashboardUiState.Syncing("Saving device info")
            deviceStore.addOrUpdate(
                macAddress = targetAddress,
                name = watchName ?: bleDeviceName,
                firmwareVersion = firmwareVersion,
                lastSyncedStepCount = null,
                voltageMv = voltageMv,
            )

            // Sync records first (drains the watch log)
            _uiState.value = DashboardUiState.Syncing("Fetching health records")
            var allRecords = emptyList<ActivityRecord>()
            try {
                // Throttle progress updates so per-record callbacks don't spam recomposition.
                var lastProgressMs = 0L
                val recordsResult = repository.syncRecords(
                    onProgress = { fetched, total ->
                        val nowMs = System.currentTimeMillis()
                        if (fetched == total || nowMs - lastProgressMs >= 150) {
                            lastProgressMs = nowMs
                            _uiState.value = DashboardUiState.Syncing("Fetching records $fetched/$total")
                        }
                    },
                ) { /* in-memory during sync */ }
                allRecords = recordsResult.records
                Log.d(VM_TAG, "sync: got ${allRecords.size} records from watch (drained=${recordsResult.drained})")
                allRecords.forEachIndexed { i, r ->
                    Log.d(VM_TAG, "  record[$i]: type=${r.type}, tStart=${r.tStart}, tEnd=${r.tEnd}, value=${r.value}")
                }
            } catch (e: Exception) {
                Log.w(VM_TAG, "Record sync failed: ${e.message}")
            }

            // Extract step intervals from synced records
            _uiState.value = DashboardUiState.Syncing("Processing step data")
            val now = System.currentTimeMillis() / 1000
            val today = LocalDate.now()
            val dateKey = today.toString()
            val syncTime = Instant.now().epochSecond
            val allStepIntervals = allRecords
                .filter { it.type == ActivityType.STEPS }
                .map { StepInterval(it.tStart, it.tEnd, it.value, syncTime) }

            Log.d(VM_TAG, "sync: total records=${allRecords.size}, step records=${allStepIntervals.size}")
            if (allStepIntervals.isEmpty()) {
                Log.d(VM_TAG, "sync: no step records returned from watch")
            } else {
                allStepIntervals.take(10).forEachIndexed { i, it ->
                    Log.d(VM_TAG, "  step interval[$i]: tStart=${it.tStart}, tEnd=${it.tEnd}, steps=${it.steps}")
                }
            }

            // Filter to today's intervals only (for daily step display)
            val todayStart = today.atStartOfDay(ZoneId.systemDefault()).toEpochSecond()
            Log.d(VM_TAG, "sync: todayStart=$todayStart, todayIntervals before dedup=${allStepIntervals.filter { it.tStart >= todayStart }.size}")
            val todayIntervals = allStepIntervals.filter { it.tStart >= todayStart }

            // Persist ALL step intervals to storage (for daily/weekly display and historical sync)
            _uiState.value = DashboardUiState.Syncing("Saving step intervals")
            var mergedIntervals = emptyList<StepInterval>()
            if (allStepIntervals.isNotEmpty()) {
                val stepCutoffEpoch = now - 14 * 24 * 3600L
                var existingIntervals = deviceStore.getStepIntervalsForDevice(targetAddress).first()
                // Prune existing step intervals older than 14 days
                val existingPrunedCount = existingIntervals.size
                existingIntervals = existingIntervals.filter { it.tEnd >= stepCutoffEpoch }
                Log.d(VM_TAG, "sync: step intervals existing pruned: ${existingPrunedCount} → ${existingIntervals.size}")

                val existingKeys = existingIntervals.map { it.tStart to it.tEnd to it.steps }.toSet()
                val newIntervals = allStepIntervals.filter { it.tStart to it.tEnd to it.steps !in existingKeys }
                mergedIntervals = existingIntervals + newIntervals
                Log.d(VM_TAG, "sync: step intervals: ${existingIntervals.size} existing + ${newIntervals.size} new (saving for $targetAddress)")
                deviceStore.saveStepIntervals(targetAddress, dateKey, mergedIntervals)
            }

            // Get already-processed interval IDs (global dedup across all dates)
            val processedKeys = deviceStore.getProcessedIntervalKeys(targetAddress, dateKey).first()
            Log.d(VM_TAG, "sync: processedKeys count=${processedKeys.size}, allStepIntervals=$allStepIntervals.size")

            // Filter to only new intervals (idempotent sync across all dates)
            val newIntervals = allStepIntervals.filter { intervalId(it.tStart, it.tEnd, it.steps) !in processedKeys }
            Log.d(VM_TAG, "sync: newIntervals=${newIntervals.size} (filtered from ${allStepIntervals.size} total)")

            // Write ALL new intervals to Health Connect (not just today's)
            if (newIntervals.isEmpty()) {
                if (allStepIntervals.isNotEmpty()) {
                    Log.d(VM_TAG, "sync: all step intervals already processed — skipping Health Connect write")
                } else {
                    Log.d(VM_TAG, "sync: no new step intervals to write to Health Connect")
                }
            } else {
                _uiState.value = DashboardUiState.Syncing("Writing to Health Connect")
                Log.d(VM_TAG, "sync: writing ${newIntervals.size} new step intervals to Health Connect")
                healthConnectClient.writeStepIntervals(newIntervals)
                val newKeys = newIntervals.map { intervalId(it.tStart, it.tEnd, it.steps) }.toSet()
                deviceStore.markIntervalsAsProcessed(targetAddress, dateKey, newKeys)
            }

            // Compute daily and weekly steps from the merged intervals (no reload)
            val allIntervals = if (mergedIntervals.isNotEmpty()) mergedIntervals else deviceStore.getStepIntervalsForDevice(targetAddress).first()
            Log.d(VM_TAG, "sync: after save, read ${allIntervals.size} total intervals for $targetAddress")
            allIntervals.take(10).forEachIndexed { i, it ->
                Log.d(VM_TAG, "  stored interval[$i]: tStart=${it.tStart}, tEnd=${it.tEnd}, steps=${it.steps}")
            }
            val dailyStepsFromStorage = allIntervals.filter { it.tStart >= todayStart }.sumOf { it.steps }
            Log.d(VM_TAG, "sync: dailyStepsFromStorage=$dailyStepsFromStorage from ${allIntervals.size} total")

            val weekStartEpoch = weekStartEpoch(today)
            val weeklyStepsFromStorage = allIntervals.filter { it.tStart >= weekStartEpoch }.sumOf { it.steps }

            val rawTemperatureRecords = allRecords.filter { it.type == ActivityType.TEMPERATURE }
            val rawHeartRateRecords = allRecords.filter { it.type == ActivityType.HEART_RATE }
            val rawCounterRecords = allRecords.filter { it.type == ActivityType.COUNTER }
            val rawStopwatchRecords = allRecords.filter { it.type == ActivityType.STOPWATCH }

            val temperatureRecords = rawTemperatureRecords
                .distinctBy { it.tStart }
                .map { RecordItem(it.type, it.tStart, it.tEnd, it.value, now) }

            val heartRateRecords = rawHeartRateRecords
                .distinctBy { it.tStart }
                .map { RecordItem(it.type, it.tStart, it.tEnd, it.value, now) }
            val counterRecords = rawCounterRecords
                .distinctBy { it.tStart }
                .map { RecordItem(it.type, it.tStart, it.tEnd, it.value, now) }
            val stopwatchRecords = rawStopwatchRecords
                .distinctBy { it.tStart }
                .map {
                    val tStart = if (it.type == ActivityType.STOPWATCH) it.tStart - (it.value / 1000) else it.tStart
                    RecordItem(it.type, tStart, it.tEnd, it.value, now)
                }

            Log.d(VM_TAG, "sync: deduplicated temp ${rawTemperatureRecords.size} -> ${temperatureRecords.size}, hr ${rawHeartRateRecords.size} -> ${heartRateRecords.size}, counter ${rawCounterRecords.size} -> ${counterRecords.size}, stopwatch ${rawStopwatchRecords.size} -> ${stopwatchRecords.size}")
            if (rawStopwatchRecords.isNotEmpty()) {
                Log.d(VM_TAG, "sync: raw stopwatch records: ${rawStopwatchRecords.map { "type=${it.type} tStart=${it.tStart} tEnd=${it.tEnd} value=${it.value}" }}")
            }
            if (rawCounterRecords.isNotEmpty()) {
                Log.d(VM_TAG, "sync: counter records: ${rawCounterRecords.map { "tStart=${it.tStart} value=${it.value}" }}")
            }

            _uiState.value = DashboardUiState.Syncing("Saving health data")
            
            // Load existing records once, compute merged data in memory to avoid reloading later
            var existingTempRecords = deviceStore.getCachedTempRecords().first()
            // Prune existing temp records older than 15 days
            val tempCutoffEpoch = now - 15 * 24 * 3600L
            val existingTempPrunedCount = existingTempRecords.size
            existingTempRecords = existingTempRecords.filter { it.tStart >= tempCutoffEpoch }
            Log.d(VM_TAG, "sync: temp records existing pruned: ${existingTempPrunedCount} → ${existingTempRecords.size}")
            val existingHRRecords = deviceStore.getCachedHRRecords().first()
            val existingCounterRecords = deviceStore.getCachedCounterRecords().first()
            val existingStopwatchRecords = deviceStore.getCachedStopwatchRecords().first()

            val existingTempKeys = existingTempRecords.map { it.tStart }.toSet()
            val existingHRKeys = existingHRRecords.map { it.tStart }.toSet()
            val existingCounterKeys = existingCounterRecords.map { it.tStart }.toSet()
            val existingStopwatchKeys = existingStopwatchRecords.map { it.tStart }.toSet()
            val mergedTempRecords = existingTempRecords + temperatureRecords.filter { it.tStart !in existingTempKeys }
            Log.d(VM_TAG, "sync: temp records: ${existingTempRecords.size} existing + ${temperatureRecords.filter { it.tStart !in existingTempKeys }.size} new (saving)")
            val mergedHRRecords = existingHRRecords + heartRateRecords.filter { it.tStart !in existingHRKeys }
            val mergedCounterRecords = existingCounterRecords + counterRecords.filter { it.tStart !in existingCounterKeys }
            val mergedStopwatchRecords = existingStopwatchRecords + stopwatchRecords.filter { it.tStart !in existingStopwatchKeys }
            
            deviceStore.saveRecentRecords(
                temperature = mergedTempRecords,
                heartRate = mergedHRRecords,
                counter = mergedCounterRecords,
                stopwatch = mergedStopwatchRecords,
            )

            // Add new stopwatch records as pending activities (duration > 5 minutes)
            val newStopwatchRecords = stopwatchRecords.filter { it.tStart !in existingStopwatchKeys }
            if (newStopwatchRecords.isNotEmpty()) {
                Log.d(VM_TAG, "sync: ${newStopwatchRecords.size} new stopwatch records, checking for pending activities")
                for (sw in newStopwatchRecords) {
                    val durationMillis = sw.value
                    if (durationMillis > 1000) { // > 1 second
                        val activityId = "sw_${sw.tStart}_${sw.tEnd}"
                        val pendingActivity = DeviceStore.PendingActivity(
                            id = activityId,
                            tStart = sw.tStart,
                            tEnd = sw.tEnd,
                            durationMillis = durationMillis,
                            syncTime = now,
                        )
                        deviceStore.addPendingActivity(pendingActivity)
                        Log.d(VM_TAG, "sync: added pending activity: $activityId, duration=${formatDuration(durationMillis)}")
                    } else {
                        Log.d(VM_TAG, "sync: stopwatch record too short (${formatDuration(durationMillis)}), skipping")
                    }
                }
            }

            healthConnectClient.writeSkinTemperatureRecords(mergedTempRecords)
            healthConnectClient.writeHeartRateRecords(heartRateRecords)

            deviceStore.saveLastSyncTime(Instant.now().epochSecond)

            try {
                repository.disconnect()
                Log.d(VM_TAG, "sync: disconnected after successful sync")
            } catch (e: Exception) {
                Log.w(VM_TAG, "Disconnect failed after sync: ${e.message}")
            }

            // Update Ready state in-place to avoid flicker (use in-memory merged data)
            val devices = deviceStore.getAllPairedDevices().first()
            val lastSyncEpoch = deviceStore.getLastSyncTime().first()
            
            if (devices.isNotEmpty()) {
                loadDeviceState(devices.first().macAddress, mergedTempRecords, mergedHRRecords, mergedCounterRecords, mergedStopwatchRecords, lastSyncEpoch)
            } else {
                loadNoDeviceState(mergedTempRecords, mergedHRRecords, mergedCounterRecords, mergedStopwatchRecords, lastSyncEpoch)
            }

        } catch (e: Exception) {
            _uiState.value = DashboardUiState.Error("Sync failed: ${e.message}")
        }
    }

    fun disconnect() = viewModelScope.launch {
        repository.disconnect()
        _uiState.value = DashboardUiState.Disconnected
    }

    fun clearError() {
        _uiState.value = DashboardUiState.Disconnected
    }
}
