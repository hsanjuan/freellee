package link.hector.freellee.ui.charts

import link.hector.freellee.data.DeviceStore.RecordItem
import link.hector.freellee.data.DeviceStore.StepInterval
import link.hector.freellee.data.MIN_VALID_TEMPERATURE
import link.hector.freellee.data.TemperaturePeriod
import link.hector.freellee.data.periodBaseline
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** One bar in the daily steps chart. */
data class DailyStepsPoint(
    val date: LocalDate,
    val steps: Int,
)

/** One hourly bucket in the skin-temperature chart. A null average means no valid reading. */
data class HourlyTemperaturePoint(
    val startEpochSecond: Long,
    val endEpochSecond: Long,
    val averageCelsius: Double?,
)

/** The typical value for one clock hour, based on earlier readings at that same hour. */
data class HourlyAveragePoint(
    val startEpochSecond: Long,
    val endEpochSecond: Long,
    val averageCelsius: Double?,
)

/** A horizontal baseline segment for one day part. [endEpochSecond] is exclusive. */
data class BaselineSegment(
    val startEpochSecond: Long,
    val endEpochSecond: Long,
    val celsius: Double,
)

/**
 * Daily step totals for the [dayCount] days ending on [endDate] (inclusive).
 * Intervals are assigned to the local day containing their start, matching the existing
 * "Today" aggregation in the ViewModel.
 */
fun dailyStepsWindow(
    intervals: List<StepInterval>,
    endDate: LocalDate,
    dayCount: Int,
    zone: ZoneId = ZoneId.systemDefault(),
): List<DailyStepsPoint> {
    require(dayCount > 0)
    val startDate = endDate.minusDays((dayCount - 1).toLong())
    val totals = LongArray(dayCount)

    intervals.forEach { interval ->
        val date = Instant.ofEpochSecond(interval.tStart).atZone(zone).toLocalDate()
        val index = ChronoUnit.DAYS.between(startDate, date).toInt()
        if (index in 0 until dayCount) {
            totals[index] += interval.steps.toLong()
        }
    }

    return List(dayCount) { index ->
        DailyStepsPoint(
            date = startDate.plusDays(index.toLong()),
            steps = totals[index].coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        )
    }
}

/**
 * Daily average over the rolling [days]-day window ending at [nowEpochSecond].
 * Returns null when the window has no step data, so the chart can hide the reference line.
 */
fun rollingDailyAverageSteps(
    intervals: List<StepInterval>,
    nowEpochSecond: Long,
    days: Int = 7,
): Double? {
    require(days > 0)
    val windowStart = nowEpochSecond - days.toLong() * SECONDS_PER_DAY
    val total = intervals
        .filter { it.tStart in windowStart..nowEpochSecond }
        .sumOf { it.steps.toLong() }
    return if (total > 0) total.toDouble() / days else null
}

/**
 * Hourly averages for the [hourCount] hours ending at [endEpochSecond] (exclusive).
 * A null average marks an hour with no valid reading.
 */
fun hourlyTemperatureWindow(
    records: List<RecordItem>,
    endEpochSecond: Long,
    hourCount: Int = 24,
    zone: ZoneId = ZoneId.systemDefault(),
): List<HourlyTemperaturePoint> {
    require(hourCount > 0)
    val endHour = Instant.ofEpochSecond(endEpochSecond)
        .atZone(zone)
        .truncatedTo(ChronoUnit.HOURS)

    return (hourCount downTo 1).map { hoursAgo ->
        val start = endHour.minusHours(hoursAgo.toLong())
        val end = start.plusHours(1)
        val startEpoch = start.toEpochSecond()
        val endEpoch = end.toEpochSecond()
        val values = records
            .filter {
                it.tStart in startEpoch until endEpoch &&
                    it.value >= MIN_VALID_TEMPERATURE
            }
            .map { it.value / 100.0 }

        HourlyTemperaturePoint(
            startEpochSecond = startEpoch,
            endEpochSecond = endEpoch,
            averageCelsius = if (values.isEmpty()) null else values.average(),
        )
    }
}

