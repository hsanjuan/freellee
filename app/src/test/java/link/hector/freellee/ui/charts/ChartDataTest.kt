package link.hector.freellee.ui.charts

import link.hector.freellee.data.DeviceStore.RecordItem
import link.hector.freellee.data.DeviceStore.StepInterval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

private val zone: ZoneId = ZoneId.of("UTC")

private fun epoch(iso: String): Long = Instant.parse(iso).epochSecond

private fun step(iso: String, steps: Int): StepInterval {
    val start = epoch(iso)
    return StepInterval(tStart = start, tEnd = start + 60, steps = steps)
}

private fun temperature(iso: String, centiCelsius: Int): RecordItem {
    val start = epoch(iso)
    return RecordItem(
        type = 1,
        tStart = start,
        tEnd = start,
        value = centiCelsius,
        syncTime = 0L,
    )
}

class ChartDataTest {

    @Test
    fun `temperature chart marks long reading gaps as not worn`() {
        val records = listOf(
            temperature("2024-01-10T08:00:00Z", 3100),
            temperature("2024-01-10T08:04:00Z", 3100),
            temperature("2024-01-10T09:00:00Z", 3100),
        )

        val chart = buildTemperatureChartData(
            records = records,
            nowEpochSecond = epoch("2024-01-10T12:00:00Z"),
            zone = zone,
        )

        assertEquals(1, chart.notWornRanges.size)
        val range = chart.notWornRanges.single()
        assertEquals(epoch("2024-01-10T08:04:00Z") + 240, range.startEpochSecond)
        assertEquals(epoch("2024-01-10T09:00:00Z"), range.endEpochSecond)
    }

    @Test
    fun `hourly sampling does not report every normal gap as not worn`() {
        val start = epoch("2024-01-10T00:00:00Z")
        val records = (0 until 24).map { hour ->
            temperature(
                Instant.ofEpochSecond(start + hour * 3600L).toString(),
                3100,
            )
        }

        val chart = buildTemperatureChartData(
            records = records,
            nowEpochSecond = epoch("2024-01-11T00:00:00Z"),
            zone = zone,
        )

        assertTrue(chart.notWornRanges.isEmpty())
    }

    @Test
    fun `temperature chart marks the period after the last sync as unsynced`() {
        val records = listOf(
            temperature("2024-01-10T08:00:00Z", 3100),
            temperature("2024-01-10T08:04:00Z", 3100),
        )

        val chart = buildTemperatureChartData(
            records = records,
            nowEpochSecond = epoch("2024-01-10T12:00:00Z"),
            zone = zone,
            lastSyncEpochSecond = epoch("2024-01-10T08:04:00Z"),
        )

        val range = chart.unsyncedRange
        assertEquals(epoch("2024-01-10T08:04:00Z"), range?.startEpochSecond)
        assertEquals(epoch("2024-01-10T12:00:00Z"), range?.endEpochSecond)
        // The unsynced tail must not be reported as a genuine reading gap.
        assertTrue(chart.notWornRanges.isEmpty())
    }

    @Test
    fun `a fresh sync does not create an unsynced region`() {
        val records = listOf(
            temperature("2024-01-10T11:56:00Z", 3100),
            temperature("2024-01-10T12:00:00Z", 3100),
        )

        val chart = buildTemperatureChartData(
            records = records,
            nowEpochSecond = epoch("2024-01-10T12:00:00Z"),
            zone = zone,
            lastSyncEpochSecond = epoch("2024-01-10T12:00:00Z"),
        )

        assertNull(chart.unsyncedRange)
    }

    @Test
    fun `dailyStepsWindow buckets by local date and fills empty days`() {
        val result = dailyStepsWindow(
            intervals = listOf(
                step("2024-01-07T10:00:00Z", 999),
                step("2024-01-08T10:00:00Z", 100),
                step("2024-01-08T23:59:00Z", 50),
                step("2024-01-10T00:00:00Z", 25),
            ),
            endDate = LocalDate.of(2024, 1, 10),
            dayCount = 3,
            zone = zone,
        )

        assertEquals(listOf(150, 0, 25), result.map { it.steps })
        assertEquals(listOf(true, false, true), result.map { it.hasData })
        assertEquals(
            listOf(
                LocalDate.of(2024, 1, 8),
                LocalDate.of(2024, 1, 9),
                LocalDate.of(2024, 1, 10),
            ),
            result.map { it.date },
        )
    }

