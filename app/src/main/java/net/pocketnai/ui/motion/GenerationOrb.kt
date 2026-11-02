package net.pocketnai.ui.motion

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.*

/** 原创的流动光核：不对称椭球与内侧光弧缓慢变形，完整循环，无虚构进度。 */
@Composable
internal fun GenerationOrb(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    description: String? = "生成中",
    phaseOverride: Float? = null,
) {
    val transition = rememberInfiniteTransition(label = "流动光核")
    val phase by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(4200, easing = LinearEasing)), label = "光核周期")
    Canvas(modifier.size(52.dp).semantics { description?.let { contentDescription = it } }) {
        val t = (phaseOverride ?: phase) * (2 * PI).toFloat()
        val radius = size.minDimension * 0.32f
        val center = Offset(size.width / 2, size.height / 2)
        val haloRadius = radius * (1.38f + 0.05f * sin(t))
        drawCircle(color.copy(alpha = 0.08f), haloRadius, center)
        val points = List(96) { index ->
            val angle = index * 2 * PI.toFloat() / 96
            val modulation = 1f + 0.16f * (0.5f + 0.5f * sin(t)) * sin(3 * angle + t) + 0.055f * cos(2 * angle - 2 * t)
            val x = cos(angle) * radius * modulation * (1f + 0.07f * sin(t))
            val y = sin(angle) * radius * modulation * (1f - 0.07f * sin(t))
            Offset(center.x + x, center.y + y)
        }
        val path = Path().apply {
            moveTo(points[0].x, points[0].y)
            for (index in points.indices) {
                val p0 = points[(index - 1 + points.size) % points.size]
                val p1 = points[index]
                val p2 = points[(index + 1) % points.size]
                val p3 = points[(index + 2) % points.size]
                cubicTo(p1.x + (p2.x - p0.x) / 6, p1.y + (p2.y - p0.y) / 6,
                    p2.x - (p3.x - p1.x) / 6, p2.y - (p3.y - p1.y) / 6, p2.x, p2.y)
            }
            close()
        }
        drawPath(path, color)
        rotate(360f * (phaseOverride ?: phase), center) {
            drawOval(Color.White.copy(alpha = 0.22f),
                topLeft = center + Offset(-radius * 0.58f, -radius * 0.6f),
                size = androidx.compose.ui.geometry.Size(radius * 0.45f, radius * 0.19f))
        }
    }
}
