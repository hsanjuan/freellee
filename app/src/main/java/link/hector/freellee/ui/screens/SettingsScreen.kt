package link.hector.freellee.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import link.hector.freellee.data.ADVANCED_STEP_INTERVAL_SECONDS
import link.hector.freellee.data.ADVANCED_TEMPERATURE_INTERVAL_SECONDS
import link.hector.freellee.data.AdvancedModeSetting
import link.hector.freellee.data.HcWriteStats
import link.hector.freellee.data.HealthConnectAvailability
import link.hector.freellee.data.HealthConnectClient
import link.hector.freellee.data.SamplingProfile
import link.hector.freellee.data.formatSamplingInterval
import link.hector.freellee.ui.theme.AccentAmber
import link.hector.freellee.ui.theme.AccentGreen
import link.hector.freellee.ui.theme.CardBackground
import link.hector.freellee.ui.theme.StatusError
import link.hector.freellee.ui.theme.TextPrimary
import link.hector.freellee.ui.theme.TextSecondary
import link.hector.freellee.ui.theme.TextTertiary
import link.hector.freellee.util.formatTimestamp
import link.hector.freellee.viewmodel.DashboardUiState
import link.hector.freellee.viewmodel.OlleeViewModel
import java.time.Instant

@Composable
fun SettingsScreen(
    viewModel: OlleeViewModel,
    healthConnectClient: HealthConnectClient,
) {
    val sampling by viewModel.samplingProfile.collectAsState()
    val mode by viewModel.advancedMode.collectAsState()
    val enabled by viewModel.advancedEnabled.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val writeStats by viewModel.hcWriteStats.collectAsState()
    val state = uiState as? DashboardUiState.Ready ?: viewModel.lastReadyState
    var hcStatus by remember { mutableStateOf<HealthConnectClient.HealthConnectStatus?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    // Permissions may change while the app is in the background (for example when the user
    // grants them in Health Connect), so refresh on every resume rather than only on writes.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            hcStatus = healthConnectClient.status()
        }
    }
    var showFirmwareDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Reset sleep corrections?") },
            text = {
                Text(
                    "Every sleep session from the last 28 days is discarded and detected again " +
                        "from the raw data. That includes sessions you confirmed, adjusted or " +
                        "removed. Older sessions are kept.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.resetSleepCorrections()
                        showResetDialog = false
                    },
                ) {
                    Text("Reset", color = StatusError)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (showFirmwareDialog) {
        FirmwareRequiredDialog(
            detectedSteps = sampling.stepIntervalSeconds,
            onConfirm = {
                viewModel.setAdvancedMode(AdvancedModeSetting.ON)
                showFirmwareDialog = false
            },
            onDismiss = { showFirmwareDialog = false },
        )
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SamplingCard(
                sampling = sampling,
                firmwareVersion = state?.firmwareVersion,
            )
        }

        item {
            AdvancedFeaturesCard(
                enabled = enabled,
                mode = mode,
                sampling = sampling,
                onToggle = { wanted ->
                    if (wanted && !sampling.supportsSleepDetection) {
                        showFirmwareDialog = true
                    } else {
                        viewModel.setAdvancedMode(
                            if (wanted) AdvancedModeSetting.ON else AdvancedModeSetting.OFF,
                        )
                    }
                },
                onUseAutomatic = { viewModel.setAdvancedMode(AdvancedModeSetting.AUTO) },
            )
        }

        item {
            HealthConnectCard(
                status = hcStatus,
                stats = writeStats,
                onResync = { viewModel.resyncHealthConnect() },
            )
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CardBackground),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(Icons.Default.Bedtime, AccentGreen)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Sleep data",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "Undo past confirmations",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextTertiary,
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = { showResetDialog = true },
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                    ) {
                        Text("Reset all sleep corrections", color = StatusError)
                    }
                }
            }
        }

        item {
            Text(
                text = "Advanced features expect a patched watch firmware that logs steps every " +
                    "15 minutes and skin temperature every 4 minutes. Stock firmware logs both " +
                    "once an hour. Without the patch the app keeps working: it still records " +
                    "skin temperature and syncs it to Health Connect.",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun SamplingCard(
    sampling: SamplingProfile,
    firmwareVersion: String?,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Default.Settings, AccentGreen)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Watch sampling",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "What the watch is actually reporting",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            SamplingRow(
                label = "Steps",
                intervalSeconds = sampling.stepIntervalSeconds,
                advancedThreshold = ADVANCED_STEP_INTERVAL_SECONDS,
            )
            HorizontalDivider(
                color = TextTertiary.copy(alpha = 0.15f),
                modifier = Modifier.padding(vertical = 8.dp),
            )
            SamplingRow(
                label = "Temperature",
                intervalSeconds = sampling.temperatureIntervalSeconds?.toLong(),
                advancedThreshold = ADVANCED_TEMPERATURE_INTERVAL_SECONDS.toLong(),
            )
            if (firmwareVersion != null) {
                HorizontalDivider(
                    color = TextTertiary.copy(alpha = 0.15f),
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Firmware",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        firmwareVersion,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun SamplingRow(
    label: String,
    intervalSeconds: Long?,
    advancedThreshold: Long,
) {
    val isAdvanced = intervalSeconds != null && intervalSeconds <= advancedThreshold
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = TextPrimary,
            modifier = Modifier.weight(1f),
        )
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = if (intervalSeconds != null) {
                    "Every ${formatSamplingInterval(intervalSeconds)}"
                } else {
                    "No data yet"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (intervalSeconds != null) TextSecondary else TextTertiary,
            )
            Text(
                text = when {
                    intervalSeconds == null -> ""
                    isAdvanced -> "Advanced"
                    else -> "Stock"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (isAdvanced) AccentGreen else AccentAmber,
            )
        }
    }
}

