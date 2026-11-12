package link.hector.freellee.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import link.hector.freellee.data.DeviceStore
import link.hector.freellee.data.HealthConnectClient
import link.hector.freellee.viewmodel.OlleeViewModel

/**
 * Combines the stopwatch activities and the counter events into one tab, since both are
 * pending events that need a type/meaning before they can be written to Health Connect.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventsScreen(
    deviceStore: DeviceStore,
    healthConnectClient: HealthConnectClient,
    onSync: () -> Unit,
    viewModel: OlleeViewModel,
) {
    var section by rememberSaveable { mutableIntStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            SegmentedButton(
                selected = section == 0,
                onClick = { section = 0 },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) {
                Text("Activities")
            }
            SegmentedButton(
                selected = section == 1,
                onClick = { section = 1 },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) {
                Text("Counters")
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            when (section) {
                0 -> ActivitiesScreen(
                    deviceStore = deviceStore,
                    healthConnectClient = healthConnectClient,
                    onSync = onSync,
                    viewModel = viewModel,
                )

                else -> CounterEventsScreen(
                    deviceStore = deviceStore,
                    healthConnectClient = healthConnectClient,
                    onSync = onSync,
                    viewModel = viewModel,
                )
            }
        }
    }
}
