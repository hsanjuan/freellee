package link.hector.freellee.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import link.hector.freellee.data.HealthConnectAvailability
import link.hector.freellee.ui.theme.AccentAmber
import link.hector.freellee.ui.theme.AccentGreen
import link.hector.freellee.ui.theme.CardBackground
import link.hector.freellee.ui.theme.TextPrimary
import link.hector.freellee.ui.theme.TextSecondary
import link.hector.freellee.viewmodel.PermissionGrantState
import link.hector.freellee.viewmodel.PermissionsUiState

/**
 * Non-blocking banners shown on the main screen when a permission is missing after onboarding.
 *
 * They exist purely to inform and to offer a one-tap fix; the app keeps working without them.
 */
@Composable
fun PermissionBanner(
    state: PermissionsUiState,
    onRequestBluetooth: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onRequestHealthConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val showBluetooth = !state.bluetoothGranted
    // Only offer an action when Health Connect is present but access is missing. If the provider
    // is absent or outdated there is nothing the app can do (and it must not send users to a
    // store), so it simply stays quiet and keeps data local.
    val showHealthConnect = state.healthConnectAvailability == HealthConnectAvailability.AVAILABLE &&
        !state.healthConnectGranted

    if (!showBluetooth && !showHealthConnect) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (showBluetooth) {
            val blocked = state.bluetoothState == PermissionGrantState.PERMANENTLY_DENIED
            PermissionBannerCard(
                icon = Icons.Default.Warning,
                iconTint = AccentAmber,
                message = if (blocked) {
                    "Bluetooth permission is blocked. Enable it in app settings to find and sync your watch."
                } else {
                    "Bluetooth permission is needed to find and sync your watch."
                },
                actionLabel = if (blocked) "Open settings" else "Allow",
                onAction = if (blocked) onOpenAppSettings else onRequestBluetooth,
            )
        }

        if (showHealthConnect) {
            PermissionBannerCard(
                icon = Icons.Default.Favorite,
                iconTint = AccentGreen,
                message = "Health Connect access is off. Your data is still saved on this phone.",
                actionLabel = "Allow",
                onAction = onRequestHealthConnect,
            )
        }
    }
}

@Composable
private fun PermissionBannerCard(
    icon: ImageVector,
    iconTint: Color,
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = iconTint)
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            )
            TextButton(onClick = onAction) {
                Text(actionLabel, color = TextPrimary)
            }
        }
    }
}
