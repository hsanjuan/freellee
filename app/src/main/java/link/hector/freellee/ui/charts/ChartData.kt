package link.hector.freellee.ui.charts

import androidx.compose.runtime.Immutable
import link.hector.freellee.STEP_RETENTION_DAYS
import link.hector.freellee.TEMPERATURE_RETENTION_DAYS
import link.hector.freellee.data.DeviceStore.RecordItem
import link.hector.freellee.data.DeviceStore.StepInterval
import link.hector.freellee.data.MIN_VALID_TEMPERATURE
import link.hector.freellee.data.SleepInsights
import link.hector.freellee.data.SleepSession
import link.hector.freellee.data.TemperatureDayAggregate
import link.hector.freellee.data.TemperatureInsights
import link.hector.freellee.data.TemperaturePeriod
import link.hector.freellee.data.buildSleepInsights
import link.hector.freellee.data.buildTemperatureDayAggregates
import link.hector.freellee.data.buildTemperatureInsights
import link.hector.freellee.data.inferStepIntervalSeconds
import link.hector.freellee.data.inferTemperatureSampleSeconds
import link.hector.freellee.data.medianOrNull
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
    val today: LocalDate,
    /** Today's step intervals since 00:00, sorted by start. */
    val todayIntervals: List<TodayStepInterval>,
)

/** One interval in today's compact steps chart. */
@Immutable
data class TodayStepInterval(
    val startEpochSecond: Long,
    val endEpochSecond: Long,
    val steps: Int,
)

/** Everything the temperature chart and summary card need, prepared off the UI thread. */
@Immutable
data class TemperatureChartData(
    val points: List<HourlyTemperaturePoint>,
    /** Gaps with no valid readings; the watch may not have been worn or the values were rejected. */
    val notWornRanges: List<NotWornRange>,
    /** Time since the last successful sync; these readings simply have not arrived yet. */
    val unsyncedRange: NotWornRange?,
    /** The personal reference profile, drawn as a dashed step line. */
    val baselines: List<BaselineSegment>,
    val insights: TemperatureInsights,
    val windowStartEpochSecond: Long,
    val endHourEpochSecond: Long,
    val totalHours: Int,
)

/** A stretch with no valid temperature readings, shaded on the chart. */
@Immutable
data class NotWornRange(
    val startEpochSecond: Long,
    val endEpochSecond: Long,
)

/** Prepared chart inputs for the Data tab. */
@Immutable
data class DashboardChartData(
    val steps: StepsChartData,
    val temperature: TemperatureChartData,
    val sleep: SleepInsights,
)

/** One bar in the daily steps chart. */
@Immutable
data class DailyStepsPoint(
    val date: LocalDate,
    val steps: Int,
    /** False when the day has no synced intervals, so "no data" differs from a genuine zero. */
    val hasData: Boolean,
)

/**
 * One hourly bucket in the skin-temperature chart. The value is the median of the valid readings
 * in the bucket; a null median means no valid reading.
 */
@Immutable
data class HourlyTemperaturePoint(
    val startEpochSecond: Long,
    val endEpochSecond: Long,
    val medianCelsius: Double?,
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
    temperatureAggregates: List<TemperatureDayAggregate> = emptyList(),
    sleepSessions: List<SleepSession> = emptyList(),
    lastSyncEpochSecond: Long? = null,
): DashboardChartData = DashboardChartData(
    steps = buildStepsChartData(stepIntervals, nowEpochSecond, zone),
    temperature = buildTemperatureChartData(
        records = temperatureRecords,
        nowEpochSecond = nowEpochSecond,
        zone = zone,
        aggregates = temperatureAggregates,
        lastSyncEpochSecond = lastSyncEpochSecond,
    ),
    sleep = buildSleepInsights(
        sessions = sleepSessions,
        temperatureRecords = temperatureRecords,
        aggregates = temperatureAggregates,
        nowEpochSecond = nowEpochSecond,
        zone = zone,
        sampleSeconds = inferTemperatureSampleSeconds(temperatureRecords),
        stepIntervalSeconds = inferStepIntervalSeconds(stepIntervals),
    ),
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
    val todayStart = today.atStartOfDay(zone).toEpochSecond()
    val tomorrowStart = today.plusDays(1).atStartOfDay(zone).toEpochSecond()
    return StepsChartData(
        days = dailyStepsWindow(intervals, today, totalDays, zone),
        referenceAverage = rollingDailyAverageSteps(intervals, nowEpochSecond, zone = zone),
        today = today,
        todayIntervals = intervals
            .asSequence()
            .filter { it.tStart >= todayStart && it.tStart < tomorrowStart && it.steps > 0 }
            .sortedBy { it.tStart }
            .map { TodayStepInterval(it.tStart, it.tEnd, it.steps) }
            .toList(),
    )
}

