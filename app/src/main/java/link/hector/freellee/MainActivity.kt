package link.hector.freellee

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.bluetooth.BluetoothManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import link.hector.freellee.ble.WatchGattClient
import link.hector.freellee.ble.WatchRepository
import link.hector.freellee.data.DeviceStore
import link.hector.freellee.data.HealthConnectClient
import link.hector.freellee.permissions.AppPermissions
import link.hector.freellee.ui.screens.ActivitiesScreen
import link.hector.freellee.ui.screens.ConnectionScreen
import link.hector.freellee.ui.screens.DashboardScreen
import link.hector.freellee.ui.screens.PermissionBanner
import link.hector.freellee.ui.screens.PermissionsOnboardingScreen
import link.hector.freellee.ui.theme.FreelleeTheme
import link.hector.freellee.viewmodel.DeviceViewModel
import link.hector.freellee.viewmodel.OlleeViewModel
import link.hector.freellee.viewmodel.PermissionsUiState
import link.hector.freellee.viewmodel.PermissionsViewModel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val gattManager by lazy { WatchGattClient(this) }
    private val repository by lazy { WatchRepository(gattManager) }
    private val deviceStore by lazy { DeviceStore(this) }
    private val healthConnectClient by lazy { HealthConnectClient(this) }

    private val olleeViewModel by viewModels<OlleeViewModel> {
        OlleeViewModelFactory(
            repository,
            deviceStore,
            getSystemService(BluetoothManager::class.java),
            healthConnectClient,
            deviceViewModel.uiState.map { it.bluetoothEnabled },
        )
    }
    private val deviceViewModel by viewModels<DeviceViewModel> {
        DeviceViewModelFactory(gattManager, deviceStore, this)
    }
    private val permissionsViewModel by viewModels<PermissionsViewModel> {
        PermissionsViewModelFactory(applicationContext, deviceStore, healthConnectClient)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        setContent {
            FreelleeTheme {
                MainContent(
                    olleeViewModel = olleeViewModel,
                    deviceViewModel = deviceViewModel,
                    permissionsViewModel = permissionsViewModel,
                    healthConnectClient = healthConnectClient,
                    deviceStore = deviceStore,
                    onConnectClick = { olleeViewModel.sync() },
                    onSync = { olleeViewModel.sync() },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Pick up permission changes made in system settings while the app was in the background.
        permissionsViewModel.refresh()
    }
}

@Composable
fun MainContent(
    olleeViewModel: OlleeViewModel,
    deviceViewModel: DeviceViewModel,
    permissionsViewModel: PermissionsViewModel,
    healthConnectClient: HealthConnectClient,
    deviceStore: DeviceStore,
    onConnectClick: () -> Unit,
    onSync: () -> Unit,
) {
    val permissions by permissionsViewModel.uiState.collectAsState()
    val context = LocalContext.current
    val activity = context.findActivity()

    val healthConnectPermissionLauncher = rememberLauncherForActivityResult(
        healthConnectClient.permissionContract(),
    ) {
        permissionsViewModel.onHealthConnectPermissionResult()
    }

    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val permanentlyDenied = activity != null &&
            AppPermissions.isPermanentlyDenied(
                activity,
                AppPermissions.missingBluetoothPermissions(context),
            )
        permissionsViewModel.onBluetoothPermissionResult(permanentlyDenied)
    }

    val requestBluetooth: () -> Unit = {
        bluetoothPermissionLauncher.launch(
            AppPermissions.requiredBluetoothPermissions().toTypedArray(),
        )
    }
    val requestHealthConnect: () -> Unit = {
        healthConnectPermissionLauncher.launch(permissions.requiredHealthConnectPermissions)
    }
    val openAppSettings: () -> Unit = {
        try {
            context.startActivity(AppPermissions.appSettingsIntent(context))
        } catch (_: Exception) {
            // System settings unavailable; nothing actionable to do here.
        }
    }
    val openPrivacyPolicy: () -> Unit = {
        context.startActivity(Intent(context, PermissionsRationaleActivity::class.java))
    }

    when {
        permissions.loading -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }

        permissions.showOnboarding -> {
            PermissionsOnboardingScreen(
                state = permissions,
                onRequestBluetooth = requestBluetooth,
                onRequestHealthConnect = requestHealthConnect,
                onOpenAppSettings = openAppSettings,
                onContinue = { permissionsViewModel.completeOnboarding() },
                onOpenPrivacyPolicy = openPrivacyPolicy,
            )
        }

        else -> {
            MainTabs(
                olleeViewModel = olleeViewModel,
                deviceViewModel = deviceViewModel,
                healthConnectClient = healthConnectClient,
                deviceStore = deviceStore,
                permissions = permissions,
                onConnectClick = onConnectClick,
                onSync = onSync,
                onRequestBluetooth = requestBluetooth,
                onOpenAppSettings = openAppSettings,
                onRequestHealthConnect = requestHealthConnect,
            )
        }
    }
}

@Composable
private fun MainTabs(
    olleeViewModel: OlleeViewModel,
    deviceViewModel: DeviceViewModel,
    healthConnectClient: HealthConnectClient,
    deviceStore: DeviceStore,
    permissions: PermissionsUiState,
    onConnectClick: () -> Unit,
    onSync: () -> Unit,
    onRequestBluetooth: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onRequestHealthConnect: () -> Unit,
) {
    val tabs = listOf("Data", "Activities", "Devices")
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val coroutineScope = rememberCoroutineScope()

    Scaffold(modifier = Modifier.fillMaxSize()) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            PrimaryTabRow(
                selectedTabIndex = pagerState.currentPage,
                divider = {},
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = pagerState.currentPage == index,
                        onClick = {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(index)
                            }
                        },
                        icon = {
                            Icon(
                                if (index == 0) Icons.Default.FitnessCenter else Icons.Default.BluetoothConnected,
                                contentDescription = title,
                            )
                        },
                        text = { Text(title) },
                    )
                }
            }

            PermissionBanner(
                state = permissions,
                onRequestBluetooth = onRequestBluetooth,
                onOpenAppSettings = onOpenAppSettings,
                onRequestHealthConnect = onRequestHealthConnect,
            )

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
            ) { page ->
                when (page) {
                    0 -> DashboardScreen(
                        viewModel = olleeViewModel,
                        onConnectClick = onConnectClick,
                        onSync = onSync,
                    )
                    1 -> ActivitiesScreen(
                        deviceStore = deviceStore,
                        healthConnectClient = healthConnectClient,
                        onSync = onSync,
                        viewModel = olleeViewModel,
                    )
                    2 -> ConnectionScreen(
                        viewModel = deviceViewModel,
                        onConnectClick = { address -> deviceViewModel.connect(address) },
                        onDisconnect = { deviceViewModel.disconnect() },
                    )
                }
            }
        }
    }
}

/** Walks the [Context] chain to find the hosting [Activity], or null for non-activity contexts. */
private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
