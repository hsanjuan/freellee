package link.hector.freellee.ui.charts

import androidx.compose.runtime.Immutable
import link.hector.freellee.STEP_RETENTION_DAYS
import link.hector.freellee.TEMPERATURE_RETENTION_DAYS
import link.hector.freellee.data.DeviceStore.RecordItem
import link.hector.freellee.data.DeviceStore.StepInterval
import link.hector.freellee.data.MIN_VALID_TEMPERATURE
import link.hector.freellee.data.TemperaturePeriod
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.PriorityQueue

/** Number of days visible in the steps viewport. */
const val STEPS_WINDOW_DAYS = 7

/** Number of hours visible in the temperature viewport. */
const val TEMPERATURE_WINDOW_HOURS = 24

/** Everything the steps chart needs to draw, prepared off the UI thread. */
@Immutable
data class StepsChartData(
    val days: List<DailyStepsPoint>,
    val referenceAverage: Double?,
)

/** Everything the temperature chart needs to draw, prepared off the UI thread. */
@Immutable
data class TemperatureChartData(
    val points: List<HourlyTemperaturePoint>,
    val hourlyAverages: List<HourlyAveragePoint>,
    val baselines: List<BaselineSegment>,
    val windowStartEpochSecond: Long,
    val endHourEpochSecond: Long,
    val totalHours: Int,
)

/** Prepared chart inputs for the Data tab. */
@Immutable
data class DashboardChartData(
    val steps: StepsChartData,
    val temperature: TemperatureChartData,
)

/** One bar in the daily steps chart. */
@Immutable
data class DailyStepsPoint(
    val date: LocalDate,
    val steps: Int,
)

/** One hourly bucket in the skin-temperature chart. A null average means no valid reading. */
@Immutable
data class HourlyTemperaturePoint(
    val startEpochSecond: Long,
    val endEpochSecond: Long,
    val averageCelsius: Double?,
)

/** The typical value for one clock hour, based on earlier readings at that same hour. */
@Immutable
data class HourlyAveragePoint(
    val startEpochSecond: Long,
    val endEpochSecond: Long,
    val averageCelsius: Double?,
)

/** A horizontal baseline segment for one day part. [endEpochSecond] is exclusive. */
@Immutable
data class BaselineSegment(
    val startEpochSecond: Long,
    val endEpochSecond: Long,
    val celsius: Double,
)

/**
 * Builds both chart data sets in one call. This is intentionally pure and cheap enough to run
 * on [kotlinx.coroutines.Dispatchers.Default] whenever the underlying records change.
 */
fun buildDashboardChartData(
    stepIntervals: List<StepInterval>,
    temperatureRecords: List<RecordItem>,
    nowEpochSecond: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): DashboardChartData = DashboardChartData(
    steps = buildStepsChartData(stepIntervals, nowEpochSecond, zone),
    temperature = buildTemperatureChartData(temperatureRecords, nowEpochSecond, zone),
)

fun buildStepsChartData(
    intervals: List<StepInterval>,
    nowEpochSecond: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): StepsChartData {
    val today = Instant.ofEpochSecond(nowEpochSecond).atZone(zone).toLocalDate()
    val oldestDate = intervals.minOfOrNull {
        Instant.ofEpochSecond(it.tStart).atZone(zone).toLocalDate()
    }
    val totalDays = maxOf(
        ChronoUnit.DAYS.between(oldestDate ?: today, today).toInt() + 1,
        STEPS_WINDOW_DAYS,
    ).coerceAtMost(STEP_RETENTION_DAYS.toInt())
    return StepsChartData(
        days = dailyStepsWindow(intervals, today, totalDays, zone),
        referenceAverage = rollingDailyAverageSteps(intervals, nowEpochSecond),
    )
}