fun buildTemperatureChartData(
    records: List<RecordItem>,
    nowEpochSecond: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    aggregates: List<TemperatureDayAggregate> = emptyList(),
    lastSyncEpochSecond: Long? = null,
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
    val effectiveAggregates = aggregates.ifEmpty {
        buildTemperatureDayAggregates(
            records = records,
            nowEpochSecond = nowEpochSecond,
            zone = zone,
        )
    }
    val insights = buildTemperatureInsights(
        aggregates = effectiveAggregates,
        nowEpochSecond = nowEpochSecond,
        zone = zone,
        syncedUntilEpochSecond = lastSyncEpochSecond,
    )
    val profile = insights.referenceProfile
    val baselines = if (profile.any { it != null }) {
        temperatureReferenceSegments(profile, windowStart, endHour, zone)
    } else {
        temperatureBaselineSegments(records, windowStart, endHour, zone)
    }
    return TemperatureChartData(
        points = hourlyTemperatureWindow(records, endHour, totalHours, zone),
        notWornRanges = notWornRanges(records, windowStart, endHour),
        unsyncedRange = unsyncedRange(
            records = records,
            windowStart = windowStart,
            nowEpochSecond = nowEpochSecond,
            lastSyncEpochSecond = lastSyncEpochSecond,
        ),
        baselines = baselines,
        insights = insights,
        windowStartEpochSecond = windowStart,
        endHourEpochSecond = endHour,
        totalHours = totalHours,
    )
}

/**
 * The stretch between the last successful sync and now. Those readings have not arrived yet,
 * so the chart shows them separately from genuine gaps instead of flagging them as missing.
 */
private fun unsyncedRange(
    records: List<RecordItem>,
    windowStart: Long,
    nowEpochSecond: Long,
    lastSyncEpochSecond: Long?,
): NotWornRange? {
    val lastSync = lastSyncEpochSecond ?: return null
    val start = lastSync.coerceIn(windowStart, nowEpochSecond)
    if (start >= nowEpochSecond) return null
    val sampleSeconds = inferTemperatureSampleSeconds(records).toLong()
    // Ignore a sync that is only one or two samples old; that is normal live data.
    val threshold = (sampleSeconds * 2).coerceIn(8L * 60L, 60L * 60L)
    return if (nowEpochSecond - start >= threshold) {
        NotWornRange(startEpochSecond = start, endEpochSecond = nowEpochSecond)
    } else {
        null
    }
}

/**
 * Interior gaps between valid readings that are long enough to mean the watch was off the
 * wrist. The threshold scales with the sampling interval, so hourly firmware does not light up
 * every normal one-hour gap.
 */
