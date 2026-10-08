package link.hector.freellee

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import link.hector.freellee.ui.screens.PrivacyPolicyScreen
import link.hector.freellee.ui.theme.FreelleeTheme

/**
 * Shows Freellee's privacy policy.
 *
 * Android 14 (the minimum supported version) launches this activity through the
 * `ViewPermissionUsageActivity` alias with the `android.intent.action.VIEW_PERMISSION_USAGE`
 * action and `android.intent.category.HEALTH_PERMISSIONS` category. The user reaches it by
 * tapping the privacy policy link in the Health Connect permissions screen. The activity is also
 * launched explicitly from the app's own UI.
 */
class PermissionsRationaleActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FreelleeTheme {
                PrivacyPolicyScreen(onBack = { finish() })
            }
        }
    }
}
