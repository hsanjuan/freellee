package link.hector.freellee.data

import link.hector.freellee.data.DeviceStore.RecordItem
import link.hector.freellee.data.DeviceStore.StepInterval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

class SleepSessionTest {

    private val zone = ZoneId.of("UTC")
    private val nightDate = LocalDate.of(2026, 11, 9)

    private fun record(date: LocalDate, hour: Int, minute: Int, celsius: Double): RecordItem {
        val start = date.atTime(hour, minute).atZone(zone).toEpochSecond()
        return RecordItem(
            type = 1,
            tStart = start,
            tEnd = start + 240,
            value = (celsius * 100).roundToInt(),
        )
    }

    private fun temperatureRange(
        date: LocalDate,
        startHour: Int,
        endHour: Int,
        celsius: Double,
    ): List<RecordItem> =
        (startHour until endHour).flatMap { hour ->
            (0 until 60 step 4).map { minute ->
                record(date, hour, minute, celsius)
            }
        }

    private fun step(date: LocalDate, hour: Int, steps: Int): StepInterval {
        val start = date.atTime(hour, 0).atZone(zone).toEpochSecond()
        return StepInterval(tStart = start, tEnd = start + 3600, steps = steps)
    }

    private fun now(): Long = nightDate.plusDays(1).atTime(12, 0).atZone(zone).toEpochSecond()

    private fun biphasicFixture(): Pair<List<RecordItem>, List<StepInterval>> {
        val temps = buildList {
            addAll(temperatureRange(nightDate.minusDays(1), 22, 24, 29.5))
            addAll(temperatureRange(nightDate, 0, 7, 32.0))
            addAll(temperatureRange(nightDate, 7, 9, 30.5))
            addAll(temperatureRange(nightDate, 9, 11, 33.0))
            addAll(temperatureRange(nightDate, 11, 13, 30.0))
        }
        val steps = buildList {
            add(step(nightDate.minusDays(1), 21, 120))
            add(step(nightDate.minusDays(1), 22, 120))
            add(step(nightDate.minusDays(1), 23, 120))
            (0..6).forEach { add(step(nightDate, it, 0)) }
            add(step(nightDate, 7, 300))
            add(step(nightDate, 8, 250))
            add(step(nightDate, 9, 0))
            add(step(nightDate, 10, 0))
            add(step(nightDate, 11, 400))
            add(step(nightDate, 12, 600))
        }
        return temps to steps
    }

    @Test
    fun `detects a main night and a separate morning nap`() {
        val (temps, steps) = biphasicFixture()

        val sessions = detectSleepSessions(
            temperatureRecords = temps,
            stepIntervals = steps,
            nowEpochSecond = now(),
            zone = zone,
        )

        assertEquals(2, sessions.size)
        val main = sessions.single { it.kind == SleepSessionKind.MAIN }
        val nap = sessions.single { it.kind == SleepSessionKind.NAP }
        assertEquals(nightDate.atTime(0, 0).atZone(zone).toEpochSecond(), main.startEpochSecond)
        assertEquals(nightDate.atTime(7, 0).atZone(zone).toEpochSecond(), main.endEpochSecond)
        assertEquals(nightDate.atTime(9, 0).atZone(zone).toEpochSecond(), nap.startEpochSecond)
        assertEquals(nightDate.atTime(11, 0).atZone(zone).toEpochSecond(), nap.endEpochSecond)
        assertTrue(main.confidence > nap.confidence)
    }