private fun notWornRanges(
    records: List<RecordItem>,
    windowStart: Long,
    windowEnd: Long,
): List<NotWornRange> {
    val starts = records
        .asSequence()
        .filter { it.value >= MIN_VALID_TEMPERATURE }
        .map { it.tStart }
        .distinct()
        .sorted()
        .filter { it >= windowStart && it < windowEnd }
        .toList()
    if (starts.size < 2) return emptyList()

    val sampleSeconds = inferTemperatureSampleSeconds(records).toLong()
    val threshold = (sampleSeconds * 3).coerceIn(30L * 60L, 3L * 3_600L)
    return buildList {
        starts.zipWithNext { previous, next ->
            if (next - previous > threshold) {
                add(NotWornRange(previous + sampleSeconds, next))
            }
        }
    }
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
    val present = BooleanArray(dayCount)

    intervals.forEach { interval ->
        val date = Instant.ofEpochSecond(interval.tStart).atZone(zone).toLocalDate()
        val index = ChronoUnit.DAYS.between(startDate, date).toInt()
        if (index in 0 until dayCount) {
            totals[index] += interval.steps.toLong()
            present[index] = true
        }
    }

    return List(dayCount) { index ->
        DailyStepsPoint(
            date = startDate.plusDays(index.toLong()),
            steps = totals[index].coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            hasData = present[index],
        )
    }
}

/**
 * Average steps over the previous [days] complete days, excluding today. Today is still in
 * progress and would drag the average down. Returns null when none of those days have data.
 */
fun rollingDailyAverageSteps(
    intervals: List<StepInterval>,
    nowEpochSecond: Long,
    days: Int = 7,
    zone: ZoneId = ZoneId.systemDefault(),
): Double? {
    require(days > 0)
    val today = Instant.ofEpochSecond(nowEpochSecond).atZone(zone).toLocalDate()
    val previous = dailyStepsWindow(
        intervals = intervals,
        endDate = today.minusDays(1),
        dayCount = days,
        zone = zone,
    ).filter { it.hasData }
    if (previous.isEmpty()) return null
    return previous.sumOf { it.steps.toDouble() } / previous.size
}

/**
 * Hourly medians for the [hourCount] hours ending at [endEpochSecond] (exclusive).
 *
 * The median resists the short spikes that are common in wrist temperature; the mean does not.
 * A null median marks an hour with no valid reading.
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
    val valuesByHour = HashMap<Long, MutableList<Double>>()
    records.forEach { record ->
        if (record.value < MIN_VALID_TEMPERATURE) return@forEach
        if (record.tStart < windowStartEpoch || record.tStart >= windowEndEpoch) return@forEach
        val bucketStart = localHours.hourStart(record.tStart)
        valuesByHour.getOrPut(bucketStart) { mutableListOf() } += record.value / 100.0
    }

    return (hourCount downTo 1).map { hoursAgo ->
        val start = endHour.minusHours(hoursAgo.toLong())
        val end = start.plusHours(1)
        val startEpoch = start.toEpochSecond()
        HourlyTemperaturePoint(
            startEpochSecond = startEpoch,
            endEpochSecond = end.toEpochSecond(),
            medianCelsius = valuesByHour[startEpoch]?.medianOrNull(),
        )
    }
}

/**
 * Step line for the personal reference profile across the visible chart window. The profile is
 * indexed by local clock hour and repeated for every visible day. Hours without enough history
 * are omitted.
 */
fun temperatureReferenceSegments(
    profile: List<Double?>,
    windowStartEpochSecond: Long,
    windowEndEpochSecond: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): List<BaselineSegment> {
    if (windowEndEpochSecond <= windowStartEpochSecond) return emptyList()

    val segments = mutableListOf<BaselineSegment>()
    var hour = Instant.ofEpochSecond(windowStartEpochSecond)
        .atZone(zone)
        .truncatedTo(ChronoUnit.HOURS)
    while (hour.toEpochSecond() < windowEndEpochSecond) {
        val next = hour.plusHours(1)
        val segmentStart = maxOf(hour.toEpochSecond(), windowStartEpochSecond)
        val segmentEnd = minOf(next.toEpochSecond(), windowEndEpochSecond)
        val celsius = profile.getOrNull(hour.hour)
        if (celsius != null && segmentStart < segmentEnd) {
            segments += BaselineSegment(segmentStart, segmentEnd, celsius)
        }
        hour = next
    }
    return segments
}

/**
 * Fallback day-part baseline used while there is not yet a personal hourly reference profile.
 * Parts without enough history are omitted.
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
