package link.hector.freellee.data

import link.hector.freellee.data.DeviceStore.RecordItem
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Local-time day parts used to group skin-temperature readings.
 *
 * Health Connect writes one [androidx.health.connect.client.records.SkinTemperatureRecord] per
 * day part, and the dashboard shows the same baseline values. Keeping the definition here makes
 * sure both views always agree.
 */
enum class TemperaturePeriod(val label: String) {
    MORNING("Morning"),
    AFTERNOON("Afternoon"),
    EVENING("Evening"),
    NIGHT("Night");

    fun getBoundaries(date: LocalDate, zone: ZoneId): Pair<Instant, Instant> {
        val start = when (this) {
            NIGHT -> date.atTime(0, 0).atZone(zone).toInstant()
            MORNING -> date.atTime(6, 0).atZone(zone).toInstant()
            AFTERNOON -> date.atTime(12, 0).atZone(zone).toInstant()
            EVENING -> date.atTime(18, 0).atZone(zone).toInstant()
        }
        val end = when (this) {
            NIGHT -> date.atTime(5, 59, 59).atZone(zone).toInstant()
            MORNING -> date.atTime(11, 59, 59).atZone(zone).toInstant()
            AFTERNOON -> date.atTime(17, 59, 59).atZone(zone).toInstant()
            EVENING -> date.atTime(23, 59, 59).atZone(zone).toInstant()
        }
        return start to end
    }

    companion object {
        /** Order used for presentation, starting with the morning. */
        val displayOrder: List<TemperaturePeriod> = listOf(MORNING, AFTERNOON, EVENING, NIGHT)

        fun fromHour(hour: Int): TemperaturePeriod = when (hour) {
            in 0..5 -> NIGHT
            in 6..11 -> MORNING
            in 12..17 -> AFTERNOON
            in 18..23 -> EVENING
            else -> throw IllegalArgumentException("Invalid hour: $hour")
        }

        /** The period that contains [epochSecond] in [zone]. */
        fun of(epochSecond: Long, zone: ZoneId): TemperaturePeriod =
            fromHour(Instant.ofEpochSecond(epochSecond).atZone(zone).toLocalTime().hour)
    }
}

/** One measured (or currently in-progress) day-part interval. */
data class TemperatureInterval(
    val period: TemperaturePeriod,
    val startEpochSecond: Long,
    val endEpochSecond: Long,
    /** Median of earlier readings in this period, in °C, or null when there is no history yet. */
    val baselineCelsius: Double?,
    /** Mean deviation of this interval's readings from [baselineCelsius], in °C. */
    val deviationCelsius: Double?,
    /** Timestamp of the most recent reading in this interval. */
    val lastMeasuredAtEpochSecond: Long?,
    /** Number of valid readings (at or above 25 °C) in this interval. */
    val readingCount: Int,
)

/**
 * The day-part interval that contains [nowEpochSecond], including readings recorded so far.
 *
 * Its baseline is the median of earlier readings in the same period (matching
 * [link.hector.freellee.data.HealthConnectClient]); readings below 25 °C are discarded. This
 * interval is still being built, so Health Connect will only receive it after it completes.
 */
fun currentTemperatureInterval(
    records: List<RecordItem>,
    nowEpochSecond: Long = Instant.now().epochSecond,
    zone: ZoneId = ZoneId.systemDefault(),
): TemperatureInterval {
    val local = Instant.ofEpochSecond(nowEpochSecond).atZone(zone)
    val date = local.toLocalDate()
    val period = TemperaturePeriod.fromHour(local.toLocalTime().hour)
    val start = period.getBoundaries(date, zone).first.epochSecond

    return buildTemperatureInterval(
        records = records.filter { it.tStart <= nowEpochSecond },
        period = period,
        startEpochSecond = start,
        endEpochSecond = nowEpochSecond,
        nowEpochSecond = nowEpochSecond,
        zone = zone,
    )
}

