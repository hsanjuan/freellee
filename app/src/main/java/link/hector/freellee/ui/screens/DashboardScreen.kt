package link.hector.freellee.ui.screens

import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import link.hector.freellee.data.DeviceStore.RecordItem
import link.hector.freellee.data.DeviceStore.StepInterval
import link.hector.freellee.data.TemperatureInterval
import link.hector.freellee.data.currentTemperatureInterval
import link.hector.freellee.data.previousTemperatureIntervals
import link.hector.freellee.ui.components.WatchStatusIndicator
import link.hector.freellee.ui.theme.AccentAmber
import link.hector.freellee.ui.theme.AccentBlue
import link.hector.freellee.ui.theme.AccentGreen
import link.hector.freellee.ui.theme.CardBackground
import link.hector.freellee.ui.theme.StatusError
import link.hector.freellee.ui.theme.SurfaceDark
import link.hector.freellee.ui.theme.TextPrimary
import link.hector.freellee.ui.theme.TextSecondary
import link.hector.freellee.ui.theme.TextTertiary
import link.hector.freellee.util.formatClockRange
import link.hector.freellee.util.formatIntervalRange
import link.hector.freellee.util.formatTimestamp
import link.hector.freellee.viewmodel.DashboardUiState
import link.hector.freellee.viewmodel.HeartRateStats
import link.hector.freellee.viewmodel.HistoryEntry
import link.hector.freellee.viewmodel.OlleeViewModel
import link.hector.freellee.viewmodel.todayHeartRateStats
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: OlleeViewModel,
    onSync: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
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

        PullToRefreshBox(
            isRefreshing = isRefreshing,
            state = rememberPullToRefreshState(),
            onRefresh = onSync,
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
    statusIndicator: @Composable () -> Unit,
) {
    val dailySteps = state?.steps ?: 0
    val weeklySteps = state?.weeklySteps ?: 0
    val stepIntervals = state?.stepIntervals ?: emptyList()
    val lastStepInterval = stepIntervals.maxByOrNull { it.tStart }
    val heartRateRecords = state?.heartRateRecords ?: emptyList()
    val temperatureRecords = state?.temperatureRecords ?: emptyList()

    val nowEpochSecond = remember(heartRateRecords, temperatureRecords) {
        Instant.now().epochSecond
    }
    val heartRate = remember(heartRateRecords, nowEpochSecond) {
        todayHeartRateStats(heartRateRecords, nowEpochSecond)
    }
    val temperatureReadings = remember(temperatureRecords) {
        temperatureRecords.map {
            RecordItem(0, it.watchTimestampStart, it.watchTimestampEnd, it.value, it.syncTimestamp)
        }
    }
    val currentTemperature = remember(temperatureReadings, nowEpochSecond) {
        currentTemperatureInterval(temperatureReadings, nowEpochSecond)
    }
    val previousTemperatures = remember(temperatureReadings, nowEpochSecond) {
        previousTemperatureIntervals(temperatureReadings, count = 3, nowEpochSecond = nowEpochSecond)
    }
    val lastHeartRate = heartRateRecords.lastOrNull()
    val lastTemperature = temperatureRecords.lastOrNull()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            statusIndicator()
        }

        // Daily steps + rolling seven-day steps
        item {
            StepsSection(
                dailySteps = dailySteps,
                weeklySteps = weeklySteps,
                lastInterval = lastStepInterval,
            )
        }

        item {
            TemperatureSection(
                current = currentTemperature,
                previous = previousTemperatures,
                lastRecord = lastTemperature,
            )
        }

        item {
            HeartRateSection(
                stats = heartRate,
                lastRecord = lastHeartRate,
            )
        }
    }
}

