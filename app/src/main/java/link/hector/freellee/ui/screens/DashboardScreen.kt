package link.hector.freellee.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.Bedtime
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import link.hector.freellee.data.SleepInsights
import link.hector.freellee.data.SleepSessionKind
import link.hector.freellee.data.SleepSessionSummary
import link.hector.freellee.data.TemperaturePeriodSummary
import link.hector.freellee.ui.charts.DashboardChartData
import link.hector.freellee.ui.charts.StepsBarChart
import link.hector.freellee.ui.charts.StepsChartData
import link.hector.freellee.ui.charts.TemperatureChartData
import link.hector.freellee.ui.charts.TemperatureLineChart
import link.hector.freellee.ui.charts.TodayStepsChart
import link.hector.freellee.ui.theme.AccentAmber
import link.hector.freellee.ui.theme.AccentBlue
import link.hector.freellee.ui.theme.AccentGreen
import link.hector.freellee.ui.theme.AccentPurple
import link.hector.freellee.ui.theme.CardBackground
import link.hector.freellee.ui.theme.StatusError
import link.hector.freellee.ui.theme.SurfaceDark
import link.hector.freellee.ui.theme.TextPrimary
import link.hector.freellee.ui.theme.TextSecondary
import link.hector.freellee.ui.theme.TextTertiary
import link.hector.freellee.util.formatSleepDuration
import link.hector.freellee.util.formatSleepRange
import link.hector.freellee.util.formatTimestamp
import link.hector.freellee.viewmodel.DashboardUiState
import link.hector.freellee.viewmodel.HeartRateStats
import link.hector.freellee.viewmodel.HistoryEntry
import link.hector.freellee.viewmodel.OlleeViewModel
import link.hector.freellee.viewmodel.todayHeartRateStats
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: OlleeViewModel,
    onSync: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val chartData by viewModel.chartData.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val advancedEnabled by viewModel.advancedEnabled.collectAsState()
    val nowEpochSecond = rememberCurrentEpochSecond()

    LaunchedEffect(nowEpochSecond) {
        viewModel.refreshChartData(nowEpochSecond)
    }

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
            val dashboardState = uiState as? DashboardUiState.Ready ?: viewModel.lastReadyState

            UnifiedDashboard(
                state = dashboardState,
                chartData = chartData,
                sleepEnabled = advancedEnabled,
                nowEpochSecond = nowEpochSecond,
            )
        }
    }
}

/** Wall-clock seconds that refresh every minute while this screen is resumed. */
@Composable
private fun rememberCurrentEpochSecond(): Long {
    val lifecycleOwner = LocalLifecycleOwner.current
    var nowEpochSecond by remember { mutableLongStateOf(Instant.now().epochSecond) }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                nowEpochSecond = Instant.now().epochSecond
                delay(60_000L)
            }
        }
    }
    return nowEpochSecond
}

@Composable
private fun UnifiedDashboard(
    state: DashboardUiState.Ready?,
    chartData: DashboardChartData,
    sleepEnabled: Boolean,
    nowEpochSecond: Long,
) {
    val dailySteps = state?.steps ?: 0
    val heartRateRecords = state?.heartRateRecords ?: emptyList()
    val temperatureRecords = state?.temperatureRecords ?: emptyList()

    val heartRate = remember(heartRateRecords, nowEpochSecond) {
        todayHeartRateStats(heartRateRecords, nowEpochSecond)
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
            TemperatureSection(
                lastRecord = lastTemperature,
                chartData = chartData.temperature,
            )
        }

        if (sleepEnabled) {
            item {
                SleepSection(sleep = chartData.sleep)
            }
        }

        // Daily steps + rolling seven-day steps
        item {
            StepsSection(
                dailySteps = dailySteps,
                stepGoal = state?.stepGoal,
                lastSyncTime = state?.lastSyncTime,
                chartData = chartData.steps,
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
    stepGoal: Int?,
    lastSyncTime: Instant?,
    chartData: StepsChartData,
) {
    val syncTime = remember {
        DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
            .withZone(ZoneId.systemDefault())
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionHeader(
                icon = Icons.AutoMirrored.Filled.DirectionsWalk,
                accent = AccentGreen,
                title = "Steps",
                subtitle = lastSyncTime?.let { "Today · synced ${syncTime.format(it)}" } ?: "Today",
                trailing = null,
            )

            Spacer(Modifier.height(16.dp))
            if (stepGoal != null && stepGoal > 0) {
                StepGoalProgress(steps = dailySteps, goal = stepGoal)
                Spacer(Modifier.height(16.dp))
            }
            StepsBarChart(
                data = chartData,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))
            Text(
                "Today by interval",
                style = MaterialTheme.typography.labelLarge,
                color = TextSecondary,
            )
            Spacer(Modifier.height(4.dp))
            TodayStepsChart(
                data = chartData,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun StepGoalProgress(steps: Int, goal: Int) {
    val fraction = (steps.toDouble() / goal).coerceIn(0.0, 1.0).toFloat()
    val percent = (steps.toDouble() / goal * 100).roundToInt()
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "${steps.formatNumber()} / ${goal.formatNumber()} steps",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = "$percent%",
            style = MaterialTheme.typography.titleMedium,
            color = AccentGreen,
            fontWeight = FontWeight.SemiBold,
        )
    }
    Spacer(Modifier.height(6.dp))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(SurfaceDark),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .fillMaxHeight()
                .background(AccentGreen),
        )
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
    lastRecord: HistoryEntry?,
    chartData: TemperatureChartData,
) {
    val insights = chartData.insights
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
                trailing = insights.currentPeriod?.period?.label,
            )

            Spacer(Modifier.height(16.dp))
            TemperatureLineChart(
                data = chartData,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))
            PeriodDeltasBlock(
                periods = insights.recentPeriods,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            CoverageStatBlock(
                periods = insights.recentPeriods,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Deviation for the rolling last four day parts, oldest first, ending with the current one. */
@Composable
private fun PeriodDeltasBlock(
    periods: List<TemperaturePeriodSummary>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(SurfaceDark, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 12.dp),
    ) {
        Text(
            "Deltas · last 24h",
            style = MaterialTheme.typography.labelSmall,
            color = TextTertiary,
            maxLines = 1,
        )
        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            periods.forEach { summary ->
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = summary.deviationCelsius.formatDeviation(),
                        style = MaterialTheme.typography.titleMedium,
                        color = summary.deviationCelsius.deviationColor(),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    PeriodLabel(summary)
                }
            }
        }
    }
}