    @Test
    fun `hourly temperature still detects a night with fifteen-minute steps`() {
        val nightStart = nightDate.minusDays(1).atTime(22, 0).atZone(zone).toEpochSecond()
        val nightEnd = nightDate.atTime(7, 0).atZone(zone).toEpochSecond()
        val temps = (20..32).map { hourOffset ->
            val instant = nightDate.minusDays(1).atStartOfDay(zone).plusHours(hourOffset.toLong())
            val sleeping = instant.hour >= 22 || instant.hour < 7
            RecordItem(
                type = 1,
                tStart = instant.toEpochSecond(),
                tEnd = instant.toEpochSecond() + 3600,
                value = ((if (sleeping) 32.0 else 30.0) * 100).roundToInt(),
            )
        }
        val steps = buildList {
            (0 until 8).forEach { quarter ->
                val start = nightDate.minusDays(1).atTime(20, 0).atZone(zone).toEpochSecond() +
                    quarter * 900L
                add(StepInterval(tStart = start, tEnd = start + 900, steps = 200))
            }
            var start = nightStart
            while (start < nightEnd) {
                add(StepInterval(tStart = start, tEnd = start + 900, steps = 0))
                start += 900
            }
            (0 until 8).forEach { quarter ->
                val start = nightEnd + quarter * 900L
                add(StepInterval(tStart = start, tEnd = start + 900, steps = 200))
            }
        }

        val sessions = detectSleepSessions(
            temperatureRecords = temps,
            stepIntervals = steps,
            nowEpochSecond = now(),
            zone = zone,
            sampleSeconds = 3600,
        )

        assertEquals(1, sessions.size)
        assertEquals(nightStart, sessions.single().startEpochSecond)
        assertEquals(nightEnd, sessions.single().endEpochSecond)
        assertEquals(SleepSessionKind.MAIN, sessions.single().kind)
    }

    @Test
    fun `temperature onset trims an early zero-step start`() {
        val nightStart = nightDate.minusDays(1).atTime(22, 0).atZone(zone).toEpochSecond()
        val onset = nightDate.atTime(1, 30).atZone(zone).toEpochSecond()
        val nightEnd = nightDate.atTime(7, 0).atZone(zone).toEpochSecond()
        val temps = buildList {
            addAll(temperatureRange(nightDate.minusDays(1), 20, 22, 30.5))
            addAll(temperatureRange(nightDate.minusDays(1), 22, 24, 29.5))
            addAll(temperatureRange(nightDate, 0, 1, 29.5))
            (0 until 30 step 4).forEach { minute -> add(record(nightDate, 1, minute, 29.5)) }
            (30 until 60 step 4).forEach { minute -> add(record(nightDate, 1, minute, 33.0)) }
            addAll(temperatureRange(nightDate, 2, 7, 31.5))
        }
        val steps = buildList {
            add(step(nightDate.minusDays(1), 21, 300))
            var start = nightStart
            while (start < nightEnd) {
                add(StepInterval(tStart = start, tEnd = start + 3600, steps = 0))
                start += 3600
            }
            add(step(nightDate, 7, 300))
        }

        val sessions = detectSleepSessions(
            temperatureRecords = temps,
            stepIntervals = steps,
            nowEpochSecond = now(),
            zone = zone,
        )

        assertEquals(1, sessions.size)
        assertEquals(onset, sessions.single().startEpochSecond)
        assertEquals(nightEnd, sessions.single().endEpochSecond)
    }

    @Test
    fun `temperature onset trims a start less than an hour after the run begins`() {
        val nightStart = nightDate.minusDays(1).atTime(23, 0).atZone(zone).toEpochSecond()
        val onset = nightDate.minusDays(1).atTime(23, 30).atZone(zone).toEpochSecond()
        val nightEnd = nightDate.atTime(7, 0).atZone(zone).toEpochSecond()
        val temps = buildList {
            addAll(temperatureRange(nightDate.minusDays(1), 21, 23, 29.5))
            (0 until 30 step 4).forEach { minute ->
                add(record(nightDate.minusDays(1), 23, minute, 29.5))
            }
            (30 until 60 step 4).forEach { minute ->
                add(record(nightDate.minusDays(1), 23, minute, 33.0))
            }
            addAll(temperatureRange(nightDate, 0, 7, 31.5))
        }
        val steps = buildList {
            add(step(nightDate.minusDays(1), 22, 300))
            var start = nightStart
            while (start < nightEnd) {
                add(StepInterval(tStart = start, tEnd = start + 3600, steps = 0))
                start += 3600
            }
            add(step(nightDate, 7, 300))
        }

        val sessions = detectSleepSessions(
            temperatureRecords = temps,
            stepIntervals = steps,
            nowEpochSecond = now(),
            zone = zone,
        )

        assertEquals(1, sessions.size)
        assertEquals(onset, sessions.single().startEpochSecond)
        assertEquals(nightEnd, sessions.single().endEpochSecond)
    }

