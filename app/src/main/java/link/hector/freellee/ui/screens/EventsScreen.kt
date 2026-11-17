package link.hector.freellee.ui.screens

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
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import link.hector.freellee.data.CounterEventWriter
import link.hector.freellee.data.DeviceStore
import link.hector.freellee.ui.components.RecordCard
import link.hector.freellee.ui.icons.heart_plus
import link.hector.freellee.ui.theme.AccentGreen
import link.hector.freellee.ui.theme.AccentPurple
import link.hector.freellee.ui.theme.AccentTeal
import link.hector.freellee.ui.theme.CardBackground
import link.hector.freellee.ui.theme.TextPrimary
import link.hector.freellee.ui.theme.TextSecondary
import link.hector.freellee.ui.theme.TextTertiary
import link.hector.freellee.util.formatDuration
import link.hector.freellee.util.formatTimeOnly
import link.hector.freellee.viewmodel.OlleeViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Duration of the slide-out animation for a deleted event, in milliseconds. */
private const val REMOVE_ANIMATION_MILLIS = 300

/** How long a row stays in the undoable "pending delete" state before it is removed. */
private const val DELETE_GRACE_MILLIS = 4_000L

/**
 * One entry in the merged Events list: a stopwatch activity or a counter event. Both live in
 * the same list so the user does not have to switch between two separate sections.
 */
private sealed interface EventRow {
    val id: String
    val tStart: Long

    data class Activity(val activity: DeviceStore.PendingActivity) : EventRow {
        override val id: String get() = activity.id
        override val tStart: Long get() = activity.tStart
    }

    data class Counter(val event: DeviceStore.PendingCounterEvent) : EventRow {
        override val id: String get() = event.id
        override val tStart: Long get() = event.tStart
    }
}

