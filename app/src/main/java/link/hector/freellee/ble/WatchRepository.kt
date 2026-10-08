package link.hector.freellee.ble

import android.annotation.SuppressLint
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.TimeZone

private const val MAX_RECORDS = 5_000
private const val TAG = "WatchRepository"

/** Result of a full health/activity log drain. */
data class RecordSyncResult(
    val records: List<ActivityRecord>,
    val expectedCount: Int,
    val drained: Boolean,
    val acknowledged: Boolean,
    val failure: String? = null,
)

/**
 * High-level Ollee watch API built on [WatchGattClient].
 *
 * Each method sends one OAP request and decodes the reply with the codecs in
 * `OapCodecs.kt`. Reply payloads are the bytes after the reply command byte.
 */
class WatchRepository(val gatt: WatchGattClient) {

    private val connectMutex = Mutex()

    val connectionState get() = gatt.state

    val deviceName: String?
        @SuppressLint("MissingPermission")
        get() = gatt.connectedDevice?.name

    val macAddress: String?
        get() = gatt.connectedDevice?.address

    val isConnected: Boolean get() = gatt.state.value == ConnectionState.READY

    suspend fun connect(
        address: String,
        autoConnect: Boolean = false,
        timeoutMs: Long = 120_000,
    ): Boolean = connectMutex.withLock {
        if (gatt.state.value == ConnectionState.READY) {
            false
        } else {
            gatt.connect(address, autoConnect, timeoutMs)
            true
        }
    }

    suspend fun disconnect() = gatt.disconnect()

    // ── request plumbing ────────────────────────────────────────────────────

    private suspend fun read(
        cmd: Int,
        timeoutMs: Long = 5_000,
        retries: Int = 1,
    ): ByteArray = gatt.request(cmd, timeoutMs = timeoutMs, retries = retries).payload

    private suspend fun write(
        cmd: Int,
        payload: ByteArray,
        timeoutMs: Long = 5_000,
        retries: Int = 0,
    ): ByteArray = gatt.request(cmd, payload, timeoutMs = timeoutMs, retries = retries).payload

    /** Send any command verbatim (subject to the protocol's safety checks). */
    suspend fun raw(cmd: Int, payload: ByteArray = ByteArray(0), allowDangerous: Boolean = false): ByteArray {
        OapProtocol.commandProblem(cmd, payload)?.let { throw OapCodecException(it) }
        if (cmd in OapProtocol.DANGEROUS_CMDS && !allowDangerous) {
            throw OapCodecException("0x%02X destroys user data; pass allowDangerous".format(cmd))
        }
        return gatt.request(cmd, payload, timeoutMs = 8_000, retries = 0).payload
    }

    // ── identity ────────────────────────────────────────────────────────────

    /** Parsed info from a single version (0x2A) request: firmware string + battery voltage. */
    data class DeviceInfo(val firmware: String, val voltageMv: Int?, val version: VersionInfo? = null)

    /** Read firmware string and battery voltage in a single 0x2A request. */
    suspend fun deviceInfo(): DeviceInfo {
        val payload = read(0x2A, timeoutMs = 3_000, retries = 1)
        val version = VersionInfo.decode(payload)
        return DeviceInfo(version.firmware, version.voltageMv, version)
    }

    /** Full version reply: hash id, hardware id, firmware id, serial, voltage. */
    suspend fun version(): VersionInfo = VersionInfo.decode(read(0x2A, timeoutMs = 3_000, retries = 1))

    /** 6-byte Bluetooth name tag (0x2E). */
    suspend fun name(): String = Nametag.decode(read(0x2E, timeoutMs = 3_000, retries = 1))

    /** Set the 6-byte Bluetooth name tag (0x2F). */
    suspend fun setNameTag(value: String) {
        write(0x2F, Nametag.encode(value), timeoutMs = 3_000)
    }

    // ── health / activity records ───────────────────────────────────────────

    /** Daily step goal (0x30, first 4 bytes big-endian). */
    suspend fun stepGoal(): Int {
        val payload = read(0x30, timeoutMs = 3_000, retries = 1)
        if (payload.size < 4) throw IOException("invalid pedometer response")
        return payload.beI32(0)
    }

    /** Step goal + pulsometer target beats (0x30). */
    suspend fun health(): HealthSettings = HealthSettings.decode(read(0x30, timeoutMs = 3_000, retries = 1))

    /** Step goal + pulsometer target beats (0x31). */
    suspend fun setHealth(health: HealthSettings) {
        write(0x31, health.encode(), timeoutMs = 3_000)
    }

    /** Current BLE connection interval (0x39, u16 big-endian). */
    suspend fun connectionInterval(): Int = decodeConnInterval(read(0x39, timeoutMs = 3_000, retries = 1))