    @Test
    fun `temperature fall trims a late step-based end`() {
        val nightStart = nightDate.minusDays(1).atTime(22, 0).atZone(zone).toEpochSecond()
        val wake = nightDate.atTime(6, 45).atZone(zone).toEpochSecond()
        val temps = buildList {
            addAll(temperatureRange(nightDate.minusDays(1), 20, 22, 29.5))
            addAll(temperatureRange(nightDate.minusDays(1), 22, 24, 32.0))
            addAll(temperatureRange(nightDate, 0, 6, 32.0))
            (0 until 45 step 4).forEach { minute ->
                add(record(nightDate, 6, minute, 32.0))
            }
            (45 until 60 step 4).forEach { minute ->
                add(record(nightDate, 6, minute, 31.0))
            }
            addAll(temperatureRange(nightDate, 7, 8, 30.5))
        }
        val steps = buildList {
            add(step(nightDate.minusDays(1), 21, 300))
            var start = nightStart
            while (start < nightDate.atTime(6, 45).atZone(zone).toEpochSecond()) {
                add(StepInterval(tStart = start, tEnd = start + 900, steps = 0))
                start += 900
            }
            // Few steps while waking up: not enough to close the zero-step run by themselves.
            add(StepInterval(tStart = start, tEnd = start + 900, steps = 5))
            start += 900
            add(StepInterval(tStart = start, tEnd = start + 900, steps = 0))
            start += 900
            add(StepInterval(tStart = start, tEnd = start + 900, steps = 300))
        }

        val sessions = detectSleepSessions(
            temperatureRecords = temps,
            stepIntervals = steps,
            nowEpochSecond = now(),
            zone = zone,
        )

        assertEquals(1, sessions.size)
        assertEquals(nightStart, sessions.single().startEpochSecond)
        assertEquals(wake, sessions.single().endEpochSecond)
    }

    @Test
    fun `temperature onset keeps the start when the night is already warm`() {
        val nightStart = nightDate.minusDays(1).atTime(22, 0).atZone(zone).toEpochSecond()
        val nightEnd = nightDate.atTime(7, 0).atZone(zone).toEpochSecond()
        val temps = buildList {
            addAll(temperatureRange(nightDate.minusDays(1), 20, 22, 30.0))
            addAll(temperatureRange(nightDate.minusDays(1), 22, 24, 33.0))
            addAll(temperatureRange(nightDate, 0, 7, 33.0))
        }
        val steps = buildList {
            add(step(nightDate.minusDays(1), 21, 300))
            var start = nightStart
            while (start < nightEnd) {
                add(StepInterval(tStart = start, tEnd = start + 3600, steps = 0))
                start += 3600
            }
            add(step(nightDate, 7, 300))
        }

        val sessions = detectSleepSessions(
            temperatureRecords = temps,
            stepIntervals = steps,
            nowEpochSecond = now(),
            zone = zone,
        )

        assertEquals(1, sessions.size)
        assertEquals(nightStart, sessions.single().startEpochSecond)
    }

    @Test
    fun `editing a night start stays on the same sleep day`() {
        val anchor = sleepDayKey(
            nightDate.atTime(22, 0).atZone(zone).toEpochSecond(),
            zone,
        )

        val start = epochInSleepDay(anchor, 2, 0, zone)
        val end = epochInSleepDay(anchor, 6, 0, zone)

        assertEquals(nightDate.plusDays(1).atTime(2, 0).atZone(zone).toEpochSecond(), start)
        assertEquals(nightDate.plusDays(1).atTime(6, 0).atZone(zone).toEpochSecond(), end)
        assertEquals(nightDate.atTime(22, 0).atZone(zone).toEpochSecond(), epochInSleepDay(anchor, 22, 0, zone))
        assertEquals(nightDate.atTime(16, 0).atZone(zone).toEpochSecond(), epochInSleepDay(anchor, 16, 0, zone))
        // 12:00 is before the 16:00 boundary, so it belongs to the next calendar day.
        assertEquals(
            nightDate.plusDays(1).atTime(12, 0).atZone(zone).toEpochSecond(),
            epochInSleepDay(anchor, 12, 0, zone),
        )
    }

