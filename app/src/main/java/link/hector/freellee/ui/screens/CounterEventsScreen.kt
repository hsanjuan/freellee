package link.hector.freellee.ui.screens

import android.util.Log
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Numbers
import link.hector.freellee.ui.icons.heart_plus
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import link.hector.freellee.data.CounterEventWriter
import link.hector.freellee.data.DeviceStore
import link.hector.freellee.data.HealthConnectClient
import link.hector.freellee.ui.components.RecordCard
import link.hector.freellee.ui.components.WatchStatusIndicator
import link.hector.freellee.ui.theme.AccentGreen
import link.hector.freellee.ui.theme.AccentTeal
import link.hector.freellee.ui.theme.CardBackground
import link.hector.freellee.ui.theme.TextPrimary
import link.hector.freellee.ui.theme.TextSecondary
import link.hector.freellee.ui.theme.TextTertiary
import link.hector.freellee.util.formatTimeOnly
import link.hector.freellee.viewmodel.OlleeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Duration of the slide-out animation for a pending event, in milliseconds. */
private const val REMOVE_ANIMATION_MILLIS = 300

/** How long a row stays in the undoable "pending delete" state before it is removed. */
private const val DELETE_GRACE_MILLIS = 4_000L

private const val TAG = "CounterEventsScreen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CounterEventsScreen(
    deviceStore: DeviceStore,
    healthConnectClient: HealthConnectClient,
    onSync: () -> Unit,
    viewModel: OlleeViewModel,
) {
    val pendingEvents by deviceStore.getPendingCounterEvents().collectAsState(initial = emptyList())
    val lastCounterMeaning by deviceStore.lastCounterMeaning.collectAsState(initial = null)
    var showMeaningSheet by remember { mutableStateOf(false) }
    var selectedEvent by remember { mutableStateOf<DeviceStore.PendingCounterEvent?>(null) }
    var removingIds by remember { mutableStateOf(emptySet<String>()) }
    var pendingDeleteIds by remember { mutableStateOf(emptySet<String>()) }
    val deleteJobs = remember { mutableMapOf<String, Job>() }
    var syncingAll by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val pullRefreshState = rememberPullToRefreshState()
    var isRefreshing by remember { mutableStateOf(false) }

    // Swiping right marks a row for deletion. The row is removed once the grace period
    // elapses, unless the user taps it to undo first.
    fun scheduleDelete(event: DeviceStore.PendingCounterEvent) {
        if (event.id in pendingDeleteIds) return
        pendingDeleteIds = pendingDeleteIds + event.id
        deleteJobs[event.id]?.cancel()
        deleteJobs[event.id] = coroutineScope.launch {
            delay(DELETE_GRACE_MILLIS)
            deviceStore.removePendingCounterEvent(event.id)
            pendingDeleteIds = pendingDeleteIds - event.id
            deleteJobs.remove(event.id)
        }
    }

    fun cancelDelete(id: String) {
        deleteJobs.remove(id)?.cancel()
        pendingDeleteIds = pendingDeleteIds - id
    }

    Scaffold(
        floatingActionButton = {
            val assignedCount = pendingEvents.count {
                it.assignedMeaning != null && it.id !in pendingDeleteIds
            }
            if (assignedCount > 0 && !syncingAll) {
                FloatingActionButton(
                    onClick = { syncingAll = true },
                    containerColor = AccentGreen,
                    contentColor = Color.White,
                ) {
                    Icon(
                        heart_plus,
                        contentDescription = "Sync assigned events",
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        },
    ) { padding ->
        val listState = rememberLazyListState()

        PullToRefreshBox(
            isRefreshing = isRefreshing,
            state = pullRefreshState,
            onRefresh = {
                isRefreshing = true
                coroutineScope.launch {
                    onSync()
                    delay(500)
                    isRefreshing = false
                }
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
            ) {
                // Connection status indicator (same as Data tab)
                WatchStatusIndicator(
                    uiState = viewModel.uiState.collectAsState().value,
                    bleState = viewModel.connectionState.collectAsState().value,
                    hasPairedDevice = viewModel.hasPairedDevice.collectAsState().value,
                    bluetoothEnabled = true,
                    modifier = Modifier.padding(vertical = 12.dp),
                )

                // Swipe help text — persistent, always visible above the list
                Text(
                    "Swipe right to delete · Swipe left to tag · Tap to set meaning",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary.copy(alpha = 0.6f),
                    modifier = Modifier.padding(vertical = 4.dp),
                )

                if (pendingEvents.isEmpty()) {
                    // Keep a scrollable child so pull-to-refresh works on the empty state too.
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        item {
                            Box(
                                modifier = Modifier.fillParentMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        Icons.Default.FitnessCenter,
                                        contentDescription = null,
                                        tint = TextTertiary,
                                        modifier = Modifier.size(48.dp).padding(bottom = 16.dp),
                                    )
                                    Text(
                                        "No pending counter events",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = TextTertiary,
                                    )
                                    Text(
                                        "Counter records from the watch will appear here",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextTertiary,
                                        modifier = Modifier.padding(top = 4.dp),
                                    )
                                }
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        // Group events by date
                        val grouped = pendingEvents.groupBy { event ->
                            val zone = ZoneId.systemDefault()
                            Instant.ofEpochSecond(event.tStart).atZone(zone).toLocalDate()
                        }

                        val today = LocalDate.now()
                        val yesterday = today.minusDays(1)

                        // Order groups: Today, Yesterday, then older dates descending
                        val sortedGroups = LinkedHashMap<LocalDate, List<DeviceStore.PendingCounterEvent>>(
                            grouped.toList().sortedWith(
                                compareBy<Pair<LocalDate, List<DeviceStore.PendingCounterEvent>>> { (date, _) ->
                                    when (date) {
                                        today -> 0
                                        yesterday -> 1
                                        else -> 2
                                    }
                                }.thenByDescending { (date, _) -> date },
                            ).associate { (date, events) -> date to events },
                        )

                        for ((date, events) in sortedGroups) {
                            item(key = "header-$date") {
                                Text(
                                    getDateLabel(date, today, yesterday),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = TextSecondary,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier
                                        .animateItem()
                                        .padding(vertical = 8.dp, horizontal = 4.dp),
                                )
                            }
                            itemsIndexed(events, key = { _, event -> event.id }) { _, event ->
                                CounterEventSwipeItem(
                                    modifier = Modifier.animateItem(),
                                    event = event,
                                    isPendingDelete = event.id in pendingDeleteIds,
                                    onTap = {
                                        selectedEvent = event
                                        showMeaningSheet = true
                                    },
                                    removingIds = removingIds,
                                    onDelete = { scheduleDelete(event) },
                                    onCancelDelete = { cancelDelete(event.id) },
                                    onTag = { evt ->
                                        // Swipe left: tag with the most recently used meaning
                                        coroutineScope.launch {
                                            deviceStore.updatePendingCounterEvent(
                                                evt.copy(
                                                    assignedMeaning = lastCounterMeaning
                                                        ?: CounterEventWriter.MEANING_NUTRITION_ENERGY,
                                                ),
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        // Batch sync: write all assigned events to HC once, when the user starts a sync.
        LaunchedEffect(syncingAll) {
            if (!syncingAll) return@LaunchedEffect
            val assigned = pendingEvents.filter { it.assignedMeaning != null && it.id !in pendingDeleteIds }
            var removedIds = emptySet<String>()
            for (event in assigned) {
                val written = try {
                    healthConnectClient.writeCounterEvent(
                        tStart = event.tStart,
                        value = event.value,
                        meaningType = event.assignedMeaning!!,
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to write counter event ${event.id}", e)
                    false
                }
                if (written) {
                    removedIds = removedIds + event.id
                }
            }
            // Animate out, then remove from DB
            if (removedIds.isNotEmpty()) {
                removingIds = removingIds + removedIds
                delay(REMOVE_ANIMATION_MILLIS.toLong())
                withContext(Dispatchers.IO) {
                    for (id in removedIds) {
                        deviceStore.removePendingCounterEvent(id)
                    }
                }
                removingIds = removingIds - removedIds
            }
            syncingAll = false
        }
    }

    // Meaning selection bottom sheet
    if (showMeaningSheet && selectedEvent != null) {
        MeaningBottomSheet(
            count = selectedEvent!!.value,
            onSelected = { meaningType ->
                showMeaningSheet = false
                val event = selectedEvent!!
                // Persist meaning assignment + remember it as the most recently used
                coroutineScope.launch {
                    deviceStore.updatePendingCounterEvent(event.copy(assignedMeaning = meaningType))
                    deviceStore.setLastCounterMeaning(meaningType)
                }
                selectedEvent = null
            },
            onDismiss = {
                showMeaningSheet = false
                selectedEvent = null
            },
        )
    }
}

/** Get a human-readable date label for grouping. */
private fun getDateLabel(date: LocalDate, today: LocalDate, yesterday: LocalDate): String {
    return when (date) {
        today -> "Today"
        yesterday -> "Yesterday"
        else -> DateTimeFormatter.ofPattern("MMM d, yyyy").format(date)
    }
}

@Composable
private fun CounterEventSwipeItem(
    modifier: Modifier = Modifier,
    event: DeviceStore.PendingCounterEvent,
    isPendingDelete: Boolean,
    onTap: () -> Unit,
    removingIds: Set<String>,
    onDelete: (DeviceStore.PendingCounterEvent) -> Unit,
    onCancelDelete: () -> Unit,
    onTag: (DeviceStore.PendingCounterEvent) -> Unit,
) {
    val isRemoving = event.id in removingIds
    val removeProgress by animateFloatAsState(
        targetValue = if (isRemoving) 1f else 0f,
        animationSpec = tween(
            durationMillis = REMOVE_ANIMATION_MILLIS,
            easing = FastOutSlowInEasing,
        ),
        label = "removeCounterEvent",
    )

    val coroutineScope = rememberCoroutineScope()
    // Plain remember (not rememberSaveable): LazyColumn preserves saveable state by item key,
    // which would re-apply the dismissed state when a deleted row is restored via Undo.
    val dismissState = remember {
        SwipeToDismissBoxState(
            initialValue = SwipeToDismissBoxValue.Settled,
            positionalThreshold = { distance -> distance * 0.5f },
        )
    }

    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier.graphicsLayer {
            translationX = removeProgress * size.width * 0.9f
            alpha = 1f - removeProgress
        },
        onDismiss = { direction ->
            when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    // Swipe right → mark for deletion; tapping the row undoes it
                    onDelete(event)
                    coroutineScope.launch { dismissState.reset() }
                }

                SwipeToDismissBoxValue.EndToStart -> {
                    // Swipe left → tag with the most recently used meaning
                    onTag(event)
                    coroutineScope.launch { dismissState.reset() }
                }

                SwipeToDismissBoxValue.Settled -> Unit
            }
        },
        backgroundContent = {
            val direction = dismissState.dismissDirection
            if (direction != SwipeToDismissBoxValue.Settled) {
                val isDelete = direction == SwipeToDismissBoxValue.StartToEnd
                val accent = if (isDelete) Color.Red else AccentGreen
                val icon = if (isDelete) Icons.Default.Delete else heart_plus
                val iconAlignment = if (isDelete) Alignment.CenterStart else Alignment.CenterEnd
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(16.dp))
                        .background(accent.copy(alpha = 0.15f))
                        .padding(horizontal = 20.dp),
                    contentAlignment = iconAlignment,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accent.copy(alpha = 0.7f),
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        },
    ) {
        CounterEventCard(
            event = event,
            isPendingDelete = isPendingDelete,
            onClick = { if (isPendingDelete) onCancelDelete() else onTap() },
        )
    }
}

@Composable
private fun CounterEventCard(
    event: DeviceStore.PendingCounterEvent,
    isPendingDelete: Boolean,
    onClick: () -> Unit,
) {
    val startFormatted = formatTimeOnly(Instant.ofEpochSecond(event.tStart))
    val meaning = event.assignedMeaning?.let { CounterEventWriter.meaning(it) }
    val title = if (meaning == null) {
        "Tap to set meaning"
    } else {
        CounterEventWriter.preview(event.value, meaning.type)
            ?.let { "${meaning.label} ($it)" }
            ?: meaning.label
    }

    RecordCard(
        title = title,
        subtitle = startFormatted,
        leadingIcon = Icons.Default.Numbers,
        leadingEmoji = meaning?.icon,
        trailing = event.value.toString(),
        isPendingDelete = isPendingDelete,
        onClick = onClick,
        accent = AccentGreen,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MeaningBottomSheet(
    count: Int,
    onSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = CardBackground,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                "What does this counter mean?",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Medium,
            )
            Text(
                "Select the meaning for this counter event.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(1.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp),
            ) {
                // Group meanings by category
                val categories = CounterEventWriter.meanings.groupBy { it.category }
                categories.forEach { (category, meanings) ->
                    item {
                        Text(
                            category,
                            style = MaterialTheme.typography.titleSmall,
                            color = AccentTeal,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                        )
                    }
                    items(meanings, key = { it.type }) { meaning ->
                        MeaningItem(
                            meaning = meaning,
                            preview = CounterEventWriter.preview(count, meaning.type),
                            onClick = { onSelected(meaning.type) },
                        )
                    }
                }
            }

            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
            ) {
                Text("Cancel", color = TextSecondary)
            }
        }
    }
}

@Composable
private fun MeaningItem(
    meaning: CounterEventWriter.Meaning,
    preview: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = meaning.icon,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(end = 12.dp),
        )
        Text(
            text = meaning.label,
            style = MaterialTheme.typography.bodyLarge,
            color = TextPrimary,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (preview != null) {
            Text(
                text = "($preview)",
                style = MaterialTheme.typography.bodyMedium,
                color = TextTertiary,
                maxLines = 1,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