    /** Connectivity probe; the keep-alive uses the same command. */
    suspend fun liveValue(): Int = connectionInterval()

    /**
     * Read today's incremental steps from synced records.
     * Returns 0 if no step records are present.
     */
    suspend fun readTodayStepsFromRecords(records: List<ActivityRecord>): Int {
        val now = System.currentTimeMillis() / 1000
        val todayStart = now - (now % 86400)

        val stepRecords = records
            .filter { it.type == ActivityType.STEPS }
            .filter { it.tStart >= todayStart }

        val totalSteps = stepRecords.sumOf { it.value }
        Log.d(TAG, "Today's steps from records: $totalSteps (${stepRecords.size} records)")
        return totalSteps
    }

    /**
     * Drain the watch's health/activity log: ask for the count (0x27), fetch
     * each record (0x28), durably persist them, then acknowledge (0x2D).
     */
    suspend fun syncRecords(
        onProgress: (fetched: Int, total: Int) -> Unit = { _, _ -> },
        persistRecords: suspend (List<ActivityRecord>) -> Unit,
    ): RecordSyncResult = gatt.burst {
        val count = try {
            countRecords()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = "Could not read the watch record count: ${e.message ?: "unknown error"}"
            Log.w(TAG, reason, e)
            return@burst RecordSyncResult(
                emptyList(), expectedCount = 0, drained = false,
                acknowledged = false, failure = reason,
            )
        }
        if (count !in 0..MAX_RECORDS) {
            val reason = "Watch returned an invalid record count: $count"
            Log.w(TAG, reason)
            return@burst RecordSyncResult(
                emptyList(), count, drained = false, acknowledged = false, failure = reason,
            )
        }
        if (count == 0) {
            return@burst RecordSyncResult(
                emptyList(), expectedCount = 0, drained = true,
                acknowledged = true,
            )
        }
        val records = ArrayList<ActivityRecord>(count)

        suspend fun preservePartial(reason: String): RecordSyncResult {
            persistRecords(records.toList())
            return RecordSyncResult(
                records, count, drained = false,
                acknowledged = false, failure = reason,
            )
        }

        repeat(count) { index ->
            try {
                val frame = gatt.request(0x28, timeoutMs = 4_000, retries = 0)
                val record = parseActivityRecord(frame.payload)
                if (record == null) {
                    val reason = "Watch returned a malformed record at ${index + 1} of $count"
                    Log.w(TAG, reason)
                    return@burst preservePartial(reason)
                }
                records.add(record)
                onProgress(index + 1, count)
                if (record.type == ActivityType.STEPS) {
                    Log.d(
                        TAG,
                        "syncRecords: step record #${index + 1}: tStart=${record.tStart}, " +
                            "tEnd=${record.tEnd}, value=${record.value}",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason = "Record fetch ${index + 1} of $count failed: " +
                    (e.message ?: "unknown error")
                Log.w(TAG, reason, e)
                return@burst preservePartial(reason)
            }
        }

        persistRecords(records.toList())

        var cleanupFailure: String? = null
        val cleanupConfirmed = try {
            gatt.request(0x2D, timeoutMs = 3_000, retries = 0)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = "Could not verify record cleanup: " +
                (e.message ?: "unknown error")
            cleanupFailure = reason
            Log.w(TAG, reason, e)
            false
        }
        RecordSyncResult(
            records, count, drained = true, acknowledged = cleanupConfirmed,
            failure = cleanupFailure,
        )
    }

    private suspend fun countRecords(): Int {
        val payload = gatt.request(0x27, timeoutMs = 5_000, retries = 2).payload
        Log.d(TAG, "record count response: ${payload.size} bytes: ${OapProtocol.hex(payload)}")
        if (payload.size < 4) throw IOException("invalid record-count response")
        val count = payload.beI32(0)
        Log.d(TAG, "record count parsed value: $count")
        return count
    }

    /** Erase all stored activity/health records (0x2D). Destructive. */
    suspend fun eraseActivity() {
        gatt.request(0x2D, timeoutMs = 8_000, retries = 0)
    }

    // ── time reference ──────────────────────────────────────────────────────

    /**
     * Push the phone's time and timezone. Location is left unset; use
     * [setTimeReference] when a real latitude/longitude/lunitidal interval is known.
     */
    suspend fun syncTime() {
        val tz = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000
        setTimeReference(TimeReference.now(tz))
    }

    /** Push the full time/location reference (0x23). */
    suspend fun setTimeReference(timeReference: TimeReference) {
        write(0x23, timeReference.encode(), timeoutMs = 3_000)
    }

    // ── configuration ───────────────────────────────────────────────────────

    /** Read the device settings register (0x32). */
    suspend fun config(): DeviceConfig = DeviceConfig.decode(read(0x32, timeoutMs = 8_000, retries = 1))

    /** Write the device settings register (0x33). */
    suspend fun setConfig(config: DeviceConfig) {
        write(0x33, config.encode(), timeoutMs = 8_000)
    }

    /** Read the complete alarm configuration (0x2B). */
    suspend fun alarms(): Alarm = Alarm.decode(read(0x2B, timeoutMs = 8_000, retries = 1))

    /** Write the complete alarm configuration (0x25). */
    suspend fun setAlarms(alarm: Alarm) {
        write(0x25, alarm.encode(), timeoutMs = 8_000)
    }

    /**
     * Convenience: enable the main alarm at [hour]:[minute] on the given weekday
     * mask ([daysMask] uses `1 shl (weekday.ordinal + 1)`, bit 0 unused).
     */
    suspend fun setAlarm(hour: Int, minute: Int, daysMask: Int) {
        val days = Weekday.entries
            .filter { (daysMask and (1 shl (it.ordinal + 1))) != 0 }
            .toSet()
        setAlarms(Alarm.build(alarmOn = true, hours = hour, minutes = minute, days = days, snoozeOn = true))
    }

    /** Disable the main alarm. */
    suspend fun clearAlarm() = setAlarms(Alarm.build(alarmOn = false))

    /** Read the countdown timer state (0x2C). */
    suspend fun timerState(): TimerState = TimerState.decode(read(0x2C, timeoutMs = 3_000, retries = 1))

    /**
     * Upload the timer presets (0x26). The watch cannot report them back, so all
     * ten are always written; by default the request only sets the duration.
     */
    suspend fun setTimerPresets(
        presetsSeconds: List<Int> = TimerSettings.DEFAULT_PRESETS_SECONDS,
        hours: Int = 0,
        minutes: Int = 0,
        seconds: Int = 0,
        action: TimerAction = TimerAction.SET_DURATION,
    ) {
        write(0x26, TimerSettings.encode(presetsSeconds, hours, minutes, seconds, action), timeoutMs = 8_000)
    }

    /** Read the watch faces and their swipe order (0x37). */
    suspend fun faces(): List<Face> = Faces.decode(read(0x37, timeoutMs = 8_000, retries = 1))

    /** Write the watch faces and their swipe order (0x36). */
    suspend fun setFaces(faces: List<Face>) {
        write(0x36, Faces.encode(faces), timeoutMs = 8_000)
    }

    /** Read the world-time offset and weekday names (0x35). */
    suspend fun worldTime(): WorldTime = WorldTime.decode(read(0x35, timeoutMs = 8_000, retries = 1))

    /** Write the world-time offset and weekday names (0x34). */
    suspend fun setWorldTime(value: WorldTime) {
        write(0x34, value.encode(), timeoutMs = 8_000)
    }

    /** Read the databank vault (0x3C). */
    suspend fun databank(): List<DatabankEntry> = Databank.decode(read(0x3C, timeoutMs = 8_000, retries = 1))

    /** Write the databank vault (0x3B). Destructive to the unused tail of the flash record. */
    suspend fun setDatabank(entries: List<DatabankEntry>) {
        write(0x3B, Databank.encode(entries), timeoutMs = 8_000)
    }

    /** Push a 5-day weather forecast (0x3A, write-only). */
    suspend fun setWeather(weather: Weather) {
        write(0x3A, weather.encode(), timeoutMs = 8_000)
    }

    /** Push a pairing code and switch the watch to its pairing screen (0x29). */
    suspend fun setPairCode(pairCode: PairCode) {
        write(0x29, pairCode.encode(), timeoutMs = 3_000)
    }

    /** Inject a synthetic button gesture (0x38, debug). */
    suspend fun setButtonInput(input: ButtonInput) {
        write(0x38, input.encode(), timeoutMs = 3_000)
    }

    /** Reboot the watch (0x20 with a non-destructive sub-command). */
    suspend fun reboot() {
        // 0x06 is the app's plain warm reboot; unknown sub-commands also reboot.
        gatt.request(0x20, byteArrayOf(0x06), timeoutMs = 3_000, retries = 0)
    }

    /** Request a reboot into the OTA bootloader (0x21). The reply may arrive just before reset. */
    suspend fun startBootloader() {
        gatt.request(0x21, timeoutMs = 3_000, retries = 0)
    }

    /** Factory reset: wipes settings and stored activity (0x20 sub-command 0x01). Destructive. */
    suspend fun factoryReset() {
        gatt.request(0x20, byteArrayOf(0x01), timeoutMs = 8_000, retries = 0)
    }
}
