package link.hector.freellee.viewmodel

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HealthStatsTest {

    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 1, 10)
    private val now = today.atTime(12, 0).atZone(zone).toEpochSecond()

    private fun entry(value: Int, date: LocalDate, time: LocalTime): HistoryEntry {
        val epoch = date.atTime(time).atZone(zone).toEpochSecond()
        return HistoryEntry(value, epoch, epoch, epoch, isInstantaneous = true)
    }

    @Test
    fun `today stats include only today's samples`() {
        val records = listOf(
            entry(60, today, LocalTime.of(8, 0)),
            entry(80, today, LocalTime.of(9, 0)),
            entry(100, today, LocalTime.of(10, 0)),
            entry(999, today.minusDays(1), LocalTime.of(23, 0)),
        )

        val stats = todayHeartRateStats(records, now, zone)

        assertEquals(80.0, stats.average!!, 0.001)
        assertEquals(60, stats.minimum)
        assertEquals(100, stats.maximum)
        assertEquals(3, stats.sampleCount)
    }

    @Test
    fun `empty history returns empty stats`() {
        val stats = todayHeartRateStats(emptyList(), now, zone)

        assertNull(stats.average)
        assertNull(stats.minimum)
        assertNull(stats.maximum)
        assertEquals(0, stats.sampleCount)
    }

    @Test
    fun `future samples are ignored`() {
        val records = listOf(
            entry(120, today, LocalTime.of(13, 0)),
        )

        val stats = todayHeartRateStats(records, now, zone)

        assertEquals(0, stats.sampleCount)
    }

    @Test
    fun `rolling window starts seven days before now`() {
        assertEquals(now - 7L * 24 * 60 * 60, rollingWindowStartEpoch(now))
    }
}
