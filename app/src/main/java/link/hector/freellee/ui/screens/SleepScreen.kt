package link.hector.freellee.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import link.hector.freellee.data.DeviceStore.StepInterval
import link.hector.freellee.data.MIN_VALID_TEMPERATURE
import link.hector.freellee.data.SleepSession
import link.hector.freellee.data.SleepSessionKind
import link.hector.freellee.data.SleepSessionSource
import link.hector.freellee.data.defaultSleepRange
import link.hector.freellee.data.epochInSleepDay
import link.hector.freellee.data.inProgressWindowSeconds
import link.hector.freellee.data.isInProgress
import link.hector.freellee.data.medianOrNull
import link.hector.freellee.data.sleepDayEnd
import link.hector.freellee.data.sleepDayKey
import link.hector.freellee.data.sleepDayStart
import link.hector.freellee.ui.theme.AccentAmber
import link.hector.freellee.ui.theme.AccentGreen
import link.hector.freellee.ui.theme.AccentPurple
import link.hector.freellee.ui.theme.CardBackground
import link.hector.freellee.ui.theme.StatusError
import link.hector.freellee.ui.theme.SurfaceDark
import link.hector.freellee.ui.theme.TextPrimary
import link.hector.freellee.ui.theme.TextSecondary
import link.hector.freellee.ui.theme.TextTertiary
import link.hector.freellee.util.formatSleepDuration
import link.hector.freellee.util.formatSleepRange
import link.hector.freellee.viewmodel.DashboardUiState
import link.hector.freellee.viewmodel.HistoryEntry
import link.hector.freellee.viewmodel.OlleeViewModel
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepScreen(
    viewModel: OlleeViewModel,
    onSync: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val state = uiState as? DashboardUiState.Ready ?: viewModel.lastReadyState
    val isRefreshing = uiState is DashboardUiState.Connecting ||
        uiState is DashboardUiState.Connected ||
        uiState is DashboardUiState.Syncing

    val zone = remember { ZoneId.systemDefault() }
    val sampling by viewModel.samplingProfile.collectAsState()
    val now = remember(state?.sleepSessions) { Instant.now().epochSecond }
    val progressWindow = remember(sampling.stepIntervalSeconds) {
        inProgressWindowSeconds(sampling.stepIntervalSeconds)
    }
    val todaySleepDay = remember(now) { sleepDayKey(now, zone) }
    var selectedEpochDay by rememberSaveable { mutableStateOf(todaySleepDay.toEpochDay()) }
    val selectedDate = LocalDate.ofEpochDay(selectedEpochDay)
    var editorTarget by remember { mutableStateOf<SleepSession?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }

    val allSessions = state?.sleepSessions.orEmpty()
    val sessions = allSessions
        .filter { !it.dismissed && sleepDayKey(it.startEpochSecond, zone) == selectedDate }
        .sortedBy { it.startEpochSecond }
    val windowStart = sleepDayStart(selectedDate, zone)
    val windowEnd = sleepDayEnd(selectedDate, zone)

    val (defaultAddStart, defaultAddEnd) = remember(allSessions, selectedEpochDay, now, zone) {
        defaultSleepRange(selectedDate, allSessions, now, zone)
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    if (editorTarget != null) {
        val target = editorTarget!!
        SleepSessionEditorDialog(
            title = "Adjust sleep",
            initialStart = target.startEpochSecond,
            initialEnd = target.endEpochSecond,
            onSave = { start, end ->
                viewModel.adjustSleepSession(target.id, start, end)
                editorTarget = null
            },
            onDismiss = { editorTarget = null },
        )
    }

    if (showAddDialog) {
        SleepSessionEditorDialog(
            title = "Add sleep",
            initialStart = defaultAddStart,
            initialEnd = defaultAddEnd,
            onSave = { start, end ->
                viewModel.addSleepSession(start, end)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false },
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            state = rememberPullToRefreshState(),
            onRefresh = onSync,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    SleepDayHeader(
                        date = selectedDate,
                        zone = zone,
                        canGoForward = selectedEpochDay < todaySleepDay.toEpochDay(),
                        onPrevious = { selectedEpochDay -= 1 },
                        onNext = {
                            if (selectedEpochDay < todaySleepDay.toEpochDay()) {
                                selectedEpochDay += 1
                            }
                        },
                    )
                }

                item {
                    SleepTimelineCard(
                        windowStart = windowStart,
                        windowEnd = windowEnd,
                        temperature = state?.temperatureRecords.orEmpty(),
                        steps = state?.stepIntervals.orEmpty(),
                        sessions = sessions,
                        zone = zone,
                        onAdjust = { id, start, end ->
                            viewModel.adjustSleepSession(id, start, end)
                        },
                    )
                }

                item {
                    OutlinedButton(
                        onClick = { showAddDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, AccentGreen.copy(alpha = 0.7f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color.Transparent,
                            contentColor = AccentGreen,
                        ),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Add missed sleep")
                    }
                }

                item {
                    SleepSectionHeading("Sessions")
                }

                if (sessions.isEmpty()) {
                    item {
                        Text(
                            "No sleep detected for this sleep day.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextTertiary,
                        )
                    }
                } else {
                    items(sessions.size) { index ->
                        val session = sessions[index]
                        SleepSessionCard(
                            session = session,
                            zone = zone,
                            inProgress = session.isInProgress(now, progressWindow),
                            canMergeWithNext = index < sessions.lastIndex,
                            onConfirm = { viewModel.confirmSleepSession(session.id) },
                            onAdjust = { editorTarget = session },
                            onMergeWithNext = { viewModel.mergeSleepSessionWithNext(session.id) },
                            onReset = { viewModel.resetSleepSession(session.id) },
                            onDismiss = {
                                viewModel.dismissSleepSession(session.id)
                                scope.launch {
                                    val result = snackbarHostState.showSnackbar(
                                        message = "Sleep session removed",
                                        actionLabel = "Undo",
                                        duration = SnackbarDuration.Long,
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        viewModel.restoreSleepSession(session)
                                    }
                                }
                            },
                        )
                    }
                }

                item {
                    SleepSectionHeading("Sleep rhythm")
                }

                item {
                    SleepRhythmCard(
                        selectedDate = selectedDate,
                        sessions = allSessions,
                        zone = zone,
                    )
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
        )
    }
}

@Composable
private fun SleepDayHeader(
    date: LocalDate,
    zone: ZoneId,
    canGoForward: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(AccentPurple.copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Bedtime, contentDescription = null, tint = AccentPurple)
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Sleep",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        formatSleepDayLabel(date, zone),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary,
                    )
                }
                IconButton(onClick = onPrevious) {
                    Icon(Icons.Default.ChevronLeft, contentDescription = "Previous day")
                }
                IconButton(onClick = onNext, enabled = canGoForward) {
                    Icon(Icons.Default.ChevronRight, contentDescription = "Next day")
                }
            }
        }
    }
}

