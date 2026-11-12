package link.hector.freellee.data

import link.hector.freellee.TEMPERATURE_AGGREGATE_RETENTION_DAYS
import link.hector.freellee.TEMPERATURE_REFERENCE_WINDOW_DAYS
import link.hector.freellee.TEMPERATURE_SAMPLE_SECONDS
import link.hector.freellee.data.DeviceStore.RecordItem
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** Minimum number of days with data in a clock hour before it contributes to the reference. */
const val MIN_REFERENCE_DAYS_PER_HOUR = 5

/** Minimum share of expected samples before a night is considered reliable. */
const val MIN_NIGHT_COVERAGE = 0.5

/** Minimum number of night hours with a valid reading before a night is summarised. */
const val MIN_NIGHT_HOURS = 4

/**
 * One local day of derived temperature statistics.
 *
 * The raw 4-minute samples are only kept for [link.hector.freellee.TEMPERATURE_RETENTION_DAYS]
 * days. This compact aggregate is retained for much longer so the personal reference profile can
 * span weeks and a multi-night anomaly cannot teach the baseline that it is normal.
 */
data class TemperatureDayAggregate(
    val date: LocalDate,
    val validCount: Int,
    val expectedCount: Int,
    val firstEpochSecond: Long?,
    val lastEpochSecond: Long?,
    /** Median of the valid readings in each local clock hour, or null when an hour has no data. */
    val hourlyMedians: List<Double?>,
    val hourlyCounts: List<Int>,
    /**
     * Baseline frozen when the day was first aggregated, in °C, per day part. Freezing it keeps
     * Health Connect records stable when the same day is written again after a later sync.
     */
    val periodBaselines: Map<TemperaturePeriod, Double>,
) {
    fun baselineCelsius(period: TemperaturePeriod): Double? = periodBaselines[period]

    fun hourCount(hour: Int): Int = hourlyCounts.getOrElse(hour) { 0 }

    fun nightReadingCount(): Int = TemperaturePeriod.NIGHT.hours.sumOf(::hourCount)

    /** Share of the expected samples that are present for the whole day, in `0.0..1.0`. */
    fun coverage(): Double =
        if (expectedCount <= 0) 0.0 else (validCount.toDouble() / expectedCount).coerceIn(0.0, 1.0)
}

/** One night's deviation from the personal reference profile. */
data class TemperatureNightSummary(
    val date: LocalDate,
    /** Median of the hourly deviations from the reference profile, in °C. */
    val deviationCelsius: Double,
    val readingCount: Int,
    val coverage: Double,
    val hoursWithData: Int,
    /** False when coverage is too low to treat the number as meaningful. */
    val reliable: Boolean,
)

/** The in-progress day part, compared with the same hours in the reference profile. */
data class TemperatureCurrentPeriod(
    val period: TemperaturePeriod,
    val deviationCelsius: Double?,
    val readingCount: Int,
    /** Expected readings for the elapsed part of the period that was actually synced. */
    val expectedCount: Int,
    val coverage: Double,
    val hoursWithData: Int,
)

/** Data completeness for one local day. */
data class TemperatureCoveragePoint(
    val date: LocalDate,
    val validCount: Int,
    val expectedCount: Int,
) {
    val coverage: Double =
        if (expectedCount <= 0) 0.0 else (validCount.toDouble() / expectedCount).coerceIn(0.0, 1.0)
}

/** Today's deviation and coverage for one day part, or null when there is too little data. */
data class TemperaturePeriodSummary(
    val period: TemperaturePeriod,
    val deviationCelsius: Double?,
    val coverage: Double?,
)

/** Everything the temperature summary card needs, derived from the stored day aggregates. */
data class TemperatureInsights(
    /** The personal 24-hour reference profile, before today. Null entries mean no history. */
    val referenceProfile: List<Double?>,
    val lastNight: TemperatureNightSummary?,
    /** Up to seven nights, newest first. */
    val recentNights: List<TemperatureNightSummary>,
    val threeNightDeviationCelsius: Double?,
    val currentPeriod: TemperatureCurrentPeriod?,
    /** Today's deviation for each day part, in Night → Evening order. */
    val todayPeriods: List<TemperaturePeriodSummary>,
    val dailyCoverage: List<TemperatureCoveragePoint>,
)

