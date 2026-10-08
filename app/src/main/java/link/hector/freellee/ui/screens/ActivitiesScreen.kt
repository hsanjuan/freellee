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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FitnessCenter
import link.hector.freellee.ui.icons.heart_plus
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.health.connect.client.records.ExerciseSessionRecord
import link.hector.freellee.data.DeviceStore
import link.hector.freellee.data.HealthConnectClient
import link.hector.freellee.ui.components.WatchStatusIndicator
import link.hector.freellee.ui.theme.AccentGreen
import link.hector.freellee.ui.theme.CardBackground
import link.hector.freellee.ui.theme.StatusError
import link.hector.freellee.ui.theme.TextPrimary
import link.hector.freellee.ui.theme.TextSecondary
import link.hector.freellee.ui.theme.TextTertiary
import link.hector.freellee.util.formatDuration
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

/** Duration of the slide-out animation for a pending activity, in milliseconds. */
private const val REMOVE_ANIMATION_MILLIS = 300

/** How long a row stays in the undoable "pending delete" state before it is removed. */
private const val DELETE_GRACE_MILLIS = 4_000L

private const val TAG = "ActivitiesScreen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivitiesScreen(
    deviceStore: DeviceStore,
    healthConnectClient: HealthConnectClient,
    onSync: () -> Unit,
    viewModel: OlleeViewModel,
) {
    val pendingActivities by deviceStore.getPendingActivities().collectAsState(initial = emptyList())
    val lastExerciseType by deviceStore.lastExerciseType.collectAsState(initial = null)
    var showExerciseTypeSheet by remember { mutableStateOf(false) }
    var selectedActivity by remember { mutableStateOf<DeviceStore.PendingActivity?>(null) }
    var removingIds by remember { mutableStateOf(emptySet<String>()) }
    var pendingDeleteIds by remember { mutableStateOf(emptySet<String>()) }
    val deleteJobs = remember { mutableMapOf<String, Job>() }
    var syncingAll by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val pullRefreshState = rememberPullToRefreshState()
    var isRefreshing by remember { mutableStateOf(false) }

    // Swiping right marks a row for deletion. The row is removed once the grace period
    // elapses, unless the user taps it to undo first.
    fun scheduleDelete(activity: DeviceStore.PendingActivity) {
        if (activity.id in pendingDeleteIds) return
        pendingDeleteIds = pendingDeleteIds + activity.id
        deleteJobs[activity.id]?.cancel()
        deleteJobs[activity.id] = coroutineScope.launch {
            delay(DELETE_GRACE_MILLIS)
            deviceStore.removePendingActivity(activity.id)
            pendingDeleteIds = pendingDeleteIds - activity.id
            deleteJobs.remove(activity.id)
        }
    }

    fun cancelDelete(id: String) {
        deleteJobs.remove(id)?.cancel()
        pendingDeleteIds = pendingDeleteIds - id
    }

    Scaffold(
        floatingActionButton = {
            val assignedCount = pendingActivities.count {
                it.assignedExerciseType != null && it.id !in pendingDeleteIds
            }
            if (assignedCount > 0 && !syncingAll) {
                FloatingActionButton(
                    onClick = { syncingAll = true },
                    containerColor = AccentGreen,
                    contentColor = Color.White,
                ) {
                    Icon(
                        heart_plus,
                        contentDescription = "Sync assigned activities",
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
                    "Swipe right to delete · Swipe left to tag · Tap to change type",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary.copy(alpha = 0.6f),
                    modifier = Modifier.padding(vertical = 4.dp),
                )

                if (pendingActivities.isEmpty()) {
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
                                        "No pending activities",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = TextTertiary,
                                    )
                                    Text(
                                        "Stopwatch records > 1 second will appear here",
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
                        // Group activities by date
                        val grouped = pendingActivities.groupBy { activity ->
                            val zone = ZoneId.systemDefault()
                            Instant.ofEpochSecond(activity.tStart).atZone(zone).toLocalDate()
                        }

                        val today = LocalDate.now()
                        val yesterday = today.minusDays(1)

                        // Order groups: Today, Yesterday, then older dates descending
                        val sortedGroups = LinkedHashMap<LocalDate, List<DeviceStore.PendingActivity>>(
                            grouped.toList().sortedWith(
                                compareByDescending<Pair<LocalDate, List<DeviceStore.PendingActivity>>> { (date, _) ->
                                    if (date == today) 0 else if (date == yesterday) 1 else 2
                                }.thenByDescending { (date, _) -> date },
                            ).associate { (date, activities) -> date to activities },
                        )

                        for ((date, activities) in sortedGroups) {
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
                            items(activities, key = { it.id }) { activity ->
                                ActivitySwipeItem(
                                    modifier = Modifier.animateItem(),
                                    activity = activity,
                                    isPendingDelete = activity.id in pendingDeleteIds,
                                    onTap = {
                                        selectedActivity = activity
                                        showExerciseTypeSheet = true
                                    },
                                    removingIds = removingIds,
                                    onDelete = { scheduleDelete(activity) },
                                    onCancelDelete = { cancelDelete(activity.id) },
                                    onTag = { act ->
                                        // Swipe left: tag with the most recently used exercise type
                                        coroutineScope.launch {
                                            deviceStore.updatePendingActivity(
                                                act.copy(
                                                    assignedExerciseType = lastExerciseType
                                                        ?: ExerciseSessionRecord.EXERCISE_TYPE_WALKING,
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

        // Batch sync: write all assigned activities to HC once, when the user starts a sync.
        LaunchedEffect(syncingAll) {
            if (!syncingAll) return@LaunchedEffect
            val assigned = pendingActivities.filter { it.assignedExerciseType != null && it.id !in pendingDeleteIds }
            var removedIds = emptySet<String>()
            for (activity in assigned) {
                val written = try {
                    healthConnectClient.writeExerciseSessionWithType(
                        tStart = activity.tStart,
                        tEnd = activity.tEnd,
                        exerciseType = activity.assignedExerciseType!!,
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to write exercise ${activity.id}", e)
                    false
                }
                if (written) {
                    removedIds = removedIds + activity.id
                }
            }
            // Animate out, then remove from DB
            if (removedIds.isNotEmpty()) {
                removingIds = removingIds + removedIds
                delay(REMOVE_ANIMATION_MILLIS.toLong())
                withContext(Dispatchers.IO) {
                    for (id in removedIds) {
                        deviceStore.removePendingActivity(id)
                    }
                }
                removingIds = removingIds - removedIds
            }
            syncingAll = false
        }
    }

    // Exercise type selection bottom sheet
    if (showExerciseTypeSheet && selectedActivity != null) {
        ExerciseTypeBottomSheet(
            onSelected = { exerciseType ->
                showExerciseTypeSheet = false
                val activity = selectedActivity!!
                // Persist type assignment + remember it as the most recently used type
                coroutineScope.launch {
                    deviceStore.updatePendingActivity(activity.copy(assignedExerciseType = exerciseType))
                    deviceStore.setLastExerciseType(exerciseType)
                }
                selectedActivity = null
            },
            onDismiss = {
                showExerciseTypeSheet = false
                selectedActivity = null
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
private fun ActivitySwipeItem(
    modifier: Modifier = Modifier,
    activity: DeviceStore.PendingActivity,
    isPendingDelete: Boolean,
    onTap: () -> Unit,
    removingIds: Set<String>,
    onDelete: (DeviceStore.PendingActivity) -> Unit,
    onCancelDelete: () -> Unit,
    onTag: (DeviceStore.PendingActivity) -> Unit,
) {
    val isRemoving = activity.id in removingIds
    val removeProgress by animateFloatAsState(
        targetValue = if (isRemoving) 1f else 0f,
        animationSpec = tween(
            durationMillis = REMOVE_ANIMATION_MILLIS,
            easing = FastOutSlowInEasing,
        ),
        label = "removeActivity",
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
                    onDelete(activity)
                    coroutineScope.launch { dismissState.reset() }
                }

                SwipeToDismissBoxValue.EndToStart -> {
                    // Swipe left → tag with the most recently used exercise type
                    onTag(activity)
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
                        .clip(RoundedCornerShape(12.dp))
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
        ActivityCard(
            activity = activity,
            isPendingDelete = isPendingDelete,
            onClick = { if (isPendingDelete) onCancelDelete() else onTap() },
        )
    }
}

@Composable
private fun ActivityCard(
    activity: DeviceStore.PendingActivity,
    isPendingDelete: Boolean,
    onClick: () -> Unit,
) {
    val startInstant = Instant.ofEpochSecond(activity.tStart)
    val endInstant = Instant.ofEpochSecond(activity.tEnd)
    val startFormatted = formatTimeOnly(startInstant)
    val endFormatted = formatTimeOnly(endInstant)
    val duration = formatDuration(activity.durationMillis)

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isPendingDelete) StatusError.copy(alpha = 0.25f) else CardBackground,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "$startFormatted -> $endFormatted",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        duration,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (isPendingDelete) {
                    Text(
                        text = "Tap to undo",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextPrimary,
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        // Show assigned type badge if present
                        activity.assignedExerciseType?.let { type ->
                            val typeInfo = ExerciseTypeInfo.fromType(type)
                            if (typeInfo != null) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(AccentGreen.copy(alpha = 0.15f))
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            typeInfo.icon,
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        Text(
                                            typeInfo.label,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = AccentGreen,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExerciseTypeBottomSheet(
    onSelected: (Int) -> Unit,
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
                "What activity was this?",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Medium,
            )
            Text(
                "Select the type of exercise for this stopwatch session.",
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
                items(ExerciseTypeInfo.all) { info ->
                    ExerciseTypeItem(
                        info = info,
                        onClick = { onSelected(info.type) },
                    )
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
private fun ExerciseTypeItem(
    info: ExerciseTypeInfo,
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
            text = info.icon,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(end = 12.dp),
        )
        Text(
            text = info.label,
            style = MaterialTheme.typography.bodyLarge,
            color = TextPrimary,
        )
    }
}

/**
 * Exercise type information with display label and icon.
 * Only includes types that exist in the Health Connect API.
 */
private data class ExerciseTypeInfo(
    val type: Int,
    val label: String,
    val icon: String,
) {
    companion object {
        val all = listOf(
            ExerciseTypeInfo(ExerciseSessionRecord.EXERCISE_TYPE_WALKING, "Walking", "🚶"),
            ExerciseTypeInfo(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, "Running", "🏃"),
            ExerciseTypeInfo(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL, "Treadmill", "🏃"),
            ExerciseTypeInfo(ExerciseSessionRecord.EXERCISE_TYPE_BIKING, "Biking", "🚴"),
            ExerciseTypeInfo(ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY, "Stationary Biking", "🚴"),
            ExerciseTypeInfo(ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL, "Pool Swimming", "🏊"),
            ExerciseTypeInfo(ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER, "Open Water Swimming", "🏊"),
            ExerciseTypeInfo(ExerciseSessionRecord.EXERCISE_TYPE_DANCING, "Dancing", "💃"),
            ExerciseTypeInfo(ExerciseSessionRecord.EXERCISE_TYPE_MARTIAL_ARTS, "Martial Arts", "🥋"),
            ExerciseTypeInfo(ExerciseSessionRecord.EXERCISE_TYPE_ROCK_CLIMBING, "Rock Climbing", "🧗"),
            ExerciseTypeInfo(ExerciseSessionRecord.EXERCISE_TYPE_HIKING, "Hiking", "🥾"),
            ExerciseTypeInfo(ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING, "HIIT", "⚡"),
            ExerciseTypeInfo(ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING, "Strength Training", "🏋️"),
            ExerciseTypeInfo(ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT, "Other Workout", "🏅"),
            ExerciseTypeInfo(ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON, "Badminton", "🏸"),
            ExerciseTypeInfo(ExerciseSessionRecord.EXERCISE_TYPE_BASEBALL, "Baseball", "⚾"),
            ExerciseTypeInfo(ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL, "Basketball", "🏀"),
            ExerciseTypeInfo(ExerciseSessionRecord.EXERCISE_TYPE_SOCCER, "Soccer", "⚽"),
            ExerciseTypeInfo(ExerciseSessionRecord.EXERCISE_TYPE_FOOTBALL_AMERICAN, "American Football", "🏈"),
            ExerciseTypeInfo(ExerciseSessionRecord.EXERCISE_TYPE_FOOTBALL_AUSTRALIAN, "Australian Football", "🏉"),
            ExerciseTypeInfo(ExerciseSessionRecord.EXERCISE_TYPE_GOLF, "Golf", "⛳"),
            ExerciseTypeInfo(ExerciseSessionRecord.EXERCISE_TYPE_HANDBALL, "Handball", "🤾"),
            ExerciseTypeInfo(ExerciseSessionRecord.EXERCISE_TYPE_ICE_HOCKEY, "Ice Hockey", "🏒"),
            ExerciseTypeInfo(ExerciseSessionRecord.EXERCISE_TYPE_PILATES, "Pilates", "🧘"),
            ExerciseTypeInfo(ExerciseSessionRecord.EXERCISE_TYPE_RACQUETBALL, "Racquetball", "🎾"),
            ExerciseTypeInfo(ExerciseSessionRecord.EXERCISE_TYPE_ROWING, "Rowing", "🚣"),
            ExerciseTypeInfo(ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE, "Rowing Machine", "🚣"),
            ExerciseTypeInfo(ExerciseSessionRecord.EXERCISE_TYPE_RUGBY, "Rugby", "🏉"),
            ExerciseTypeInfo(ExerciseSessionRecord.EXERCISE_TYPE_SAILING, "Sailing", "⛵"),
            ExerciseTypeInfo(ExerciseSessionRecord.EXERCISE_TYPE_SKATING, "Skating", "⛸️"),
            ExerciseTypeInfo(ExerciseSessionRecord.EXERCISE_TYPE_ICE_SKATING, "Ice Skating", "⛸️"),
            ExerciseTypeInfo(ExerciseSessionRecord.EXERCISE_TYPE_SKIING, "Skiing", "⛷️"),
            ExerciseTypeInfo(ExerciseSessionRecord.EXERCISE_TYPE_SNOWBOARDING, "Snowboarding", "🏂"),
            ExerciseTypeInfo(ExerciseSessionRecord.EXERCISE_TYPE_SQUASH, "Squash", "🎾"),
            ExerciseTypeInfo(ExerciseSessionRecord.EXERCISE_TYPE_TABLE_TENNIS, "Table Tennis", "🏓"),
            ExerciseTypeInfo(ExerciseSessionRecord.EXERCISE_TYPE_TENNIS, "Tennis", "🎾"),
            ExerciseTypeInfo(ExerciseSessionRecord.EXERCISE_TYPE_VOLLEYBALL, "Volleyball", "🏐"),
            ExerciseTypeInfo(ExerciseSessionRecord.EXERCISE_TYPE_CRICKET, "Cricket", "🏏"),
            ExerciseTypeInfo(ExerciseSessionRecord.EXERCISE_TYPE_FENCING, "Fencing", "🤺"),
            ExerciseTypeInfo(ExerciseSessionRecord.EXERCISE_TYPE_GUIDED_BREATHING, "Guided Breathing", "🌬️"),
            ExerciseTypeInfo(ExerciseSessionRecord.EXERCISE_TYPE_PADDLING, "Paddling", "🛶"),
            ExerciseTypeInfo(ExerciseSessionRecord.EXERCISE_TYPE_SURFING, "Surfing", "🏄"),
            ExerciseTypeInfo(ExerciseSessionRecord.EXERCISE_TYPE_WATER_POLO, "Water Polo", "🤽"),
            ExerciseTypeInfo(ExerciseSessionRecord.EXERCISE_TYPE_YOGA, "Yoga", "🧘"),
            ExerciseTypeInfo(ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING, "Weightlifting", "🏋️"),
            ExerciseTypeInfo(ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING, "Stair Climbing", "🪜"),
            ExerciseTypeInfo(ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE, "Stair Climbing Machine", "🪜"),
            ExerciseTypeInfo(ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL, "Elliptical", "🏃"),
            ExerciseTypeInfo(ExerciseSessionRecord.EXERCISE_TYPE_BOXING, "Boxing", "🥊"),
            ExerciseTypeInfo(ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS, "Calisthenics", "💪"),
            ExerciseTypeInfo(ExerciseSessionRecord.EXERCISE_TYPE_BOOT_CAMP, "Boot Camp", "🎖️"),
            ExerciseTypeInfo(ExerciseSessionRecord.EXERCISE_TYPE_EXERCISE_CLASS, "Exercise Class", "👥"),
            ExerciseTypeInfo(ExerciseSessionRecord.EXERCISE_TYPE_PARAGLIDING, "Paragliding", "🪂"),
            ExerciseTypeInfo(ExerciseSessionRecord.EXERCISE_TYPE_SCUBA_DIVING, "Scuba Diving", "🤿"),
            ExerciseTypeInfo(ExerciseSessionRecord.EXERCISE_TYPE_SNOWSHOEING, "Snowshoeing", "🥾"),
            ExerciseTypeInfo(ExerciseSessionRecord.EXERCISE_TYPE_ROLLER_HOCKEY, "Roller Hockey", "🛼"),
            ExerciseTypeInfo(ExerciseSessionRecord.EXERCISE_TYPE_FRISBEE_DISC, "Frisbee / Disc", "🥏"),
            ExerciseTypeInfo(ExerciseSessionRecord.EXERCISE_TYPE_SOFTBALL, "Softball", "⚾"),
            ExerciseTypeInfo(ExerciseSessionRecord.EXERCISE_TYPE_WHEELCHAIR, "Wheelchair", "♿"),
            ExerciseTypeInfo(ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING, "Stretching", "🧘"),
        ).distinctBy { it.type }.sortedBy { it.label }

        private val typeMap = all.associateBy { it.type }

        fun fromType(type: Int): ExerciseTypeInfo? = typeMap[type]
    }
}
