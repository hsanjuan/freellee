package link.hector.freellee.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class BatteryHistoryTest {

    private val zone = ZoneId.of("UTC")

    private fun epoch(date: LocalDate, hour: Int, minute: Int = 0): Long =
        date.atTime(hour, minute).atZone(zone).toEpochSecond()

    @Test
    fun `battery percentage maps and clamps the coin-cell range`() {
        assertEquals(100, batteryPercentage(3000))
        assertEquals(50, batteryPercentage(2800))
        assertEquals(0, batteryPercentage(2600))
        assertEquals(100, batteryPercentage(3200))
        assertEquals(0, batteryPercentage(2500))
    }

    @Test
    fun `older samples are pruned when a new reading is appended`() {
        val now = epoch(LocalDate.of(2026, 11, 13), 12)
        val old = BatterySample(now - 61 * 86_400L, 2700)
        val recent = BatterySample(now - 59 * 86_400L, 2750)

        val result = appendBatterySample(
            history = listOf(old, recent),
            sample = BatterySample(now, 2800),
        )

        assertEquals(listOf(recent, BatterySample(now, 2800)), result)
    }

    @Test
    fun `an identical reading within an hour is not recorded twice`() {
        val now = epoch(LocalDate.of(2026, 11, 13), 12)
        val first = BatterySample(now, 2800)

        val afterHalfHour = appendBatterySample(
            history = listOf(first),
            sample = BatterySample(now + 1_800, 2800),
        )
        val afterAnHour = appendBatterySample(
            history = listOf(first),
            sample = BatterySample(now + 3_600, 2800),
        )

        assertEquals(listOf(first), afterHalfHour)
        assertEquals(2, afterAnHour.size)
    }

    @Test
    fun `daily series keeps the last reading of each day and leaves gaps`() {
        val end = LocalDate.of(2026, 11, 13)
        val samples = listOf(
            BatterySample(epoch(end.minusDays(2), 8), 2700),
            BatterySample(epoch(end.minusDays(2), 20), 2800),
            BatterySample(epoch(end, 10), 2900),
        )

        val result = batteryDailySeries(samples, end, days = 3, zone = zone)

        assertEquals(
            listOf(end.minusDays(2), end.minusDays(1), end),
            result.map { it.date },
        )
        assertEquals(50, result[0].percent)
        assertNull(result[1].percent)
        assertEquals(75, result[2].percent)
    }

    @Test
    fun `daily series covers the requested sixty-day window`() {
        val end = LocalDate.of(2026, 11, 13)

        val result = batteryDailySeries(emptyList(), end, zone = zone)

        assertEquals(BATTERY_HISTORY_DAYS, result.size)
        assertEquals(end.minusDays(59), result.first().date)
        assertEquals(end, result.last().date)
    }
}
