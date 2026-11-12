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
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import link.hector.freellee.ble.WatchGattClient
import link.hector.freellee.ble.WatchRepository
import link.hector.freellee.data.DeviceStore
import link.hector.freellee.data.HealthConnectClient
import link.hector.freellee.permissions.AppPermissions
import link.hector.freellee.ui.screens.ConnectionScreen
import link.hector.freellee.ui.screens.DashboardScreen
import link.hector.freellee.ui.screens.EventsScreen
import link.hector.freellee.ui.screens.HistoryScreen
import link.hector.freellee.ui.screens.PermissionBanner
import link.hector.freellee.ui.screens.PermissionsOnboardingScreen
import link.hector.freellee.ui.screens.SettingsScreen
import link.hector.freellee.ui.screens.SleepScreen
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
    onSync: () -> Unit,
    onRequestBluetooth: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onRequestHealthConnect: () -> Unit,
) {
    val advancedEnabled by olleeViewModel.advancedEnabled.collectAsState()
    val tabs = remember(advancedEnabled) {
        buildList {
            add(MainTab("data", "Data", Icons.Default.Insights))
            // The Sleep tab only makes sense with fine-grained step data.
            if (advancedEnabled) add(MainTab("sleep", "Sleep", Icons.Default.Bedtime))
            add(MainTab("history", "History", Icons.Default.History))
            add(MainTab("events", "Events", Icons.Default.Event))
            add(MainTab("devices", "Devices", Icons.Default.Watch))
            add(MainTab("settings", "Settings", Icons.Default.Settings))
        }
    }
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val coroutineScope = rememberCoroutineScope()

    // The tab list changes when advanced features are toggled, which shifts every index after
    // Sleep. Track the destination by key so the user stays on the page they were looking at.
    var selectedTabKey by rememberSaveable { mutableStateOf("data") }
    LaunchedEffect(tabs) {
        val index = tabs.indexOfFirst { it.key == selectedTabKey }
        if (index >= 0 && index != pagerState.currentPage) {
            pagerState.scrollToPage(index)
        }
    }
    val currentTabs by rememberUpdatedState(tabs)
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            currentTabs.getOrNull(page)?.let { selectedTabKey = it.key }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = pagerState.currentPage == index,
                        onClick = {
                            selectedTabKey = tab.key
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(index)
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
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
                when (tabs.getOrNull(page)?.key) {
                    "data" -> DashboardScreen(
                        viewModel = olleeViewModel,
                        onSync = onSync,
                    )
                    "sleep" -> SleepScreen(
                        viewModel = olleeViewModel,
                        onSync = onSync,
                    )
                    "history" -> HistoryScreen(
                        viewModel = olleeViewModel,
                        onSync = onSync,
                    )
                    "events" -> EventsScreen(
                        deviceStore = deviceStore,
                        healthConnectClient = healthConnectClient,
                        onSync = onSync,
                        viewModel = olleeViewModel,
                    )
                    "devices" -> ConnectionScreen(
                        viewModel = deviceViewModel,
                        onConnectClick = { address -> deviceViewModel.connect(address) },
                        onDisconnect = { deviceViewModel.disconnect() },
                    )
                    "settings" -> SettingsScreen(
                        viewModel = olleeViewModel,
                        healthConnectClient = healthConnectClient,
                    )
                }
            }
        }
    }
}

/** One bottom-bar destination. [key] keeps content mapping stable when tabs appear or disappear. */
private data class MainTab(
    val key: String,
    val label: String,
    val icon: ImageVector,
)

/** Walks the [Context] chain to find the hosting [Activity], or null for non-activity contexts. */
private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
