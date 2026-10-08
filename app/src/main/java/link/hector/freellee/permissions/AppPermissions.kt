package link.hector.freellee.permissions

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Runtime permissions required for the app's Bluetooth Low Energy (watch) functionality.
 *
 * Freellee requires Android 14 (API 34) or newer, so only the granular Android 12+ runtime
 * permissions are needed: `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT`.
 */
object AppPermissions {

    fun requiredBluetoothPermissions(): List<String> = listOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
    )

    fun missingBluetoothPermissions(context: Context): List<String> =
        requiredBluetoothPermissions().filterNot { isGranted(context, it) }

    fun hasBluetoothPermissions(context: Context): Boolean =
        missingBluetoothPermissions(context).isEmpty()

    private fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * True when the user has permanently denied a permission and the system will no longer show
     * the request dialog ("Don't ask again"). Call this only *after* a permission request has
     * been made; before the first request [ActivityCompat.shouldShowRequestPermissionRationale]
     * also returns false, which would produce a false positive.
     */
    fun isPermanentlyDenied(activity: Activity, permissions: List<String>): Boolean =
        permissions.any { permission ->
            !isGranted(activity, permission) &&
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
        }

    /** Intent that opens this app's system settings page, used after a permanent denial. */
    fun appSettingsIntent(context: Context): Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