@Composable
private fun SleepTimelineCard(
    windowStart: Long,
    windowEnd: Long,
    temperature: List<HistoryEntry>,
    steps: List<StepInterval>,
    sessions: List<SleepSession>,
    zone: ZoneId,
    onAdjust: (String, Long, Long) -> Unit,
) {
    val points = remember(temperature, windowStart, windowEnd, zone) {
        buildTemperaturePoints(temperature, windowStart, windowEnd, zone)
    }
    val stepBars = remember(steps, windowStart, windowEnd) {
        buildStepBars(steps, windowStart, windowEnd)
    }
    val blocks = sessions.filter { it.startEpochSecond < windowEnd && it.endEpochSecond > windowStart }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "24-hour view",
                style = MaterialTheme.typography.labelLarge,
                color = TextSecondary,
            )
            Spacer(Modifier.height(8.dp))
            Box(modifier = Modifier.fillMaxWidth().height(240.dp)) {
                SleepTimelineCanvas(
                    windowStart = windowStart,
                    windowEnd = windowEnd,
                    points = points,
                    stepBars = stepBars,
                    blocks = blocks,
                    zone = zone,
                    onAdjust = onAdjust,
                    modifier = Modifier.fillMaxSize(),
                )
                if (points.isEmpty()) {
                    Text(
                        "No temperature data for this day",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Tap a sleep block, then drag its handles to adjust the start or end.",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LegendDot(AccentAmber, "Temperature")
                LegendDot(AccentGreen, "Steps")
                LegendDot(AccentPurple, "Sleep")
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, RoundedCornerShape(4.dp)),
        )
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextTertiary)
    }
}

