package net.pocketnai.ui.motion

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.*

/** 数值是真实额度，波形只表现流动，不改变所代表的比例。 */
@Composable
internal fun WavyQuotaBar(progress: Float?, modifier: Modifier = Modifier, animate: Boolean = true, phaseOverride: Float? = null) {
    val target = progress?.coerceIn(0f, 1f) ?: 0f
    val fraction by animateFloatAsState(target, tween(300), label = "真实额度比例")
    val transition = rememberInfiniteTransition(label = "额度波形")
    val phase by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(2100, easing = LinearEasing)), label = "波形相位")
    val active = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.height(20.dp).semantics {
        if (progress != null) progressBarRangeInfo = ProgressBarRangeInfo(target, 0f..1f)
    }) {
        val stroke = 3.dp.toPx()
        val radius = stroke / 2
        val start = radius
        val end = (size.width - radius).coerceAtLeast(start)
        val y = size.height / 2
        val fill = start + (end - start) * fraction
        if (fraction < 1f) drawLine(track, Offset(if (fraction > 0) (fill + 6.dp.toPx()).coerceAtMost(end) else start, y),
            Offset(end, y), stroke, StrokeCap.Round)
        if (fraction > 0f) {
            val path = Path()
            val length = fill - start
            val segments = (length / 2.dp.toPx()).roundToInt().coerceAtLeast(2)
            for (index in 0..segments) {
                val x = length * index / segments
                val taper = min(x / 7.dp.toPx(), (length - x) / 7.dp.toPx()).coerceIn(0f, 1f)
                val wave = sin(x / 26.dp.toPx() * 2 * PI - (phaseOverride ?: if (animate) phase else 0f) * 2 * PI).toFloat()
                val point = Offset(start + x, y + wave * 2.6.dp.toPx() * taper)
                if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
            }
            drawPath(path, active, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        drawCircle(if (fraction >= 1f) active else track, radius, Offset(end, y))
    }
}
