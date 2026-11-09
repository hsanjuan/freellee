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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import link.hector.freellee.ui.theme.AccentAmber
import link.hector.freellee.ui.theme.AccentPurple
import link.hector.freellee.ui.theme.AccentTeal
import link.hector.freellee.ui.theme.TextSecondary
import link.hector.freellee.ui.theme.TextTertiary
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val TEMPERATURE_Y_AXIS_WIDTH = 44.dp

/**
 * Line chart of hourly skin-temperature averages. The whole stored range is drawn into a
 * horizontally scrollable canvas, so dragging scrolls continuously (with fling).
 */
@Composable
fun TemperatureLineChart(
    data: TemperatureChartData,
    modifier: Modifier = Modifier,
) {
    val zone = remember { ZoneId.systemDefault() }
    val points = data.points
    val hourlyAverages = data.hourlyAverages
    val baselines = data.baselines
    val windowStart = data.windowStartEpochSecond
    val endHour = data.endHourEpochSecond
    val totalHours = data.totalHours

    val allValues = buildList {
        points.mapNotNullTo(this) { it.averageCelsius }
        hourlyAverages.mapNotNullTo(this) { it.averageCelsius }
        baselines.mapTo(this) { it.celsius }
    }
    val ticks = temperatureAxisTicks(allValues)
    val valueMin = ticks.first()
    val valueMax = ticks.last()

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
    val measuredColor = AccentAmber
    val baselineColor = AccentTeal
    val averageColor = AccentPurple.copy(alpha = 0.85f)
    val timeFormatter = remember(zone) {
        DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()).withZone(zone)
    }
    val xTickLabels = remember(points, labelStyle, textMeasurer, timeFormatter, zone) {
        points.mapNotNull { point ->
            val localHour = Instant.ofEpochSecond(point.startEpochSecond).atZone(zone).hour
            if (localHour % 6 != 0) return@mapNotNull null
            point.startEpochSecond to textMeasurer.measure(
                timeFormatter.format(Instant.ofEpochSecond(point.startEpochSecond)),
                labelStyle,
            )
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "Skin temperature line chart"
            },
    ) {
        val viewportWidthPx = with(density) {
            (maxWidth - TEMPERATURE_Y_AXIS_WIDTH).toPx()
        }.coerceAtLeast(1f)
        val hourWidthPx = viewportWidthPx / TEMPERATURE_WINDOW_HOURS
        val fullWidthPx = hourWidthPx * totalHours
        val fullWidth = with(density) { fullWidthPx.toDp() }

        Column(modifier = Modifier.fillMaxWidth()) {
            TemperaturePeriodLabel(
                scrollState = scrollState,
                fullWidthPx = fullWidthPx,
                viewportWidthPx = viewportWidthPx,
                windowStart = windowStart,
                totalHours = totalHours,
                zone = zone,
            )
            Spacer(Modifier.height(4.dp))

            Row(modifier = Modifier.fillMaxWidth().height(184.dp)) {
                ChartYAxis(
                    ticks = ticks,
                    valueMin = valueMin,
                    valueMax = valueMax,
                    labelFor = { "%.1f°".format(Locale.getDefault(), it) },
                    width = TEMPERATURE_Y_AXIS_WIDTH,
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
                            val spanSeconds = (endHour - windowStart).toFloat().coerceAtLeast(1f)
                            val axisRange = (valueMax - valueMin).coerceAtLeast(0.0001)

                            fun xFor(epochSecond: Long): Float =
                                left + ((epochSecond - windowStart) / spanSeconds) * (right - left)

                            fun yFor(value: Double): Float =
                                bottom - ((value - valueMin) / axisRange * plotHeight).toFloat()

                            ticks.forEach { value ->
                                val y = yFor(value)
                                drawLine(
                                    color = gridColor,
                                    start = Offset(left, y),
                                    end = Offset(right, y),
                                    strokeWidth = 1.dp.toPx(),
                                )
                            }

                            xTickLabels.forEach { (epochSecond, labelLayout) ->
                                val x = xFor(epochSecond)
                                drawLine(
                                    color = gridColor,
                                    start = Offset(x, top),
                                    end = Offset(x, bottom),
                                    strokeWidth = 1.dp.toPx(),
                                )
                                drawText(
                                    textLayoutResult = labelLayout,
                                    topLeft = Offset(
                                        x = (x - labelLayout.size.width / 2f)
                                            .coerceIn(0f, size.width - labelLayout.size.width),
                                        y = bottom + 4.dp.toPx(),
                                    ),
                                )
                            }

                            val dashEffect = PathEffect.dashPathEffect(
                                floatArrayOf(6.dp.toPx(), 5.dp.toPx()),
                            )

                            var previousBaseline: BaselineSegment? = null
                            baselines.forEach { segment ->
                                val xStart = xFor(segment.startEpochSecond)
                                val xEnd = xFor(segment.endEpochSecond)
                                val y = yFor(segment.celsius)
                                previousBaseline?.let { previous ->
                                    drawLine(
                                        color = baselineColor.copy(alpha = 0.75f),
                                        start = Offset(xStart, yFor(previous.celsius)),
                                        end = Offset(xStart, y),
                                        strokeWidth = 1.5.dp.toPx(),
                                        pathEffect = dashEffect,
                                    )
                                }
                                drawLine(
                                    color = baselineColor.copy(alpha = 0.75f),
                                    start = Offset(xStart, y),
                                    end = Offset(xEnd, y),
                                    strokeWidth = 1.5.dp.toPx(),
                                    pathEffect = dashEffect,
                                )
                                previousBaseline = segment
                            }

                            val averagePath = Path()
                            var averageDrawing = false
                            hourlyAverages.forEach { point ->
                                val average = point.averageCelsius
                                if (average == null) {
                                    averageDrawing = false
                                } else {
                                    val x = xFor((point.startEpochSecond + point.endEpochSecond) / 2)
                                    val y = yFor(average)
                                    if (averageDrawing) {
                                        averagePath.lineTo(x, y)
                                    } else {
                                        averagePath.moveTo(x, y)
                                        averageDrawing = true
                                    }
                                }
                            }
                            drawPath(
                                path = averagePath,
                                color = averageColor,
                                style = Stroke(
                                    width = 1.75.dp.toPx(),
                                    cap = StrokeCap.Round,
                                    join = StrokeJoin.Round,
                                ),
                            )

                            val path = Path()
                            var drawing = false
                            points.forEach { point ->
                                val average = point.averageCelsius
                                if (average == null) {
                                    drawing = false
                                } else {
                                    val x = xFor((point.startEpochSecond + point.endEpochSecond) / 2)
                                    val y = yFor(average)
                                    if (drawing) {
                                        path.lineTo(x, y)
                                    } else {
                                        path.moveTo(x, y)
                                        drawing = true
                                    }
                                }
                            }
                            drawPath(
                                path = path,
                                color = measuredColor,
                                style = Stroke(
                                    width = 2.5.dp.toPx(),
                                    cap = StrokeCap.Round,
                                    join = StrokeJoin.Round,
                                ),
                            )
                            points.forEach { point ->
                                val average = point.averageCelsius ?: return@forEach
                                drawCircle(
                                    color = measuredColor,
                                    radius = 2.5.dp.toPx(),
                                    center = Offset(
                                        xFor((point.startEpochSecond + point.endEpochSecond) / 2),
                                        yFor(average),
                                    ),
                                )
                            }
                        }
                    }

                    if (
                        points.all { it.averageCelsius == null } &&
                        hourlyAverages.all { it.averageCelsius == null } &&
                        baselines.isEmpty()
                    ) {
                        Text(
                            text = "No temperature readings yet",
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
                    add(LegendEntry("Measured", measuredColor))
                    if (baselines.isNotEmpty()) {
                        add(LegendEntry("Baseline", baselineColor, dashed = true))
                    }
                    if (hourlyAverages.any { it.averageCelsius != null }) {
                        add(LegendEntry("Hourly average", averageColor))
                    }
                },
            )
        }
    }
}