/**
 * Rebuilds the day aggregates from the retained raw samples, carrying forward aggregates that have
 * already aged out of the raw window.
 *
 * Baselines are only computed from days *before* the day being aggregated. Once a day has a
 * baseline it is never recomputed, so re-syncing a day cannot make its Health Connect baseline
 * drift.
 */
fun buildTemperatureDayAggregates(
    records: List<RecordItem>,
    existing: List<TemperatureDayAggregate> = emptyList(),
    zone: ZoneId = ZoneId.systemDefault(),
    sampleSeconds: Int = TEMPERATURE_SAMPLE_SECONDS,
    nowEpochSecond: Long = Instant.now().epochSecond,
): List<TemperatureDayAggregate> {
    val nowDate = Instant.ofEpochSecond(nowEpochSecond).atZone(zone).toLocalDate()
    val cutoff = nowDate.minusDays(TEMPERATURE_AGGREGATE_RETENTION_DAYS)
    val validRecords = records
        .asSequence()
        .filter { it.value >= MIN_VALID_TEMPERATURE }
        .distinctBy { it.tStart }
        .toList()
    val rawByDate = validRecords.groupBy { localDate(it.tStart, zone) }
    val existingByDate = existing
        .filter { !it.date.isBefore(cutoff) && !it.date.isAfter(nowDate) }
        .associateBy { it.date }

    val dates = (rawByDate.keys + existingByDate.keys)
        .filter { !it.isBefore(cutoff) && !it.isAfter(nowDate) }
        .sorted()

    val result = ArrayList<TemperatureDayAggregate>(dates.size)
    dates.forEach { date ->
        val raw = rawByDate[date]
        val previous = existingByDate[date]
        // The oldest raw day can be truncated by the retention cutoff. Keep the more complete
        // aggregate instead of shrinking it on every later sync.
        val aggregate = if (raw != null && (previous == null || raw.size >= previous.validCount)) {
            buildDayAggregate(
                date = date,
                records = raw,
                zone = zone,
                sampleSeconds = sampleSeconds,
                periodBaselines = previous?.periodBaselines.orEmpty(),
            )
        } else {
            previous ?: return@forEach
        }
        val withBaselines = if (aggregate.periodBaselines.isEmpty()) {
            val profile = temperatureReferenceProfile(result, beforeDate = date)
            aggregate.copy(periodBaselines = periodBaselinesFromProfile(profile))
        } else {
            aggregate
        }
        result += withBaselines
    }
    return result
}

/** Infers the watch's usual sampling interval from the retained samples. */
fun inferTemperatureSampleSeconds(
    records: List<RecordItem>,
    default: Int = TEMPERATURE_SAMPLE_SECONDS,
): Int = inferTemperatureIntervalSeconds(records) ?: default

/**
 * The personal reference profile for [beforeDate], built from hourly medians in the preceding
 * [windowDays] days. Hours with fewer than [minDaysPerHour] days of data stay null rather than
 * being filled with a guess.
 */
fun temperatureReferenceProfile(
    aggregates: List<TemperatureDayAggregate>,
    beforeDate: LocalDate,
    windowDays: Int = TEMPERATURE_REFERENCE_WINDOW_DAYS,
    minDaysPerHour: Int = MIN_REFERENCE_DAYS_PER_HOUR,
): List<Double?> {
    val start = beforeDate.minusDays(windowDays.toLong())
    val window = aggregates.filter { !it.date.isBefore(start) && it.date.isBefore(beforeDate) }
    return List(24) { hour ->
        val values = window.mapNotNull { it.hourlyMedians.getOrNull(hour) }
        if (values.size < minDaysPerHour) null else values.medianOrNull()
    }
}

