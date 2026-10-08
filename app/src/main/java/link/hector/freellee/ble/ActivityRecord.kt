package link.hector.freellee.ble

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Activity record types, taken from word 0 of a `0x48` record.
 *
 * [GAME_ONE_SCORE] and [GAME_TWO_SCORE] are part of the protocol but are not
 * emitted by any known device.
 */
object ActivityType {
    const val STEPS = 0          // value = steps in [tStart, tEnd]
    const val TEMPERATURE = 1    // value = skin temperature (centi-°C)
    const val HEART_RATE = 2     // instantaneous sample (tEnd = 0); value = bpm
    const val COUNTER = 3        // cumulative counter (tEnd = 0)
    const val GAME_ONE_SCORE = 4 // game-1 score (not emitted by known devices)
    const val GAME_TWO_SCORE = 5 // game-2 score (not emitted by known devices)
    const val STOPWATCH = 6      // stopwatch session; value = elapsed ms

    fun label(type: Int): String = when (type) {
        STEPS -> "steps"
        TEMPERATURE -> "temperature"
        HEART_RATE -> "heart rate"
        COUNTER -> "counter"
        GAME_ONE_SCORE -> "game 1 score"
        GAME_TWO_SCORE -> "game 2 score"
        STOPWATCH -> "stopwatch"
        else -> "type $type"
    }
}

/** A single `0x48` health/activity record. Timestamps are normalized Unix seconds. */
data class ActivityRecord(val type: Int, val tStart: Long, val tEnd: Long, val value: Int) {
    val celsius: Double get() = value / 100.0
    val bpm: Int get() = value
}

/**
 * Decode a `0x48` record payload: `[type:4][tStart:4][tEnd:4][value:4]`, big-endian.
 *
 * The watch encodes timestamps as local wall-clock fields in an epoch-shaped
 * integer (UTC epoch + local offset), rather than as true UTC instants. Decode
 * those fields in [zoneId] so storage and day grouping use real Unix seconds.
 */
fun parseActivityRecord(payload: ByteArray, zoneId: ZoneId = ZoneId.systemDefault()): ActivityRecord? {
    if (payload.size < 16) return null
    return ActivityRecord(
        payload.beI32(0),
        watchLocalEpochToUnix(payload.beU32(4), zoneId),
        watchLocalEpochToUnix(payload.beU32(8), zoneId),
        payload.beI32(12),
    )
}

private fun watchLocalEpochToUnix(value: Long, zoneId: ZoneId): Long {
    if (value == 0L) return 0L
    val localFields = LocalDateTime.ofEpochSecond(value, 0, ZoneOffset.UTC)
    return localFields.atZone(zoneId).toEpochSecond()
}