    @Test
    fun `a brief quiet awakening is rejoined into one night`() {
        val nightStart = nightDate.minusDays(1).atTime(22, 0).atZone(zone).toEpochSecond()
        val nightEnd = nightDate.atTime(7, 0).atZone(zone).toEpochSecond()
        val temps = temperatureRange(nightDate.minusDays(1), 22, 24, 32.0) +
            temperatureRange(nightDate, 0, 7, 32.0) +
            temperatureRange(nightDate, 7, 9, 30.0)
        val steps = buildList {
            add(step(nightDate.minusDays(1), 21, 300))
            var start = nightStart
            while (start < nightEnd) {
                val stepsInInterval = if (start == nightDate.atTime(3, 0).atZone(zone).toEpochSecond()) 120 else 0
                add(StepInterval(tStart = start, tEnd = start + 900, steps = stepsInInterval))
                start += 900
            }
            add(step(nightDate, 7, 300))
        }

        val sessions = detectSleepSessions(
            temperatureRecords = temps,
            stepIntervals = steps,
            nowEpochSecond = now(),
            zone = zone,
        )

        assertEquals(1, sessions.size)
        assertEquals(nightStart, sessions.single().startEpochSecond)
        assertEquals(nightEnd, sessions.single().endEpochSecond)
        assertEquals(SleepSessionKind.MAIN, sessions.single().kind)
    }

    @Test
    fun `a walk during the night keeps the sessions split`() {
        val nightStart = nightDate.minusDays(1).atTime(22, 0).atZone(zone).toEpochSecond()
        val walkStart = nightDate.atTime(3, 0).atZone(zone).toEpochSecond()
        val temps = temperatureRange(nightDate.minusDays(1), 22, 24, 32.0) +
            temperatureRange(nightDate, 0, 7, 32.0) +
            temperatureRange(nightDate, 7, 9, 30.0)
        val steps = buildList {
            add(step(nightDate.minusDays(1), 21, 300))
            var start = nightStart
            while (start < nightDate.atTime(7, 0).atZone(zone).toEpochSecond()) {
                val walking = start >= walkStart && start < walkStart + 30 * 60
                add(StepInterval(tStart = start, tEnd = start + 900, steps = if (walking) 200 else 0))
                start += 900
            }
            add(step(nightDate, 7, 300))
        }

        val sessions = detectSleepSessions(
            temperatureRecords = temps,
            stepIntervals = steps,
            nowEpochSecond = now(),
            zone = zone,
        )

        assertEquals(2, sessions.size)
    }

    @Test
    fun `a session whose provisional end is in the future is in progress`() {
        val start = nightDate.atTime(22, 0).atZone(zone).toEpochSecond()
        val session = SleepSession(
            id = "running",
            startEpochSecond = start,
            endEpochSecond = start + 3600,
            kind = SleepSessionKind.MAIN,
            confidence = 1.0,
            source = SleepSessionSource.DETECTED,
        )

        assertTrue(session.isInProgress(nowEpochSecond = start + 1800, windowSeconds = 7_200))
        assertTrue(session.isInProgress(nowEpochSecond = start + 3600 + 7_200, windowSeconds = 7_200))
        assertFalse(session.isInProgress(nowEpochSecond = start + 3600 + 7_201, windowSeconds = 7_200))
    }

    @Test
    fun `add sleep prefill stays inside the selected sleep day`() {
        // An early-morning main sleep belongs to the morning after the sleep day starts.
        val previous = SleepSession(
            id = "previous",
            startEpochSecond = nightDate.minusDays(1).atTime(1, 0).atZone(zone).toEpochSecond(),
            endEpochSecond = nightDate.minusDays(1).atTime(7, 0).atZone(zone).toEpochSecond(),
            kind = SleepSessionKind.MAIN,
            confidence = 1.0,
            source = SleepSessionSource.USER,
        )

        val (start, end) = defaultSleepRange(nightDate, listOf(previous), now(), zone)

        val dayStart = sleepDayStart(nightDate, zone)
        val dayEnd = sleepDayEnd(nightDate, zone)
        assertTrue(start >= dayStart)
        assertTrue(end <= dayEnd)
        assertEquals(
            nightDate.plusDays(1).atTime(1, 0).atZone(zone).toEpochSecond(),
            start,
        )
        assertEquals(
            nightDate.plusDays(1).atTime(7, 0).atZone(zone).toEpochSecond(),
            end,
        )
    }

