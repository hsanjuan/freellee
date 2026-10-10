package link.hector.freellee.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize.Max
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.bluetooth.BluetoothManager
import android.content.Context
import link.hector.freellee.ui.components.WatchStatusIndicator
import link.hector.freellee.ui.theme.AccentGreen
import link.hector.freellee.ui.theme.CardBackground
import link.hector.freellee.ui.theme.StatusError
import link.hector.freellee.ui.theme.TextPrimary
import link.hector.freellee.ui.theme.TextSecondary
import link.hector.freellee.ui.theme.TextTertiary
import link.hector.freellee.util.formatDuration
import link.hector.freellee.util.formatTimestamp
import link.hector.freellee.viewmodel.DashboardUiState
import link.hector.freellee.viewmodel.HistoryEntry
import link.hector.freellee.viewmodel.OlleeViewModel
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: OlleeViewModel,
    onConnectClick: () -> Unit,
    onSync: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val isConnected by viewModel.isConnected.collectAsState()
    val bleState by viewModel.connectionState.collectAsState()
    val hasPairedDevice by viewModel.hasPairedDevice.collectAsState()

    LaunchedEffect(uiState) {
        if (uiState is DashboardUiState.Error) {
            snackbarHostState.showSnackbar(
                message = (uiState as DashboardUiState.Error).message,
                duration = SnackbarDuration.Indefinite,
                withDismissAction = true,
            )
            viewModel.clearError()
        }
    }

    // Full-screen record detail replaces the dashboard content while open.
    val detailScreen by viewModel.detailScreen.collectAsStateWithLifecycle()
    (detailScreen as? OlleeViewModel.DetailScreen.Records)?.let { detail ->
        RecordDetailScreen(
            title = detail.title,
            records = detail.records,
            onBack = viewModel::dismissDetailScreen,
        )
        return
    }

    Scaffold(
        // The outer MainTabs Scaffold already applies the system-bar insets, so request none here
        // to avoid double padding while still consuming the content PaddingValues below.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    containerColor = StatusError.copy(alpha = 0.9f),
                    contentColor = TextPrimary,
                    actionContentColor = TextPrimary,
                    shape = MaterialTheme.shapes.small,
                    content = {
                        Text(
                            text = data.visuals.message,
                            color = TextPrimary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    action = {
                        TextButton(
                            onClick = { viewModel.clearError() },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = TextPrimary,
                            ),
                        ) {
                            Text("Dismiss", color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
                        }
                    },
                )
            }
        },
    ) { padding ->
        val isRefreshing = when (uiState) {
            is DashboardUiState.Connecting,
            is DashboardUiState.Connected,
            is DashboardUiState.Syncing -> true

            else -> false
        }

        val onRefresh: () -> Unit = {
            onSync()
        }

        PullToRefreshBox(
            isRefreshing = isRefreshing,
            state = rememberPullToRefreshState(),
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            val context = LocalContext.current
            val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val bluetoothEnabled = btManager?.adapter?.isEnabled == true

            val dashboardState = uiState as? DashboardUiState.Ready ?: viewModel.lastReadyState

            UnifiedDashboard(
                state = dashboardState,
                isConnected = isConnected,
                onConnectClick = onConnectClick,
                onViewAll = viewModel::showRecordsDetail,
            ) {
                WatchStatusIndicator(
                    uiState = uiState,
                    bleState = bleState,
                    hasPairedDevice = hasPairedDevice,
                    bluetoothEnabled = bluetoothEnabled,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun UnifiedDashboard(
    state: DashboardUiState.Ready?,
    isConnected: Boolean,
    onConnectClick: () -> Unit,
    onViewAll: (String, List<HistoryEntry>) -> Unit,
    statusIndicator: @Composable () -> Unit,
) {
    val dailySteps = state?.steps ?: 0
    val weeklySteps = state?.weeklySteps ?: 0
    val stepIntervals = state?.stepIntervals ?: emptyList()
    val latestStepSyncTime = stepIntervals.maxOfOrNull { it.syncTimestamp }
    val heartRateRecords = state?.heartRateRecords ?: emptyList()
    val temperatureRecords = state?.temperatureRecords ?: emptyList()
    val counterRecords = state?.counterRecords ?: emptyList()
    val stopwatchRecords = state?.stopwatchRecords ?: emptyList()

    // ── Dashboard content ────────────────────────────────────────────────

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            statusIndicator()
        }

        // Daily steps + Weekly steps row
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Max),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    colors = CardDefaults.cardColors(containerColor = CardBackground),
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            "Daily Steps",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextSecondary,
                        )
                        Spacer(modifier = Modifier.padding(top = 8.dp))
                        Text(
                            dailySteps.formatNumber(),
                            style = MaterialTheme.typography.displayMedium,
                            color = if (dailySteps > 0) AccentGreen else TextTertiary,
                        )
                        if (latestStepSyncTime != null && latestStepSyncTime > 0) {
                            Text(
                                "Last synced: ${formatTimestamp(Instant.ofEpochSecond(latestStepSyncTime))}",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextTertiary,
                            )
                        } else {
                            Text(
                                "Never synced",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextTertiary,
                            )
                        }
                    }
                }

                Card(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    colors = CardDefaults.cardColors(containerColor = CardBackground),
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            "Weekly Steps",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextSecondary,
                        )
                        Spacer(modifier = Modifier.padding(top = 8.dp))
                        Text(
                            weeklySteps.formatNumber(),
                            style = MaterialTheme.typography.displayMedium,
                            color = if (weeklySteps > 0) AccentGreen else TextTertiary,
                        )
                    }
                }
            }
        }

        // Heart rate + Temperature row
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Max),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    colors = CardDefaults.cardColors(containerColor = CardBackground),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.Favorite,
                                contentDescription = null,
                                tint = TextSecondary,
                            )
                            Column {
                                Text(
                                    "Last Heart Rate",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextTertiary,
                                )
                                Spacer(modifier = Modifier.padding(top = 4.dp))
                                if (heartRateRecords.isNotEmpty()) {
                                    val lastHR = heartRateRecords.last()
                                    Text(
                                        "${lastHR.value} bpm",
                                        style = MaterialTheme.typography.headlineMedium,
                                        color = AccentGreen,
                                    )
                                    Text(
                                        formatTimestamp(Instant.ofEpochSecond(lastHR.watchTimestampStart)),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextTertiary,
                                    )
                                } else {
                                    Text(
                                        "--",
                                        style = MaterialTheme.typography.headlineMedium,
                                        color = TextPrimary,
                                    )
                                }
                            }
                        }
                    }
                }

                Card(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    colors = CardDefaults.cardColors(containerColor = CardBackground),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.Thermostat,
                                contentDescription = null,
                                tint = TextSecondary,
                            )
                            Column {
                                Text(
                                    "Last Temperature",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextTertiary,
                                )
                                Spacer(modifier = Modifier.padding(top = 4.dp))
                                if (temperatureRecords.isNotEmpty()) {
                                    val lastTemp = temperatureRecords.last()
                                    Text(
                                        "%.1f°C".format(lastTemp.value / 100.0),
                                        style = MaterialTheme.typography.headlineMedium,
                                        color = AccentGreen,
                                    )
                                    Text(
                                        formatTimestamp(Instant.ofEpochSecond(lastTemp.watchTimestampStart)),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextTertiary,
                                    )
                                } else {
                                    Text(
                                        "--",
                                        style = MaterialTheme.typography.headlineMedium,
                                        color = TextPrimary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── History sections ───────────────────────────────────────────

        // Steps history section
        item {
            val expandedSteps = remember { mutableStateOf(false) }
            val recentSteps = stepIntervals.takeLast(20).reversed()
            ExpandableHistorySection(
                title = "Steps",
                count = stepIntervals.size,
                isExpanded = expandedSteps.value,
                onToggle = { expandedSteps.value = !expandedSteps.value },
                onViewAll = { onViewAll("Steps", stepIntervals.map { HistoryEntry(it.steps, it.tStart, it.tEnd, it.syncTimestamp) }) },
            ) {
                if (stepIntervals.isEmpty()) {
                    Text(
                        "No records yet. Sync your watch to see history.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextTertiary,
                    )
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        recentSteps.forEach { interval ->
                            val start = formatTimestamp(Instant.ofEpochSecond(interval.tStart))
                            val end = formatTimestamp(Instant.ofEpochSecond(interval.tEnd))
                            HistoryEntryRow(
                                watchRange = "$start -> $end",
                                syncTime = if (interval.syncTimestamp > 0) formatTimestamp(Instant.ofEpochSecond(interval.syncTimestamp)) else "—",
                                value = interval.steps.formatNumber(),
                            )
                        }
                    }
                }
            }
        }

        // Heart rate history section
        if (heartRateRecords.isNotEmpty()) {
            item {
                val expandedHR = remember { mutableStateOf(false) }
                val recentHR = heartRateRecords.takeLast(20).reversed()
                ExpandableHistorySection(
                    title = "Heart Rate",
                    count = heartRateRecords.size,
                    isExpanded = expandedHR.value,
                    onToggle = { expandedHR.value = !expandedHR.value },
                    onViewAll = { onViewAll("Heart Rate", heartRateRecords) },
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        recentHR.forEach { entry ->
                            val watchTime = formatTimestamp(Instant.ofEpochSecond(entry.watchTimestampStart))
                            HistoryEntryRow(
                                watchRange = watchTime,
                                syncTime = formatTimestamp(Instant.ofEpochSecond(entry.syncTimestamp)),
                                value = "${entry.value} bpm",
                            )
                        }
                    }
                }
            }
        }

        // Temperature history section
        if (temperatureRecords.isNotEmpty()) {
            item {
                val expandedTemp = remember { mutableStateOf(false) }
                val recentTemp = temperatureRecords.takeLast(20).reversed()
                ExpandableHistorySection(
                    title = "Temperature",
                    count = temperatureRecords.size,
                    isExpanded = expandedTemp.value,
                    onToggle = { expandedTemp.value = !expandedTemp.value },
                    onViewAll = { onViewAll("Temperature", temperatureRecords) },
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        recentTemp.forEach { entry ->
                            val start = formatTimestamp(Instant.ofEpochSecond(entry.watchTimestampStart))
                            val end = formatTimestamp(Instant.ofEpochSecond(entry.watchTimestampEnd))
                            HistoryEntryRow(
                                watchRange = "$start -> $end",
                                syncTime = formatTimestamp(Instant.ofEpochSecond(entry.syncTimestamp)),
                                value = "%.1f°C".format(entry.value / 100.0),
                            )
                        }
                    }
                }
            }
        }

        // Counter history section
        if (counterRecords.isNotEmpty()) {
            item {
                val expandedCounter = remember { mutableStateOf(false) }
                val recentCounter = counterRecords.takeLast(20).reversed()
                ExpandableHistorySection(
                    title = "Counter",
                    count = counterRecords.size,
                    isExpanded = expandedCounter.value,
                    onToggle = { expandedCounter.value = !expandedCounter.value },
                    onViewAll = { onViewAll("Counter", counterRecords) },
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        recentCounter.forEach { entry ->
                            val watchTime = formatTimestamp(Instant.ofEpochSecond(entry.watchTimestampStart))
                            HistoryEntryRow(
                                watchRange = watchTime,
                                syncTime = formatTimestamp(Instant.ofEpochSecond(entry.syncTimestamp)),
                                value = "${entry.value}",
                            )
                        }
                    }
                }
            }
        }

        // Stopwatch history section
        if (stopwatchRecords.isNotEmpty()) {
            item {
                val expandedStopwatch = remember { mutableStateOf(false) }
                val recentStopwatch = stopwatchRecords.takeLast(20).reversed()
                ExpandableHistorySection(
                    title = "Stopwatch",
                    count = stopwatchRecords.size,
                    isExpanded = expandedStopwatch.value,
                    onToggle = { expandedStopwatch.value = !expandedStopwatch.value },
                    onViewAll = { onViewAll("Stopwatch", stopwatchRecords) },
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        recentStopwatch.forEach { entry ->
                            val start = formatTimestamp(Instant.ofEpochSecond(entry.watchTimestampStart))
                            val end = formatTimestamp(Instant.ofEpochSecond(entry.watchTimestampEnd))
                            HistoryEntryRow(
                                watchRange = "$start -> $end",
                                syncTime = formatTimestamp(Instant.ofEpochSecond(entry.syncTimestamp)),
                                value = formatDuration(entry.value),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpandableHistorySection(
    title: String,
    count: Int,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onViewAll: () -> Unit,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.History, contentDescription = null, tint = AccentGreen, modifier = Modifier.padding(end = 8.dp))
                    Column {
                        Text(title, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                        Text("$count records", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                    }
                }
                Icon(
                    if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    tint = TextTertiary,
                )
            }
            if (isExpanded) {
                HorizontalDivider(color = TextTertiary.copy(alpha = 0.3f), thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
                Column(modifier = Modifier.padding(12.dp)) {
                    content()
                    if (count > 20) {
                        TextButton(
                            onClick = onViewAll,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.textButtonColors(contentColor = AccentGreen),
                        ) {
                            Text(
                                "View all $count records",
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = null,
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryEntryRow(watchRange: String, syncTime: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Watch: $watchRange", style = MaterialTheme.typography.bodySmall, color = TextTertiary)
            Text("Sync: $syncTime", style = MaterialTheme.typography.bodySmall, color = TextTertiary)
        }
        Text(value, style = MaterialTheme.typography.bodyMedium, color = TextPrimary, modifier = Modifier.padding(start = 8.dp))
    }
}

private fun Int.formatNumber(): String {
    return this.toString().reversed().chunked(3).joinToString(",").reversed()
}
