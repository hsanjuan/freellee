package link.hector.freellee.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import link.hector.freellee.data.DeviceStore.StepInterval
import link.hector.freellee.ui.theme.AccentGreen
import link.hector.freellee.ui.theme.TextSecondary
import link.hector.freellee.ui.theme.TextTertiary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

private const val STEP_DAYS = 7
private val STEP_Y_AXIS_WIDTH = 40.dp

/**
 * Bar chart of daily step totals. The whole stored range is drawn into a horizontally
 * scrollable canvas, so dragging scrolls continuously (with fling) and bars can be partially
 * visible at either edge.
 */
@Composable
fun StepsBarChart(
    intervals: List<StepInterval>,
    nowEpochSecond: Long,
    modifier: Modifier = Modifier,
) {
    val zone = remember { ZoneId.systemDefault() }
    val today = remember(nowEpochSecond, zone) {
        Instant.ofEpochSecond(nowEpochSecond).atZone(zone).toLocalDate()
    }
    val oldestDate = remember(intervals, zone) {
        intervals.minOfOrNull { Instant.ofEpochSecond(it.tStart).atZone(zone).toLocalDate() }
    }
    val totalDays = remember(oldestDate, today) {
        val oldest = oldestDate ?: today
        maxOf(ChronoUnit.DAYS.between(oldest, today).toInt() + 1, STEP_DAYS)
    }
    val days = remember(intervals, today, totalDays, zone) {
        dailyStepsWindow(intervals, today, totalDays, zone)
    }
    val reference = remember(intervals, nowEpochSecond) {
        rollingDailyAverageSteps(intervals, nowEpochSecond)
    }

    val maxValue = maxOf(days.maxOf { it.steps }.toDouble(), reference ?: 0.0)
    val ticks = stepsAxisTicks(maxValue)
    val axisMax = ticks.last().takeIf { it > 0.0 } ?: 1.0

    val scrollState = rememberScrollState()
    LaunchedEffect(scrollState.maxValue) {
        if (scrollState.maxValue > 0) {
            scrollState.scrollTo(scrollState.maxValue)
        }
    }

    val density = LocalDensity.current
    val labelStyle = MaterialTheme.typography.labelSmall.copy(
        color = TextTertiary,
        fontSize = 10.sp,
    )
    val textMeasurer = rememberTextMeasurer()
    val gridColor = TextTertiary.copy(alpha = 0.18f)
    val barColor = AccentGreen
    val referenceColor = TextSecondary

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "Daily steps bar chart, ${days.first().date} to ${days.last().date}"
            },
    ) {
        val viewportWidthPx = with(density) {
            (maxWidth - STEP_Y_AXIS_WIDTH).toPx()
        }.coerceAtLeast(1f)
        val slotWidthPx = viewportWidthPx / STEP_DAYS
        val fullWidth = with(density) {
            (slotWidthPx * days.size).toDp()
        }

        Column(modifier = Modifier.fillMaxWidth()) {
            StepsPeriodLabel(
                days = days,
                scrollState = scrollState,
                pixelsPerDay = slotWidthPx,
                viewportWidthPx = viewportWidthPx,
            )
            Spacer(Modifier.height(4.dp))

            Row(modifier = Modifier.fillMaxWidth().height(176.dp)) {
                ChartYAxis(
                    ticks = ticks,
                    valueMin = 0.0,
                    valueMax = axisMax,
                    labelFor = ::formatStepAxis,
                    width = STEP_Y_AXIS_WIDTH,
                    modifier = Modifier.fillMaxHeight(),
                )
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .horizontalScroll(scrollState),
                    ) {
                        Canvas(
                            modifier = Modifier
                                .width(fullWidth)
                                .fillMaxHeight(),
                        ) {
                            val left = 0f
                            val right = size.width
                            val top = ChartTopPadding.toPx()
                            val bottom = size.height - ChartBottomPadding.toPx()
                            val plotHeight = (bottom - top).coerceAtLeast(0f)
                            val slotWidth = if (days.isEmpty()) 0f else (right - left) / days.size

                            ticks.forEach { value ->
                                val y = bottom - (value / axisMax * plotHeight).toFloat()
                                drawLine(
                                    color = gridColor,
                                    start = Offset(left, y),
                                    end = Offset(right, y),
                                    strokeWidth = 1.dp.toPx(),
                                )
                            }

                            days.forEachIndexed { index, day ->
                                val slotStart = left + slotWidth * index
                                val centerX = slotStart + slotWidth / 2f
                                val labelLayout = textMeasurer.measure(
                                    day.date.dayOfMonth.toString(),
                                    labelStyle,
                                )
                                drawText(
                                    textLayoutResult = labelLayout,
                                    topLeft = Offset(
                                        centerX - labelLayout.size.width / 2f,
                                        bottom + 4.dp.toPx(),
                                    ),
                                )

                                if (day.steps <= 0) return@forEachIndexed
                                val barWidth = slotWidth * 0.55f
                                val barLeft = slotStart + (slotWidth - barWidth) / 2f
                                val barHeight = (day.steps / axisMax * plotHeight).toFloat()
                                val barTop = bottom - barHeight
                                val radius = min(4.dp.toPx(), barHeight / 2f)
                                val path = Path().apply {
                                    moveTo(barLeft, bottom)
                                    lineTo(barLeft, barTop + radius)
                                    quadraticTo(barLeft, barTop, barLeft + radius, barTop)
                                    lineTo(barLeft + barWidth - radius, barTop)
                                    quadraticTo(
                                        barLeft + barWidth,
                                        barTop,
                                        barLeft + barWidth,
                                        barTop + radius,
                                    )
                                    lineTo(barLeft + barWidth, bottom)
                                    close()
                                }
                                drawPath(path = path, color = barColor)
                            }

                            reference?.let { value ->
                                val y = bottom - (value / axisMax * plotHeight).toFloat()
                                drawLine(
                                    color = referenceColor,
                                    start = Offset(left, y),
                                    end = Offset(right, y),
                                    strokeWidth = 1.5.dp.toPx(),
                                    pathEffect = PathEffect.dashPathEffect(
                                        floatArrayOf(6.dp.toPx(), 5.dp.toPx()),
                                    ),
                                )
                            }
                        }
                    }

                    if (intervals.isEmpty()) {
                        Text(
                            text = "No step data yet",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextTertiary,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            ChartLegend(
                entries = buildList {
                    add(LegendEntry("Daily steps", barColor))
                    if (reference != null) {
                        add(LegendEntry("7-day average", referenceColor, dashed = true))
                    }
                },
            )
        }
    }
}

@Composable
private fun StepsPeriodLabel(
    days: List<DailyStepsPoint>,
    scrollState: ScrollState,
    pixelsPerDay: Float,
    viewportWidthPx: Float,
) {
    val label by remember(days, scrollState, pixelsPerDay, viewportWidthPx) {
        derivedStateOf {
            if (days.isEmpty() || pixelsPerDay <= 0f) {
                ""
            } else {
                val firstIndex = floor(scrollState.value / pixelsPerDay)
                    .toInt()
                    .coerceIn(0, days.lastIndex)
                val lastIndex = (
                    ceil((scrollState.value + viewportWidthPx) / pixelsPerDay).toInt() - 1
                    ).coerceIn(0, days.lastIndex)
                formatStepPeriod(days[firstIndex].date, days[lastIndex].date)
            }
        }
    }

    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = TextSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun formatStepPeriod(start: LocalDate, end: LocalDate): String {
    val formatter = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
    return if (start == end) {
        formatter.format(start)
    } else {
        "${formatter.format(start)} – ${formatter.format(end)}"
    }
}

private fun formatStepAxis(value: Double): String {
    if (value < 1_000.0) return value.roundToInt().toString()
    val thousands = value / 1_000.0
    return if (thousands == thousands.toInt().toDouble()) {
        "${thousands.toInt()}k"
    } else {
        "%.1fk".format(Locale.getDefault(), thousands)
    }
}