    @Test
    fun `add sleep prefill keeps a night that starts on the selected date`() {
        val previous = SleepSession(
            id = "previous",
            startEpochSecond = nightDate.minusDays(1).atTime(22, 0).atZone(zone).toEpochSecond(),
            endEpochSecond = nightDate.atTime(6, 0).atZone(zone).toEpochSecond(),
            kind = SleepSessionKind.MAIN,
            confidence = 1.0,
            source = SleepSessionSource.USER,
        )

        val (start, end) = defaultSleepRange(nightDate, listOf(previous), now(), zone)

        assertEquals(nightDate.atTime(22, 0).atZone(zone).toEpochSecond(), start)
        assertEquals(nightDate.plusDays(1).atTime(6, 0).atZone(zone).toEpochSecond(), end)
    }

    @Test
    fun `confirming a detection keeps its original times and confidence`() {
        val detected = SleepSession(
            id = "det",
            startEpochSecond = nightDate.atTime(0, 30).atZone(zone).toEpochSecond(),
            endEpochSecond = nightDate.atTime(6, 45).atZone(zone).toEpochSecond(),
            kind = SleepSessionKind.MAIN,
            confidence = 0.72,
            source = SleepSessionSource.DETECTED,
        )

        val confirmed = detected.confirmDetected()

        assertEquals(SleepSessionSource.USER, confirmed.source)
        assertEquals(1.0, confirmed.confidence, 0.0)
        assertEquals(detected.startEpochSecond, confirmed.detectedStartEpochSecond)
        assertEquals(detected.endEpochSecond, confirmed.detectedEndEpochSecond)
        assertEquals(0.72, confirmed.detectedConfidence!!, 0.001)
        assertTrue(confirmed.hasDetectedOrigin)
    }

    @Test
    fun `adjusting a detection keeps its original times and confidence`() {
        val detected = SleepSession(
            id = "det",
            startEpochSecond = nightDate.atTime(0, 30).atZone(zone).toEpochSecond(),
            endEpochSecond = nightDate.atTime(6, 45).atZone(zone).toEpochSecond(),
            kind = SleepSessionKind.MAIN,
            confidence = 0.72,
            source = SleepSessionSource.DETECTED,
        )
        val adjustedStart = detected.startEpochSecond + 30 * 60
        val adjustedEnd = detected.endEpochSecond - 15 * 60

        val adjusted = detected.withAdjustedRange(adjustedStart, adjustedEnd, zone)
        val readjusted = adjusted.withAdjustedRange(adjustedStart + 900, adjustedEnd, zone)

        assertEquals(SleepSessionSource.USER, adjusted.source)
        assertEquals(adjustedStart, adjusted.startEpochSecond)
        assertEquals(adjustedEnd, adjusted.endEpochSecond)
        assertEquals(detected.startEpochSecond, adjusted.detectedStartEpochSecond)
        assertEquals(detected.endEpochSecond, adjusted.detectedEndEpochSecond)
        assertEquals(0.72, adjusted.detectedConfidence!!, 0.001)
        // A second adjustment keeps the snapshot from the original detection.
        assertEquals(detected.startEpochSecond, readjusted.detectedStartEpochSecond)
        assertEquals(0.72, readjusted.detectedConfidence!!, 0.001)
    }