/** Drag editing snaps to this grid. */
private const val TIMELINE_SNAP_SECONDS = 15L * 60L

private enum class TimelineEdge { START, END }

private data class TimelineDraft(val id: String, val start: Long, val end: Long)

private fun snapToGrid(epochSecond: Long): Long =
    ((epochSecond + TIMELINE_SNAP_SECONDS / 2) / TIMELINE_SNAP_SECONDS) * TIMELINE_SNAP_SECONDS

private fun draftFor(
    edge: TimelineEdge,
    anchor: TimelineDraft,
    deltaSeconds: Float,
    windowStart: Long,
    windowEnd: Long,
): TimelineDraft {
    val delta = deltaSeconds.roundToLong()
    return when (edge) {
        TimelineEdge.START -> anchor.copy(
            start = snapToGrid(anchor.start + delta).coerceIn(
                windowStart,
                anchor.end - TIMELINE_SNAP_SECONDS,
            ),
        )

        TimelineEdge.END -> anchor.copy(
            end = snapToGrid(anchor.end + delta).coerceIn(
                anchor.start + TIMELINE_SNAP_SECONDS,
                windowEnd,
            ),
        )
    }
}

@Composable
private fun SleepTimelineCanvas(
    windowStart: Long,
    windowEnd: Long,
    points: List<TimelineTempPoint>,
    stepBars: List<TimelineStepBar>,
    blocks: List<SleepSession>,
    zone: ZoneId,
    onAdjust: (String, Long, Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(
        color = TextTertiary,
        fontSize = 10.sp,
    )
    val leftPadding = with(density) { 40.dp.toPx() }
    val rightPadding = with(density) { 4.dp.toPx() }
    val topPadding = with(density) { 8.dp.toPx() }
    val bottomPadding = with(density) { 24.dp.toPx() }
    val handleSlop = with(density) { 28.dp.toPx() }
    val handleRadius = with(density) { 5.dp.toPx() }
    val gridColor = TextTertiary.copy(alpha = 0.15f)
    val span = (windowEnd - windowStart).toFloat().coerceAtLeast(1f)

    // Selection and drag state belong to the visible day, so they reset when the day changes.
    var selectedId by remember(windowStart) { mutableStateOf<String?>(null) }
    var draft by remember(windowStart) { mutableStateOf<TimelineDraft?>(null) }
    var dragDistancePx by remember(windowStart) { mutableStateOf(0f) }

    val displayBlocks = remember(blocks, draft) {
        val current = draft ?: return@remember blocks
        blocks.map { block ->
            if (block.id == current.id) {
                block.copy(
                    startEpochSecond = current.start,
                    endEpochSecond = current.end,
                )
            } else {
                block
            }
        }
    }

    val minTemp = points.minOfOrNull { it.celsius } ?: 28.0
    val maxTemp = points.maxOfOrNull { it.celsius } ?: 34.0
    val tempPadding = 0.4
    val axisMin = minTemp - tempPadding
    val axisMax = maxTemp + tempPadding
    val axisRange = (axisMax - axisMin).coerceAtLeast(0.1)
    val maxSteps = stepBars.maxOfOrNull { it.steps }?.coerceAtLeast(1) ?: 1

    Canvas(
        modifier = modifier
            .semantics {
                contentDescription = "Sleep timeline with temperature, steps and sleep sessions"
            }
            .pointerInput(blocks, windowStart, windowEnd) {
                detectTapGestures { offset ->
                    val left = leftPadding
                    val right = size.width - rightPadding
                    if (offset.x < left || offset.x > right) return@detectTapGestures
                    val plotWidth = (right - left).coerceAtLeast(1f)
                    val hit = blocks.lastOrNull { block ->
                        val from = left + ((block.startEpochSecond - windowStart) / span) * plotWidth
                        val to = left + ((block.endEpochSecond - windowStart) / span) * plotWidth
                        offset.x in from..to
                    }
                    selectedId = if (hit?.id == selectedId) null else hit?.id
                    draft = null
                }
            }
            .pointerInput(selectedId, blocks, windowStart, windowEnd) {
                val selected = blocks.firstOrNull { it.id == selectedId } ?: return@pointerInput
                val left = leftPadding
                val plotWidth = (size.width - rightPadding - left).coerceAtLeast(1f)
                val secondsPerPixel = span / plotWidth

                fun xFor(epochSecond: Long): Float =
                    left + ((epochSecond - windowStart) / span) * plotWidth

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val startX = xFor(selected.startEpochSecond)
                    val endX = xFor(selected.endEpochSecond)
                    val edge = when {
                        kotlin.math.abs(down.position.x - startX) <= handleSlop -> TimelineEdge.START
                        kotlin.math.abs(down.position.x - endX) <= handleSlop -> TimelineEdge.END
                        else -> null
                    } ?: return@awaitEachGesture

                    val anchor = TimelineDraft(
                        selected.id,
                        selected.startEpochSecond,
                        selected.endEpochSecond,
                    )
                    draft = anchor
                    var movedPx = 0f

                    val dragChange = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                        change.consume()
                        movedPx += over
                        draft = draftFor(
                            edge = edge,
                            anchor = anchor,
                            deltaSeconds = movedPx * secondsPerPixel,
                            windowStart = windowStart,
                            windowEnd = windowEnd,
                        )
                    }
                    if (dragChange != null) {
                        horizontalDrag(dragChange.id) { change ->
                            movedPx += change.positionChange().x
                            change.consume()
                            draft = draftFor(
                                edge = edge,
                                anchor = anchor,
                                deltaSeconds = movedPx * secondsPerPixel,
                                windowStart = windowStart,
                                windowEnd = windowEnd,
                            )
                        }
                    }

                    val result = draft
                    if (result != null &&
                        (result.start != anchor.start || result.end != anchor.end)
                    ) {
                        onAdjust(result.id, result.start, result.end)
                    }
                    draft = null
                }
            },
    ) {
        val left = leftPadding
        val right = size.width - rightPadding
        val top = topPadding
        val bottom = size.height - bottomPadding
        val plotWidth = (right - left).coerceAtLeast(1f)
        val plotHeight = (bottom - top).coerceAtLeast(1f)

        fun xFor(epochSecond: Long): Float =
            left + ((epochSecond - windowStart) / span) * plotWidth

        fun yFor(celsius: Double): Float =
            bottom - ((celsius - axisMin) / axisRange * plotHeight).toFloat()

        // Horizontal temperature grid.
        val ticks = listOf(axisMin, (axisMin + axisMax) / 2.0, axisMax)
        ticks.forEach { value ->
            val y = yFor(value)
            drawLine(gridColor, Offset(left, y), Offset(right, y), 1.dp.toPx())
            val label = "%.1f°".format(Locale.US, value)
            val layout = textMeasurer.measure(label, labelStyle)
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(
                    x = (left - layout.size.width - 4.dp.toPx()).coerceAtLeast(0f),
                    y = y - layout.size.height / 2f,
                ),
            )
        }

        // Vertical time grid every three hours.
        var tick = Instant.ofEpochSecond(windowStart).atZone(zone)
            .truncatedTo(java.time.temporal.ChronoUnit.HOURS)
        while (tick.toEpochSecond() < windowEnd) {
            if (tick.hour % 3 == 0) {
                val x = xFor(tick.toEpochSecond())
                drawLine(gridColor, Offset(x, top), Offset(x, bottom), 1.dp.toPx())
                val label = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()).format(tick)
                val layout = textMeasurer.measure(label, labelStyle)
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(
                        x = (x - layout.size.width / 2f).coerceIn(left, right - layout.size.width),
                        y = bottom + 4.dp.toPx(),
                    ),
                )
            }
            tick = tick.plusHours(1)
        }

        // Step bars along the bottom.
        stepBars.forEach { bar ->
            val xStart = xFor(bar.startEpochSecond)
            val xEnd = xFor(bar.endEpochSecond)
            val height = (bar.steps.toFloat() / maxSteps) * plotHeight * 0.18f
            if (height > 0f) {
                drawRoundRect(
                    color = AccentGreen.copy(alpha = 0.22f),
                    topLeft = Offset(xStart, bottom - height),
                    size = Size((xEnd - xStart).coerceAtLeast(1f), height),
                    cornerRadius = CornerRadius(1.dp.toPx()),
                )
            }
        }

        // Sleep blocks behind the temperature line.
        displayBlocks.forEach { block ->
            val xStart = xFor(max(block.startEpochSecond, windowStart))
            val xEnd = xFor(min(block.endEpochSecond, windowEnd))
            if (xEnd > xStart) {
                val selected = block.id == selectedId
                drawRoundRect(
                    color = AccentPurple.copy(alpha = if (selected) 0.28f else 0.16f),
                    topLeft = Offset(xStart, top),
                    size = Size(xEnd - xStart, plotHeight),
                    cornerRadius = CornerRadius(4.dp.toPx()),
                )
            }
        }

        // Handles and the live time range for the selected block.
        displayBlocks.firstOrNull { it.id == selectedId }?.let { selected ->
            val xStart = xFor(selected.startEpochSecond).coerceIn(left, right)
            val xEnd = xFor(selected.endEpochSecond).coerceIn(left, right)
            drawLine(
                color = AccentPurple,
                start = Offset(xStart, top),
                end = Offset(xStart, bottom),
                strokeWidth = 2.dp.toPx(),
            )
            drawLine(
                color = AccentPurple,
                start = Offset(xEnd, top),
                end = Offset(xEnd, bottom),
                strokeWidth = 2.dp.toPx(),
            )
            val handleY = top + plotHeight * 0.5f
            drawCircle(AccentPurple, handleRadius, Offset(xStart, handleY))
            drawCircle(AccentPurple, handleRadius, Offset(xEnd, handleY))

            val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
            val label = "${
                timeFormatter.format(Instant.ofEpochSecond(selected.startEpochSecond).atZone(zone))
            } – ${
                timeFormatter.format(Instant.ofEpochSecond(selected.endEpochSecond).atZone(zone))
            }"
            val layout = textMeasurer.measure(label, labelStyle.copy(color = TextPrimary))
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(
                    x = ((xStart + xEnd) / 2f - layout.size.width / 2f)
                        .coerceIn(left, right - layout.size.width),
                    y = top + 2.dp.toPx(),
                ),
            )
        }

        // Temperature line.
        val path = Path()
        var drawing = false
        points.forEach { point ->
            val x = xFor(point.epochSecond)
            val y = yFor(point.celsius)
            if (drawing) path.lineTo(x, y) else path.moveTo(x, y)
            drawing = true
        }
        if (points.isNotEmpty()) {
            drawPath(
                path = path,
                color = AccentAmber,
                style = Stroke(
                    width = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
        }
    }
}

