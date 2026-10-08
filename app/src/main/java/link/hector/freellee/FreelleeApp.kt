package link.hector.freellee

import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import link.hector.freellee.ble.WatchGattClient
import link.hector.freellee.ble.WatchRepository
import link.hector.freellee.data.DeviceStore
import link.hector.freellee.data.HealthConnectClient
import link.hector.freellee.viewmodel.DeviceViewModel
import link.hector.freellee.viewmodel.OlleeViewModel
import link.hector.freellee.viewmodel.PermissionsViewModel
import kotlinx.coroutines.flow.Flow

class OlleeViewModelFactory(
    private val repository: WatchRepository,
    private val deviceStore: DeviceStore,
    private val bluetoothManager: BluetoothManager,
    private val healthConnectClient: HealthConnectClient,
    private val bluetoothEnabled: Flow<Boolean>,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(OlleeViewModel::class.java)) {
            return OlleeViewModel(repository, deviceStore, healthConnectClient, bluetoothManager, bluetoothEnabled) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

class DeviceViewModelFactory(
    private val gattManager: WatchGattClient,
    private val deviceStore: DeviceStore,
    private val context: Context,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(DeviceViewModel::class.java)) {
            return DeviceViewModel(gattManager, deviceStore, context) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

class PermissionsViewModelFactory(
    private val appContext: Context,
    private val deviceStore: DeviceStore,
    private val healthConnectClient: HealthConnectClient,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(PermissionsViewModel::class.java)) {
            return PermissionsViewModel(appContext, deviceStore, healthConnectClient) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