    @Test
    fun `manual merge produces one user session spanning both`() {
        val first = SleepSession(
            id = "a",
            startEpochSecond = nightDate.atTime(22, 0).atZone(zone).toEpochSecond(),
            endEpochSecond = nightDate.plusDays(1).atTime(1, 0).atZone(zone).toEpochSecond(),
            kind = SleepSessionKind.NAP,
            confidence = 0.5,
            source = SleepSessionSource.DETECTED,
        )
        val second = SleepSession(
            id = "b",
            startEpochSecond = nightDate.plusDays(1).atTime(3, 0).atZone(zone).toEpochSecond(),
            endEpochSecond = nightDate.plusDays(1).atTime(7, 0).atZone(zone).toEpochSecond(),
            kind = SleepSessionKind.MAIN,
            confidence = 0.8,
            source = SleepSessionSource.DETECTED,
        )

        val merged = combineSleepSessions(first, second, zone)

        assertEquals(first.startEpochSecond, merged.startEpochSecond)
        assertEquals(second.endEpochSecond, merged.endEpochSecond)
        assertEquals(SleepSessionKind.MAIN, merged.kind)
        assertEquals(SleepSessionSource.USER, merged.source)
        assertEquals(1.0, merged.confidence, 0.0)
        assertEquals(first.startEpochSecond, merged.detectedStartEpochSecond)
        assertEquals(second.endEpochSecond, merged.detectedEndEpochSecond)
        assertEquals(0.8, merged.detectedConfidence!!, 0.001)
    }

    @Test
    fun `rejects a quiet afternoon with falling temperature`() {
        val temps = buildList {
            addAll(temperatureRange(nightDate, 10, 12, 32.0))
            addAll(temperatureRange(nightDate, 12, 15, 28.0))
            addAll(temperatureRange(nightDate, 15, 17, 28.0))
        }
        val steps = buildList {
            add(step(nightDate, 10, 300))
            add(step(nightDate, 11, 300))
            add(step(nightDate, 12, 0))
            add(step(nightDate, 13, 0))
            add(step(nightDate, 14, 0))
            add(step(nightDate, 15, 300))
            add(step(nightDate, 16, 300))
        }

        val sessions = detectSleepSessions(
            temperatureRecords = temps,
            stepIntervals = steps,
            nowEpochSecond = now(),
            zone = zone,
        )

        assertTrue(sessions.isEmpty())
    }

    @Test
    fun `sleep day groups the night with the following morning nap`() {
        val midnight = nightDate.atTime(0, 0).atZone(zone).toEpochSecond()
        val morning = nightDate.atTime(9, 0).atZone(zone).toEpochSecond()

        assertEquals(nightDate.minusDays(1), sleepDayKey(midnight, zone))
        assertEquals(nightDate.minusDays(1), sleepDayKey(morning, zone))
    }

    @Test
    fun `user sessions suppress overlapping detections`() {
        val user = SleepSession(
            id = "user",
            startEpochSecond = nightDate.atTime(0, 0).atZone(zone).toEpochSecond(),
            endEpochSecond = nightDate.atTime(7, 0).atZone(zone).toEpochSecond(),
            kind = SleepSessionKind.MAIN,
            confidence = 1.0,
            source = SleepSessionSource.USER,
        )
        val dismissed = SleepSession(
            id = "dismissed",
            startEpochSecond = nightDate.atTime(9, 0).atZone(zone).toEpochSecond(),
            endEpochSecond = nightDate.atTime(11, 0).atZone(zone).toEpochSecond(),
            kind = SleepSessionKind.NAP,
            confidence = 0.0,
            source = SleepSessionSource.DETECTED,
            dismissed = true,
        )
        val old = user.copy(
            id = "old",
            startEpochSecond = nightDate.minusDays(20).atTime(0, 0).atZone(zone).toEpochSecond(),
            endEpochSecond = nightDate.minusDays(20).atTime(7, 0).atZone(zone).toEpochSecond(),
        )
        val detected = listOf(
            SleepSession(
                id = "det-night",
                startEpochSecond = nightDate.minusDays(1).atTime(23, 0).atZone(zone).toEpochSecond(),
                endEpochSecond = nightDate.atTime(7, 0).atZone(zone).toEpochSecond(),
                kind = SleepSessionKind.MAIN,
                confidence = 0.8,
                source = SleepSessionSource.DETECTED,
            ),
            SleepSession(
                id = "det-nap",
                startEpochSecond = nightDate.atTime(9, 0).atZone(zone).toEpochSecond(),
                endEpochSecond = nightDate.atTime(11, 0).atZone(zone).toEpochSecond(),
                kind = SleepSessionKind.NAP,
                confidence = 0.8,
                source = SleepSessionSource.DETECTED,
            ),
            SleepSession(
                id = "det-new",
                startEpochSecond = nightDate.atTime(13, 0).atZone(zone).toEpochSecond(),
                endEpochSecond = nightDate.atTime(14, 0).atZone(zone).toEpochSecond(),
                kind = SleepSessionKind.NAP,
                confidence = 0.6,
                source = SleepSessionSource.DETECTED,
            ),
        )

        val merged = mergeSleepSessions(
            existing = listOf(user, dismissed, old),
            detected = detected,
            nowEpochSecond = now(),
            detectionDays = 2,
        )

        assertEquals(4, merged.size)
        assertNotNull(merged.firstOrNull { it.id == "old" })
        assertNotNull(merged.firstOrNull { it.id == "user" })
        assertNotNull(merged.firstOrNull { it.id == "dismissed" })
        assertNotNull(merged.firstOrNull { it.id == "det-new" })
        assertNull(merged.firstOrNull { it.id == "det-night" })
        assertNull(merged.firstOrNull { it.id == "det-nap" })
    }