@Composable
private fun ActionIconButton(
    icon: ImageVector,
    description: String,
    tint: Color,
    onClick: () -> Unit,
) {
    OutlinedIconButton(
        onClick = onClick,
        modifier = Modifier.size(40.dp),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.45f)),
        colors = IconButtonDefaults.outlinedIconButtonColors(contentColor = tint),
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun SleepSessionCard(
    session: SleepSession,
    zone: ZoneId,
    inProgress: Boolean,
    canMergeWithNext: Boolean,
    onConfirm: () -> Unit,
    onAdjust: () -> Unit,
    onMergeWithNext: () -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isEdited = session.source == SleepSessionSource.USER
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (session.kind == SleepSessionKind.MAIN) "Main sleep" else "Nap",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (isEdited) {
                        Text(
                            "Confirmed ${formatSleepRange(
                                session.startEpochSecond,
                                session.endEpochSecond,
                                zone,
                            )}",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (session.hasDetectedOrigin) {
                            val confidence = session.detectedConfidence
                                ?.let { " · ${Math.round(it * 100)}%" }
                                .orEmpty()
                            Text(
                                "Detected ${formatSleepRange(
                                    session.detectedStartEpochSecond!!,
                                    session.detectedEndEpochSecond!!,
                                    zone,
                                )}$confidence",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextTertiary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    } else {
                        Text(
                            "Detected ${formatSleepRange(
                                session.startEpochSecond,
                                session.endEpochSecond,
                                zone,
                            )} · ${Math.round(session.confidence * 100)}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextTertiary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        formatSleepDuration(session.durationSeconds),
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = when {
                            inProgress -> "In progress"
                            isEdited -> "Confirmed"
                            else -> "Detected"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = when {
                            inProgress -> AccentAmber
                            isEdited || session.confidence >= 0.7 -> AccentGreen
                            else -> TextTertiary
                        },
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!inProgress && !isEdited) {
                    ActionIconButton(
                        icon = Icons.Default.Check,
                        description = "Looks right",
                        tint = AccentGreen,
                        onClick = onConfirm,
                    )
                }
                if (!inProgress) {
                    ActionIconButton(
                        icon = Icons.Default.Edit,
                        description = "Adjust",
                        tint = TextSecondary,
                        onClick = onAdjust,
                    )
                }
                if (!inProgress && isEdited) {
                    ActionIconButton(
                        icon = Icons.Default.Refresh,
                        description = "Reset to detected",
                        tint = TextSecondary,
                        onClick = onReset,
                    )
                }
                if (!inProgress && canMergeWithNext) {
                    ActionIconButton(
                        icon = Icons.AutoMirrored.Filled.CallMerge,
                        description = "Merge with next session",
                        tint = TextSecondary,
                        onClick = onMergeWithNext,
                    )
                }
                ActionIconButton(
                    icon = Icons.Default.Delete,
                    description = "Remove",
                    tint = StatusError,
                    onClick = onDismiss,
                )
            }
        }
    }
}

