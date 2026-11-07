package ua.madeisy.iot.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import ua.madeisy.iot.data.Sample
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/** Графік потужності за період [fromTs]..[toTs]. Розриви довші за 2 хв не з'єднуються. */
@Composable
fun PowerChart(samples: List<Sample>, fromTs: Long, toTs: Long, modifier: Modifier = Modifier) {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    val maxP = max(10.0, samples.maxOfOrNull { it.power } ?: 0.0) * 1.1
    val fmt = SimpleDateFormat(if (toTs - fromTs > 12 * 3600_000L) "HH:mm" else "HH:mm", Locale.getDefault())

    Column(modifier) {
        Box(Modifier.fillMaxWidth().height(170.dp)) {
            if (samples.size < 2) {
                Text(
                    "Даних ще мало — графік з'явиться, поки додаток відкритий або увімкнено фоновий моніторинг",
                    style = MaterialTheme.typography.bodySmall, color = label,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            Canvas(Modifier.fillMaxWidth().height(170.dp)) {
                val w = size.width
                val h = size.height
                val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 8f))
                for (i in 0..3) {
                    val y = h * i / 3f
                    drawLine(grid, Offset(0f, y), Offset(w, y), strokeWidth = 1f, pathEffect = dash)
                }
                if (samples.size < 2) return@Canvas
                val span = (toTs - fromTs).coerceAtLeast(1).toFloat()
                fun x(ts: Long) = ((ts - fromTs) / span * w).coerceIn(0f, w)
                fun y(p: Double) = (h - (p / maxP * h)).toFloat()

                val stroke = Path()
                val fill = Path()
                var segStart = -1f
                var prev: Sample? = null
                for (s in samples) {
                    val px = x(s.ts); val py = y(s.power)
                    val p = prev
                    if (p == null || s.ts - p.ts > 120_000) {
                        if (p != null && segStart >= 0) {
                            fill.lineTo(x(p.ts), h); fill.lineTo(segStart, h); fill.close()
                        }
                        stroke.moveTo(px, py)
                        fill.moveTo(px, h); fill.lineTo(px, py)
                        segStart = px
                    } else {
                        stroke.lineTo(px, py)
                        fill.lineTo(px, py)
                    }
                    prev = s
                }
                prev?.let { fill.lineTo(x(it.ts), h); fill.lineTo(segStart, h); fill.close() }
                drawPath(fill, Brush.verticalGradient(listOf(line.copy(alpha = 0.35f), line.copy(alpha = 0.02f))))
                drawPath(stroke, line, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            Text(
                formatPower(maxP / 1.1), style = MaterialTheme.typography.labelSmall, color = label,
                modifier = Modifier.align(Alignment.TopStart)
            )
        }
        Row(Modifier.fillMaxWidth()) {
            Text(fmt.format(Date(fromTs)), style = MaterialTheme.typography.labelSmall, color = label)
            Box(Modifier.weight(1f))
            Text("зараз", style = MaterialTheme.typography.labelSmall, color = label)
        }
    }
}
