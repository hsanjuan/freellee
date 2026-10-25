package link.hector.freellee.viewmodel

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Heart-rate aggregate over a set of samples. */
data class HeartRateStats(
    val average: Double? = null,
    val minimum: Int? = null,
    val maximum: Int? = null,
    val sampleCount: Int = 0,
)

/** Length of the rolling window used for "weekly" aggregates. */
const val ROLLING_WINDOW_SECONDS: Long = 7L * 24 * 60 * 60

/** Start of the rolling seven-day window that ends at [nowEpochSecond]. */
fun rollingWindowStartEpoch(nowEpochSecond: Long): Long = nowEpochSecond - ROLLING_WINDOW_SECONDS

/** Computes the heart-rate statistics for the local day that contains [nowEpochSecond]. */
fun todayHeartRateStats(
    records: List<HistoryEntry>,
    nowEpochSecond: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): HeartRateStats {
    val todayStart = LocalDate.ofInstant(Instant.ofEpochSecond(nowEpochSecond), zone)
        .atStartOfDay(zone)
        .toEpochSecond()
    return aggregate(records.filter { it.watchTimestampStart in todayStart..nowEpochSecond })
}

private fun aggregate(records: List<HistoryEntry>): HeartRateStats {
    if (records.isEmpty()) return HeartRateStats()
    val values = records.map { it.value }
    return HeartRateStats(
        average = values.average(),
        minimum = values.min(),
        maximum = values.max(),
        sampleCount = values.size,
    )
}
