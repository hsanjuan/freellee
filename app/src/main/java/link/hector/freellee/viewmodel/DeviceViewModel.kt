package link.hector.freellee.viewmodel

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import link.hector.freellee.ble.ConnectionState
import link.hector.freellee.ble.WatchGattClient
import link.hector.freellee.ble.WatchRepository
import link.hector.freellee.data.DeviceStore
import link.hector.freellee.data.PairedDevice
import link.hector.freellee.BLE_CONNECT_TIMEOUT_MS
import link.hector.freellee.DEFAULT_WATCH_NAME
import link.hector.freellee.BLE_WATCH_NAME_PREFIX
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

data class DeviceUiState(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val deviceName: String? = null,
    val macAddress: String? = null,
    val firmwareVersion: String? = null,
    val voltageMv: Int? = null,
    val pairedDevices: List<PairedDevice> = emptyList(),
    val isScanning: Boolean = false,
    val foundDevices: List<ScanResult> = emptyList(),
    val bluetoothEnabled: Boolean = true,
    val error: String? = null,
)

class DeviceViewModel(
    private val gattManager: WatchGattClient,
    private val deviceStore: DeviceStore,
    private val appContext: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DeviceUiState())
    val uiState: StateFlow<DeviceUiState> = _uiState.asStateFlow()

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    val enabled = state == BluetoothAdapter.STATE_ON
                    _uiState.value = _uiState.value.copy(bluetoothEnabled = enabled)
                    if (!enabled && _uiState.value.isScanning) {
                        stopScan()
                        _uiState.value = _uiState.value.copy(
                            error = "Bluetooth turned off. Scan stopped."
                        )
                    }
                }
            }
        }
    }

    init {
        val btManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = btManager.adapter
        bleScanner = bluetoothAdapter?.bluetoothLeScanner
        checkBluetoothState()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(
                bluetoothReceiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                Context.RECEIVER_EXPORTED,
            )
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(
                bluetoothReceiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            )
        }

        viewModelScope.launch {
            gattManager.state.collect { state ->
                _uiState.value = _uiState.value.copy(connectionState = state)
            }
        }
        viewModelScope.launch {
            deviceStore.getAllPairedDevices().collect { devices ->
                _uiState.value = _uiState.value.copy(pairedDevices = devices)
            }
        }

        if (bluetoothAdapter?.isEnabled == true) {
            viewModelScope.launch {
                val savedMac = deviceStore.activeDeviceMac.firstOrNull()
                if (savedMac != null) {
                    connect(savedMac)
                }
            }
        }
    }

    private fun checkBluetoothState() {
        _uiState.value = _uiState.value.copy(
            bluetoothEnabled = bluetoothAdapter?.isEnabled == true
        )
    }

    fun startScan() {
        if (bluetoothAdapter?.isEnabled != true) {
            _uiState.value = _uiState.value.copy(error = "Bluetooth is turned off. Please enable it to scan for your watch.")
            return
        }

        if (bleScanner == null) {
            _uiState.value = _uiState.value.copy(error = "Bluetooth LE not available on this device.")
            return
        }

        stopScan()

        val discovered = mutableListOf<ScanResult>()
        scanCallback = object : ScanCallback() {
            @SuppressLint("MissingPermission")
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val deviceName = result.device.name ?: ""
                if (deviceName.contains(BLE_WATCH_NAME_PREFIX, ignoreCase = true)) {
                    synchronized(discovered) {
                        val existing = discovered.indexOfFirst { it.device.address == result.device.address }
                        if (existing >= 0) {
                            discovered[existing] = result
                        } else {
                            discovered.add(result)
                        }
                        _uiState.value = _uiState.value.copy(
                            foundDevices = discovered.toList(),
                            isScanning = true,
                            error = null,
                        )
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                val errorMsg = when (errorCode) {
                    ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "Scan already in progress"
                    ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "Cannot register for scan"
                    ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "Scan not supported on this device"
                    ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "Scan failed with error code $errorCode"
                    ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> "Scan failed with error code $errorCode"
                    ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY -> "Scan failed with error code $errorCode"
                    else -> "Scan failed with unknown error code $errorCode"
                }
                _uiState.value = _uiState.value.copy(
                    isScanning = false,
                    foundDevices = emptyList(),
                    error = errorMsg,
                )
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .build()

        val filters = emptyList<ScanFilter>()

        try {
            bleScanner?.startScan(filters, settings, scanCallback)
            _uiState.value = _uiState.value.copy(
                isScanning = true,
                foundDevices = emptyList(),
                error = null,
            )
        } catch (_: SecurityException) {
            _uiState.value = _uiState.value.copy(
                isScanning = false,
                error = "Bluetooth scan permission not granted. Please allow permissions in settings."
            )
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(
                isScanning = false,
                error = "Failed to start scan: ${e.message}"
            )
        }
    }

    fun stopScan() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
            }
            bleScanner?.stopScan(scanCallback)
        } catch (_: Exception) {}
        scanCallback = null
        _uiState.value = _uiState.value.copy(
            isScanning = false,
        )
    }

    fun connect(macAddress: String) = viewModelScope.launch {
        stopScan()
        val storedName = deviceStore.getDeviceName(macAddress).firstOrNull()
        _uiState.value = _uiState.value.copy(
            error = null,
            deviceName = storedName,
            macAddress = macAddress,
        )
        try {
            gattManager.connect(macAddress, timeoutMs = BLE_CONNECT_TIMEOUT_MS)
            val repo = WatchRepository(gattManager)
            val deviceInfo = try {
                repo.deviceInfo()
            } catch (_: Exception) {
                null
            }
            val watchName = try {
                repo.name()
            } catch (_: Exception) {
                null
            }
            val connectedDevice = gattManager.connectedDevice
            val displayName = watchName ?: connectedDevice?.name ?: DEFAULT_WATCH_NAME
            deviceStore.addOrUpdate(
                macAddress = macAddress,
                name = displayName,
                firmwareVersion = deviceInfo?.firmware,
                lastSyncedStepCount = null,
                voltageMv = deviceInfo?.voltageMv,
            )
            _uiState.value = _uiState.value.copy(
                deviceName = displayName,
                macAddress = connectedDevice?.address,
                firmwareVersion = deviceInfo?.firmware,
                voltageMv = deviceInfo?.voltageMv,
            )
        } catch (_: SecurityException) {
            _uiState.value = _uiState.value.copy(error = "Connection permission denied. Please allow Bluetooth access.")
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(error = "Connection failed: ${e.message}")
        }
    }

    fun disconnect() = viewModelScope.launch {
        gattManager.disconnect()
        _uiState.value = _uiState.value.copy(
            deviceName = null,
            macAddress = null,
            firmwareVersion = null,
        )
    }

    fun removeDevice(macAddress: String) = viewModelScope.launch {
        deviceStore.remove(macAddress)
    }

    fun setActiveDevice(macAddress: String) = viewModelScope.launch {
        deviceStore.setActiveDevice(macAddress)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    override fun onCleared() {
        try {
            appContext.unregisterReceiver(bluetoothReceiver)
        } catch (_: Exception) {}
        stopScan()
    }
}