fun buildTemperatureChartData(
    records: List<RecordItem>,
    nowEpochSecond: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): TemperatureChartData {
    val endHour = Instant.ofEpochSecond(nowEpochSecond)
        .atZone(zone)
        .truncatedTo(ChronoUnit.HOURS)
        .plusHours(1)
        .toEpochSecond()
    val firstRecord = records.minOfOrNull { it.tStart } ?: nowEpochSecond
    val lastRecord = records.maxOfOrNull { it.tStart } ?: nowEpochSecond
    val localHours = LocalHourBucketer(zone, firstRecord, lastRecord)
    val earliestHour = records.minOfOrNull { localHours.hourStart(it.tStart) }
    val totalHours = Duration.between(
        Instant.ofEpochSecond(earliestHour ?: endHour),
        Instant.ofEpochSecond(endHour),
    ).toHours().toInt().coerceIn(
        TEMPERATURE_WINDOW_HOURS,
        (TEMPERATURE_RETENTION_DAYS * 24).toInt(),
    )
    val windowStart = shiftHours(endHour, -totalHours.toLong(), zone)
    return TemperatureChartData(
        points = hourlyTemperatureWindow(records, endHour, totalHours, zone),
        hourlyAverages = hourlyAverageWindow(records, endHour, totalHours, zone),
        baselines = temperatureBaselineSegments(records, windowStart, endHour, zone),
        windowStartEpochSecond = windowStart,
        endHourEpochSecond = endHour,
        totalHours = totalHours,
    )
}

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
    val windowStartEpoch = endHour.minusHours(hourCount.toLong()).toEpochSecond()
    val windowEndEpoch = endHour.toEpochSecond()
    val localHours = LocalHourBucketer(zone, windowStartEpoch, windowEndEpoch - 1)

    // Bucket once instead of scanning the full record list for every visible hour.
    val sums = HashMap<Long, DoubleArray>()
    records.forEach { record ->
        if (record.value < MIN_VALID_TEMPERATURE) return@forEach
        if (record.tStart < windowStartEpoch || record.tStart >= windowEndEpoch) return@forEach
        val bucketStart = localHours.hourStart(record.tStart)
        val accumulator = sums.getOrPut(bucketStart) { DoubleArray(2) }
        accumulator[0] += record.value / 100.0
        accumulator[1] += 1.0
    }

    return (hourCount downTo 1).map { hoursAgo ->
        val start = endHour.minusHours(hoursAgo.toLong())
        val end = start.plusHours(1)
        val startEpoch = start.toEpochSecond()
        val accumulator = sums[startEpoch]
        HourlyTemperaturePoint(
            startEpochSecond = startEpoch,
            endEpochSecond = end.toEpochSecond(),
            averageCelsius = if (accumulator == null || accumulator[1] == 0.0) {
                null
            } else {
                accumulator[0] / accumulator[1]
            },
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
    val firstRecord = records.minOfOrNull { it.tStart } ?: 0L
    val lastRecord = records.maxOfOrNull { it.tStart } ?: 0L
    val localHours = LocalHourBucketer(zone, firstRecord, lastRecord)
    val seriesByHour = records
        .filter { it.value >= MIN_VALID_TEMPERATURE }
        .groupBy { localHours.hourOfDay(it.tStart) }
        .mapValues { (_, values) ->
            val sorted = values.sortedBy { it.tStart }
            val starts = LongArray(sorted.size)
            val prefixSums = DoubleArray(sorted.size + 1)
            sorted.forEachIndexed { index, record ->
                starts[index] = record.tStart
                prefixSums[index + 1] = prefixSums[index] + record.value / 100.0
            }
            HourlySeries(starts, prefixSums)
        }

    return (hourCount downTo 1).map { hoursAgo ->
        val start = endHour.minusHours(hoursAgo.toLong())
        val end = start.plusHours(1)
        val startEpoch = start.toEpochSecond()
        val series = seriesByHour[start.hour]
        val count = if (series == null) 0 else countBefore(series.starts, startEpoch)

        HourlyAveragePoint(
            startEpochSecond = startEpoch,
            endEpochSecond = end.toEpochSecond(),
            averageCelsius = if (count == 0 || series == null) {
                null
            } else {
                series.prefixSums[count] / count
            },
        )
    }
}

private data class HourlySeries(
    val starts: LongArray,
    val prefixSums: DoubleArray,
)

/** Number of sorted [starts] entries strictly before [cutoff]. */
private fun countBefore(starts: LongArray, cutoff: Long): Int {
    var low = 0
    var high = starts.size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (starts[middle] < cutoff) {
            low = middle + 1
        } else {
            high = middle
        }
    }
    return low
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

    // Work out each reading's day part once instead of for every visible segment.
    val firstRecord = records.minOfOrNull { it.tStart } ?: windowStartEpochSecond
    val lastRecord = records.maxOfOrNull { it.tStart } ?: windowEndEpochSecond
    val localHours = LocalHourBucketer(zone, firstRecord, lastRecord)
    val readingsByPeriod = records
        .asSequence()
        .filter { it.value >= MIN_VALID_TEMPERATURE }
        .map { record ->
            PeriodReading(
                period = TemperaturePeriod.fromHour(localHours.hourOfDay(record.tStart)),
                tStart = record.tStart,
                celsius = record.value / 100.0,
            )
        }
        .groupBy { it.period }
        .mapValues { (_, values) -> values.sortedBy { it.tStart } }

    val baselines = baselineByDayPart(readingsByPeriod, startDate, endDate, zone)

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
                val baseline = baselines[periodStart.epochSecond]
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

private data class PeriodReading(
    val period: TemperaturePeriod,
    val tStart: Long,
    val celsius: Double,
)

/**
 * Median per visible day part, calculated incrementally with two heaps so earlier readings are
 * only visited once instead of being re-sorted for every segment.
 */
private fun baselineByDayPart(
    readingsByPeriod: Map<TemperaturePeriod, List<PeriodReading>>,
    startDate: LocalDate,
    endDate: LocalDate,
    zone: ZoneId,
): Map<Long, Double> {
    val result = HashMap<Long, Double>()

    CLOCK_ORDER.forEach { period ->
        val readings = readingsByPeriod[period].orEmpty()
        val lower = PriorityQueue(compareByDescending<Double> { it })
        val upper = PriorityQueue<Double>()
        var readingIndex = 0
        var date = startDate
        while (!date.isAfter(endDate)) {
            val periodStart = period.getBoundaries(date, zone).first.epochSecond
            while (readingIndex < readings.size && readings[readingIndex].tStart < periodStart) {
                addToMedian(lower, upper, readings[readingIndex].celsius)
                readingIndex++
            }
            median(lower, upper)?.let { result[periodStart] = it }
            date = date.plusDays(1)
        }
    }

    return result
}

private fun addToMedian(
    lower: PriorityQueue<Double>,
    upper: PriorityQueue<Double>,
    value: Double,
) {
    val lowerPeek = lower.peek()
    if (lowerPeek == null || value <= lowerPeek) {
        lower.add(value)
    } else {
        upper.add(value)
    }
    if (lower.size > upper.size + 1) {
        upper.add(lower.poll())
    } else if (upper.size > lower.size) {
        lower.add(upper.poll())
    }
}

private fun median(lower: PriorityQueue<Double>, upper: PriorityQueue<Double>): Double? {
    val lowerPeek = lower.peek() ?: return null
    val upperPeek = upper.peek() ?: return lowerPeek
    return if (lower.size == upper.size) {
        (lowerPeek + upperPeek) / 2.0
    } else {
        lowerPeek
    }
}

/** Shifts [epochSecond] by [hours] using local wall-clock hours (DST-aware). */
fun shiftHours(epochSecond: Long, hours: Long, zone: ZoneId): Long =
    Instant.ofEpochSecond(epochSecond).atZone(zone).plusHours(hours).toEpochSecond()

/**
 * Local-hour bucketing that avoids allocating a ZonedDateTime for every reading when the zone
 * offset is constant over the range. Ranges crossing a DST transition fall back to the exact
 * Java Time calculation.
 */
private class LocalHourBucketer(
    private val zone: ZoneId,
    firstEpochSecond: Long,
    lastEpochSecond: Long,
) {
    private val fixedOffsetSeconds: Int? = run {
        val firstOffset = zone.rules
            .getOffset(Instant.ofEpochSecond(firstEpochSecond))
            .totalSeconds
        val lastOffset = zone.rules
            .getOffset(Instant.ofEpochSecond(lastEpochSecond))
            .totalSeconds
        if (firstOffset == lastOffset) firstOffset else null
    }

    fun hourStart(epochSecond: Long): Long {
        val offset = fixedOffsetSeconds
        return if (offset == null) {
            Instant.ofEpochSecond(epochSecond)
                .atZone(zone)
                .truncatedTo(ChronoUnit.HOURS)
                .toEpochSecond()
        } else {
            Math.floorDiv(epochSecond + offset, SECONDS_PER_HOUR) * SECONDS_PER_HOUR - offset
        }
    }

    fun hourOfDay(epochSecond: Long): Int {
        val offset = fixedOffsetSeconds
        return if (offset == null) {
            Instant.ofEpochSecond(epochSecond).atZone(zone).hour
        } else {
            Math.floorMod((epochSecond + offset) / SECONDS_PER_HOUR, 24L).toInt()
        }
    }
}


private val CLOCK_ORDER = listOf(
    TemperaturePeriod.NIGHT,
    TemperaturePeriod.MORNING,
    TemperaturePeriod.AFTERNOON,
    TemperaturePeriod.EVENING,
)

private const val SECONDS_PER_HOUR = 3_600L
private const val SECONDS_PER_DAY = 24L * SECONDS_PER_HOUR