    @Test
    fun `rollingDailyAverageSteps uses the previous complete days and ignores older data`() {
        val now = epoch("2024-01-10T12:00:00Z")
        val intervals = listOf(
            step("2024-01-04T12:00:00Z", 1_000),
            step("2024-01-05T00:00:00Z", 2_000),
            step("2024-01-10T00:00:00Z", 4_000), // today is partial, so it is excluded
            step("2024-01-02T00:00:00Z", 99_999), // outside the window
        )

        assertEquals(1_500.0, rollingDailyAverageSteps(intervals, now, days = 7)!!, 0.001)
        assertNull(rollingDailyAverageSteps(emptyList(), now))
    }

    @Test
    fun `hourlyTemperatureWindow uses medians and leaves gaps`() {
        val result = hourlyTemperatureWindow(
            records = listOf(
                temperature("2024-01-10T09:15:00Z", 2_500), // below the valid threshold
                temperature("2024-01-10T10:15:00Z", 3_000),
                temperature("2024-01-10T10:30:00Z", 3_900), // spike: mean would be 33.7 °C
                temperature("2024-01-10T10:45:00Z", 3_200),
                temperature("2024-01-10T11:15:00Z", 3_100),
                temperature("2024-01-10T12:30:00Z", 9_999), // outside the window
            ),
            endEpochSecond = epoch("2024-01-10T12:00:00Z"),
            hourCount = 3,
            zone = zone,
        )

        assertEquals(
            listOf(epoch("2024-01-10T09:00:00Z"), epoch("2024-01-10T10:00:00Z"), epoch("2024-01-10T11:00:00Z")),
            result.map { it.startEpochSecond },
        )
        assertNull(result[0].medianCelsius)
        assertEquals(32.0, result[1].medianCelsius!!, 0.001)
        assertEquals(31.0, result[2].medianCelsius!!, 0.001)
    }

    @Test
    fun `hourly bucketing handles non-UTC offsets`() {
        val result = hourlyTemperatureWindow(
            records = listOf(temperature("2024-01-10T10:15:00Z", 3_100)),
            endEpochSecond = epoch("2024-01-10T12:00:00Z"),
            hourCount = 3,
            zone = ZoneId.of("Europe/Paris"),
        )

        assertEquals(
            listOf(
                epoch("2024-01-10T09:00:00Z"),
                epoch("2024-01-10T10:00:00Z"),
                epoch("2024-01-10T11:00:00Z"),
            ),
            result.map { it.startEpochSecond },
        )
        assertEquals(31.0, result[1].medianCelsius!!, 0.001)
    }

    @Test
    fun `temperatureBaselineSegments uses the matching day-part median`() {
        val result = temperatureBaselineSegments(
            records = listOf(
                temperature("2024-01-09T07:00:00Z", 3_000),
                temperature("2024-01-09T08:00:00Z", 3_400),
                temperature("2024-01-09T13:00:00Z", 3_200),
                temperature("2024-01-09T14:00:00Z", 3_600),
            ),
            windowStartEpochSecond = epoch("2024-01-10T00:00:00Z"),
            windowEndEpochSecond = epoch("2024-01-11T00:00:00Z"),
            zone = zone,
        )

        val morning = result.single { it.startEpochSecond == epoch("2024-01-10T06:00:00Z") }
        val afternoon = result.single { it.startEpochSecond == epoch("2024-01-10T12:00:00Z") }
        assertEquals(32.0, morning.celsius, 0.001)
        assertEquals(epoch("2024-01-10T12:00:00Z"), morning.endEpochSecond)
        assertEquals(34.0, afternoon.celsius, 0.001)
        assertEquals(epoch("2024-01-10T18:00:00Z"), afternoon.endEpochSecond)
    }

