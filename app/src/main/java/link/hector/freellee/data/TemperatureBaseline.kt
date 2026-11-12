package link.hector.freellee.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Local-time day parts used to group skin-temperature readings.
 *
 * The day parts are a presentation and Health Connect summary layer. The personal reference
 * profile used for comparisons is built from hourly medians instead, so a day part that spans
 * very different circadian phases does not distort the baseline.
 */
enum class TemperaturePeriod(val label: String, val hours: IntRange) {
    NIGHT("Night", 0..5),
    MORNING("Morning", 6..11),
    AFTERNOON("Afternoon", 12..17),
    EVENING("Evening", 18..23);

    fun getBoundaries(date: LocalDate, zone: ZoneId): Pair<Instant, Instant> {
        val start = when (this) {
            NIGHT -> date.atTime(0, 0).atZone(zone).toInstant()
            MORNING -> date.atTime(6, 0).atZone(zone).toInstant()
            AFTERNOON -> date.atTime(12, 0).atZone(zone).toInstant()
            EVENING -> date.atTime(18, 0).atZone(zone).toInstant()
        }
        val end = when (this) {
            NIGHT -> date.atTime(5, 59, 59).atZone(zone).toInstant()
            MORNING -> date.atTime(11, 59, 59).atZone(zone).toInstant()
            AFTERNOON -> date.atTime(17, 59, 59).atZone(zone).toInstant()
            EVENING -> date.atTime(23, 59, 59).atZone(zone).toInstant()
        }
        return start to end
    }

    companion object {
        /** Order used for presentation, starting with the morning. */
        val displayOrder: List<TemperaturePeriod> = listOf(MORNING, AFTERNOON, EVENING, NIGHT)

        fun fromHour(hour: Int): TemperaturePeriod =
            entries.firstOrNull { hour in it.hours }
                ?: throw IllegalArgumentException("Invalid hour: $hour")
    }
}

/** Readings below this value (in centi-degrees Celsius) are treated as sensor noise. */
const val MIN_VALID_TEMPERATURE = 2600
