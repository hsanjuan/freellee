package link.hector.freellee.ui.screens

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Battery0Bar
import androidx.compose.material.icons.filled.Battery1Bar
import androidx.compose.material.icons.filled.Battery2Bar
import androidx.compose.material.icons.filled.Battery3Bar
import androidx.compose.material.icons.filled.Battery4Bar
import androidx.compose.material.icons.filled.Battery5Bar
import androidx.compose.material.icons.filled.Battery6Bar
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import link.hector.freellee.ble.ConnectionState
import link.hector.freellee.data.PairedDevice
import link.hector.freellee.ui.components.FreelleeOutlinedButton
import link.hector.freellee.ui.theme.AccentGreen
import link.hector.freellee.ui.theme.CardBackground
import link.hector.freellee.ui.theme.StatusConnected
import link.hector.freellee.ui.theme.StatusConnecting
import link.hector.freellee.ui.theme.StatusDisconnected
import link.hector.freellee.ui.theme.StatusError
import link.hector.freellee.ui.theme.TextPrimary
import link.hector.freellee.ui.theme.TextSecondary
import link.hector.freellee.ui.theme.TextTertiary
import link.hector.freellee.viewmodel.DeviceUiState
import link.hector.freellee.viewmodel.DeviceViewModel

@Composable
fun ConnectionScreen(
    viewModel: DeviceViewModel,
    onConnectClick: (String) -> Unit,
    onDisconnect: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(
                message = error,
                duration = SnackbarDuration.Indefinite,
                withDismissAction = true,
            )
            viewModel.clearError()
        }
    }

    Scaffold(
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Bluetooth status section
            item {
                if (!uiState.bluetoothEnabled) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(StatusError.copy(alpha = 0.2f))
                            .padding(16.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.BluetoothDisabled,
                                contentDescription = null,
                                tint = StatusError,
                            )
                            Text(
                                "Bluetooth is off. Please enable Bluetooth to scan for your watch.",
                                color = StatusError,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }
            }

            // Connection status section
            item {
                ConnectionStatusCard(uiState, onDisconnect)
            }

            // Connected device details
            if (uiState.connectionState == ConnectionState.READY) {
                item {
                    DeviceDetailsCard(uiState)
                }
            }

            // Paired devices list
            item {
                Text(
                    "Paired Devices",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            if (uiState.pairedDevices.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = CardBackground),
                    ) {
                        Text(
                            "No paired devices yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextTertiary,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }

            items(uiState.pairedDevices) { device ->
                PairedDeviceCard(
                    device = device,
                    onActivate = { viewModel.setActiveDevice(device.macAddress) },
                    onConnect = {
                        if (uiState.bluetoothEnabled && uiState.connectionState != ConnectionState.CONNECTING) {
                            viewModel.connect(device.macAddress)
                        }
                    },
                    onRemove = { viewModel.removeDevice(device.macAddress) },
                )
            }

            // Found devices during scan
            if (uiState.isScanning && uiState.foundDevices.isNotEmpty()) {
                item {
                    Text(
                        "Found ${uiState.foundDevices.size} device(s)",
                        style = MaterialTheme.typography.titleMedium,
                        color = AccentGreen,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                @SuppressLint("MissingPermission")
                items(uiState.foundDevices) { result ->
                    val device = result.device
                    FoundDeviceCard(
                        name = device.name ?: "Unknown Device",
                        macAddress = device.address,
                        rssi = result.rssi,
                        onConnect = { viewModel.connect(device.address) },
                    )
                }
            }

            // Scan button
            item {
                FreelleeOutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        if (uiState.isScanning) {
                            viewModel.stopScan()
                        } else {
                            viewModel.startScan()
                        }
                    },
                ) {
                    if (uiState.isScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 8.dp),
                            strokeWidth = 2.dp,
                        )
                        Text("Stop Scan", modifier = Modifier.padding(start = 8.dp))
                    } else {
                        Icon(Icons.Default.Hub, contentDescription = null)
                        Text("Scan for Watch", modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun FoundDeviceCard(
    name: String,
    macAddress: String,
    rssi: Int,
    onConnect: () -> Unit,
) {
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextPrimary,
                    )
                    Row {
                        Text(
                            macAddress,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextTertiary,
                        )
                        Text(
                            "  ·  RSSI: $rssi dBm",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextTertiary,
                        )
                    }
                }
                FreelleeOutlinedButton(
                    onClick = onConnect,
                ) {
                    Text("Connect")
                }
            }
        }
    }
}

@Composable
private fun ConnectionStatusCard(uiState: DeviceUiState, onDisconnect: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Connection Status",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextSecondary,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        iconForState(uiState.connectionState),
                        contentDescription = null,
                        tint = colorForState(uiState.connectionState),
                    )
                    Text(
                        stateLabel(uiState),
                        style = MaterialTheme.typography.bodyLarge,
                        color = colorForState(uiState.connectionState),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                    if (uiState.connectionState == ConnectionState.READY) {
                        FreelleeOutlinedButton(
                            onClick = onDisconnect,
                            modifier = Modifier.padding(start = 8.dp),
                        ) {
                            Text("Disconnect")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceDetailsCard(uiState: DeviceUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            DetailRow("Name", uiState.deviceName ?: "Unknown")
            DetailRow("MAC Address", uiState.macAddress ?: "N/A")
            if (uiState.firmwareVersion != null) {
                DetailRow("Firmware", uiState.firmwareVersion)
            }
            if (uiState.voltageMv != null) {
                val pct = batteryPercentage(uiState.voltageMv)
                DetailRow(
                    "Battery",
                    "$pct% (${uiState.voltageMv} mV)",
                )
            }
        }
    }
}

@Composable
private fun PairedDeviceCard(
    device: PairedDevice,
    onActivate: () -> Unit,
    onConnect: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onConnect),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (device.isActive) {
                            Icon(Icons.Default.Done, contentDescription = "Active", tint = AccentGreen, modifier = Modifier.padding(end = 8.dp))
                        }
                        Column {
                            Text(
                                device.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = TextPrimary,
                            )
                            Text(
                                device.macAddress,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextTertiary,
                            )
                        }
                    }
                    // Battery indicator
                    device.voltageMv?.let {
                        val batteryPct = batteryPercentage(it)
                        Icon(
                            batteryIcon(batteryPct),
                            contentDescription = null,
                            tint = if (batteryPct <= 20) StatusError else AccentGreen,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                IconButton(onClick = onRemove) {
                    Icon(Icons.Default.Delete, contentDescription = "Remove", tint = StatusError)
                }
            }
        }
    }
}

