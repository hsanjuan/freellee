package link.hector.freellee.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class FormattersTest {

    private val zone = ZoneId.of("UTC")

    private fun epoch(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toEpochSecond()

    @Test
    fun `sleep range omits the date for same-day sessions by default`() {
        val start = epoch(2026, 11, 11, 9, 0)
        val end = epoch(2026, 11, 11, 11, 45)

        assertEquals("09:00–11:45", formatSleepRange(start, end, zone))
    }

    @Test
    fun `sleep range can show the date on same-day sessions`() {
        val start = epoch(2026, 11, 11, 9, 0)
        val end = epoch(2026, 11, 11, 11, 45)
        val date = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
            .format(LocalDateTime.of(2026, 11, 11, 9, 0))

        assertEquals(
            "$date · 09:00–11:45",
            formatSleepRange(start, end, zone, alwaysShowDate = true),
        )
    }

    @Test
    fun `sleep range shows both dates when it crosses midnight`() {
        val start = epoch(2026, 11, 11, 23, 0)
        val end = epoch(2026, 11, 12, 6, 45)
        val dateTime = DateTimeFormatter.ofPattern("MMM d HH:mm", Locale.getDefault())
        val expected = "${dateTime.format(LocalDateTime.of(2026, 11, 11, 23, 0))} – " +
            dateTime.format(LocalDateTime.of(2026, 11, 12, 6, 45))

        assertEquals(expected, formatSleepRange(start, end, zone))
        assertEquals(expected, formatSleepRange(start, end, zone, alwaysShowDate = true))
    }
}