    @Test
    fun `an older confirmation backfills the matching detection snapshot`() {
        val start = nightDate.atTime(0, 30).atZone(zone).toEpochSecond()
        val end = nightDate.atTime(6, 45).atZone(zone).toEpochSecond()
        val user = SleepSession(
            id = "det_${start}_$end",
            startEpochSecond = start,
            endEpochSecond = end,
            kind = SleepSessionKind.MAIN,
            confidence = 1.0,
            source = SleepSessionSource.USER,
        )
        val detection = SleepSession(
            id = "det_detected",
            startEpochSecond = nightDate.atTime(0, 0).atZone(zone).toEpochSecond(),
            endEpochSecond = nightDate.atTime(7, 0).atZone(zone).toEpochSecond(),
            kind = SleepSessionKind.MAIN,
            confidence = 0.8,
            source = SleepSessionSource.DETECTED,
        )

        val merged = mergeSleepSessions(
            existing = listOf(user),
            detected = listOf(detection),
            nowEpochSecond = now(),
            detectionDays = 2,
        )

        val confirmed = merged.single()
        assertEquals(user.id, confirmed.id)
        assertEquals(detection.startEpochSecond, confirmed.detectedStartEpochSecond)
        assertEquals(detection.endEpochSecond, confirmed.detectedEndEpochSecond)
        assertEquals(0.8, confirmed.detectedConfidence!!, 0.001)
    }

    @Test
    fun `insights select the last main sleep and list naps`() {
        val main = SleepSession(
            id = "main",
            startEpochSecond = nightDate.atTime(0, 0).atZone(zone).toEpochSecond(),
            endEpochSecond = nightDate.atTime(7, 0).atZone(zone).toEpochSecond(),
            kind = SleepSessionKind.MAIN,
            confidence = 0.9,
            source = SleepSessionSource.USER,
        )
        val nap = SleepSession(
            id = "nap",
            startEpochSecond = nightDate.atTime(9, 0).atZone(zone).toEpochSecond(),
            endEpochSecond = nightDate.atTime(11, 0).atZone(zone).toEpochSecond(),
            kind = SleepSessionKind.NAP,
            confidence = 0.8,
            source = SleepSessionSource.USER,
        )
        val temps = temperatureRange(nightDate, 0, 7, 32.0) +
            temperatureRange(nightDate, 9, 11, 33.0)
        val at = nightDate.atTime(12, 0).atZone(zone).toEpochSecond()

        val insights = buildSleepInsights(
            sessions = listOf(main, nap),
            temperatureRecords = temps,
            aggregates = emptyList(),
            nowEpochSecond = at,
            zone = zone,
        )

        assertEquals("main", insights.lastMainSleep?.session?.id)
        assertEquals(2, insights.recentConfirmed.size)
        assertTrue(insights.lastMainSleep!!.coverage > 0.5)
        // Main (7h) + nap (2h) inside both the 24h and 7-day windows.
        assertEquals(9 * 3600L, insights.totalSleepSecondsLast24h)
        assertEquals(9 * 3600L / 7, insights.averageDailySleepSecondsLast7)
    }

