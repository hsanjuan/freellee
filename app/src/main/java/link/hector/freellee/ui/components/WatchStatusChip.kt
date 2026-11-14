package link.hector.freellee.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import link.hector.freellee.ble.ConnectionState
import link.hector.freellee.ui.theme.AccentGreen
import link.hector.freellee.ui.theme.StatusConnected
import link.hector.freellee.ui.theme.StatusConnecting
import link.hector.freellee.ui.theme.StatusDisconnected
import link.hector.freellee.ui.theme.StatusError
import link.hector.freellee.ui.theme.TextSecondary
import link.hector.freellee.viewmodel.DashboardUiState

/** A compact status chip: short text, colour, icon and whether the icon should spin. */
private data class StatusInfo(
    val text: String,
    val color: Color,
    val icon: ImageVector,
    val spinner: Boolean,
)

/**
 * The watch connection state, shown in the top app bar on every tab. Tapping it opens the
 * Devices tab, where reconnect and disconnect controls live.
 */
@Composable
fun WatchStatusChip(
    uiState: DashboardUiState,
    bleState: ConnectionState,
    hasPairedDevice: Boolean,
    bluetoothEnabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val statusInfo = watchStatusInfo(uiState, bleState, hasPairedDevice, bluetoothEnabled)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(statusInfo.color.copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (statusInfo.spinner) {
            val infiniteTransition = rememberInfiniteTransition()
            val rotation by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1000),
                    repeatMode = RepeatMode.Restart,
                ),
            )
            Icon(
                imageVector = statusInfo.icon,
                contentDescription = null,
                tint = statusInfo.color,
                modifier = Modifier
                    .size(16.dp)
                    .rotate(rotation),
            )
        } else {
            Icon(
                imageVector = statusInfo.icon,
                contentDescription = null,
                tint = statusInfo.color,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = statusInfo.text,
            style = MaterialTheme.typography.labelMedium,
            color = statusInfo.color,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun watchStatusInfo(
    uiState: DashboardUiState,
    bleState: ConnectionState,
    hasPairedDevice: Boolean,
    bluetoothEnabled: Boolean,
): StatusInfo = when {
    // Bluetooth is off — show this regardless of connection state.
    !bluetoothEnabled -> StatusInfo(
        "Bluetooth off",
        StatusError,
        Icons.Default.BluetoothDisabled,
        false,
    )

    uiState is DashboardUiState.Disconnected -> when (bleState) {
        ConnectionState.READY -> StatusInfo(
            "Connected",
            StatusConnected,
            Icons.Default.BluetoothConnected,
            false,
        )

        ConnectionState.CONNECTING -> StatusInfo(
            "Connecting",
            StatusConnecting,
            Icons.Default.Hub,
            true,
        )

        ConnectionState.DISCONNECTED -> if (hasPairedDevice) {
            StatusInfo("Offline", StatusDisconnected, Icons.Default.BluetoothDisabled, false)
        } else {
            StatusInfo("Pair watch", TextSecondary, Icons.Default.BluetoothDisabled, false)
        }
    }

    uiState is DashboardUiState.Connecting -> StatusInfo(
        "Connecting",
        StatusConnecting,
        Icons.Default.Hub,
        true,
    )

    uiState is DashboardUiState.Connected -> StatusInfo(
        "Connected",
        StatusConnected,
        Icons.Default.BluetoothConnected,
        false,
    )

    // Keep the record progress number visible while syncing, e.g. "Syncing 120/840".
    uiState is DashboardUiState.Syncing -> StatusInfo(
        uiState.step.removePrefix("Fetching records ").let { progress ->
            if (progress != uiState.step) "Syncing $progress" else uiState.step
        },
        StatusConnecting,
        Icons.Default.Hub,
        true,
    )

    uiState is DashboardUiState.Ready -> when (bleState) {
        ConnectionState.READY -> StatusInfo(
            "Connected",
            StatusConnected,
            Icons.Default.BluetoothConnected,
            false,
        )

        ConnectionState.CONNECTING -> StatusInfo(
            "Connecting",
            StatusConnecting,
            Icons.Default.Hub,
            true,
        )

        ConnectionState.DISCONNECTED -> if (hasPairedDevice) {
            StatusInfo("Offline", StatusDisconnected, Icons.Default.BluetoothDisabled, false)
        } else {
            StatusInfo("Pair watch", TextSecondary, Icons.Default.BluetoothDisabled, false)
        }
    }

    uiState is DashboardUiState.SyncSuccessful -> StatusInfo(
        "Synced",
        AccentGreen,
        Icons.Default.CheckCircle,
        false,
    )

    uiState is DashboardUiState.Error -> StatusInfo(
        "Sync failed",
        StatusError,
        Icons.Default.Error,
        false,
    )

    else -> StatusInfo("Offline", StatusDisconnected, Icons.Default.BluetoothDisabled, false)
}