@Composable
private fun RhythmStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(SurfaceDark, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 12.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = TextTertiary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            color = TextPrimary,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Section heading shared by the session list and the rhythm summary. */
@Composable
private fun SleepSectionHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = TextSecondary,
    )
}

@Composable
private fun SleepRhythmCard(
    selectedDate: LocalDate,
    sessions: List<SleepSession>,
    zone: ZoneId,
) {
    // Confirmed sessions only: this is the data the user trusts and the part written to
    // Health Connect, so the rhythm should not be skewed by unconfirmed detections.
    val confirmed = sessions.filter {
        !it.dismissed && it.source == SleepSessionSource.USER
    }
    val days = (0..6).map { offset -> selectedDate.minusDays(offset.toLong()) }
    val mainsByDay = days.associateWith { day ->
        confirmed
            .filter {
                sleepDayKey(it.startEpochSecond, zone) == day &&
                    it.kind == SleepSessionKind.MAIN
            }
            .maxByOrNull { it.durationSeconds }
    }

    val bedtimeOffsets = mainsByDay.values.filterNotNull().map { main ->
        (main.startEpochSecond - sleepDayStart(
            sleepDayKey(main.startEpochSecond, zone),
            zone,
        )) / 60.0
    }
    val avgDuration = mainsByDay.values.filterNotNull()
        .takeIf { it.isNotEmpty() }
        ?.map { it.durationSeconds }
        ?.average()
    val bedtimeSpread = bedtimeOffsets
        .takeIf { it.size >= 2 }
        ?.let { offsets ->
            val mean = offsets.average()
            sqrt(offsets.sumOf { (it - mean) * (it - mean) } / offsets.size)
        }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Confirmed sessions · last 7 sleep days",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
            )

            Spacer(Modifier.height(8.dp))
            if (avgDuration == null) {
                Text(
                    "Confirm a few sleep sessions to see your average duration and bedtime " +
                        "consistency.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary,
                )
            } else {
                Row(
                    modifier = Modifier.height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RhythmStat(
                        label = "Avg main sleep",
                        value = formatSleepDuration(avgDuration.toLong()),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                    RhythmStat(
                        label = "Bedtime deviation",
                        value = bedtimeSpread?.let { "±${Math.round(it)}m" } ?: "—",
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                    RhythmStat(
                        label = "Nights",
                        value = "${mainsByDay.values.count { it != null }}/7",
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Date",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextTertiary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "Main",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextTertiary,
                    modifier = Modifier.width(128.dp),
                )
                Text(
                    "Nap",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextTertiary,
                    modifier = Modifier.width(100.dp),
                )
            }
            days.forEach { day ->
                val daySessions = confirmed.filter {
                    sleepDayKey(it.startEpochSecond, zone) == day
                }
                val main = mainsByDay[day]
                val napSeconds = daySessions
                    .filter { it.kind == SleepSessionKind.NAP }
                    .sumOf { it.durationSeconds }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                ) {
                    Text(
                        formatShortDate(day),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = main?.let { formatSleepDuration(it.durationSeconds) } ?: "—",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (main == null) TextTertiary else TextSecondary,
                        modifier = Modifier.width(128.dp),
                    )
                    Text(
                        text = if (napSeconds > 0) {
                            formatSleepDuration(napSeconds)
                        } else {
                            "—"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (napSeconds > 0) AccentPurple else TextTertiary,
                        modifier = Modifier.width(100.dp),
                    )
                }
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SleepSessionEditorDialog(
    title: String,
    initialStart: Long,
    initialEnd: Long,
    onSave: (Long, Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    val startLocal = remember(initialStart, zone) {
        Instant.ofEpochSecond(initialStart).atZone(zone)
    }
    val endLocal = remember(initialEnd, zone) {
        Instant.ofEpochSecond(initialEnd).atZone(zone)
    }
    val startState = rememberTimePickerState(
        initialHour = startLocal.hour,
        initialMinute = startLocal.minute,
        is24Hour = true,
    )
    val endState = rememberTimePickerState(
        initialHour = endLocal.hour,
        initialMinute = endLocal.minute,
        is24Hour = true,
    )
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text("Start", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
                TimeInput(state = startState)
                Spacer(Modifier.height(12.dp))
                Text("End", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
                TimeInput(state = endState)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Times are kept within the same sleep day (16:00 to 16:00).",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary,
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = StatusError)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // Anchor on the sleep day (16:00 to 16:00) rather than the start's
                    // calendar date. Otherwise moving a 22:00 start to 02:00 would move the
                    // session to the previous morning instead of later the same night.
                    val anchor = sleepDayKey(initialStart, zone)
                    val start = epochInSleepDay(anchor, startState.hour, startState.minute, zone)
                    var end = epochInSleepDay(anchor, endState.hour, endState.minute, zone)
                    if (end <= start) end += 86_400L
                    if (end - start < 15 * 60) {
                        error = "A session must be at least 15 minutes long."
                    } else {
                        onSave(start, end)
                    }
                },
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

private data class TimelineTempPoint(
    val epochSecond: Long,
    val celsius: Double,
)

private data class TimelineStepBar(
    val startEpochSecond: Long,
    val endEpochSecond: Long,
    val steps: Int,
)

private fun buildTemperaturePoints(
    records: List<HistoryEntry>,
    windowStart: Long,
    windowEnd: Long,
    zone: ZoneId,
): List<TimelineTempPoint> {
    val buckets = HashMap<Long, MutableList<Double>>()
    records.forEach { record ->
        if (record.value < MIN_VALID_TEMPERATURE) return@forEach
        if (record.watchTimestampStart < windowStart || record.watchTimestampStart >= windowEnd) {
            return@forEach
        }
        val local = Instant.ofEpochSecond(record.watchTimestampStart).atZone(zone)
        val bucketStart = local
            .withMinute((local.minute / 15) * 15)
            .withSecond(0)
            .withNano(0)
            .toEpochSecond()
        buckets.getOrPut(bucketStart) { mutableListOf() } += record.value / 100.0
    }
    return buckets.mapNotNull { (epoch, values) ->
        values.medianOrNull()?.let { TimelineTempPoint(epoch, it) }
    }.sortedBy { it.epochSecond }
}

private fun buildStepBars(
    intervals: List<StepInterval>,
    windowStart: Long,
    windowEnd: Long,
): List<TimelineStepBar> =
    intervals
        .filter { it.tEnd > windowStart && it.tStart < windowEnd }
        .map {
            TimelineStepBar(
                startEpochSecond = max(it.tStart, windowStart),
                endEpochSecond = min(it.tEnd, windowEnd),
                steps = it.steps,
            )
        }
        .sortedBy { it.startEpochSecond }

private fun formatSleepDayLabel(date: LocalDate, zone: ZoneId): String {
    val formatter = DateTimeFormatter.ofPattern("EEE, MMM d HH:mm", Locale.getDefault())
    val start = Instant.ofEpochSecond(sleepDayStart(date, zone)).atZone(zone)
    val end = start.plusDays(1)
    return "Sleep day · ${formatter.format(start)} → ${formatter.format(end)}"
}

private fun formatShortDate(date: LocalDate): String =
    DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault()).format(date)

