package link.hector.freellee.data

import link.hector.freellee.data.DeviceStore.RecordItem
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TemperatureBaselineTest {

    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 1, 10)
    private val now = today.atTime(12, 0).atZone(zone).toEpochSecond()

    private fun record(date: LocalDate, time: LocalTime, value: Int): RecordItem {
        val epoch = date.atTime(time).atZone(zone).toEpochSecond()
        return RecordItem(0, epoch, epoch, value)
    }

    private fun history(): List<RecordItem> = listOf(
        record(today.minusDays(2), LocalTime.of(3, 0), 3000),
        record(today.minusDays(2), LocalTime.of(13, 0), 3400),
        record(today.minusDays(1), LocalTime.of(3, 0), 3100),
        record(today.minusDays(1), LocalTime.of(7, 0), 3200),
        record(today.minusDays(1), LocalTime.of(8, 0), 3400),
        record(today.minusDays(1), LocalTime.of(13, 0), 3300),
        record(today, LocalTime.of(7, 0), 3500),
        record(today, LocalTime.of(8, 0), 3600),
        record(today, LocalTime.of(12, 0), 3450),
    )

    @Test
    fun `current interval shows its period baseline and deviation`() {
        val current = currentTemperatureInterval(history(), now, zone)

        assertEquals(TemperaturePeriod.AFTERNOON, current.period)
        // Prior afternoons are 34.0 and 33.0 °C, so the median baseline is 33.5 °C.
        assertEquals(33.5, current.baselineCelsius!!, 0.001)
        assertEquals(1.0, current.deviationCelsius!!, 0.001)
        assertEquals(1, current.readingCount)
        assertEquals(now, current.lastMeasuredAtEpochSecond)
    }

    @Test
    fun `current interval can have a baseline without readings yet`() {
        val morning = today.atTime(7, 30).atZone(zone).toEpochSecond()
        val records = listOf(
            record(today.minusDays(1), LocalTime.of(7, 0), 3200),
            record(today.minusDays(1), LocalTime.of(8, 0), 3400),
        )

        val current = currentTemperatureInterval(records, morning, zone)

        assertEquals(TemperaturePeriod.MORNING, current.period)
        assertEquals(33.0, current.baselineCelsius!!, 0.001)
        assertNull(current.deviationCelsius)
        assertEquals(0, current.readingCount)
    }

    @Test
    fun `previous intervals are the last three completed groups newest first`() {
        val previous = previousTemperatureIntervals(history(), count = 3, nowEpochSecond = now, zone = zone)

        assertEquals(3, previous.size)
        assertEquals(
            listOf(TemperaturePeriod.MORNING, TemperaturePeriod.AFTERNOON, TemperaturePeriod.MORNING),
            previous.map { it.period },
        )
        assertEquals(
            listOf(today, today.minusDays(1), today.minusDays(1)),
            previous.map { java.time.Instant.ofEpochSecond(it.startEpochSecond).atZone(zone).toLocalDate() },
        )
        assertTrue(previous.all { it.readingCount > 0 })
    }

    @Test
    fun `previous interval deviations use the same period baseline`() {
        val previous = previousTemperatureIntervals(history(), count = 3, nowEpochSecond = now, zone = zone)

        val morning = previous.first()
        assertEquals(33.0, morning.baselineCelsius!!, 0.001)
        assertEquals(2.5, morning.deviationCelsius!!, 0.001)

        val afternoon = previous[1]
        assertEquals(34.0, afternoon.baselineCelsius!!, 0.001)
        assertEquals(-1.0, afternoon.deviationCelsius!!, 0.001)

        // Yesterday morning has no earlier morning readings, so it has no baseline yet.
        assertNull(previous[2].baselineCelsius)
        assertNull(previous[2].deviationCelsius)
    }

    @Test
    fun `previous intervals follow reverse chronological order across day parts`() {
        val morning = today.atTime(7, 0).atZone(zone).toEpochSecond()
        val records = listOf(
            record(today.minusDays(2), LocalTime.of(7, 0), 3200),
            record(today.minusDays(1), LocalTime.of(13, 0), 3300),
            record(today.minusDays(1), LocalTime.of(20, 0), 3400),
            record(today, LocalTime.of(3, 0), 3100),
            record(today, LocalTime.of(7, 0), 3300),
        )

        val previous = previousTemperatureIntervals(records, count = 3, nowEpochSecond = morning, zone = zone)

        assertEquals(
            listOf(TemperaturePeriod.NIGHT, TemperaturePeriod.EVENING, TemperaturePeriod.AFTERNOON),
            previous.map { it.period },
        )
        assertEquals(
            listOf(today, today.minusDays(1), today.minusDays(1)),
            previous.map { java.time.Instant.ofEpochSecond(it.startEpochSecond).atZone(zone).toLocalDate() },
        )
    }

    @Test
    fun `readings below 25 degrees are discarded`() {
        val records = listOf(
            record(today.minusDays(1), LocalTime.of(7, 0), 2000),
            record(today.minusDays(1), LocalTime.of(8, 0), 3200),
            record(today, LocalTime.of(7, 0), 3300),
        )
        val morning = today.atTime(9, 0).atZone(zone).toEpochSecond()

        val current = currentTemperatureInterval(records, morning, zone)

        assertEquals(32.0, current.baselineCelsius!!, 0.001)
        assertEquals(1.0, current.deviationCelsius!!, 0.001)
        assertEquals(1, current.readingCount)
    }

    @Test
    fun `previous intervals are empty when no earlier groups exist`() {
        val morning = today.atTime(7, 0).atZone(zone).toEpochSecond()
        val records = listOf(
            record(today, LocalTime.of(7, 0), 3300),
        )

        val previous = previousTemperatureIntervals(records, count = 3, nowEpochSecond = morning, zone = zone)

        assertTrue(previous.isEmpty())
    }
}
