package link.hector.freellee.ui.charts

import link.hector.freellee.data.DeviceStore.RecordItem
import link.hector.freellee.data.DeviceStore.StepInterval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `rollingDailyAverageSteps uses the seven-day window and ignores older data`() {
        val now = epoch("2024-01-10T12:00:00Z")
        val intervals = listOf(
            step("2024-01-04T12:00:00Z", 1_000), // exactly seven days before now
            step("2024-01-05T00:00:00Z", 2_000),
            step("2024-01-10T00:00:00Z", 4_000),
            step("2024-01-03T00:00:00Z", 99_999), // outside the window
        )

        assertEquals(1_000.0, rollingDailyAverageSteps(intervals, now, days = 7)!!, 0.001)
        assertNull(rollingDailyAverageSteps(emptyList(), now))
    }

    @Test
    fun `hourlyTemperatureWindow averages valid readings and leaves gaps`() {
        val result = hourlyTemperatureWindow(
            records = listOf(
                temperature("2024-01-10T09:15:00Z", 2_500), // below the valid threshold
                temperature("2024-01-10T10:15:00Z", 3_000),
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
        assertNull(result[0].averageCelsius)
        assertEquals(31.0, result[1].averageCelsius!!, 0.001)
        assertEquals(31.0, result[2].averageCelsius!!, 0.001)
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
        assertEquals(31.0, result[1].averageCelsius!!, 0.001)
    }

    @Test
    fun `hourlyAverageWindow uses earlier days at the same clock hour`() {
        val result = hourlyAverageWindow(
            records = listOf(
                temperature("2024-01-09T09:15:00Z", 3_000),
                temperature("2024-01-08T09:45:00Z", 3_000),
                temperature("2024-01-07T09:30:00Z", 3_600),
                temperature("2024-01-10T09:30:00Z", 9_999), // in-progress hour, ignored
                temperature("2024-01-09T10:15:00Z", 2_500), // below the valid threshold
                temperature("2024-01-09T11:30:00Z", 3_100),
                temperature("2024-01-08T11:30:00Z", 2_900),
            ),
            endEpochSecond = epoch("2024-01-10T12:00:00Z"),
            hourCount = 3,
            zone = zone,
        )

        assertEquals(
            listOf(epoch("2024-01-10T09:00:00Z"), epoch("2024-01-10T10:00:00Z"), epoch("2024-01-10T11:00:00Z")),
            result.map { it.startEpochSecond },
        )
        assertEquals(32.0, result[0].averageCelsius!!, 0.001)
        assertNull(result[1].averageCelsius)
        assertEquals(30.0, result[2].averageCelsius!!, 0.001)
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
        assertEquals(150.0 / 7.0, data.referenceAverage!!, 0.001)
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
        assertEquals(219, data.hourlyAverages.size)
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