@Composable
private fun AdvancedFeaturesCard(
    enabled: Boolean,
    mode: AdvancedModeSetting,
    sampling: SamplingProfile,
    onToggle: (Boolean) -> Unit,
    onUseAutomatic: () -> Unit,
) {
    val forcedOnWithoutSupport = mode == AdvancedModeSetting.ON && !sampling.supportsSleepDetection

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Default.Bedtime, AccentAmber)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Advanced features",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Automatic sleep detection",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary,
                    )
                }
                Switch(checked = enabled, onCheckedChange = onToggle)
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = if (enabled) {
                    "Sleep sessions are detected from fine-grained step data and shown in the " +
                        "Sleep tab."
                } else {
                    "Sleep detection is off. The app records skin temperature, builds baselines " +
                        "and syncs them to Health Connect."
                },
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )

            if (forcedOnWithoutSupport) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = AccentAmber,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Advanced features are forced on, but the watch still reports " +
                            "hourly steps. Sleep boundaries will be off by up to an hour. Flash " +
                            "a patched firmware for accurate sessions.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AccentAmber,
                    )
                }
            }

            if (mode != AdvancedModeSetting.AUTO) {
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onUseAutomatic) {
                    Text("Use automatic detection")
                }
            }
        }
    }
}

@Composable
private fun HealthConnectCard(
    status: HealthConnectClient.HealthConnectStatus?,
    stats: HcWriteStats,
    onResync: () -> Unit,
) {
    val availability = status?.availability
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Default.Favorite, AccentGreen)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Health Connect",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Export status",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            StatusRow(
                label = "Service",
                value = when (availability) {
                    null -> "Checking…"
                    HealthConnectAvailability.AVAILABLE -> "Available"
                    HealthConnectAvailability.UPDATE_REQUIRED -> "Update required"
                    HealthConnectAvailability.UNSUPPORTED -> "Not supported"
                },
                ok = availability == HealthConnectAvailability.AVAILABLE,
            )
            StatusRow(
                label = "Sleep export",
                value = when (status?.sleepPermission) {
                    null -> "—"
                    true -> "Granted"
                    false -> "Not granted"
                },
                ok = status?.sleepPermission == true,
            )
            StatusRow(
                label = "Skin temperature",
                value = when {
                    status == null -> "—"
                    !status.temperatureSupported -> "Not supported"
                    status.temperaturePermission -> "Granted"
                    else -> "Not granted"
                },
                ok = status?.temperaturePermission == true,
            )
            StatusRow(
                label = "Last temperature write",
                value = stats.temperatureEpochSecond?.let { epoch ->
                    "${formatTimestamp(Instant.ofEpochSecond(epoch))} · " +
                        "${stats.temperatureCount} records"
                } ?: "Never",
                ok = stats.temperatureEpochSecond != null,
            )
            StatusRow(
                label = "Last sleep write",
                value = stats.sleepEpochSecond?.let { epoch ->
                    "${formatTimestamp(Instant.ofEpochSecond(epoch))} · " +
                        "${stats.sleepCount} sessions"
                } ?: "Never",
                ok = stats.sleepEpochSecond != null,
            )

            Spacer(Modifier.height(4.dp))
            TextButton(
                onClick = onResync,
                enabled = availability == HealthConnectAvailability.AVAILABLE,
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
            ) {
                Text("Re-sync temperature & sleep now")
            }
            Text(
                "Uses cached data, so the watch does not need to be connected.",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
            )
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String, ok: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = if (ok) AccentGreen else TextSecondary,
        )
    }
}

@Composable
private fun FirmwareRequiredDialog(
    detectedSteps: Long?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = AccentAmber) },
        title = { Text("Patched firmware required") },
        text = {
            Column {
                Text(
                    "Advanced sleep detection expects steps every 15 minutes and skin " +
                        "temperature every 4 minutes. Stock firmware only reports both once an " +
                        "hour, so sleep start and end times can be off by up to an hour.",
                )
                detectedSteps?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Your watch currently reports steps every ${formatSamplingInterval(it)}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "You can enable the features anyway, but flashing a patched firmware with " +
                        "15-minute steps and 4-minute temperature is the only way to get accurate " +
                        "sleep sessions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Enable anyway")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun IconBadge(icon: ImageVector, tint: Color) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(tint.copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint)
    }
}
