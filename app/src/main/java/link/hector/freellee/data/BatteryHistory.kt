package link.hector.freellee.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Number of days of battery readings kept and shown for each device. */
const val BATTERY_HISTORY_DAYS = 60

/** One battery reading reported by the watch. Voltage is stored because that is what it reports. */
data class BatterySample(
    val epochSecond: Long,
    val voltageMv: Int,
)

/**
 * Approximate battery percentage for a CR2016 coin cell: 3000 mV = 100 %, 2600 mV = 0 %.
 */
fun batteryPercentage(voltageMv: Int): Int =
    (((voltageMv - 2600) * 100) / 400).coerceIn(0, 100)

/**
 * Appends a reading and drops samples older than [retentionDays]. Repeated identical readings
 * within an hour are ignored so frequent syncs do not fill the history with duplicates.
 */
fun appendBatterySample(
    history: List<BatterySample>,
    sample: BatterySample,
    retentionDays: Int = BATTERY_HISTORY_DAYS,
): List<BatterySample> {
    val cutoff = sample.epochSecond - retentionDays * 86_400L
    val pruned = history.filter {
        it.epochSecond >= cutoff && it.epochSecond != sample.epochSecond
    }
    val last = pruned.lastOrNull()
    if (last != null &&
        last.voltageMv == sample.voltageMv &&
        sample.epochSecond - last.epochSecond < 3_600L
    ) {
        return pruned
    }
    return (pruned + sample).sortedBy { it.epochSecond }
}

/** One day in the battery chart. [percent] is null when there is no reading for that day. */
data class BatteryPoint(
    val date: LocalDate,
    val percent: Int?,
)

/**
 * Daily battery percentage for the [days] days ending on [endDate]. The last reading of each
 * local day is used. Missing days stay null so the chart breaks instead of inventing a value.
 */
fun batteryDailySeries(
    samples: List<BatterySample>,
    endDate: LocalDate,
    days: Int = BATTERY_HISTORY_DAYS,
    zone: ZoneId = ZoneId.systemDefault(),
): List<BatteryPoint> {
    require(days > 0)
    val start = endDate.minusDays((days - 1).toLong())
    val byDate = samples.groupBy {
        Instant.ofEpochSecond(it.epochSecond).atZone(zone).toLocalDate()
    }
    return List(days) { index ->
        val date = start.plusDays(index.toLong())
        val latest = byDate[date]?.maxByOrNull { it.epochSecond }
        BatteryPoint(date, latest?.let { batteryPercentage(it.voltageMv) })
    }
}