/**
 * Lists every event recorded by the watch. Assigning an activity or meaning writes the record
 * to Health Connect right away; the event stays in this list until the user swipes it away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventsScreen(
    deviceStore: DeviceStore,
    onSync: () -> Unit,
    viewModel: OlleeViewModel,
    active: Boolean,
) {
    val pendingActivities by deviceStore.getPendingActivities().collectAsState(initial = emptyList())
    val pendingEvents by deviceStore.getPendingCounterEvents().collectAsState(initial = emptyList())
    val lastExerciseType by deviceStore.lastExerciseType.collectAsState(initial = null)
    val lastRecordType by deviceStore.lastRecordType.collectAsState(initial = null)
    val lastCounterMeaning by deviceStore.lastCounterMeaning.collectAsState(initial = null)

    var selectedActivity by remember { mutableStateOf<DeviceStore.PendingActivity?>(null) }
    var selectedEvent by remember { mutableStateOf<DeviceStore.PendingCounterEvent?>(null) }
    var removingIds by remember { mutableStateOf(emptySet<String>()) }
    var pendingDeleteIds by remember { mutableStateOf(emptySet<String>()) }
    val deleteJobs = remember { mutableMapOf<String, Job>() }
    val coroutineScope = rememberCoroutineScope()
    val pullRefreshState = rememberPullToRefreshState()
    var isRefreshing by remember { mutableStateOf(false) }

    val rows = remember(pendingActivities, pendingEvents) {
        buildList<EventRow> {
            pendingActivities.forEach { add(EventRow.Activity(it)) }
            pendingEvents.forEach { add(EventRow.Counter(it)) }
        }.sortedByDescending { it.tStart }
    }

    // Retry events that were tagged while Health Connect was unavailable or not permitted.
    LaunchedEffect(active) {
        if (active) viewModel.syncAssignedEvents()
    }

    fun scheduleDelete(row: EventRow) {
        if (row.id in pendingDeleteIds) return
        pendingDeleteIds = pendingDeleteIds + row.id
        deleteJobs[row.id]?.cancel()
        deleteJobs[row.id] = coroutineScope.launch {
            delay(DELETE_GRACE_MILLIS)
            when (row) {
                is EventRow.Activity -> viewModel.deleteActivity(row.activity)
                is EventRow.Counter -> viewModel.deleteCounterEvent(row.event)
            }
            pendingDeleteIds = pendingDeleteIds - row.id
            deleteJobs.remove(row.id)
        }
    }

    fun cancelDelete(id: String) {
        deleteJobs.remove(id)?.cancel()
        pendingDeleteIds = pendingDeleteIds - id
    }

    fun quickTag(row: EventRow) {
        when (row) {
            is EventRow.Activity -> {
                val activity = row.activity
                if (lastRecordType == DeviceStore.RecordType.SLEEP) {
                    viewModel.assignActivityType(activity, DeviceStore.RecordType.SLEEP, null)
                    coroutineScope.launch {
                        deviceStore.setLastRecordType(DeviceStore.RecordType.SLEEP)
                    }
                } else {
                    val type = lastExerciseType ?: ExerciseSessionRecord.EXERCISE_TYPE_WALKING
                    viewModel.assignActivityType(activity, DeviceStore.RecordType.EXERCISE, type)
                    coroutineScope.launch {
                        deviceStore.setLastRecordType(DeviceStore.RecordType.EXERCISE)
                        deviceStore.setLastExerciseType(type)
                    }
                }
            }

            is EventRow.Counter -> {
                val meaning = lastCounterMeaning ?: CounterEventWriter.MEANING_NUTRITION_ENERGY
                viewModel.assignCounterMeaning(row.event, meaning)
                coroutineScope.launch {
                    deviceStore.setLastCounterMeaning(meaning)
                }
            }
        }
    }

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
        val listState = rememberLazyListState()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
        ) {
            Text(
                "Swipe right to delete · Swipe left to tag · Tap to assign",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary.copy(alpha = 0.6f),
                modifier = Modifier.padding(vertical = 4.dp),
            )

            if (rows.isEmpty()) {
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
                                    Icons.Default.Event,
                                    contentDescription = null,
                                    tint = TextTertiary,
                                    modifier = Modifier
                                        .size(48.dp)
                                        .padding(bottom = 16.dp),
                                )
                                Text(
                                    "No events yet",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = TextTertiary,
                                )
                                Text(
                                    "Stopwatch and counter records from the watch will appear here",
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
                    val grouped = rows.groupBy { row ->
                        Instant.ofEpochSecond(row.tStart)
                            .atZone(ZoneId.systemDefault())
                            .toLocalDate()
                    }

                    val today = LocalDate.now()
                    val yesterday = today.minusDays(1)

                    val sortedGroups = grouped.toList().sortedWith(
                        compareBy<Pair<LocalDate, List<EventRow>>> { (date, _) ->
                            when (date) {
                                today -> 0
                                yesterday -> 1
                                else -> 2
                            }
                        }.thenByDescending { (date, _) -> date },
                    )

                    for ((date, dateRows) in sortedGroups) {
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
                        items(dateRows, key = { it.id }) { row ->
                            EventSwipeItem(
                                modifier = Modifier.animateItem(),
                                row = row,
                                isPendingDelete = row.id in pendingDeleteIds,
                                removingIds = removingIds,
                                onTap = {
                                    when (row) {
                                        is EventRow.Activity -> selectedActivity = row.activity
                                        is EventRow.Counter -> selectedEvent = row.event
                                    }
                                },
                                onDelete = { scheduleDelete(row) },
                                onCancelDelete = { cancelDelete(row.id) },
                                onTag = { quickTag(row) },
                            )
                        }
                    }
                }
            }
        }
    }

    selectedActivity?.let { activity ->
        ExerciseTypeBottomSheet(
            onSelected = { exerciseType ->
                viewModel.assignActivityType(
                    activity,
                    DeviceStore.RecordType.EXERCISE,
                    exerciseType,
                )
                coroutineScope.launch {
                    deviceStore.setLastRecordType(DeviceStore.RecordType.EXERCISE)
                    deviceStore.setLastExerciseType(exerciseType)
                }
                selectedActivity = null
            },
            onSleepSelected = {
                viewModel.assignActivityType(activity, DeviceStore.RecordType.SLEEP, null)
                coroutineScope.launch {
                    deviceStore.setLastRecordType(DeviceStore.RecordType.SLEEP)
                }
                selectedActivity = null
            },
            onDismiss = { selectedActivity = null },
        )
    }

    selectedEvent?.let { event ->
        MeaningBottomSheet(
            count = event.value,
            onSelected = { meaningType ->
                viewModel.assignCounterMeaning(event, meaningType)
                coroutineScope.launch {
                    deviceStore.setLastCounterMeaning(meaningType)
                }
                selectedEvent = null
            },
            onDismiss = { selectedEvent = null },
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
private fun EventSwipeItem(
    modifier: Modifier = Modifier,
    row: EventRow,
    isPendingDelete: Boolean,
    removingIds: Set<String>,
    onTap: () -> Unit,
    onDelete: () -> Unit,
    onCancelDelete: () -> Unit,
    onTag: () -> Unit,
) {
    val isRemoving = row.id in removingIds
    val removeProgress by animateFloatAsState(
        targetValue = if (isRemoving) 1f else 0f,
        animationSpec = tween(
            durationMillis = REMOVE_ANIMATION_MILLIS,
            easing = FastOutSlowInEasing,
        ),
        label = "removeEvent",
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
                    onDelete()
                    coroutineScope.launch { dismissState.reset() }
                }

                SwipeToDismissBoxValue.EndToStart -> {
                    // Swipe left → tag with the most recently used activity/meaning
                    onTag()
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
        when (row) {
            is EventRow.Activity -> ActivityCard(
                activity = row.activity,
                isPendingDelete = isPendingDelete,
                onClick = { if (isPendingDelete) onCancelDelete() else onTap() },
            )

            is EventRow.Counter -> CounterEventCard(
                event = row.event,
                isPendingDelete = isPendingDelete,
                onClick = { if (isPendingDelete) onCancelDelete() else onTap() },
            )
        }
    }
}

@Composable
private fun ActivityCard(
    activity: DeviceStore.PendingActivity,
    isPendingDelete: Boolean,
    onClick: () -> Unit,
) {
    val startFormatted = formatTimeOnly(Instant.ofEpochSecond(activity.tStart))
    val endFormatted = formatTimeOnly(Instant.ofEpochSecond(activity.tEnd))
    val isSleep = activity.recordType == DeviceStore.RecordType.SLEEP
    val typeInfo = if (isSleep) null else activity.assignedExerciseType?.let { ExerciseTypeInfo.fromType(it) }

    RecordCard(
        title = if (isSleep) "Sleep" else (typeInfo?.label ?: "Tap to set activity"),
        subtitle = "$startFormatted – $endFormatted",
        leadingIcon = Icons.Default.Timer,
        leadingEmoji = if (isSleep) "🌙" else typeInfo?.icon,
        trailing = formatDuration(activity.durationMillis),
        isPendingDelete = isPendingDelete,
        onClick = onClick,
        accent = if (isSleep) AccentPurple else AccentGreen,
    )
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
private fun ExerciseTypeBottomSheet(
    onSelected: (Int) -> Unit,
    onSleepSelected: () -> Unit,
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
                "Choose how to record this stopwatch session.",
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
                item {
                    HorizontalDivider(color = TextTertiary.copy(alpha = 0.2f))
                }
                item {
                    SleepListItem(onClick = onSleepSelected)
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

@Composable
private fun SleepListItem(
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
            text = "🌙",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(end = 12.dp),
        )
        Text(
            text = "Sleep",
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
