package link.hector.freellee.data

import link.hector.freellee.TEMPERATURE_SAMPLE_SECONDS
import link.hector.freellee.data.DeviceStore.RecordItem
import link.hector.freellee.data.DeviceStore.StepInterval
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min

/** Long-term retention for detected and user-corrected sleep sessions. */
const val SLEEP_SESSION_RETENTION_DAYS = 400L

/** How far back the detector recomputes sessions from raw temperature and step data. */
const val SLEEP_DETECTION_DAYS = 28

private const val SECONDS_PER_DAY = 86_400L
private const val MIN_SESSION_SECONDS = 3_600L
private const val MAX_MERGE_GAP_SECONDS = 60L * 60L
private const val MAX_MERGE_GAP_STEPS = 100

/** A short awakening that splits a night is rejoined when it stayed this quiet. */
private const val MAX_SPLIT_GAP_SECONDS = 45L * 60L
private const val MAX_SPLIT_GAP_STEPS = 150

/** Temperature-onset refinement of a zero-step run's start. */
private const val ONSET_BUCKET_SECONDS = 900L
private const val ONSET_RISE_CELSIUS = 1.5
private const val ONSET_SUSTAIN_CELSIUS = 0.8
private const val ONSET_MIN_SHIFT_SECONDS = 3_600L
private const val ONSET_MAX_SHIFT_SECONDS = 4 * 3_600L
private const val ONSET_BASELINE_WINDOW_SECONDS = 5_400L
private const val MIN_TEMP_COVERAGE = 0.4
private const val MIN_DAY_RISE_CELSIUS = 0.4
private const val MIN_SCORE = 0.45

enum class SleepSessionKind { MAIN, NAP }

enum class SleepSessionSource { DETECTED, USER }

/**
 * One sleep bout. The start/end are Unix seconds in the device's local context.
 *
 * Detected sessions are recomputed from raw data. User sessions are authoritative and suppress
 * any overlapping detection. Dismissed sessions are kept out of the UI but still suppress a
 * repeated detection with the same boundaries.
 */
data class SleepSession(
    val id: String,
    val startEpochSecond: Long,
    val endEpochSecond: Long,
    val kind: SleepSessionKind,
    val confidence: Double,
    val source: SleepSessionSource,
    val dismissed: Boolean = false,
) {
    val durationSeconds: Long get() = (endEpochSecond - startEpochSecond).coerceAtLeast(0)
    val durationHours: Double get() = durationSeconds / 3600.0

    /** True when the two sessions share any time at all. */
    fun touches(other: SleepSession): Boolean =
        startEpochSecond < other.endEpochSecond && endEpochSecond > other.startEpochSecond

    /** True when at least half of the shorter session overlaps this one. */
    fun overlaps(other: SleepSession): Boolean {
        val overlap = min(endEpochSecond, other.endEpochSecond) -
            max(startEpochSecond, other.startEpochSecond)
        if (overlap <= 0) return false
        val shorter = min(durationSeconds, other.durationSeconds).coerceAtLeast(1)
        return overlap.toDouble() / shorter >= 0.5
    }
}

/** How far back the dashboard's "recent confirmed sleep" list reaches. */
private const val RECENT_CONFIRMED_SECONDS = 48L * 3_600L

/**
 * A session that ended within roughly two step intervals of now is still running: the next
 * step data has not arrived yet, so the end is provisional.
 */
fun inProgressWindowSeconds(stepIntervalSeconds: Long?): Long =
    ((stepIntervalSeconds ?: 900L) * 2).coerceIn(1_800L, 7_200L)

fun SleepSession.isInProgress(
    nowEpochSecond: Long,
    windowSeconds: Long = inProgressWindowSeconds(null),
): Boolean {
    // The provisional end of a running session comes from the current step bucket and can be
    // slightly in the future, so a negative "since end" must count as in progress as well.
    return nowEpochSecond - endEpochSecond <= windowSeconds
}

/** Hour at which a sleep day begins. A night and the following morning nap share one day. */
const val SLEEP_DAY_START_HOUR = 16

