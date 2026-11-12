package link.hector.freellee.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import link.hector.freellee.ui.theme.TextTertiary
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** Vertical padding used by every chart plot, shared so the fixed Y axis aligns with it. */
internal val ChartTopPadding = 8.dp
internal val ChartBottomPadding = 22.dp

/** One entry in the small legend under a chart. */
internal data class LegendEntry(
    val label: String,
    val color: Color,
    val dashed: Boolean = false,
    /** Draw a filled block (a shaded region) instead of a line. */
    val band: Boolean = false,
    /** Draw vertical stripes inside the block (used for regions, not measured data). */
    val hatched: Boolean = false,
)

/** Rounds [rawStep] up to a visually pleasant 1/2/5 × 10ⁿ value. */
internal fun niceStep(rawStep: Double): Double {
    if (!rawStep.isFinite() || rawStep <= 0.0) return 1.0
    val magnitude = 10.0.pow(floor(log10(rawStep)))
    val normalized = rawStep / magnitude
    val nice = when {
        normalized <= 1.0 -> 1.0
        normalized <= 2.0 -> 2.0
        normalized <= 5.0 -> 5.0
        else -> 10.0
    }
    return nice * magnitude
}

/** Step values from zero to a nice upper bound that covers [maxValue]. */
internal fun stepsAxisTicks(maxValue: Double, targetIntervals: Int = 4): List<Double> {
    if (maxValue <= 0.0) return listOf(0.0)
    val step = niceStep(maxValue / targetIntervals)
    val axisMax = ceil(maxValue / step) * step
    return generateSequence(0.0) { it + step }
        .takeWhile { it <= axisMax + step / 1000.0 }
        .toList()
}

/** A rounded axis range that covers [values], with a little breathing room. */
internal fun temperatureAxisTicks(values: List<Double>, targetIntervals: Int = 4): List<Double> {
    if (values.isEmpty()) return listOf(0.0, 1.0)
    var minimum = values.min()
    var maximum = values.max()
    if (maximum - minimum < 0.6) {
        val middle = (minimum + maximum) / 2.0
        minimum = middle - 0.3
        maximum = middle + 0.3
    }
    val padding = (maximum - minimum) * 0.1
    val step = niceStep((maximum - minimum + padding * 2) / targetIntervals)
    val axisMin = floor((minimum - padding) / step) * step
    val axisMax = ceil((maximum + padding) / step) * step
    return generateSequence(axisMin) { it + step }
        .takeWhile { it <= axisMax + step / 1000.0 }
        .toList()
}

/**
 * Fixed Y-axis labels drawn beside the scrollable plot. The plot uses the same
 * [ChartTopPadding]/[ChartBottomPadding], so labels line up with its grid lines.
 */
@Composable
internal fun ChartYAxis(
    ticks: List<Double>,
    valueMin: Double,
    valueMax: Double,
    labelFor: (Double) -> String,
    width: Dp,
    modifier: Modifier = Modifier,
) {
    val labelStyle = MaterialTheme.typography.labelSmall.copy(
        color = TextTertiary,
        fontSize = 10.sp,
    )
    val textMeasurer = rememberTextMeasurer()
    val labelLayouts = remember(ticks, labelStyle, textMeasurer) {
        ticks.map { textMeasurer.measure(labelFor(it), labelStyle) }
    }

    Canvas(modifier = modifier.width(width).fillMaxHeight()) {
        val top = ChartTopPadding.toPx()
        val bottom = size.height - ChartBottomPadding.toPx()
        val plotHeight = (bottom - top).coerceAtLeast(0f)
        val range = (valueMax - valueMin).coerceAtLeast(0.0001)

        ticks.forEachIndexed { index, value ->
            val y = bottom - ((value - valueMin) / range * plotHeight).toFloat()
            val labelLayout = labelLayouts[index]
            drawText(
                textLayoutResult = labelLayout,
                topLeft = Offset(
                    x = (size.width - labelLayout.size.width - 6.dp.toPx()).coerceAtLeast(0f),
                    y = y - labelLayout.size.height / 2f,
                ),
            )
        }
    }
}

@Composable
internal fun ChartLegend(
    entries: List<LegendEntry>,
    modifier: Modifier = Modifier,
) {
    // Wrap instead of clipping: the temperature chart can show up to four entries, which does
    // not fit on a narrow screen.
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        entries.forEach { entry ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(modifier = Modifier.size(width = 18.dp, height = 8.dp)) {
                    val y = size.height / 2f
                    if (entry.band) {
                        if (entry.hatched) {
                            drawRect(color = entry.color.copy(alpha = 0.18f))
                            var x = 1.dp.toPx()
                            while (x < size.width) {
                                drawLine(
                                    color = entry.color.copy(alpha = 0.75f),
                                    start = Offset(x, 0f),
                                    end = Offset(x, size.height),
                                    strokeWidth = 1.dp.toPx(),
                                )
                                x += 4.dp.toPx()
                            }
                        } else {
                            drawRect(color = entry.color)
                        }
                    } else if (entry.dashed) {
                        drawLine(
                            color = entry.color,
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = 2.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(
                                floatArrayOf(4.dp.toPx(), 3.dp.toPx()),
                            ),
                        )
                    } else {
                        drawLine(
                            color = entry.color,
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = 2.dp.toPx(),
                            cap = StrokeCap.Round,
                        )
                        drawCircle(
                            color = entry.color,
                            radius = 3.dp.toPx(),
                            center = Offset(size.width / 2f, y),
                        )
                    }
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    text = entry.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextTertiary,
                )
            }
        }
    }
}