    @Test
    fun `sleep totals clip to the rolling windows`() {
        val running = SleepSession(
            id = "night",
            startEpochSecond = nightDate.minusDays(1).atTime(22, 0).atZone(zone).toEpochSecond(),
            endEpochSecond = nightDate.atTime(6, 0).atZone(zone).toEpochSecond(),
            kind = SleepSessionKind.MAIN,
            confidence = 1.0,
            source = SleepSessionSource.USER,
        )
        val at = nightDate.atTime(12, 0).atZone(zone).toEpochSecond()

        val insights = buildSleepInsights(
            sessions = listOf(running),
            temperatureRecords = temperatureRange(nightDate.minusDays(1), 22, 24, 32.0),
            aggregates = emptyList(),
            nowEpochSecond = at,
            zone = zone,
        )

        // Only 22:00 to 06:00 lies in the last 24 hours; earlier sleep is outside the window.
        assertEquals(8 * 3600L, insights.totalSleepSecondsLast24h)
        assertEquals(8 * 3600L / 7, insights.averageDailySleepSecondsLast7)
    }

    @Test
    fun `insights ignore unconfirmed detections`() {
        val detected = SleepSession(
            id = "detected",
            startEpochSecond = nightDate.atTime(0, 0).atZone(zone).toEpochSecond(),
            endEpochSecond = nightDate.atTime(7, 0).atZone(zone).toEpochSecond(),
            kind = SleepSessionKind.MAIN,
            confidence = 0.9,
            source = SleepSessionSource.DETECTED,
        )
        val at = nightDate.atTime(12, 0).atZone(zone).toEpochSecond()

        val insights = buildSleepInsights(
            sessions = listOf(detected),
            temperatureRecords = temperatureRange(nightDate, 0, 7, 32.0),
            aggregates = emptyList(),
            nowEpochSecond = at,
            zone = zone,
        )

        assertNull(insights.lastMainSleep)
        assertTrue(insights.recentConfirmed.isEmpty())
    }

    @Test
    fun `an in-progress session is not reported as last sleep`() {
        val end = nightDate.atTime(7, 0).atZone(zone).toEpochSecond()
        val running = SleepSession(
            id = "running",
            startEpochSecond = nightDate.atTime(0, 0).atZone(zone).toEpochSecond(),
            endEpochSecond = end,
            kind = SleepSessionKind.MAIN,
            confidence = 1.0,
            source = SleepSessionSource.USER,
        )

        val insights = buildSleepInsights(
            sessions = listOf(running),
            temperatureRecords = temperatureRange(nightDate, 0, 7, 32.0),
            aggregates = emptyList(),
            nowEpochSecond = end + 10 * 60,
            zone = zone,
        )

        assertNull(insights.lastMainSleep)
        assertTrue(insights.recentConfirmed.isEmpty())
    }

    @Test
    fun `a long morning nap without a night is still a nap`() {
        val temps = buildList {
            addAll(temperatureRange(nightDate, 7, 9, 30.0))
            addAll(temperatureRange(nightDate, 9, 12, 32.5))
            addAll(temperatureRange(nightDate, 12, 14, 30.0))
        }
        val steps = buildList {
            add(step(nightDate, 7, 300))
            add(step(nightDate, 8, 300))
            add(step(nightDate, 9, 0))
            add(step(nightDate, 10, 0))
            add(step(nightDate, 11, 0))
            add(step(nightDate, 12, 300))
            add(step(nightDate, 13, 300))
        }

        val sessions = detectSleepSessions(
            temperatureRecords = temps,
            stepIntervals = steps,
            nowEpochSecond = now(),
            zone = zone,
        )

        assertEquals(1, sessions.size)
        assertEquals(SleepSessionKind.NAP, sessions.single().kind)
    }

    @Test
    fun `user session kind is inferred from length and time`() {
        val nightStart = nightDate.atTime(23, 0).atZone(zone).toEpochSecond()
        val nightEnd = nightDate.plusDays(1).atTime(7, 0).atZone(zone).toEpochSecond()
        val napStart = nightDate.atTime(9, 0).atZone(zone).toEpochSecond()
        val napEnd = nightDate.atTime(11, 0).atZone(zone).toEpochSecond()

        assertEquals(SleepSessionKind.MAIN, inferSleepKind(nightStart, nightEnd, zone))
        assertEquals(SleepSessionKind.NAP, inferSleepKind(napStart, napEnd, zone))
    }
}