/**
 * Up to [count] completed day-part intervals before the current one, newest first.
 * Intervals without readings are skipped.
 */
fun previousTemperatureIntervals(
    records: List<RecordItem>,
    count: Int = 3,
    nowEpochSecond: Long = Instant.now().epochSecond,
    zone: ZoneId = ZoneId.systemDefault(),
): List<TemperatureInterval> {
    val local = Instant.ofEpochSecond(nowEpochSecond).atZone(zone)
    val currentDate = local.toLocalDate()
    val currentPeriod = TemperaturePeriod.fromHour(local.toLocalTime().hour)
    val currentStart = currentPeriod.getBoundaries(currentDate, zone).first.epochSecond

    val pastRecords = records.filter { it.tStart < currentStart }
    val bounds = pastRecords
        .map { record ->
            val recordLocal = Instant.ofEpochSecond(record.tStart).atZone(zone)
            recordLocal.toLocalDate() to
                TemperaturePeriod.fromHour(recordLocal.toLocalTime().hour)
        }
        .distinct()
        .map { (date, period) ->
            val (start, end) = period.getBoundaries(date, zone)
            Triple(period, start.epochSecond, end.epochSecond)
        }
        .sortedByDescending { it.second }
        .take(count)

    return bounds.map { (period, start, end) ->
        buildTemperatureInterval(
            records = pastRecords,
            period = period,
            startEpochSecond = start,
            endEpochSecond = end,
            nowEpochSecond = nowEpochSecond,
            zone = zone,
        )
    }
}

private fun buildTemperatureInterval(
    records: List<RecordItem>,
    period: TemperaturePeriod,
    startEpochSecond: Long,
    endEpochSecond: Long,
    nowEpochSecond: Long,
    zone: ZoneId,
): TemperatureInterval {
    val today = Instant.ofEpochSecond(nowEpochSecond).atZone(zone).toLocalDate()
    val yesterdayEnd = today.minusDays(1).atTime(23, 59, 59).atZone(zone).toEpochSecond()
    val baselinePool = records.filter { it.tStart <= yesterdayEnd }
    val baseline = periodBaseline(
        records = baselinePool,
        period = period,
        beforeEpochSecond = startEpochSecond,
        zone = zone,
    )

    val readings = records.filter {
        it.tStart in startEpochSecond..endEpochSecond
    }
    val validReadings = readings.filter { it.value >= MIN_VALID_TEMPERATURE }
    val deviation = if (baseline != null && validReadings.isNotEmpty()) {
        validReadings.map { it.value / 100.0 - baseline }.average()
    } else {
        null
    }

    return TemperatureInterval(
        period = period,
        startEpochSecond = startEpochSecond,
        endEpochSecond = endEpochSecond,
        baselineCelsius = baseline,
        deviationCelsius = deviation,
        lastMeasuredAtEpochSecond = readings.maxOfOrNull { it.tStart },
        readingCount = validReadings.size,
    )
}

/**
 * Median of the readings that belong to [period] and started before [beforeEpochSecond].
 * Readings below [MIN_VALID_TEMPERATURE] are ignored.
 */
fun periodBaseline(
    records: List<RecordItem>,
    period: TemperaturePeriod,
    beforeEpochSecond: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): Double? {
    val values = records
        .filter { it.tStart < beforeEpochSecond && it.value >= MIN_VALID_TEMPERATURE }
        .filter { TemperaturePeriod.of(it.tStart, zone) == period }
        .map { it.value / 100.0 }
        .sorted()

    if (values.isEmpty()) return null
    val mid = values.size / 2
    return if (values.size % 2 == 0) {
        (values[mid - 1] + values[mid]) / 2.0
    } else {
        values[mid]
    }
}

/** Readings below this value (in centi-degrees Celsius) are treated as sensor noise. */
const val MIN_VALID_TEMPERATURE = 2500