/** Median of the reference profile over the hours in [period], or null with too little history. */
fun periodBaselineFromProfile(
    profile: List<Double?>,
    period: TemperaturePeriod,
): Double? {
    val values = period.hours.mapNotNull { profile.getOrNull(it) }
    val required = (period.hours.count() + 1) / 2
    return if (values.size >= required) values.medianOrNull() else null
}

/** The complete set of frozen day-part baselines for [profile]. */
fun periodBaselinesFromProfile(
    profile: List<Double?>,
): Map<TemperaturePeriod, Double> =
    TemperaturePeriod.displayOrder.mapNotNull { period ->
        periodBaselineFromProfile(profile, period)?.let { period to it }
    }.toMap()

/**
 * Builds the "last night vs usual" view from the stored aggregates.
 *
 * A night is the fixed 00:00–06:00 temperature window, which is independent of the confirmed
 * sleep sessions in the Sleep tab. The current night is only reported after it has finished.
 */
fun buildTemperatureInsights(
    aggregates: List<TemperatureDayAggregate>,
    nowEpochSecond: Long = Instant.now().epochSecond,
    zone: ZoneId = ZoneId.systemDefault(),
    /**
     * Last moment the watch data was successfully synced. Coverage after it is not counted as
     * missing, because those readings simply have not arrived yet.
     */
    syncedUntilEpochSecond: Long? = null,
): TemperatureInsights {
    val now = Instant.ofEpochSecond(nowEpochSecond).atZone(zone)
    val today = now.toLocalDate()
    val syncedUntil = (syncedUntilEpochSecond ?: nowEpochSecond).coerceIn(0L, nowEpochSecond)
    val byDate = aggregates.associateBy { it.date }
    val reference = temperatureReferenceProfile(aggregates, beforeDate = today)

    val nights = mutableListOf<TemperatureNightSummary>()
    val dates = aggregates.map { it.date }.distinct().sortedDescending()
    for (date in dates) {
        if (date.isAfter(today)) continue
        // The 00:00–06:00 window is still in progress before 06:00.
        if (date == today && now.hour < 6) continue
        val day = byDate[date] ?: continue
        val night = nightSummary(day, referenceForNight(aggregates, date))
        if (night != null) nights += night
        if (nights.size == 7) break
    }

    val latestThree = nights.take(3)
    val threeNight = if (latestThree.size == 3 && latestThree.all { it.reliable }) {
        latestThree.map { it.deviationCelsius }.average()
    } else {
        null
    }

    return TemperatureInsights(
        referenceProfile = reference,
        lastNight = nights.firstOrNull(),
        recentNights = nights,
        threeNightDeviationCelsius = threeNight,
        currentPeriod = currentPeriodSummary(byDate[today], reference, now, zone, syncedUntil),
        todayPeriods = todayPeriodSummaries(byDate[today], reference, now, zone, syncedUntil),
        dailyCoverage = dailyCoverage(aggregates, today, now, zone, syncedUntil),
    )
}

private fun todayPeriodSummaries(
    day: TemperatureDayAggregate?,
    reference: List<Double?>,
    now: ZonedDateTime,
    zone: ZoneId,
    syncedUntilEpochSecond: Long,
): List<TemperaturePeriodSummary> {
    val order = listOf(
        TemperaturePeriod.NIGHT,
        TemperaturePeriod.MORNING,
        TemperaturePeriod.AFTERNOON,
        TemperaturePeriod.EVENING,
    )
    return order.map { period ->
        val residuals = day?.let {
            period.hours.mapNotNull { hour ->
                val value = it.hourlyMedians.getOrNull(hour) ?: return@mapNotNull null
                val expected = reference.getOrNull(hour) ?: return@mapNotNull null
                value - expected
            }
        }.orEmpty()
        val (periodStart, periodEnd) = period.getBoundaries(now.toLocalDate(), zone)
        val elapsed = (minOf(
            now.toEpochSecond(),
            syncedUntilEpochSecond,
            periodEnd.epochSecond,
        ) - periodStart.epochSecond).coerceAtLeast(0L)
        val expectedCount = (day?.expectedCount ?: 0) * (elapsed / 86400.0)
        val readingCount = day?.let { aggregate ->
            period.hours.sumOf { hour -> aggregate.hourCount(hour) }
        } ?: 0
        TemperaturePeriodSummary(
            period = period,
            deviationCelsius = if (residuals.size >= 2) residuals.medianOrNull() else null,
            coverage = if (expectedCount > 0.0) {
                (readingCount / expectedCount).coerceIn(0.0, 1.0)
            } else {
                null
            },
        )
    }
}