/** Convert voltage (mV) to an approximate battery percentage for a CR2016 coin cell.
 *  3000 mV = 100%, 2600 mV = 0%, linear interpolation. */
private fun batteryPercentage(voltageMv: Int): Int {
    return maxOf(0, minOf(100, ((voltageMv - 2600) * 100) / 400))
}

/** Select a battery icon based on percentage. */
private fun batteryIcon(pct: Int): ImageVector {
    return when {
        pct >= 90 -> Icons.Filled.BatteryFull
        pct > 80 -> Icons.Filled.Battery6Bar
        pct > 70 -> Icons.Filled.Battery5Bar
        pct > 60 -> Icons.Filled.Battery4Bar
        pct > 50 -> Icons.Filled.Battery3Bar
        pct > 40 -> Icons.Filled.Battery2Bar
        pct > 30 -> Icons.Filled.Battery1Bar
        pct > 20 -> Icons.Filled.Battery0Bar
        else -> Icons.Filled.BatteryAlert
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = TextTertiary)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
    }
}

private fun iconForState(state: ConnectionState) = when (state) {
    ConnectionState.READY -> Icons.Default.BluetoothConnected
    ConnectionState.CONNECTING -> Icons.Default.Hub
    ConnectionState.DISCONNECTED -> Icons.Default.BluetoothDisabled
}

private fun stateLabel(uiState: DeviceUiState): String {
    return when (uiState.connectionState) {
        ConnectionState.READY -> "Connected"
        ConnectionState.CONNECTING -> {
            val target = uiState.deviceName ?: uiState.macAddress ?: "Unknown"
            "Connecting to $target"
        }
        ConnectionState.DISCONNECTED -> "Disconnected"
    }
}

private fun colorForState(state: ConnectionState) = when (state) {
    ConnectionState.READY -> StatusConnected
    ConnectionState.CONNECTING -> StatusConnecting
    ConnectionState.DISCONNECTED -> StatusDisconnected
}
