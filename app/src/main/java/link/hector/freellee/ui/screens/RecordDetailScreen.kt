package link.hector.freellee.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale
import link.hector.freellee.ui.theme.AccentGreen
import link.hector.freellee.ui.theme.CardBackground
import link.hector.freellee.ui.theme.TextSecondary
import link.hector.freellee.ui.theme.TextTertiary
import link.hector.freellee.util.formatDuration
import link.hector.freellee.util.formatTimestamp
import link.hector.freellee.viewmodel.HistoryEntry

/** How many records are revealed per page in the detail list. */
private const val PAGE_SIZE = 500

/** Time-range filter options for the record detail screen. */
enum class TimeRangeFilter(
    val label: String,
) {
    DAY("Day"),
    WEEK("Week"),
    MONTH("Month"),
    ALL("All"),
}

/**
 * Full-screen detail view for a specific kind of health record. Shows a
 * time-range selector and a paginated, newest-first list of records.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordDetailScreen(
    title: String,
    records: List<HistoryEntry>,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    var menuExpanded by rememberSaveable { mutableStateOf(false) }
    var selectedFilter by rememberSaveable { mutableStateOf(TimeRangeFilter.ALL) }

    val cutoff = remember(selectedFilter) { selectedFilter.cutoffEpochSecond() }
    val filteredRecords = remember(records, cutoff) {
        records
            .filter { it.watchTimestampStart >= cutoff }
            .sortedByDescending { it.watchTimestampStart }
    }

    var visibleCount by rememberSaveable { mutableStateOf(PAGE_SIZE) }
    val visibleRecords = filteredRecords.take(visibleCount)
    val remaining = filteredRecords.size - visibleRecords.size

    Scaffold(
        // The tab scaffold above already consumes the system-bar insets, so this
        // screen must not apply them again or it would double the top padding.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    ExposedDropdownMenuBox(
                        expanded = menuExpanded,
                        onExpandedChange = { menuExpanded = !menuExpanded },
                    ) {
                        Row(
                            modifier = Modifier
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                selectedFilter.label,
                                style = MaterialTheme.typography.labelLarge,
                                color = AccentGreen,
                            )
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuExpanded)
                        }
                        ExposedDropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                            // Don't force the menu to the narrow anchor width, which would
                            // wrap longer labels (e.g. "Month") onto a second line.
                            matchAnchorWidth = false,
                        ) {
                            TimeRangeFilter.entries.forEach { filter ->
                                DropdownMenuItem(
                                    text = { Text(filter.label) },
                                    onClick = {
                                        selectedFilter = filter
                                        visibleCount = PAGE_SIZE
                                        menuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                },
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "${filteredRecords.size} records",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary,
                )
                if (filteredRecords.isNotEmpty()) {
                    Text(
                        "Latest: ${formatTimestamp(Instant.ofEpochSecond(filteredRecords.first().watchTimestampStart))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary,
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (filteredRecords.isEmpty()) {
                    item { EmptyRecords() }
                } else {
                    items(
                        items = visibleRecords,
                        key = { "${it.watchTimestampStart}-${it.watchTimestampEnd}-${it.value}" },
                    ) { entry ->
                        RecordRow(title = title, entry = entry)
                    }

                    if (remaining > 0) {
                        item {
                            TextButton(
                                onClick = { visibleCount += PAGE_SIZE },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                            ) {
                                Text(
                                    "Show ${minOf(PAGE_SIZE, remaining)} more",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = AccentGreen,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyRecords() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Default.History,
            contentDescription = null,
            tint = TextTertiary,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        Text(
            "No records in this time range",
            style = MaterialTheme.typography.bodyMedium,
            color = TextTertiary,
        )
    }
}

@Composable
private fun RecordRow(title: String, entry: HistoryEntry) {
    // Instantaneous records (heart rate, counter) carry tEnd == 0, so only show an
    // end time when the record actually spans a range.
    val hasRange = entry.watchTimestampEnd > entry.watchTimestampStart
    val syncedAt = if (entry.syncTimestamp > 0) {
        formatTimestamp(Instant.ofEpochSecond(entry.syncTimestamp))
    } else {
        null
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    entry.formatValue(title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = AccentGreen,
                )
                Text(
                    formatTimestamp(Instant.ofEpochSecond(entry.watchTimestampStart)),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
            if (hasRange) {
                Text(
                    "End: ${formatTimestamp(Instant.ofEpochSecond(entry.watchTimestampEnd))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (syncedAt != null) {
                Text(
                    "Synced: $syncedAt",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

private fun HistoryEntry.formatValue(title: String): String = when (title) {
    "Temperature" -> "%.1f°C".format(value / 100.0)
    "Heart Rate" -> "$value bpm"
    "Stopwatch" -> formatDuration(value)
    "Steps" -> "%,d".format(value)
    else -> value.toString()
}

/** Epoch second at which the filter's window begins (inclusive). */
private fun TimeRangeFilter.cutoffEpochSecond(): Long {
    if (this == TimeRangeFilter.ALL) return Long.MIN_VALUE
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val start = when (this) {
        TimeRangeFilter.DAY -> today
        TimeRangeFilter.WEEK -> {
            val weekFields = WeekFields.of(Locale.getDefault())
            today.with(TemporalAdjusters.previousOrSame(weekFields.firstDayOfWeek))
        }
        TimeRangeFilter.MONTH -> today.withDayOfMonth(1)
        TimeRangeFilter.ALL -> today
    }
    return start.atStartOfDay(zone).toEpochSecond()
}