/** The local "sleep day" runs from 16:00 to 16:00. */
fun sleepDayKey(epochSecond: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate {
    val local = Instant.ofEpochSecond(epochSecond).atZone(zone)
    return if (local.hour < SLEEP_DAY_START_HOUR) {
        local.toLocalDate().minusDays(1)
    } else {
        local.toLocalDate()
    }
}

fun sleepDayStart(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Long =
    date.atTime(SLEEP_DAY_START_HOUR, 0).atZone(zone).toEpochSecond()

fun sleepDayEnd(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Long =
    sleepDayStart(date, zone) + SECONDS_PER_DAY

/**
 * Maps a wall-clock time onto a sleep day: times before 16:00 belong to the morning after
 * [date], so a 02:00 edit stays on the same night as a 22:00 start.
 */
fun epochInSleepDay(
    date: LocalDate,
    hour: Int,
    minute: Int,
    zone: ZoneId = ZoneId.systemDefault(),
): Long {
    val start = sleepDayStart(date, zone)
    val base = date.atTime(hour, minute).atZone(zone).toEpochSecond()
    return if (base < start) base + SECONDS_PER_DAY else base
}

/** Kind to use for a user-created or user-corrected session. */
fun inferSleepKind(
    startEpochSecond: Long,
    endEpochSecond: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): SleepSessionKind {
    val duration = (endEpochSecond - startEpochSecond).coerceAtLeast(0)
    val hour = Instant.ofEpochSecond(startEpochSecond).atZone(zone).hour
    val night = hour >= 18 || hour < 6
    return if (duration >= 3 * 3600 && night) SleepSessionKind.MAIN else SleepSessionKind.NAP
}

/**
 * Detects sleep sessions across the whole day from zero-step runs, corroborated with the
 * temperature rise during the run and the fall afterwards.
 *
 * Step intervals are currently one hour, so boundaries are only as precise as the step data.
 * The same logic works with finer intervals when the firmware starts emitting them.
 */
fun detectSleepSessions(
    temperatureRecords: List<RecordItem>,
    stepIntervals: List<StepInterval>,
    nowEpochSecond: Long = Instant.now().epochSecond,
    zone: ZoneId = ZoneId.systemDefault(),
    sampleSeconds: Int = TEMPERATURE_SAMPLE_SECONDS,
    detectionDays: Int = SLEEP_DETECTION_DAYS,
): List<SleepSession> {
    val windowStart = nowEpochSecond - detectionDays * SECONDS_PER_DAY
    val validTemps = temperatureRecords
        .filter { it.value >= MIN_VALID_TEMPERATURE }
        .filter { it.tStart in windowStart..nowEpochSecond }
        .sortedBy { it.tStart }
    val intervals = stepIntervals
        .filter { it.tEnd > it.tStart }
        .filter { it.tStart >= windowStart - SECONDS_PER_DAY && it.tStart <= nowEpochSecond }
        .sortedBy { it.tStart }

    val candidates = stepRuns(intervals).mapNotNull { run ->
        scoreRun(run, validTemps, zone, sampleSeconds)
    }
    val rejoined = mergeSplitSessions(candidates, intervals, zone)
    val refined = rejoined.map { refineStartByTemperature(it, validTemps, zone) }
    return classifyKinds(refined, zone)
}

private data class StepRun(val start: Long, val end: Long)

private fun stepRuns(intervals: List<StepInterval>): List<StepRun> {
    val runs = mutableListOf<StepRun>()
    var runStart: Long? = null
    var runEnd: Long? = null

    fun closeRun() {
        val start = runStart ?: return
        val end = runEnd ?: return
        if (end - start >= MIN_SESSION_SECONDS) runs += StepRun(start, end)
        runStart = null
        runEnd = null
    }

    intervals.forEach { interval ->
        if (interval.steps == 0) {
            val currentEnd = runEnd
            if (runStart == null) {
                runStart = interval.tStart
                runEnd = interval.tEnd
            } else if (currentEnd != null && interval.tStart - currentEnd <= MAX_MERGE_GAP_SECONDS) {
                runEnd = interval.tEnd
            } else {
                closeRun()
                runStart = interval.tStart
                runEnd = interval.tEnd
            }
        } else if (runStart != null) {
            val currentEnd = runEnd
            if (
                currentEnd == null ||
                interval.tStart - currentEnd > MAX_MERGE_GAP_SECONDS ||
                interval.steps > MAX_MERGE_GAP_STEPS
            ) {
                closeRun()
            }
        }
    }
    closeRun()
    return runs
}

private fun scoreRun(
    run: StepRun,
    temps: List<RecordItem>,
    zone: ZoneId,
    sampleSeconds: Int,
): SleepSession? {
    val duration = run.end - run.start
    if (duration < MIN_SESSION_SECONDS) return null

    val during = medianTemperature(temps, run.start, run.end) ?: return null
    val coverage = temperatureCoverage(temps, run.start, run.end, sampleSeconds)
    if (coverage < MIN_TEMP_COVERAGE) return null

    val pre = medianTemperature(temps, run.start - 2 * 3600, run.start)
    val post = medianTemperature(temps, run.end, run.end + 2 * 3600)
    val rise = if (pre == null) 0.0 else during - pre
    val fall = if (post == null) 0.0 else during - post

    val localStart = Instant.ofEpochSecond(run.start).atZone(zone)
    val hour = localStart.hour
    val isNight = hour >= 20 || hour < 6

    // A daytime zero-step block with a falling or flat temperature is usually quiet wakefulness.
    if (!isNight && rise < MIN_DAY_RISE_CELSIUS) return null

    val durationHours = duration / 3600.0
    val durationScore = if (isNight) {
        (durationHours / 6.0).coerceIn(0.0, 1.0)
    } else {
        (durationHours / 3.0).coerceIn(0.0, 1.0)
    }
    val timePrior = when (hour) {
        in 21..23, in 0..2 -> 1.0
        in 3..5 -> 0.85
        in 8..11 -> 0.9
        in 6..7 -> 0.75
        in 12..16 -> 0.6
        else -> 0.5
    }
    val riseScore = (rise / 1.5).coerceIn(0.0, 1.0)
    val fallScore = (fall / 1.5).coerceIn(0.0, 1.0)
    val coverageScore = ((coverage - MIN_TEMP_COVERAGE) / (1.0 - MIN_TEMP_COVERAGE))
        .coerceIn(0.0, 1.0)

    val score = if (isNight) {
        0.40 * durationScore +
            0.25 * timePrior +
            0.20 * riseScore +
            0.10 * fallScore +
            0.05 * coverageScore
    } else {
        0.30 * durationScore +
            0.20 * timePrior +
            0.35 * riseScore +
            0.10 * fallScore +
            0.05 * coverageScore
    }
    if (score < MIN_SCORE) return null
    // Short nighttime bouts need stronger temperature support; otherwise they are usually
    // quiet wakefulness before bed or a recording gap.
    if (isNight && duration < 3 * 3600 && score < 0.6) return null
    // Short daytime bouts need a clear rise, otherwise sitting still looks like a nap.
    if (!isNight && duration < 90 * 60 && rise < 0.8) return null

    return SleepSession(
        id = "det_${run.start}_${run.end}",
        startEpochSecond = run.start,
        endEpochSecond = run.end,
        kind = if (durationHours >= 3.0 && isNight) {
            SleepSessionKind.MAIN
        } else {
            SleepSessionKind.NAP
        },
        confidence = score.coerceIn(0.0, 1.0),
        source = SleepSessionSource.DETECTED,
    )
}

/**
 * Rejoins detections that a brief awakening split in two: same sleep day, a short gap, and very
 * little walking in that gap. A morning nap stays separate because it is hours later.
 */
private fun mergeSplitSessions(
    sessions: List<SleepSession>,
    intervals: List<StepInterval>,
    zone: ZoneId,
): List<SleepSession> {
    if (sessions.size < 2) return sessions
    val sorted = sessions.sortedBy { it.startEpochSecond }
    val result = mutableListOf<SleepSession>()
    var current = sorted.first()

    sorted.drop(1).forEach { next ->
        val gap = next.startEpochSecond - current.endEpochSecond
        val sameDay = sleepDayKey(current.startEpochSecond, zone) ==
            sleepDayKey(next.startEpochSecond, zone)
        val quietGap = stepsBetween(intervals, current.endEpochSecond, next.startEpochSecond) <=
            MAX_SPLIT_GAP_STEPS
        if (gap in 0..MAX_SPLIT_GAP_SECONDS && sameDay && quietGap) {
            current = current.copy(
                id = "det_${current.startEpochSecond}_${next.endEpochSecond}",
                endEpochSecond = next.endEpochSecond,
                confidence = maxOf(current.confidence, next.confidence),
            )
        } else {
            result += current
            current = next
        }
    }
    result += current
    return result
}

/**
 * Skin temperature rises at sleep onset, so a long zero-step run can start well before the
 * wearer actually fell asleep (lying still in bed, watching TV, reading). Trims such a run to
 * the first clear temperature onset, but only when the period before it looks like wakefulness:
 * it must sit at or below the pre-run baseline, and the rise must be sharp and sustained.
 * Conservative by design; ambiguous nights keep the step-based start.
 */
private fun refineStartByTemperature(
    session: SleepSession,
    temps: List<RecordItem>,
    zone: ZoneId,
): SleepSession {
    val hour = Instant.ofEpochSecond(session.startEpochSecond).atZone(zone).hour
    // Daytime naps have noisy temperature; only refine the night.
    if (hour in 6..17) return session

    val start = session.startEpochSecond
    val end = session.endEpochSecond
    if (end - start < 2 * 3_600L) return session

    val preBaseline = medianTemperature(temps, start - 2 * 3_600L, start) ?: return session
    var candidate = start + ONSET_MIN_SHIFT_SECONDS
    val latestStart = minOf(end - 3_600L, start + ONSET_MAX_SHIFT_SECONDS)

    while (candidate <= latestStart) {
        val first = medianTemperature(temps, candidate, candidate + ONSET_BUCKET_SECONDS)
        val second = medianTemperature(
            temps,
            candidate + ONSET_BUCKET_SECONDS,
            candidate + 2 * ONSET_BUCKET_SECONDS,
        )
        if (first != null && second != null &&
            first >= preBaseline + ONSET_RISE_CELSIUS &&
            second >= preBaseline + ONSET_SUSTAIN_CELSIUS
        ) {
            val before = medianTemperature(
                temps,
                candidate - ONSET_BASELINE_WINDOW_SECONDS,
                candidate,
            )
            if (before != null && before <= preBaseline) {
                return session.copy(
                    id = "det_${candidate}_$end",
                    startEpochSecond = candidate,
                )
            }
        }
        candidate += ONSET_BUCKET_SECONDS
    }
    return session
}

private fun stepsBetween(intervals: List<StepInterval>, from: Long, to: Long): Int {
    if (to <= from) return 0
    return intervals
        .asSequence()
        .filter { it.tEnd > from && it.tStart < to }
        .sumOf { it.steps }
}

/**
 * Within each sleep day, the longest session of at least three hours is the main sleep;
 * everything else is a nap.
 */
private fun classifyKinds(sessions: List<SleepSession>, zone: ZoneId): List<SleepSession> =
    sessions
        .groupBy { sleepDayKey(it.startEpochSecond, zone) }
        .flatMap { (_, daySessions) ->
            val main = daySessions.maxByOrNull { it.durationSeconds }
            val mainIsNight = main?.let {
                val hour = Instant.ofEpochSecond(it.startEpochSecond).atZone(zone).hour
                hour >= 18 || hour < 6
            } ?: false
            daySessions.map { session ->
                session.copy(
                    kind = if (
                        session.id == main?.id &&
                        session.durationSeconds >= 3 * 3600 &&
                        mainIsNight
                    ) {
                        SleepSessionKind.MAIN
                    } else {
                        SleepSessionKind.NAP
                    },
                )
            }
        }
        .sortedBy { it.startEpochSecond }

/**
 * Pre-fills "Add missed sleep" with the previous main sleep, shifted onto the selected day, so
 * correcting a night does not start from a generic 90-minute block.
 */
fun defaultSleepRange(
    date: LocalDate,
    sessions: List<SleepSession>,
    nowEpochSecond: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): Pair<Long, Long> {
    val dayStart = sleepDayStart(date, zone)
    val previous = sessions
        .filter {
            !it.dismissed &&
                it.kind == SleepSessionKind.MAIN &&
                it.endEpochSecond <= dayStart
        }
        .maxByOrNull { it.endEpochSecond }

    if (previous != null) {
        val startLocal = Instant.ofEpochSecond(previous.startEpochSecond).atZone(zone)
        val endLocal = Instant.ofEpochSecond(previous.endEpochSecond).atZone(zone)
        val start = epochInSleepDay(date, startLocal.hour, startLocal.minute, zone)
        var end = epochInSleepDay(date, endLocal.hour, endLocal.minute, zone)
        if (end <= start) end += 86_400L
        val clampedEnd = minOf(end, nowEpochSecond)
        if (start < clampedEnd && clampedEnd - start >= 15 * 60) {
            return start to clampedEnd
        }
    }

    val end = minOf(dayStart + 22 * 3600L, nowEpochSecond)
    val start = (end - 90 * 60L).coerceAtLeast(dayStart)
    return start to end
}

/** Combines two sessions into one user-owned session spanning both. */
fun combineSleepSessions(
    first: SleepSession,
    second: SleepSession,
    zone: ZoneId = ZoneId.systemDefault(),
): SleepSession {
    val start = min(first.startEpochSecond, second.startEpochSecond)
    val end = max(first.endEpochSecond, second.endEpochSecond)
    return SleepSession(
        id = "user_${start}_$end",
        startEpochSecond = start,
        endEpochSecond = end,
        kind = inferSleepKind(start, end, zone),
        confidence = 1.0,
        source = SleepSessionSource.USER,
    )
}

/**
 * Merges fresh detections into the stored sessions. User sessions win, and dismissed sessions
 * suppress a repeated detection.
 */
fun mergeSleepSessions(
    existing: List<SleepSession>,
    detected: List<SleepSession>,
    nowEpochSecond: Long = Instant.now().epochSecond,
    detectionDays: Int = SLEEP_DETECTION_DAYS,
): List<SleepSession> {
    val detectionCutoff = nowEpochSecond - detectionDays * SECONDS_PER_DAY
    val retentionCutoff = nowEpochSecond - SLEEP_SESSION_RETENTION_DAYS * SECONDS_PER_DAY

    val old = existing.filter {
        it.endEpochSecond < detectionCutoff &&
            it.startEpochSecond >= retentionCutoff &&
            !it.dismissed
    }
    val recent = existing.filter {
        it.endEpochSecond >= detectionCutoff && it.startEpochSecond >= retentionCutoff
    }
    val user = recent.filter { it.source == SleepSessionSource.USER && !it.dismissed }
    val dismissed = recent.filter { it.dismissed }

    val result = mutableListOf<SleepSession>()
    result += old
    result += user
    result += dismissed
    detected.forEach { detection ->
        if (user.any { it.overlaps(detection) } || dismissed.any { it.overlaps(detection) }) {
            return@forEach
        }
        result += detection
    }

    return result
        .filter { it.startEpochSecond >= retentionCutoff }
        .distinctBy { it.id }
        .sortedBy { it.startEpochSecond }
}

private fun medianTemperature(temps: List<RecordItem>, from: Long, to: Long): Double? {
    if (to <= from) return null
    return temps
        .asSequence()
        .filter { it.tStart in from until to }
        .map { it.value / 100.0 }
        .toList()
        .medianOrNull()
}

private fun temperatureCoverage(
    temps: List<RecordItem>,
    from: Long,
    to: Long,
    sampleSeconds: Int = TEMPERATURE_SAMPLE_SECONDS,
): Double {
    if (to <= from) return 0.0
    val expected = (to - from).toDouble() / sampleSeconds.coerceAtLeast(1)
    if (expected <= 0.0) return 0.0
    val count = temps.count { it.tStart in from until to }
    return (count / expected).coerceIn(0.0, 1.0)
}

/** A session plus its temperature deviation from the personal reference. */
data class SleepSessionSummary(
    val session: SleepSession,
    val deviationCelsius: Double?,
    val coverage: Double,
)

/**
 * Sleep-specific version of the dashboard summary. Only user-confirmed sessions are included,
 * so the dashboard matches what is written to Health Connect.
 */
data class SleepInsights(
    val lastMainSleep: SleepSessionSummary?,
    /** Confirmed sessions (main or nap) that ended in the last 48 hours, newest first. */
    val recentConfirmed: List<SleepSessionSummary>,
    val threeMainSleepDeviationCelsius: Double?,
    /** Mean confirmed sleep per day over the rolling last seven days, in seconds. */
    val averageDailySleepSecondsLast7: Long?,
    /** Confirmed sleep inside the rolling last 24 hours, in seconds. */
    val totalSleepSecondsLast24h: Long?,
)

fun buildSleepInsights(
    sessions: List<SleepSession>,
    temperatureRecords: List<RecordItem>,
    aggregates: List<TemperatureDayAggregate>,
    nowEpochSecond: Long = Instant.now().epochSecond,
    zone: ZoneId = ZoneId.systemDefault(),
    sampleSeconds: Int = TEMPERATURE_SAMPLE_SECONDS,
    stepIntervalSeconds: Long? = null,
): SleepInsights {
    val temps = temperatureRecords
        .filter { it.value >= MIN_VALID_TEMPERATURE }
        .sortedBy { it.tStart }
    val progressWindow = inProgressWindowSeconds(stepIntervalSeconds)
    val active = sessions.filter {
        !it.dismissed &&
            it.source == SleepSessionSource.USER &&
            it.endEpochSecond > it.startEpochSecond &&
            !it.isInProgress(nowEpochSecond, progressWindow)
    }
    val referenceByDay = active
        .map { sleepDayKey(it.startEpochSecond, zone) }
        .distinct()
        .associateWith { day ->
            temperatureReferenceProfile(aggregates = aggregates, beforeDate = day)
        }
    val summaries = active.map { session ->
        val reference = referenceByDay[sleepDayKey(session.startEpochSecond, zone)].orEmpty()
        SleepSessionSummary(
            session = session,
            deviationCelsius = sessionDeviation(session, temps, reference, zone),
            coverage = temperatureCoverage(
                temps = temps,
                from = session.startEpochSecond,
                to = session.endEpochSecond,
                sampleSeconds = sampleSeconds,
            ),
        )
    }

    val lastMain = summaries
        .filter { it.session.kind == SleepSessionKind.MAIN }
        .maxByOrNull { it.session.endEpochSecond }

    val reliableMains = summaries
        .filter {
            it.session.kind == SleepSessionKind.MAIN &&
                it.session.durationSeconds >= 2 * 3600 &&
                it.coverage >= 0.5 &&
                it.deviationCelsius != null
        }
        .sortedByDescending { it.session.startEpochSecond }

    val threeMain = if (reliableMains.size >= 3) {
        reliableMains.take(3).mapNotNull { it.deviationCelsius }.average()
    } else {
        null
    }

    val recentConfirmed = summaries
        .filter { it.session.endEpochSecond >= nowEpochSecond - RECENT_CONFIRMED_SECONDS }
        .sortedByDescending { it.session.startEpochSecond }

    val weekStart = nowEpochSecond - 7 * SECONDS_PER_DAY
    val dayStart = nowEpochSecond - SECONDS_PER_DAY
    val weekSeconds = active.sumOf { sleepOverlapSeconds(it, weekStart, nowEpochSecond) }
    val daySeconds = active.sumOf { sleepOverlapSeconds(it, dayStart, nowEpochSecond) }

    return SleepInsights(
        lastMainSleep = lastMain,
        recentConfirmed = recentConfirmed,
        threeMainSleepDeviationCelsius = threeMain,
        averageDailySleepSecondsLast7 = weekSeconds
            .takeIf { weekSeconds > 0 }
            ?.div(7),
        totalSleepSecondsLast24h = daySeconds.takeIf { daySeconds > 0 },
    )
}

/** Seconds of [session] inside the half-open interval [from, to). */
private fun sleepOverlapSeconds(session: SleepSession, from: Long, to: Long): Long {
    val start = max(session.startEpochSecond, from)
    val end = min(session.endEpochSecond, to)
    return (end - start).coerceAtLeast(0)
}

private fun sessionDeviation(
    session: SleepSession,
    temps: List<RecordItem>,
    reference: List<Double?>,
    zone: ZoneId,
): Double? {
    val residuals = mutableListOf<Double>()
    var hourStart = Instant.ofEpochSecond(session.startEpochSecond)
        .atZone(zone)
        .truncatedTo(java.time.temporal.ChronoUnit.HOURS)
    while (hourStart.toEpochSecond() < session.endEpochSecond) {
        val hourEnd = hourStart.plusHours(1)
        val from = max(session.startEpochSecond, hourStart.toEpochSecond())
        val to = min(session.endEpochSecond, hourEnd.toEpochSecond())
        val median = medianTemperature(temps, from, to)
        val expected = reference.getOrNull(hourStart.hour)
        if (median != null && expected != null) {
            residuals += median - expected
        }
        hourStart = hourEnd
    }
    return if (residuals.size >= 2) residuals.medianOrNull() else null
}
