package link.hector.freellee.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import link.hector.freellee.data.BatteryPoint
import link.hector.freellee.ui.theme.AccentGreen
import link.hector.freellee.ui.theme.TextTertiary
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Compact battery trend for the device card. The Y range follows the data with a little padding
 * so a slow decline is still visible instead of being flattened against a fixed 0–100 % axis.
 */
@Composable
fun BatteryLineChart(
    points: List<BatteryPoint>,
    modifier: Modifier = Modifier,
) {
    val values = remember(points) { points.mapNotNull { it.percent } }
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = TextTertiary)
    if (values.isEmpty()) {
        Text(
            "Battery history appears after the next sync.",
            style = MaterialTheme.typography.bodySmall,
            color = TextTertiary,
            modifier = modifier,
        )
        return
    }

    val minValue = values.min()
    val maxValue = values.max()
    val padding = maxOf(2, (maxValue - minValue + 3) / 4)
    val yMin = (minValue - padding).coerceAtLeast(0)
    val yMax = (maxValue + padding).coerceAtMost(100)
    val range = (yMax - yMin).coerceAtLeast(1)
    val startLabel = remember(points) {
        DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()).format(points.first().date)
    }
    val gridColor = TextTertiary.copy(alpha = 0.18f)

    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Battery · last ${points.size} days",
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${values.last()}%",
                style = MaterialTheme.typography.labelSmall,
                color = AccentGreen,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(2.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .semantics { contentDescription = "Battery history, last ${points.size} days" },
        ) {
            val left = 0f
            val right = size.width
            val top = 0f
            val bottom = size.height
            val plotHeight = (bottom - top).coerceAtLeast(1f)
            val stepX = if (points.size > 1) (right - left) / (points.size - 1) else 0f

            drawLine(
                color = gridColor,
                start = Offset(left, bottom),
                end = Offset(right, bottom),
                strokeWidth = 1.dp.toPx(),
            )

            fun yFor(percent: Int): Float =
                bottom - ((percent - yMin).toFloat() / range * plotHeight)

            val path = Path()
            var drawing = false
            points.forEachIndexed { index, point ->
                val percent = point.percent
                if (percent == null) {
                    drawing = false
                    return@forEachIndexed
                }
                val x = left + stepX * index
                val y = yFor(percent)
                if (drawing) path.lineTo(x, y) else path.moveTo(x, y)
                drawing = true
            }
            drawPath(
                path = path,
                color = AccentGreen,
                style = Stroke(
                    width = 1.5.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )

            val lastIndex = points.indexOfLast { it.percent != null }
            if (lastIndex >= 0) {
                drawCircle(
                    color = AccentGreen,
                    radius = 2.5.dp.toPx(),
                    center = Offset(left + stepX * lastIndex, yFor(points[lastIndex].percent!!)),
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(startLabel, style = labelStyle)
            Spacer(Modifier.weight(1f))
            Text("Today", style = labelStyle)
        }
    }
}
