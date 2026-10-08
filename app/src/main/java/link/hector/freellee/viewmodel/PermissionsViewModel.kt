package link.hector.freellee.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import link.hector.freellee.data.DeviceStore
import link.hector.freellee.data.HealthConnectAvailability
import link.hector.freellee.data.HealthConnectClient
import link.hector.freellee.permissions.AppPermissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "PermissionsViewModel"

/** Grant status for a group of runtime permissions. */
enum class PermissionGrantState { GRANTED, MISSING, PERMANENTLY_DENIED }

/**
 * Observable permission state used by the onboarding flow and by the in-app banners.
 *
 * Permissions are always advisory: the app keeps working with locally stored data if the user
 * declines Health Connect, so this state is used to guide the user rather than to gate the UI.
 */
data class PermissionsUiState(
    val loading: Boolean = true,
    val showOnboarding: Boolean = false,
    val bluetoothState: PermissionGrantState = PermissionGrantState.MISSING,
    val healthConnectAvailability: HealthConnectAvailability = HealthConnectAvailability.UNSUPPORTED,
    val healthConnectState: PermissionGrantState = PermissionGrantState.MISSING,
    val requiredHealthConnectPermissions: Set<String> = emptySet(),
) {
    val bluetoothGranted: Boolean get() = bluetoothState == PermissionGrantState.GRANTED

    val healthConnectGranted: Boolean get() = healthConnectState == PermissionGrantState.GRANTED

    /** Whether the Health Connect provider exists on this device (even if access is missing). */
    val healthConnectSupported: Boolean
        get() = healthConnectAvailability != HealthConnectAvailability.UNSUPPORTED
}

/**
 * Owns permission discovery, tracks grant state and decides whether the first-run permission
 * onboarding should be shown.
 */
class PermissionsViewModel(
    private val appContext: Context,
    private val deviceStore: DeviceStore,
    private val healthConnectClient: HealthConnectClient,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PermissionsUiState())
    val uiState: StateFlow<PermissionsUiState> = _uiState.asStateFlow()

    /**
     * Set when the user taps "Continue". Dismisses onboarding for the current session only, so a
     * later launch with permissions still missing (and no watch paired) will show it again.
     */
    private var onboardingDismissed = false

    init {
        refresh()
        // Re-evaluate as soon as a watch is paired or removed so onboarding appears/disappears
        // without needing a restart.
        viewModelScope.launch {
            deviceStore.getAllPairedDevices()
                .map { it.isNotEmpty() }
                .distinctUntilChanged()
                .collect { refresh() }
        }
    }

    /**
     * Re-reads all permission state. Safe to call from `onResume` so grants made in system
     * settings are picked up when the user returns to the app.
     */
    fun refresh() = viewModelScope.launch(Dispatchers.IO) {
        try {
            val hasPairedDevice = deviceStore.getAllPairedDevices().first().isNotEmpty()

            val bluetoothState = when {
                AppPermissions.hasBluetoothPermissions(appContext) -> PermissionGrantState.GRANTED
                // Keep a previous "don't ask again" verdict; the system won't prompt again.
                _uiState.value.bluetoothState == PermissionGrantState.PERMANENTLY_DENIED ->
                    PermissionGrantState.PERMANENTLY_DENIED

                else -> PermissionGrantState.MISSING
            }

            val availability = healthConnectClient.availability()
            // Health Connect only counts towards "onboarded" when it can actually be granted.
            val healthConnectRequired = availability == HealthConnectAvailability.AVAILABLE
            val required = if (healthConnectRequired) {
                healthConnectClient.requiredPermissions()
            } else {
                emptySet()
            }
            val healthConnectState = when {
                healthConnectRequired && healthConnectClient.hasAllPermissions() ->
                    PermissionGrantState.GRANTED

                else -> PermissionGrantState.MISSING
            }

            val allRequiredGranted = bluetoothState == PermissionGrantState.GRANTED &&
                (!healthConnectRequired || healthConnectState == PermissionGrantState.GRANTED)

            // Onboarding is shown until the user either grants what is needed or pairs a watch.
            // It is not persisted, so revoking permissions brings it back.
            val showOnboarding = !onboardingDismissed && !allRequiredGranted && !hasPairedDevice

            _uiState.update {
                it.copy(
                    loading = false,
                    showOnboarding = showOnboarding,
                    bluetoothState = bluetoothState,
                    healthConnectAvailability = availability,
                    healthConnectState = healthConnectState,
                    requiredHealthConnectPermissions = required,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to refresh permission state", e)
            _uiState.update { it.copy(loading = false) }
        }
    }

    /** Dismiss onboarding for this session so the user can use the app immediately. */
    fun completeOnboarding() {
        onboardingDismissed = true
        _uiState.update { it.copy(showOnboarding = false) }
    }

    /**
     * Called after the Bluetooth runtime-permission dialog is dismissed.
     *
     * @param permanentlyDenied true when the system will no longer prompt (user chose
     *   "Don't ask again"), in which case the UI should offer a link to app settings.
     */
    fun onBluetoothPermissionResult(permanentlyDenied: Boolean) {
        val missing = AppPermissions.missingBluetoothPermissions(appContext)
        val state = when {
            missing.isEmpty() -> PermissionGrantState.GRANTED
            permanentlyDenied -> PermissionGrantState.PERMANENTLY_DENIED
            else -> PermissionGrantState.MISSING
        }
        Log.d(TAG, "Bluetooth permission result: $state")
        _uiState.update { it.copy(bluetoothState = state) }
    }

    /** Called after the Health Connect permission screen is dismissed. */
    fun onHealthConnectPermissionResult() {
        refresh()
    }
}