private fun buildDayAggregate(
    date: LocalDate,
    records: List<RecordItem>,
    zone: ZoneId,
    sampleSeconds: Int,
    periodBaselines: Map<TemperaturePeriod, Double>,
): TemperatureDayAggregate {
    val hourlyValues = List(24) { mutableListOf<Double>() }
    records.forEach { record ->
        val hour = Instant.ofEpochSecond(record.tStart).atZone(zone).hour
        hourlyValues[hour] += record.value / 100.0
    }
    val lastEnd = records.maxOf { record ->
        maxOf(record.tEnd, record.tStart + sampleSeconds)
    }
    return TemperatureDayAggregate(
        date = date,
        validCount = records.size,
        expectedCount = (24 * 3600 / sampleSeconds).coerceAtLeast(1),
        firstEpochSecond = records.minOf { it.tStart },
        lastEpochSecond = lastEnd,
        hourlyMedians = hourlyValues.map { it.medianOrNull() },
        hourlyCounts = hourlyValues.map { it.size },
        periodBaselines = periodBaselines,
    )
}

private fun nightSummary(
    day: TemperatureDayAggregate,
    reference: List<Double?>,
): TemperatureNightSummary? {
    val residuals = TemperaturePeriod.NIGHT.hours.mapNotNull { hour ->
        val value = day.hourlyMedians.getOrNull(hour) ?: return@mapNotNull null
        val expected = reference.getOrNull(hour) ?: return@mapNotNull null
        value - expected
    }
    if (residuals.size < MIN_NIGHT_HOURS) return null
    val deviation = residuals.medianOrNull() ?: return null
    val expected = day.expectedCount * (TemperaturePeriod.NIGHT.hours.count() / 24.0)
    val count = day.nightReadingCount()
    val coverage = if (expected <= 0.0) 0.0 else (count / expected).coerceIn(0.0, 1.0)
    return TemperatureNightSummary(
        date = day.date,
        deviationCelsius = deviation,
        readingCount = count,
        coverage = coverage,
        hoursWithData = residuals.size,
        reliable = coverage >= MIN_NIGHT_COVERAGE && residuals.size >= MIN_NIGHT_HOURS,
    )
}

private fun referenceForNight(
    aggregates: List<TemperatureDayAggregate>,
    date: LocalDate,
): List<Double?> = temperatureReferenceProfile(aggregates, beforeDate = date)

private fun currentPeriodSummary(
    day: TemperatureDayAggregate?,
    reference: List<Double?>,
    now: ZonedDateTime,
    zone: ZoneId,
    syncedUntilEpochSecond: Long,
): TemperatureCurrentPeriod? {
    if (day == null) return null
    val period = TemperaturePeriod.fromHour(now.hour)
    val residuals = period.hours.mapNotNull { hour ->
        val value = day.hourlyMedians.getOrNull(hour) ?: return@mapNotNull null
        val expected = reference.getOrNull(hour) ?: return@mapNotNull null
        value - expected
    }
    val (periodStart, periodEnd) = period.getBoundaries(now.toLocalDate(), zone)
    // Stop counting expected samples at the last successful sync: the tail has simply not
    // arrived yet and must not look like invalid readings.
    val elapsed = (minOf(now.toEpochSecond(), syncedUntilEpochSecond, periodEnd.epochSecond) -
        periodStart.epochSecond).coerceAtLeast(0L)
    val expected = day.expectedCount * (elapsed / 86400.0)
    val count = period.hours.sumOf { day.hourCount(it) }
    return TemperatureCurrentPeriod(
        period = period,
        deviationCelsius = if (residuals.size >= 2) residuals.medianOrNull() else null,
        readingCount = count,
        expectedCount = expected.toInt(),
        coverage = if (expected <= 0.0) 0.0 else (count / expected).coerceIn(0.0, 1.0),
        hoursWithData = residuals.size,
    )
}

