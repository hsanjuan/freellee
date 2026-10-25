package link.hector.freellee.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import link.hector.freellee.ui.theme.AccentAmber
import link.hector.freellee.ui.theme.AccentBlue
import link.hector.freellee.ui.theme.AccentGreen
import link.hector.freellee.ui.theme.AccentTeal
import link.hector.freellee.ui.theme.CardBackground
import link.hector.freellee.ui.theme.TextPrimary
import link.hector.freellee.ui.theme.TextSecondary
import link.hector.freellee.ui.theme.TextTertiary
import link.hector.freellee.util.formatDuration
import link.hector.freellee.util.formatTimestamp
import link.hector.freellee.viewmodel.DashboardUiState
import link.hector.freellee.viewmodel.HistoryEntry
import link.hector.freellee.viewmodel.OlleeViewModel
import java.time.Instant

/**
 * History tab: one summary card per record type. Tapping a card opens the full,
 * filterable record list, so the tab stays scannable instead of expanding long
 * lists inline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    viewModel: OlleeViewModel,
    onSync: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val detailScreen by viewModel.detailScreen.collectAsStateWithLifecycle()

    (detailScreen as? OlleeViewModel.DetailScreen.Records)?.let { detail ->
        RecordDetailScreen(
            title = detail.title,
            records = detail.records,
            onBack = viewModel::dismissDetailScreen,
        )
        return
    }

    val state = uiState as? DashboardUiState.Ready ?: viewModel.lastReadyState
    val isRefreshing = uiState is DashboardUiState.Connecting ||
        uiState is DashboardUiState.Connected ||
        uiState is DashboardUiState.Syncing

    val steps = state?.stepIntervals.orEmpty()
        .map { HistoryEntry(it.steps, it.tStart, it.tEnd, it.syncTimestamp) }
    val heartRate = state?.heartRateRecords.orEmpty()
    val temperature = state?.temperatureRecords.orEmpty()
    val counter = state?.counterRecords.orEmpty()
    val stopwatch = state?.stopwatchRecords.orEmpty()

    val cards = listOf(
        HistorySummary(
            title = "Steps",
            icon = Icons.AutoMirrored.Filled.DirectionsWalk,
            accent = AccentGreen,
            records = steps,
            countLabel = "${steps.size} intervals",
            trailing = state?.steps?.let { "%,d".format(it) },
            trailingLabel = "today",
        ),
        HistorySummary(
            title = "Heart Rate",
            icon = Icons.Default.Favorite,
            accent = AccentBlue,
            records = heartRate,
            countLabel = "${heartRate.size} measurements",
            trailing = heartRate.lastOrNull()?.let { "${it.value} bpm" },
            trailingLabel = "latest",
        ),
        HistorySummary(
            title = "Temperature",
            icon = Icons.Default.Thermostat,
            accent = AccentAmber,
            records = temperature,
            countLabel = "${temperature.size} measurements",
            trailing = temperature.lastOrNull()?.let { "%.1f°C".format(it.value / 100.0) },
            trailingLabel = "latest",
        ),
        HistorySummary(
            title = "Counter",
            icon = Icons.Default.Numbers,
            accent = AccentTeal,
            records = counter,
            countLabel = "${counter.size} events",
            trailing = counter.lastOrNull()?.let { it.value.toString() },
            trailingLabel = "latest",
        ),
        HistorySummary(
            title = "Stopwatch",
            icon = Icons.Default.Timer,
            accent = AccentGreen,
            records = stopwatch,
            countLabel = "${stopwatch.size} sessions",
            trailing = stopwatch.lastOrNull()?.let { formatDuration(it.value) },
            trailingLabel = "latest",
        ),
    )

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        state = rememberPullToRefreshState(),
        onRefresh = onSync,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Synced records",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextSecondary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }

            items(cards.size) { index ->
                val card = cards[index]
                HistorySummaryCard(
                    card = card,
                    onClick = {
                        viewModel.showRecordsDetail(card.title, card.records)
                    },
                )
            }
        }
    }
}

private data class HistorySummary(
    val title: String,
    val icon: ImageVector,
    val accent: Color,
    val records: List<HistoryEntry>,
    val countLabel: String,
    val trailing: String?,
    val trailingLabel: String,
)

@Composable
private fun HistorySummaryCard(
    card: HistorySummary,
    onClick: () -> Unit,
) {
    val hasRecords = card.records.isNotEmpty()
    val lastSync = card.records.maxOfOrNull { it.syncTimestamp }
        ?.takeIf { it > 0 }
        ?.let { formatTimestamp(Instant.ofEpochSecond(it)) }

    Card(
        onClick = onClick,
        enabled = hasRecords,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = CardBackground,
            disabledContainerColor = CardBackground,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(card.accent.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = card.icon,
                    contentDescription = null,
                    tint = card.accent,
                    modifier = Modifier.size(24.dp),
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = card.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (hasRecords) TextPrimary else TextSecondary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = card.countLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
                Text(
                    text = if (lastSync != null) "Last sync: $lastSync" else "Not synced yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary,
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = card.trailing ?: "—",
                    style = MaterialTheme.typography.titleLarge,
                    color = if (hasRecords) card.accent else TextTertiary,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = if (hasRecords) card.trailingLabel else "no data",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextTertiary,
                )
            }

            if (hasRecords) {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = "Open ${card.title}",
                    tint = TextTertiary,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }
}