    @Test
    fun `temperatureReferenceSegments repeats the profile by local hour`() {
        val profile = MutableList<Double?>(24) { null }
        profile[10] = 31.0
        profile[11] = 32.0

        val result = temperatureReferenceSegments(
            profile = profile,
            windowStartEpochSecond = epoch("2024-01-10T09:30:00Z"),
            windowEndEpochSecond = epoch("2024-01-10T11:30:00Z"),
            zone = zone,
        )

        assertEquals(2, result.size)
        assertEquals(31.0, result[0].celsius, 0.001)
        assertEquals(32.0, result[1].celsius, 0.001)
    }

    @Test
    fun `buildStepsChartData prepares at least seven days and the rolling average`() {
        val data = buildStepsChartData(
            intervals = listOf(
                step("2024-01-08T10:00:00Z", 100),
                step("2024-01-10T10:00:00Z", 50),
            ),
            nowEpochSecond = epoch("2024-01-10T12:00:00Z"),
            zone = zone,
        )

        assertEquals(7, data.days.size)
        assertEquals(LocalDate.of(2024, 1, 4), data.days.first().date)
        assertEquals(LocalDate.of(2024, 1, 10), data.days.last().date)
        assertEquals(50, data.days.last().steps)
        assertTrue(data.days.first { it.date == LocalDate.of(2024, 1, 8) }.hasData)
        assertEquals(100.0, data.referenceAverage!!, 0.001)
        assertEquals(listOf(50), data.todayIntervals.map { it.steps })
    }

    @Test
    fun `buildStepsChartData keeps only today's intervals for the intraday chart`() {
        val data = buildStepsChartData(
            intervals = listOf(
                step("2024-01-09T23:59:00Z", 10),
                step("2024-01-10T00:01:00Z", 20),
                step("2024-01-10T11:45:00Z", 30),
            ),
            nowEpochSecond = epoch("2024-01-10T12:00:00Z"),
            zone = zone,
        )

        assertEquals(listOf(20, 30), data.todayIntervals.map { it.steps })
        assertEquals(LocalDate.of(2024, 1, 10), data.today)
    }

    @Test
    fun `buildTemperatureChartData covers the whole stored range`() {
        val data = buildTemperatureChartData(
            records = listOf(
                temperature("2024-01-01T10:15:00Z", 3_000),
                temperature("2024-01-10T10:15:00Z", 3_200),
            ),
            nowEpochSecond = epoch("2024-01-10T12:00:00Z"),
            zone = zone,
        )

        assertEquals(epoch("2024-01-10T13:00:00Z"), data.endHourEpochSecond)
        assertEquals(epoch("2024-01-01T10:00:00Z"), data.windowStartEpochSecond)
        assertEquals(219, data.totalHours)
        assertEquals(219, data.points.size)
        assertEquals(24, data.insights.referenceProfile.size)
    }

    @Test
    fun `empty data produces a full empty viewport`() {
        val data = buildDashboardChartData(
            stepIntervals = emptyList(),
            temperatureRecords = emptyList(),
            nowEpochSecond = epoch("2024-01-10T12:00:00Z"),
            zone = zone,
        )

        assertEquals(7, data.steps.days.size)
        assertNull(data.steps.referenceAverage)
        assertEquals(24, data.temperature.totalHours)
        assertEquals(epoch("2024-01-09T13:00:00Z"), data.temperature.windowStartEpochSecond)
        assertEquals(24, data.temperature.points.size)
    }

    @Test
    fun `shiftHours keeps wall-clock hour alignment`() {
        val start = ZonedDateTime.of(2024, 1, 10, 23, 0, 0, 0, zone).toEpochSecond()
        val shifted = shiftHours(start, 2, zone)
        assertEquals(
            ZonedDateTime.of(2024, 1, 11, 1, 0, 0, 0, zone).toEpochSecond(),
            shifted,
        )
    }
}
