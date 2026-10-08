package link.hector.freellee.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import link.hector.freellee.data.HealthConnectAvailability
import link.hector.freellee.ui.components.FreelleeOutlinedButton
import link.hector.freellee.ui.theme.AccentAmber
import link.hector.freellee.ui.theme.AccentGreen
import link.hector.freellee.ui.theme.CardBackground
import link.hector.freellee.ui.theme.StatusError
import link.hector.freellee.ui.theme.TextPrimary
import link.hector.freellee.ui.theme.TextSecondary
import link.hector.freellee.ui.theme.TextTertiary
import link.hector.freellee.viewmodel.PermissionGrantState
import link.hector.freellee.viewmodel.PermissionsUiState

/**
 * First-run onboarding that explains and requests the permissions the app needs.
 *
 * Nothing here is mandatory: the user can always continue and use the app with locally stored
 * data, so a declined permission is a degraded state rather than a dead end.
 */
@Composable
fun PermissionsOnboardingScreen(
    state: PermissionsUiState,
    onRequestBluetooth: () -> Unit,
    onRequestHealthConnect: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onContinue: () -> Unit,
    onOpenPrivacyPolicy: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // Keep content clear of the status/navigation bars under edge-to-edge.
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Welcome to Freellee",
            style = MaterialTheme.typography.headlineMedium,
            color = TextPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "Freellee needs a couple of permissions to sync your Ollee watch. You can change these at any time in Settings.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )

        PermissionCard(
            icon = Icons.Default.Bluetooth,
            title = "Bluetooth",
            description = "Used to find, pair with and download data from your Ollee watch.",
        ) {
            when (state.bluetoothState) {
                PermissionGrantState.GRANTED -> GrantedLabel()
                PermissionGrantState.MISSING -> {
                    FreelleeOutlinedButton(onClick = onRequestBluetooth) { Text("Allow Bluetooth") }
                }
                PermissionGrantState.PERMANENTLY_DENIED -> {
                    PermanentlyDeniedLabel()
                    Spacer(modifier = Modifier.size(8.dp))
                    FreelleeOutlinedButton(onClick = onOpenAppSettings) { Text("Open app settings") }
                }
            }
        }

        PermissionCard(
            icon = Icons.Default.Favorite,
            title = "Health Connect",
            description = "Used to write your steps, heart rate, temperature and workouts into Health Connect.",
        ) {
            when {
                !state.healthConnectSupported -> {
                    Text(
                        "Health Connect isn't available on this device. Your data will stay on this phone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary,
                    )
                }
                state.healthConnectAvailability == HealthConnectAvailability.UPDATE_REQUIRED -> {
                    Text(
                        "Health Connect isn't installed on this device, so it can't be used yet. Your data will stay on this phone in the meantime.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AccentAmber,
                    )
                }
                state.healthConnectGranted -> GrantedLabel()
                else -> FreelleeOutlinedButton(onClick = onRequestHealthConnect) { Text("Allow Health Connect") }
            }
        }

        Spacer(modifier = Modifier.size(8.dp))

        FreelleeOutlinedButton(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Continue")
        }
        Text(
            "You can grant these later from the banners on the main screen.",
            style = MaterialTheme.typography.bodySmall,
            color = TextTertiary,
            modifier = Modifier.fillMaxWidth(),
        )

        TextButton(
            onClick = onOpenPrivacyPolicy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Read the privacy policy", color = AccentGreen)
        }
    }
}

@Composable
private fun PermissionCard(
    icon: ImageVector,
    title: String,
    description: String,
    action: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = AccentGreen)
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
            )
            action()
        }
    }
}

@Composable
private fun GrantedLabel() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = AccentGreen)
        Text(
            "Granted",
            style = MaterialTheme.typography.bodyMedium,
            color = AccentGreen,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun PermanentlyDeniedLabel() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Warning, contentDescription = null, tint = StatusError)
        Text(
            "Blocked — enable it from the app settings to continue.",
            style = MaterialTheme.typography.bodySmall,
            color = StatusError,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