@Composable
private fun TemperaturePeriodLabel(
    scrollState: ScrollState,
    fullWidthPx: Float,
    viewportWidthPx: Float,
    windowStart: Long,
    totalHours: Int,
    zone: ZoneId,
) {
    val label by remember(
        scrollState,
        fullWidthPx,
        viewportWidthPx,
        windowStart,
        totalHours,
        zone,
    ) {
        derivedStateOf {
            if (fullWidthPx <= 0f || totalHours <= 0) {
                ""
            } else {
                val startHours = (
                    (scrollState.value / fullWidthPx) * totalHours
                    ).roundToInt().coerceIn(0, totalHours)
                val endHours = (
                    ((scrollState.value + viewportWidthPx) / fullWidthPx) * totalHours
                    ).roundToInt().coerceIn(0, totalHours)
                val start = shiftHours(windowStart, startHours.toLong(), zone)
                val end = shiftHours(windowStart, endHours.toLong(), zone)
                formatTemperaturePeriod(start, end, zone)
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

private fun formatTemperaturePeriod(startEpochSecond: Long, endEpochSecond: Long, zone: ZoneId): String {
    val formatter = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.getDefault()).withZone(zone)
    return "${formatter.format(Instant.ofEpochSecond(startEpochSecond))} – " +
        formatter.format(Instant.ofEpochSecond(endEpochSecond))
}
