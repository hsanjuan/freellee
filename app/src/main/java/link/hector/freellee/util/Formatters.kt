package link.hector.freellee.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Formats an elapsed duration in milliseconds as `HH:mm:ss`. */
fun formatDuration(millis: Int): String {
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val secs = totalSeconds % 60
    return "%02d:%02d:%02d".format(hours, minutes, secs)
}

/** Formats an instant as a local time of day (`HH:mm:ss`). */
fun formatTimeOnly(instant: Instant): String =
    DateTimeFormatter.ofPattern("HH:mm:ss").format(instant.atZone(ZoneId.systemDefault()))

/**
 * Formats a sleep duration as `Xh Ym`, or `Ym` below one hour.
 */
fun formatSleepDuration(seconds: Long): String {
    val minutes = (seconds / 60).coerceAtLeast(0)
    val hours = minutes / 60
    val remaining = minutes % 60
    return if (hours > 0) "${hours}h ${remaining}m" else "${remaining}m"
}

/**
 * Formats a sleep session as local times (`HH:mm–HH:mm`), adding the date when the session
 * crosses midnight.
 */
fun formatSleepRange(
    startEpochSecond: Long,
    endEpochSecond: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val start = Instant.ofEpochSecond(startEpochSecond).atZone(zone)
    val end = Instant.ofEpochSecond(endEpochSecond).atZone(zone)
    val time = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
    return if (start.toLocalDate() == end.toLocalDate()) {
        "${time.format(start)}–${time.format(end)}"
    } else {
        val dateTime = DateTimeFormatter.ofPattern("MMM d HH:mm", Locale.getDefault())
        "${dateTime.format(start)} – ${dateTime.format(end)}"
    }
}

/**
 * Formats an instant for history rows: `Today at HH:mm:ss`, `Yesterday at HH:mm:ss`, otherwise
 * `MMM d, HH:mm:ss`.
 */
fun formatTimestamp(instant: Instant): String {
    val dateTime = instant.atZone(ZoneId.systemDefault())
    val today = LocalDate.now()
    val yesterday = today.minusDays(1)
    val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss")
    return when (dateTime.toLocalDate()) {
        today -> "Today at ${timeFmt.format(dateTime)}"
        yesterday -> "Yesterday at ${timeFmt.format(dateTime)}"
        else -> DateTimeFormatter.ofPattern("MMM d, HH:mm:ss").format(dateTime)
    }
}
