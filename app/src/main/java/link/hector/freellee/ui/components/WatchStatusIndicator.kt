package link.hector.freellee.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import link.hector.freellee.ble.ConnectionState
import link.hector.freellee.ui.theme.AccentGreen
import link.hector.freellee.ui.theme.StatusConnected
import link.hector.freellee.ui.theme.StatusConnecting
import link.hector.freellee.ui.theme.StatusDisconnected
import link.hector.freellee.ui.theme.StatusError
import link.hector.freellee.ui.theme.TextSecondary
import link.hector.freellee.viewmodel.DashboardUiState

/** A status line: text, colour, icon and whether the icon should spin. */
internal data class StatusInfo(
    val text: String,
    val color: Color,
    val icon: ImageVector,
    val spinner: Boolean,
)

/**
 * The watch connection/sync status line shown at the top of the Data and Activities tabs.
 *
 * The two tabs only differ in their surrounding padding, so they share this composable.
 */
@Composable
fun WatchStatusIndicator(
    uiState: DashboardUiState,
    bleState: ConnectionState,
    hasPairedDevice: Boolean,
    bluetoothEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val statusInfo = when {
        // Bluetooth is off — show this regardless of connection state
        !bluetoothEnabled -> StatusInfo(
            "Bluetooth is off. Enable Bluetooth to sync.",
            StatusError,
            Icons.Default.BluetoothDisabled,
            false,
        )

        uiState is DashboardUiState.Disconnected -> when (bleState) {
            ConnectionState.READY -> StatusInfo(
                "Connected — pull down to sync",
                StatusConnected,
                Icons.Default.Sync,
                false,
            )

            ConnectionState.CONNECTING -> StatusInfo("Connecting", StatusConnecting, Icons.Default.Hub, true)
            ConnectionState.DISCONNECTED -> if (hasPairedDevice) {
                StatusInfo("Disconnected", StatusDisconnected, Icons.Default.BluetoothDisabled, false)
            } else {
                StatusInfo("Pair a watch to sync", TextSecondary, Icons.Default.BluetoothDisabled, false)
            }
        }

        uiState is DashboardUiState.Connecting -> StatusInfo("Connecting", StatusConnecting, Icons.Default.Hub, true)
        uiState is DashboardUiState.Connected -> StatusInfo("Connected", StatusConnected, Icons.Default.BluetoothConnected, false)
        uiState is DashboardUiState.Syncing -> StatusInfo("Syncing — ${uiState.step}", StatusConnecting, Icons.Default.Hub, true)

        uiState is DashboardUiState.Ready -> when (bleState) {
            ConnectionState.READY -> StatusInfo(
                "Connected — pull down to sync",
                StatusConnected,
                Icons.Default.Sync,
                false,
            )

            ConnectionState.CONNECTING -> StatusInfo("Connecting", StatusConnecting, Icons.Default.Hub, true)
            ConnectionState.DISCONNECTED -> if (hasPairedDevice) {
                StatusInfo("Disconnected", StatusDisconnected, Icons.Default.BluetoothDisabled, false)
            } else {
                StatusInfo("Pair a watch to sync", TextSecondary, Icons.Default.BluetoothDisabled, false)
            }
        }

        uiState is DashboardUiState.SyncSuccessful -> StatusInfo(
            "Sync successful — pull to resync",
            AccentGreen,
            Icons.Default.CheckCircle,
            false,
        )

        uiState is DashboardUiState.Error -> StatusInfo(uiState.message, StatusError, Icons.Default.Error, false)
        else -> StatusInfo("Disconnected", StatusDisconnected, Icons.Default.BluetoothDisabled, false)
    }

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (statusInfo.spinner) {
                val infiniteTransition = rememberInfiniteTransition()
                val rotation by infiniteTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(animation = tween(1000), repeatMode = RepeatMode.Restart),
                )
                Icon(
                    imageVector = statusInfo.icon,
                    contentDescription = null,
                    tint = statusInfo.color,
                    modifier = Modifier.rotate(rotation),
                )
            } else {
                Icon(
                    imageVector = statusInfo.icon,
                    contentDescription = null,
                    tint = statusInfo.color,
                )
            }
            Text(
                text = statusInfo.text,
                style = MaterialTheme.typography.bodySmall,
                color = statusInfo.color,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}