private fun dailyCoverage(
    aggregates: List<TemperatureDayAggregate>,
    today: LocalDate,
    now: ZonedDateTime,
    zone: ZoneId,
    syncedUntilEpochSecond: Long,
): List<TemperatureCoveragePoint> {
    val byDate = aggregates.associateBy { it.date }
    val defaultExpected = 24 * 3600 / TEMPERATURE_SAMPLE_SECONDS
    return (0..6).map { offset ->
        val date = today.minusDays(offset.toLong())
        val day = byDate[date]
        val expected = when {
            date == today -> {
                val midnight = date.atStartOfDay(zone).toEpochSecond()
                val elapsed = (minOf(now.toEpochSecond(), syncedUntilEpochSecond) - midnight)
                    .coerceAtLeast(0L)
                val dayExpected = day?.expectedCount ?: defaultExpected
                if (elapsed <= 0L) 0 else (dayExpected * (elapsed / 86400.0)).toInt().coerceAtLeast(1)
            }

            day == null -> defaultExpected
            else -> day.expectedCount
        }
        TemperatureCoveragePoint(
            date = date,
            validCount = day?.validCount ?: 0,
            expectedCount = expected,
        )
    }
}

private fun localDate(epochSecond: Long, zone: ZoneId): LocalDate =
    Instant.ofEpochSecond(epochSecond).atZone(zone).toLocalDate()

/**
 * CSV export of the long-term daily aggregates. Epoch seconds are used for the first/last
 * readings so the file is unambiguous across time zones.
 */
fun temperatureAggregatesCsv(aggregates: List<TemperatureDayAggregate>): String {
    val header = buildList {
        add("date")
        add("valid_readings")
        add("expected_readings")
        add("coverage")
        add("first_reading_epoch")
        add("last_reading_epoch")
        (0..23).forEach { add("hour_%02d".format(java.util.Locale.US, it)) }
        TemperaturePeriod.entries.forEach {
            add("baseline_${it.name.lowercase(java.util.Locale.US)}")
        }
    }
    val builder = StringBuilder()
    builder.append(header.joinToString(",")).append('\n')
    aggregates.sortedBy { it.date }.forEach { day ->
        val row = buildList {
            add(day.date.toString())
            add(day.validCount.toString())
            add(day.expectedCount.toString())
            add("%.3f".format(java.util.Locale.US, day.coverage()))
            add(day.firstEpochSecond?.toString().orEmpty())
            add(day.lastEpochSecond?.toString().orEmpty())
            day.hourlyMedians.forEach { median ->
                add(median?.let { "%.2f".format(java.util.Locale.US, it) }.orEmpty())
            }
            TemperaturePeriod.entries.forEach { period ->
                add(
                    day.baselineCelsius(period)
                        ?.let { "%.2f".format(java.util.Locale.US, it) }
                        .orEmpty(),
                )
            }
        }
        builder.append(row.joinToString(",")).append('\n')
    }
    return builder.toString()
}

/** Median of a non-empty list, or null for an empty list. */
fun List<Double>.medianOrNull(): Double? {
    if (isEmpty()) return null
    val sorted = sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 0) {
        (sorted[middle - 1] + sorted[middle]) / 2.0
    } else {
        sorted[middle]
    }
}
