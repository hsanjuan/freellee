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
import link.hector.freellee.BLE_CONNECT_TIMEOUT_MS
import link.hector.freellee.DEFAULT_WATCH_NAME
import link.hector.freellee.STEP_RETENTION_DAYS
import link.hector.freellee.TEMPERATURE_RETENTION_DAYS
import link.hector.freellee.data.AdvancedModeSetting
import link.hector.freellee.data.DeviceStore
import link.hector.freellee.data.DeviceStore.RecordItem
import link.hector.freellee.data.DeviceStore.StepInterval
import link.hector.freellee.data.HcWriteStats
import link.hector.freellee.data.HealthConnectClient
import link.hector.freellee.data.PairedDevice
import link.hector.freellee.data.SLEEP_DETECTION_DAYS
import link.hector.freellee.data.SamplingProfile
import link.hector.freellee.data.SleepSession
import link.hector.freellee.data.SleepSessionSource
import link.hector.freellee.data.TemperatureDayAggregate
import link.hector.freellee.data.buildTemperatureDayAggregates
import link.hector.freellee.data.combineSleepSessions
import link.hector.freellee.data.detectSleepSessions
import link.hector.freellee.data.inferSleepKind
import link.hector.freellee.data.inferStepIntervalSeconds
import link.hector.freellee.data.inferTemperatureIntervalSeconds
import link.hector.freellee.data.inferTemperatureSampleSeconds
import link.hector.freellee.data.isAdvancedEnabled
import link.hector.freellee.data.mergeSleepSessions
import link.hector.freellee.data.sleepDayKey
import link.hector.freellee.ui.charts.DashboardChartData
import link.hector.freellee.ui.charts.buildDashboardChartData
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
        val connected: Boolean = false,
        val temperatureRecords: List<HistoryEntry> = emptyList(),
        val heartRateRecords: List<HistoryEntry> = emptyList(),
        val counterRecords: List<HistoryEntry> = emptyList(),
        val stopwatchRecords: List<HistoryEntry> = emptyList(),
        val firmwareVersion: String? = null,
        val temperatureAggregates: List<TemperatureDayAggregate> = emptyList(),
        val sleepSessions: List<SleepSession> = emptyList(),
        val stepGoal: Int? = null,
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

    /** Serializes Health Connect sleep writes so corrections and syncs cannot interleave. */
    private val sleepHcMutex = Mutex()

    /** Serializes read-modify-write cycles on the stored sleep sessions. */
    private val sleepMutationMutex = Mutex()

    /** Serializes the one-time skin temperature id migration. */
    private val tempHcMigrationMutex = Mutex()

    /** What the stored data says about the watch's sampling resolution. */
    private val _samplingProfile = MutableStateFlow(SamplingProfile.UNKNOWN)
    val samplingProfile: StateFlow<SamplingProfile> = _samplingProfile.asStateFlow()

    /** The user's advanced-feature preference, as persisted. */
    private val _advancedMode = MutableStateFlow(AdvancedModeSetting.AUTO)
    val advancedMode: StateFlow<AdvancedModeSetting> = _advancedMode.asStateFlow()

    /**
     * Whether advanced features are active right now. In [AdvancedModeSetting.AUTO] this follows
     * the observed sampling, so a stock watch silently falls back to basic temperature tracking.
     */
    val advancedEnabled: StateFlow<Boolean> =
        combine(_advancedMode, _samplingProfile) { mode, profile ->
            mode.isAdvancedEnabled(profile)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Chart inputs are prepared off the UI thread and cached for the lifetime of the ViewModel.
     * Switching tabs therefore reuses the prepared graphs instead of rebuilding them on the main
     * thread each time the Data page is composed.
     */
    private val _chartData = MutableStateFlow(
        buildDashboardChartData(
            stepIntervals = emptyList(),
            temperatureRecords = emptyList(),
            nowEpochSecond = Instant.now().epochSecond,
        ),
    )
    val chartData: StateFlow<DashboardChartData> = _chartData.asStateFlow()

    val connectionState: StateFlow<ConnectionState> = repository.connectionState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ConnectionState.DISCONNECTED)

    val isConnected: StateFlow<Boolean> = connectionState
        .map { it == ConnectionState.READY }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val hasPairedDevice: StateFlow<Boolean> = deviceStore.getAllPairedDevices()
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** Last successful Health Connect writes, shown on the Settings screen. */
    val hcWriteStats: StateFlow<HcWriteStats> = deviceStore.getHcWriteStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HcWriteStats())

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

        // Keep chart preparation off the UI thread as well. The pure aggregation work runs on
        // Dispatchers.Default, and the resulting immutable snapshot is reused across tab switches.
        viewModelScope.launch {
            uiState
                .filterIsInstance<DashboardUiState.Ready>()
                .collectLatest { state ->
                    val now = Instant.now().epochSecond
                    val prepared = withContext(Dispatchers.Default) {
                        val startedAt = System.nanoTime()
                        val temperatureRecords = state.temperatureRecords.map { history ->
                            RecordItem(
                                type = 0,
                                tStart = history.watchTimestampStart,
                                tEnd = history.watchTimestampEnd,
                                value = history.value,
                                syncTime = history.syncTimestamp,
                            )
                        }
                        // Advanced features can be switched off or the firmware reverted, so
                        // decide from the data in this snapshot rather than from a stale flag.
                        val profile = SamplingProfile(
                            stepIntervalSeconds = inferStepIntervalSeconds(state.stepIntervals),
                            temperatureIntervalSeconds = inferTemperatureIntervalSeconds(
                                temperatureRecords,
                            ),
                        )
                        val sleepEnabled = _advancedMode.value.isAdvancedEnabled(profile)
                        buildDashboardChartData(
                            stepIntervals = state.stepIntervals,
                            temperatureRecords = temperatureRecords,
                            nowEpochSecond = now,
                            temperatureAggregates = state.temperatureAggregates,
                            sleepSessions = if (sleepEnabled) state.sleepSessions else emptyList(),
                            lastSyncEpochSecond = state.lastSyncTime?.epochSecond,
                        ).also {
                            Log.d(
                                VM_TAG,
                                "Prepared chart data in ${(System.nanoTime() - startedAt) / 1_000_000}ms",
                            )
                        }
                    }
                    _chartData.value = prepared
                }
        }
    }

    suspend fun loadCachedData() {
        val devicesFlow = deviceStore.getAllPairedDevices()
        val tempFlow = deviceStore.getCachedTempRecords()
        val hrFlow = deviceStore.getCachedHRRecords()
        val counterFlow = deviceStore.getCachedCounterRecords()
        val stopwatchFlow = deviceStore.getCachedStopwatchRecords()
        val syncTimeFlow = deviceStore.getLastSyncTime()
        val advancedModeFlow = deviceStore.getAdvancedMode()

        data class CachedData(
            val devices: List<PairedDevice>,
            val tempRecords: List<RecordItem>,
            val hrRecords: List<RecordItem>,
            val counterRecords: List<RecordItem>,
            val stopwatchRecords: List<RecordItem>,
            val lastSyncEpoch: Long?,
            val advancedMode: AdvancedModeSetting = AdvancedModeSetting.AUTO,
        )

        combine(devicesFlow, tempFlow, hrFlow, counterFlow, stopwatchFlow) { devices, tempRecords, hrRecords, counterRecords, stopwatchRecords ->
            CachedData(devices, tempRecords, hrRecords, counterRecords, stopwatchRecords, null)
        }.combine(syncTimeFlow) { cached, lastSyncEpoch ->
            cached.copy(lastSyncEpoch = lastSyncEpoch)
        }.combine(advancedModeFlow) { cached, advancedMode ->
            cached.copy(advancedMode = advancedMode)
        }.collect { data ->
            val devices = data.devices
            val tempRecords = data.tempRecords
            val hrRecords = data.hrRecords
            val counterRecords = data.counterRecords
            val stopwatchRecords = data.stopwatchRecords
            val lastSyncEpoch = data.lastSyncEpoch
            _advancedMode.value = data.advancedMode

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

            val temperatureAggregates = refreshTemperatureAggregates(tempRecords)
            // Uses the cached data, so it does not depend on the watch being reachable.
            ensureSkinTemperatureMigration(tempRecords, temperatureAggregates)

            if (devices.isEmpty()) {
                val emitted = loadNoDeviceState(
                    tempRecords = tempRecords,
                    hrRecords = hrRecords,
                    counterRecs = counterRecords,
                    stopwatchRecs = stopwatchRecords,
                    lastSyncEpoch = lastSyncEpoch,
                    temperatureAggregates = temperatureAggregates,
                    advancedMode = data.advancedMode,
                )
                if (!emitted &&
                    _uiState.value !is DashboardUiState.Ready &&
                    _uiState.value !is DashboardUiState.Connecting &&
                    _uiState.value !is DashboardUiState.Syncing
                ) {
                    _uiState.value = DashboardUiState.Disconnected
                }
                return@collect
            }

            val device = devices.first()
            val macAddress = device.macAddress
            loadDeviceState(
                macAddress = macAddress,
                tempRecords = tempRecords,
                hrRecords = hrRecords,
                counterRecs = counterRecords,
                stopwatchRecs = stopwatchRecords,
                lastSyncEpoch = lastSyncEpoch,
                temperatureAggregates = temperatureAggregates,
                advancedMode = data.advancedMode,
            )
        }
    }

    private fun samplingProfileFor(
        intervals: List<StepInterval>,
        tempRecords: List<RecordItem>,
    ): SamplingProfile = SamplingProfile(
        stepIntervalSeconds = inferStepIntervalSeconds(intervals),
        temperatureIntervalSeconds = inferTemperatureIntervalSeconds(tempRecords),
    )

    private suspend fun loadDeviceState(
        macAddress: String,
        tempRecords: List<RecordItem>,
        hrRecords: List<RecordItem>,
        counterRecs: List<RecordItem>,
        stopwatchRecs: List<RecordItem>,
        lastSyncEpoch: Long?,
        temperatureAggregates: List<TemperatureDayAggregate>,
        advancedMode: AdvancedModeSetting,
    ) {
        val intervals = deviceStore.getStepIntervalsForDevice(macAddress).first()
        val stepGoal = deviceStore.getStepGoal().first()
        val sampling = samplingProfileFor(intervals, tempRecords)
        _samplingProfile.value = sampling
        val sleepSessions = sleepMutationMutex.withLock {
            refreshSleepSessions(
                intervals = intervals,
                temperatureRecords = tempRecords,
                advancedEnabled = advancedMode.isAdvancedEnabled(sampling),
            )
        }
        Log.d(VM_TAG, "loadDeviceState: mac=$macAddress, read ${intervals.size} step intervals from store")
        intervals.take(10).forEachIndexed { i, it ->
            Log.d(VM_TAG, "  interval[$i]: tStart=${it.tStart}, tEnd=${it.tEnd}, steps=${it.steps}")
        }
        val todayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toEpochSecond()
        Log.d(VM_TAG, "loadDeviceState: todayStart=$todayStart (${LocalDate.now()})")
        val todayIntervals = intervals.filter { it.tStart >= todayStart }
        Log.d(VM_TAG, "loadDeviceState: todayIntervals=${todayIntervals.size} (filtered from ${intervals.size})")
        val dailySteps = todayIntervals.sumOf { it.steps }

        Log.d(VM_TAG, "loadDeviceState: mac=$macAddress, dailySteps=$dailySteps (from ${intervals.size} total, ${todayIntervals.size} today)")
        Log.d(VM_TAG, "loadDeviceState: counterRecords=${counterRecs.size}, stopwatchRecords=${stopwatchRecs.size}")

        val readyState = DashboardUiState.Ready(
            steps = if (dailySteps > 0) dailySteps else null,
            lastSyncTime = lastSyncEpoch?.let { Instant.ofEpochSecond(it) },
            stepIntervals = intervals,
            connected = repository.connectionState.value == ConnectionState.READY,
            temperatureRecords = tempRecords.map { HistoryEntry(it.value, it.tStart, it.tEnd, it.syncTime) },
            heartRateRecords = hrRecords.map { HistoryEntry(it.value, it.tStart, it.tEnd, it.syncTime, isInstantaneous = true) },
            counterRecords = counterRecs.map { HistoryEntry(it.value, it.tStart, it.tEnd, it.syncTime, isInstantaneous = true) },
            stopwatchRecords = stopwatchRecs.map { HistoryEntry(it.value, it.tStart, it.tEnd, it.syncTime) },
            temperatureAggregates = temperatureAggregates,
            sleepSessions = sleepSessions,
            stepGoal = stepGoal,
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
        temperatureAggregates: List<TemperatureDayAggregate>,
        advancedMode: AdvancedModeSetting,
    ): Boolean {
        val allIntervals = deviceStore.getAllStepIntervalsAcrossDevices().first()
        val sampling = samplingProfileFor(allIntervals, tempRecords)
        _samplingProfile.value = sampling
        val sleepSessions = sleepMutationMutex.withLock {
            refreshSleepSessions(
                intervals = allIntervals,
                temperatureRecords = tempRecords,
                advancedEnabled = advancedMode.isAdvancedEnabled(sampling),
            )
        }
        Log.d(VM_TAG, "loadNoDeviceState: read ${allIntervals.size} step intervals from store, sampling=$sampling")

        if (allIntervals.isNotEmpty() || tempRecords.isNotEmpty() || hrRecords.isNotEmpty() || counterRecs.isNotEmpty() || stopwatchRecs.isNotEmpty()) {
            val readyState = DashboardUiState.Ready(
                steps = null,
                lastSyncTime = lastSyncEpoch?.let { Instant.ofEpochSecond(it) },
                stepIntervals = allIntervals,
                connected = false,
                temperatureRecords = tempRecords.map { HistoryEntry(it.value, it.tStart, it.tEnd, it.syncTime) },
                heartRateRecords = hrRecords.map { HistoryEntry(it.value, it.tStart, it.tEnd, it.syncTime, isInstantaneous = true) },
                counterRecords = counterRecs.map { HistoryEntry(it.value, it.tStart, it.tEnd, it.syncTime, isInstantaneous = true) },
                stopwatchRecords = stopwatchRecs.map { HistoryEntry(it.value, it.tStart, it.tEnd, it.syncTime) },
                temperatureAggregates = temperatureAggregates,
                sleepSessions = sleepSessions,
                stepGoal = deviceStore.getStepGoal().first(),
            )
            lastReadyState = readyState
            _uiState.value = readyState
            return true
        }
        return false
    }

    /**
     * Re-runs sleep detection over the recent window and merges it with user corrections.
     */
    private suspend fun refreshSleepSessions(
        intervals: List<StepInterval>,
        temperatureRecords: List<RecordItem>,
        advancedEnabled: Boolean,
    ): List<SleepSession> {
        val existing = deviceStore.getSleepSessions().first()
        // Without fine-grained step data the detector only produces hour-aligned guesses, so keep
        // whatever was already confirmed instead of recomputing noise.
        if (!advancedEnabled) return existing
        val now = Instant.now().epochSecond
        val detected = detectSleepSessions(
            temperatureRecords = temperatureRecords,
            stepIntervals = intervals,
            nowEpochSecond = now,
            sampleSeconds = inferTemperatureSampleSeconds(temperatureRecords),
        )
        val merged = mergeSleepSessions(
            existing = existing,
            detected = detected,
            nowEpochSecond = now,
        )
        if (merged != existing) {
            deviceStore.saveSleepSessions(merged)
        }
        return merged
    }

    /**
     * One-time move of the skin temperature records to UUID client ids. Runs from cached data,
     * so it works even when the watch cannot be reached. Legacy records are removed first, then
     * the retained readings are written again, which keeps a single copy of each day part.
     */
    private suspend fun ensureSkinTemperatureMigration(
        records: List<RecordItem>,
        aggregates: List<TemperatureDayAggregate>,
    ) {
        if (records.isEmpty() || aggregates.isEmpty()) return
        if (deviceStore.isTempHcUuidMigrated().first()) return
        if (!migrateTemperatureIdsIfNeeded()) return
        Log.d(VM_TAG, "Skin temperature id migration: ${records.size} readings")
        val written = healthConnectClient.writeSkinTemperatureRecords(records, aggregates)
        if (written > 0) {
            deviceStore.saveHcTemperatureWrite(Instant.now().epochSecond, written)
        }
    }

    /**
     * Makes sure legacy skin temperature records are gone before UUID ids are written. Returns
     * true when the write may proceed: either the migration is already done or the legacy
     * records were removed now. A failure leaves the flag unset so the next run retries.
     */
    private suspend fun migrateTemperatureIdsIfNeeded(): Boolean {
        if (deviceStore.isTempHcUuidMigrated().first()) return true
        return tempHcMigrationMutex.withLock {
            when {
                deviceStore.isTempHcUuidMigrated().first() -> true
                !healthConnectClient.deleteAllOwnSkinTemperatureRecords() -> {
                    Log.w(VM_TAG, "Skin temperature id migration skipped; will retry")
                    false
                }
                else -> {
                    deviceStore.setTempHcUuidMigrated()
                    true
                }
            }
        }
    }

    /**
     * Rebuilds the long-term daily aggregates from the retained raw samples. Baselines already
     * stored on a day are preserved, so re-syncing cannot make them drift.
     */
    private suspend fun refreshTemperatureAggregates(
        records: List<RecordItem>,
    ): List<TemperatureDayAggregate> {
        val existing = deviceStore.getTemperatureAggregates().first()
        val rebuilt = buildTemperatureDayAggregates(
            records = records,
            existing = existing,
            sampleSeconds = inferTemperatureSampleSeconds(records),
        )
        if (rebuilt != existing) {
            deviceStore.saveTemperatureAggregates(rebuilt)
        }
        return rebuilt
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

            // The goal lives on the watch, so read it while we are connected and cache it.
            try {
                val goal = repository.stepGoal()
                if (goal in 100..500_000) {
                    deviceStore.saveStepGoal(goal)
                } else {
                    Log.w(VM_TAG, "Ignoring out-of-range step goal: $goal")
                }
            } catch (e: Exception) {
                Log.w(VM_TAG, "Failed to read step goal: ${e.message}")
            }

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
                val stepCutoffEpoch = now - STEP_RETENTION_DAYS * 24 * 3600L
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
                Log.d(VM_TAG, "sync: ${rawStopwatchRecords.size} raw stopwatch records")
            }
            if (rawCounterRecords.isNotEmpty()) {
                Log.d(VM_TAG, "sync: ${rawCounterRecords.size} counter records")
            }

            _uiState.value = DashboardUiState.Syncing("Saving health data")
            
            // Load existing records once, compute merged data in memory to avoid reloading later
            var existingTempRecords = deviceStore.getCachedTempRecords().first()
            // Prune existing temp records older than the raw retention window
            val tempCutoffEpoch = now - TEMPERATURE_RETENTION_DAYS * 24 * 3600L
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

            // Add new counter records as pending counter events
            val newCounterRecords = counterRecords.filter { it.tStart !in existingCounterKeys }
            if (newCounterRecords.isNotEmpty()) {
                Log.d(VM_TAG, "sync: ${newCounterRecords.size} new counter records, creating pending events")
                for (cr in newCounterRecords) {
                    if (cr.value > 0) {
                        val eventId = "ct_${cr.tStart}"
                        val pendingEvent = DeviceStore.PendingCounterEvent(
                            id = eventId,
                            tStart = cr.tStart,
                            value = cr.value,
                            syncTime = now,
                        )
                        deviceStore.addPendingCounterEvent(pendingEvent)
                        Log.d(VM_TAG, "sync: added pending counter event: $eventId, value=${cr.value}")
                    } else {
                        Log.d(VM_TAG, "sync: counter record has zero value, skipping")
                    }
                }
            }

            val temperatureAggregates = refreshTemperatureAggregates(mergedTempRecords)
            // Legacy temperature records used readable ids, which Health Connect cannot delete.
            // Remove them once so the retained readings are written again under UUID ids.
            if (migrateTemperatureIdsIfNeeded()) {
                val tempWritten = healthConnectClient.writeSkinTemperatureRecords(
                    mergedTempRecords,
                    temperatureAggregates,
                )
                if (tempWritten > 0) {
                    deviceStore.saveHcTemperatureWrite(now, tempWritten)
                }
            }
            healthConnectClient.writeHeartRateRecords(heartRateRecords)
            syncSleepToHealthConnect(deviceStore.getSleepSessions().first())

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
                loadDeviceState(
                    macAddress = devices.first().macAddress,
                    tempRecords = mergedTempRecords,
                    hrRecords = mergedHRRecords,
                    counterRecs = mergedCounterRecords,
                    stopwatchRecs = mergedStopwatchRecords,
                    lastSyncEpoch = lastSyncEpoch,
                    temperatureAggregates = temperatureAggregates,
                    advancedMode = _advancedMode.value,
                )
            } else {
                loadNoDeviceState(
                    tempRecords = mergedTempRecords,
                    hrRecords = mergedHRRecords,
                    counterRecs = mergedCounterRecords,
                    stopwatchRecs = mergedStopwatchRecords,
                    lastSyncEpoch = lastSyncEpoch,
                    temperatureAggregates = temperatureAggregates,
                    advancedMode = _advancedMode.value,
                )
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

    /**
     * Writes temperature and confirmed sleep from the cached data. Independent of the watch,
     * so it can recover when a sync-time Health Connect write failed.
     */
    fun resyncHealthConnect() = viewModelScope.launch(Dispatchers.IO) {
        val ready = (_uiState.value as? DashboardUiState.Ready) ?: lastReadyState ?: return@launch
        val tempRecords = ready.temperatureRecords.map { history ->
            RecordItem(
                type = 0,
                tStart = history.watchTimestampStart,
                tEnd = history.watchTimestampEnd,
                value = history.value,
                syncTime = history.syncTimestamp,
            )
        }
        if (migrateTemperatureIdsIfNeeded()) {
            val written = healthConnectClient.writeSkinTemperatureRecords(
                tempRecords,
                ready.temperatureAggregates,
            )
            if (written > 0) {
                deviceStore.saveHcTemperatureWrite(Instant.now().epochSecond, written)
            }
        }
        syncSleepToHealthConnect(deviceStore.getSleepSessions().first())
    }

    // ── Advanced (patched firmware) features ─────────────────────────────

    /** Persist the advanced-feature preference; cached data reloads through the setting flow. */
    fun setAdvancedMode(mode: AdvancedModeSetting) = viewModelScope.launch(Dispatchers.IO) {
        deviceStore.saveAdvancedMode(mode)
    }

    // ── Sleep session corrections ────────────────────────────────────────

    fun confirmSleepSession(id: String) = viewModelScope.launch(Dispatchers.IO) {
        mutateSleepSessions { sessions ->
            sessions.map { session ->
                if (session.id == id && !session.dismissed) {
                    session.copy(
                        source = SleepSessionSource.USER,
                        confidence = 1.0,
                    )
                } else {
                    session
                }
            }
        }
    }

    fun adjustSleepSession(id: String, startEpochSecond: Long, endEpochSecond: Long) =
        viewModelScope.launch(Dispatchers.IO) {
            if (endEpochSecond <= startEpochSecond) return@launch
            mutateSleepSessions { sessions ->
                val target = sessions.firstOrNull { it.id == id }
                    ?: return@mutateSleepSessions sessions
                // Keep the id stable so the Health Connect record is updated, not duplicated.
                val adjusted = target.copy(
                    startEpochSecond = startEpochSecond,
                    endEpochSecond = endEpochSecond,
                    kind = inferSleepKind(startEpochSecond, endEpochSecond),
                    confidence = 1.0,
                    source = SleepSessionSource.USER,
                    dismissed = false,
                )
                (sessions.filterNot { it.id == id } + adjusted).filterNot {
                    it.source == SleepSessionSource.DETECTED && !it.dismissed && it.overlaps(adjusted)
                }
            }
        }

    /** Rejoins this session with the next one on the same sleep day. */
    fun mergeSleepSessionWithNext(id: String) = viewModelScope.launch(Dispatchers.IO) {
        mutateSleepSessions { sessions ->
            val zone = ZoneId.systemDefault()
            val active = sessions.filter { !it.dismissed }.sortedBy { it.startEpochSecond }
            val index = active.indexOfFirst { it.id == id }
            if (index < 0 || index >= active.lastIndex) return@mutateSleepSessions sessions
            val first = active[index]
            val second = active[index + 1]
            if (sleepDayKey(first.startEpochSecond, zone) !=
                sleepDayKey(second.startEpochSecond, zone)
            ) {
                return@mutateSleepSessions sessions
            }
            val merged = combineSleepSessions(first, second, zone)
            sessions.filterNot { it.id == first.id || it.id == second.id || it.id == merged.id }
                .filterNot {
                    it.source == SleepSessionSource.DETECTED && !it.dismissed && it.overlaps(merged)
                } + merged
        }
    }

    /**
     * Drops a confirmation or manual adjustment and lets the detector rebuild the session, so
     * a wrong correction does not keep overriding better detection.
     */
    fun resetSleepSession(id: String) = viewModelScope.launch(Dispatchers.IO) {
        mutateSleepSessions { sessions ->
            val target = sessions.firstOrNull { it.id == id }
                ?: return@mutateSleepSessions sessions
            sessions.filterNot { it.id == id || (it.dismissed && it.touches(target)) }
        }
        redetectSleepSessions()
    }

    /**
     * Discards every session in the detection window, including corrections and dismissed
     * detections, and rebuilds the list from the raw data. Sessions older than the window are
     * kept because they can no longer be re-derived.
     */
    fun resetSleepCorrections() = viewModelScope.launch(Dispatchers.IO) {
        val cutoff = Instant.now().epochSecond - SLEEP_DETECTION_DAYS * 86_400L
        mutateSleepSessions { sessions ->
            sessions.filter { it.startEpochSecond < cutoff }
        }
        redetectSleepSessions()
    }

    /** Re-runs detection from the current cached data and publishes the result. */
    private suspend fun redetectSleepSessions() {
        val ready = (_uiState.value as? DashboardUiState.Ready) ?: lastReadyState ?: return
        val tempRecords = ready.temperatureRecords.map { history ->
            RecordItem(
                type = 0,
                tStart = history.watchTimestampStart,
                tEnd = history.watchTimestampEnd,
                value = history.value,
                syncTime = history.syncTimestamp,
            )
        }
        // Reset is an explicit request to rebuild, so run detection even when advanced
        // features are currently gated off; otherwise the reset would silently wipe sessions.
        val sessions = sleepMutationMutex.withLock {
            val rebuilt = refreshSleepSessions(
                intervals = ready.stepIntervals,
                temperatureRecords = tempRecords,
                advancedEnabled = true,
            )
            syncSleepToHealthConnect(rebuilt)
            rebuilt
        }
        val updated = ready.copy(sleepSessions = sessions)
        lastReadyState = updated
        _uiState.value = updated
    }

    /**
     * Re-adds a removed session, used by the undo action. It becomes a user-owned session so a
     * later detection pass cannot drop it again.
     */
    fun restoreSleepSession(session: SleepSession) = viewModelScope.launch(Dispatchers.IO) {
        mutateSleepSessions { sessions ->
            sessions.filterNot {
                it.id == session.id || it.id == "dismissed_${session.id}"
            } + session.copy(
                dismissed = false,
                source = SleepSessionSource.USER,
                confidence = 1.0,
            )
        }
    }

    fun dismissSleepSession(id: String) = viewModelScope.launch(Dispatchers.IO) {
        mutateSleepSessions { sessions ->
            val target = sessions.firstOrNull { it.id == id }
                ?: return@mutateSleepSessions sessions
            sessions.filterNot { it.id == id } + target.copy(
                id = "dismissed_${target.id}",
                dismissed = true,
                confidence = 0.0,
                source = SleepSessionSource.DETECTED,
            )
        }
    }

    fun addSleepSession(startEpochSecond: Long, endEpochSecond: Long) =
        viewModelScope.launch(Dispatchers.IO) {
            if (endEpochSecond <= startEpochSecond) return@launch
            mutateSleepSessions { sessions ->
                val userSession = SleepSession(
                    id = "user_${startEpochSecond}_${endEpochSecond}",
                    startEpochSecond = startEpochSecond,
                    endEpochSecond = endEpochSecond,
                    kind = inferSleepKind(startEpochSecond, endEpochSecond),
                    confidence = 1.0,
                    source = SleepSessionSource.USER,
                )
                sessions.filterNot {
                    it.source == SleepSessionSource.DETECTED && !it.dismissed && it.overlaps(userSession)
                } + userSession
            }
        }

    private suspend fun mutateSleepSessions(
        transform: (List<SleepSession>) -> List<SleepSession>,
    ) {
        sleepMutationMutex.withLock {
            val current = deviceStore.getSleepSessions().first()
            val updated = transform(current)
                .filter { it.endEpochSecond > it.startEpochSecond }
                .distinctBy { it.id }
                .sortedBy { it.startEpochSecond }
            deviceStore.saveSleepSessions(updated)
            syncSleepToHealthConnect(updated)
            val ready = (_uiState.value as? DashboardUiState.Ready) ?: lastReadyState
                ?: return@withLock
            val newState = ready.copy(sleepSessions = updated)
            lastReadyState = newState
            _uiState.value = newState
        }
    }

    /**
     * Mirrors the stored sleep sessions into Health Connect: active sessions are (re)written
     * under their stable ids, and records for removed sessions are deleted.
     */
    private suspend fun syncSleepToHealthConnect(sessions: List<SleepSession>) {
        sleepHcMutex.withLock {
            if (!deviceStore.isSleepHcUuidMigrated().first()) {
                if (!healthConnectClient.deleteAllOwnSleepSessions()) {
                    Log.w(VM_TAG, "Sleep id migration skipped; will retry")
                    return@withLock
                }
                deviceStore.saveWrittenSleepRecordIds(emptySet())
                deviceStore.setSleepHcUuidMigrated()
            }
            // Only user-confirmed sessions are exported. Detections stay local until confirmed.
            val active = sessions.filter {
                !it.dismissed &&
                    it.source == SleepSessionSource.USER &&
                    it.endEpochSecond > it.startEpochSecond
            }
            val previous = deviceStore.getWrittenSleepRecordIds().first()
            val desired = active.map { it.id }.toSet()
            val stale = previous - desired
            if (stale.isNotEmpty()) {
                healthConnectClient.deleteSleepSessions(stale.toList())
            }
            val written = healthConnectClient.writeSleepSessions(active).toSet()
            deviceStore.saveWrittenSleepRecordIds((previous - stale) + written)
            if (written.isNotEmpty() || stale.isNotEmpty()) {
                deviceStore.saveHcSleepWrite(Instant.now().epochSecond, written.size)
            }
        }
    }

    // ── Detail screen state ──────────────────────────────────────────────

    sealed class DetailScreen {
        data class Records(
            val title: String,
            val records: List<HistoryEntry>,
        ) : DetailScreen()
    }

    private val _detailScreen = MutableStateFlow<DetailScreen?>(null)
    val detailScreen: StateFlow<DetailScreen?> = _detailScreen.asStateFlow()

    fun showRecordsDetail(title: String, records: List<HistoryEntry>) {
        _detailScreen.value = DetailScreen.Records(title, records)
    }

    fun dismissDetailScreen() {
        _detailScreen.value = null
    }
}