/** Valid-reading share for the rolling last four day parts, oldest first. */
@Composable
private fun CoverageStatBlock(
    periods: List<TemperaturePeriodSummary>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(SurfaceDark, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 12.dp),
    ) {
        Text(
            "Valid readings · last 24h",
            style = MaterialTheme.typography.labelSmall,
            color = TextTertiary,
            maxLines = 1,
        )
        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            periods.forEach { summary ->
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = summary.coverage?.let { "${Math.round(it * 100)}%" } ?: "—",
                        style = MaterialTheme.typography.titleMedium,
                        color = summary.coverage.coverageColor(),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    PeriodLabel(summary)
                }
            }
        }
    }
}

/** Day-part label plus the date when the part belongs to a previous day. */
@Composable
private fun PeriodLabel(summary: TemperaturePeriodSummary) {
    Text(
        text = summary.period.label,
        style = MaterialTheme.typography.labelSmall,
        color = TextTertiary,
        maxLines = 1,
    )
    if (summary.date != LocalDate.now()) {
        Text(
            text = formatPeriodDate(summary.date),
            style = MaterialTheme.typography.labelSmall,
            color = TextTertiary.copy(alpha = 0.7f),
            maxLines = 1,
        )
    }
}

private fun formatPeriodDate(date: LocalDate): String =
    DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()).format(date)

@Composable
private fun SleepSection(sleep: SleepInsights) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionHeader(
                icon = Icons.Default.Bedtime,
                accent = AccentPurple,
                title = "Sleep",
                subtitle = "Confirmed sessions only",
                trailing = null,
            )

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatBlock(
                    label = "Avg / day",
                    value = sleep.averageDailySleepSecondsLast7?.let(::formatSleepDuration) ?: "—",
                    modifier = Modifier.weight(1f),
                )
                StatBlock(
                    label = "Last 24h",
                    value = sleep.totalSleepSecondsLast24h?.let(::formatSleepDuration) ?: "—",
                    modifier = Modifier.weight(1f),
                )
                StatBlock(
                    label = "Vs last 3 nights",
                    value = sleep.threeMainSleepDeviationCelsius.formatDeviation(),
                    valueColor = sleep.threeMainSleepDeviationCelsius.deviationColor(),
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(16.dp))

            if (sleep.recentConfirmed.isEmpty()) {
                Text(
                    "Nothing confirmed in the last 48 hours.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary,
                )
            } else {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Confirmed sleep",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "Duration",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary,
                        modifier = Modifier.width(76.dp),
                        textAlign = TextAlign.End,
                    )
                    Text(
                        "Vs usual",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary,
                        modifier = Modifier.width(84.dp),
                        textAlign = TextAlign.End,
                    )
                }

                sleep.recentConfirmed.forEachIndexed { index, summary ->
                    if (index > 0) {
                        HorizontalDivider(
                            color = TextTertiary.copy(alpha = 0.2f),
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                    SleepSessionRow(summary)
                }
            }
        }
    }
}

@Composable
private fun SleepSessionRow(summary: SleepSessionSummary) {
    val session = summary.session
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (session.kind == SleepSessionKind.MAIN) "Main sleep" else "Nap",
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = formatSleepRange(
                    session.startEpochSecond,
                    session.endEpochSecond,
                    alwaysShowDate = true,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = formatSleepDuration(session.durationSeconds),
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
            modifier = Modifier.width(76.dp),
            textAlign = TextAlign.End,
        )
        Text(
            text = summary.deviationCelsius.formatDeviation(),
            style = MaterialTheme.typography.titleMedium,
            color = summary.deviationCelsius.deviationColor(),
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
            maxLines = 2,
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

/** Green above 90 %, amber above 50 %, red otherwise. */
private fun Double?.coverageColor(): Color = when {
    this == null -> TextTertiary
    this > 0.90 -> AccentGreen
    this > 0.50 -> AccentAmber
    else -> StatusError
}

private fun Int.formatNumber(): String {
    return this.toString().reversed().chunked(3).joinToString(",").reversed()
}