@Composable
private fun StepsSection(
    dailySteps: Int,
    weeklySteps: Int,
    lastInterval: StepInterval?,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionHeader(
                icon = Icons.AutoMirrored.Filled.DirectionsWalk,
                accent = AccentGreen,
                title = "Steps",
                subtitle = lastInterval?.let {
                    formatClockRange(
                        start = Instant.ofEpochSecond(it.tStart),
                        end = Instant.ofEpochSecond(it.tEnd),
                    )
                } ?: "Never synced",
                trailing = lastInterval?.let { "${it.steps.formatNumber()} steps" },
            )

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatBlock(
                    label = "Today",
                    value = dailySteps.formatNumber(),
                    modifier = Modifier.weight(1f),
                )
                StatBlock(
                    label = "Last 7 days",
                    value = weeklySteps.formatNumber(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun HeartRateSection(
    stats: HeartRateStats,
    lastRecord: HistoryEntry?,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionHeader(
                icon = Icons.Default.Favorite,
                accent = AccentGreen,
                title = "Heart Rate",
                subtitle = lastRecord?.let {
                    formatTimestamp(Instant.ofEpochSecond(it.watchTimestampStart))
                } ?: "No measurements yet",
                trailing = lastRecord?.let { "${it.value} bpm" },
            )

            Spacer(Modifier.height(16.dp))
            Text(
                "Today",
                style = MaterialTheme.typography.labelLarge,
                color = TextSecondary,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatBlock(
                    label = "Average",
                    value = stats.formatAverage(),
                    modifier = Modifier.weight(1f),
                )
                StatBlock(
                    label = "Maximum",
                    value = stats.maximum.formatBpm(),
                    modifier = Modifier.weight(1f),
                )
                StatBlock(
                    label = "Minimum",
                    value = stats.minimum.formatBpm(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun TemperatureSection(
    current: TemperatureInterval,
    previous: List<TemperatureInterval>,
    lastRecord: HistoryEntry?,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionHeader(
                icon = Icons.Default.Thermostat,
                accent = AccentAmber,
                title = "Skin Temperature",
                subtitle = lastRecord?.let {
                    "Last: %.1f°C · %s".format(
                        it.value / 100.0,
                        formatTimestamp(Instant.ofEpochSecond(it.watchTimestampStart)),
                    )
                } ?: "No measurements yet",
                trailing = current.period.label,
            )

            Spacer(Modifier.height(16.dp))
            Text(
                "Current interval",
                style = MaterialTheme.typography.labelLarge,
                color = TextSecondary,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatBlock(
                    label = "Baseline",
                    value = current.baselineCelsius?.let { "%.1f°C".format(it) } ?: "—",
                    modifier = Modifier.weight(1f),
                )
                StatBlock(
                    label = "Deviation",
                    value = current.deviationCelsius.formatDeviation(),
                    valueColor = current.deviationCelsius.deviationColor(),
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "Previous intervals",
                style = MaterialTheme.typography.labelLarge,
                color = TextSecondary,
            )
            Spacer(Modifier.height(8.dp))

            if (previous.isEmpty()) {
                Text(
                    "No completed intervals yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary,
                )
            } else {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Period",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "Baseline",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary,
                        modifier = Modifier.width(76.dp),
                        textAlign = TextAlign.End,
                    )
                    Text(
                        "Deviation",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary,
                        modifier = Modifier.width(84.dp),
                        textAlign = TextAlign.End,
                    )
                }

                previous.forEachIndexed { index, interval ->
                    if (index > 0) {
                        HorizontalDivider(
                            color = TextTertiary.copy(alpha = 0.2f),
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                    TemperatureIntervalRow(interval)
                }
            }
        }
    }
}

@Composable
private fun TemperatureIntervalRow(interval: TemperatureInterval) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                interval.period.label,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = if (interval.lastMeasuredAtEpochSecond != null) {
                    formatIntervalRange(
                        start = Instant.ofEpochSecond(interval.startEpochSecond),
                        end = Instant.ofEpochSecond(interval.endEpochSecond),
                    )
                } else {
                    "No readings in this period"
                },
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = interval.baselineCelsius?.let { "%.1f°C".format(it) } ?: "—",
            style = MaterialTheme.typography.titleMedium,
            color = if (interval.baselineCelsius != null) TextPrimary else TextTertiary,
            modifier = Modifier.width(76.dp),
            textAlign = TextAlign.End,
        )
        Text(
            text = interval.deviationCelsius.formatDeviation(),
            style = MaterialTheme.typography.titleMedium,
            color = interval.deviationCelsius.deviationColor(),
            modifier = Modifier.width(84.dp),
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun SectionHeader(
    icon: ImageVector,
    accent: Color,
    title: String,
    subtitle: String,
    trailing: String?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            Text(
                trailing,
                style = MaterialTheme.typography.titleMedium,
                color = accent,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun StatBlock(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = TextPrimary,
) {
    Column(
        modifier = modifier
            .background(SurfaceDark, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 12.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = TextTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            color = valueColor,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

private fun HeartRateStats.formatAverage(): String =
    average?.let { "${Math.round(it)} bpm" } ?: "—"

private fun Int?.formatBpm(): String = this?.let { "$it bpm" } ?: "—"

private fun Double?.formatDeviation(): String =
    this?.let { "%+.1f°C".format(it) } ?: "—"

private fun Double?.deviationColor(): Color = when {
    this == null -> TextTertiary
    this > 0.05 -> AccentAmber
    this < -0.05 -> AccentBlue
    else -> TextSecondary
}

private fun Int.formatNumber(): String {
    return this.toString().reversed().chunked(3).joinToString(",").reversed()
}
