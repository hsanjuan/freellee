package link.hector.freellee.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