/**
 * Average for each clock hour in the [hourCount] hours ending at [endEpochSecond] (exclusive).
 *
 * This mirrors the day-part baseline but at one-hour resolution: for a bucket, only readings
 * at the same clock hour from earlier days are considered, so the in-progress hour (and any
 * incomplete period) cannot bias its own reference value. Like the baseline, readings below
 * [MIN_VALID_TEMPERATURE] are ignored.
 */
fun hourlyAverageWindow(
    records: List<RecordItem>,
    endEpochSecond: Long,
    hourCount: Int = 24,
    zone: ZoneId = ZoneId.systemDefault(),
): List<HourlyAveragePoint> {
    require(hourCount > 0)
    val endHour = Instant.ofEpochSecond(endEpochSecond)
        .atZone(zone)
        .truncatedTo(ChronoUnit.HOURS)
    val validRecordsByHour = records
        .filter { it.value >= MIN_VALID_TEMPERATURE }
        .groupBy { Instant.ofEpochSecond(it.tStart).atZone(zone).hour }

    return (hourCount downTo 1).map { hoursAgo ->
        val start = endHour.minusHours(hoursAgo.toLong())
        val end = start.plusHours(1)
        val startEpoch = start.toEpochSecond()
        val endEpoch = end.toEpochSecond()
        val values = validRecordsByHour[start.hour]
            .orEmpty()
            .filter { it.tStart < startEpoch }
            .map { it.value / 100.0 }

        HourlyAveragePoint(
            startEpochSecond = startEpoch,
            endEpochSecond = endEpoch,
            averageCelsius = if (values.isEmpty()) null else values.average(),
        )
    }
}

/**
 * Background baseline line for [windowStartEpochSecond] until [windowEndEpochSecond].
 *
 * Each visible day part gets the same period-specific median that
 * [link.hector.freellee.data.HealthConnectClient] writes, computed only from readings that
 * existed before that day part started. Parts without enough history are omitted.
 */
fun temperatureBaselineSegments(
    records: List<RecordItem>,
    windowStartEpochSecond: Long,
    windowEndEpochSecond: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): List<BaselineSegment> {
    if (windowEndEpochSecond <= windowStartEpochSecond) return emptyList()

    val startDate = Instant.ofEpochSecond(windowStartEpochSecond).atZone(zone).toLocalDate()
    val endDate = Instant.ofEpochSecond(windowEndEpochSecond - 1).atZone(zone).toLocalDate()
    val segments = mutableListOf<BaselineSegment>()

    var date = startDate
    while (!date.isAfter(endDate)) {
        CLOCK_ORDER.forEach { period ->
            val (periodStart, periodEnd) = period.getBoundaries(date, zone)
            val segmentStart = maxOf(periodStart.epochSecond, windowStartEpochSecond)
            // getBoundaries() ends one second before the next part; add one second so the
            // displayed step line is continuous.
            val segmentEnd = minOf(periodEnd.epochSecond + 1, windowEndEpochSecond)
            if (segmentStart < segmentEnd) {
                val baseline = periodBaseline(
                    records = records,
                    period = period,
                    beforeEpochSecond = periodStart.epochSecond,
                    zone = zone,
                )
                if (baseline != null) {
                    segments += BaselineSegment(
                        startEpochSecond = segmentStart,
                        endEpochSecond = segmentEnd,
                        celsius = baseline,
                    )
                }
            }
        }
        date = date.plusDays(1)
    }

    return segments
}

/** Shifts [epochSecond] by [hours] using local wall-clock hours (DST-aware). */
fun shiftHours(epochSecond: Long, hours: Long, zone: ZoneId): Long =
    Instant.ofEpochSecond(epochSecond).atZone(zone).plusHours(hours).toEpochSecond()

private val CLOCK_ORDER = listOf(
    TemperaturePeriod.NIGHT,
    TemperaturePeriod.MORNING,
    TemperaturePeriod.AFTERNOON,
    TemperaturePeriod.EVENING,
)

private const val SECONDS_PER_DAY = 24L * 60L * 60L
