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
import androidx.compose.ui.graphics.graphicsLayer
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
    val phase = if (phaseOverride != null) rememberUpdatedState(phaseOverride) else
        rememberInfiniteTransition(label = "流动光核").animateFloat(
            0f, 1f, infiniteRepeatable(tween(4200, easing = LinearEasing)), label = "光核周期")
    val path = remember { Path() }
    val pointsX = remember { FloatArray(96) }
    val pointsY = remember { FloatArray(96) }
    Canvas(modifier.size(52.dp).graphicsLayer().semantics { description?.let { contentDescription = it } }) {
        val t = phase.value * (2 * PI).toFloat()
        val radius = size.minDimension * 0.32f
        val center = Offset(size.width / 2, size.height / 2)
        val haloRadius = radius * (1.38f + 0.05f * sin(t))
        drawCircle(color.copy(alpha = 0.08f), haloRadius, center)
        repeat(96) { index ->
            val angle = index * 2 * PI.toFloat() / 96
            val modulation = 1f + 0.16f * (0.5f + 0.5f * sin(t)) * sin(3 * angle + t) + 0.055f * cos(2 * angle - 2 * t)
            val x = cos(angle) * radius * modulation * (1f + 0.07f * sin(t))
            val y = sin(angle) * radius * modulation * (1f - 0.07f * sin(t))
            pointsX[index] = center.x + x
            pointsY[index] = center.y + y
        }
        path.reset()
        path.apply {
            moveTo(pointsX[0], pointsY[0])
            for (index in pointsX.indices) {
                val previous = (index + 95) % 96
                val next = (index + 1) % 96
                val after = (index + 2) % 96
                cubicTo(pointsX[index] + (pointsX[next] - pointsX[previous]) / 6,
                    pointsY[index] + (pointsY[next] - pointsY[previous]) / 6,
                    pointsX[next] - (pointsX[after] - pointsX[index]) / 6,
                    pointsY[next] - (pointsY[after] - pointsY[index]) / 6, pointsX[next], pointsY[next])
            }
            close()
        }
        drawPath(path, color)
        rotate(360f * phase.value, center) {
            drawOval(Color.White.copy(alpha = 0.22f),
                topLeft = center + Offset(-radius * 0.58f, -radius * 0.6f),
                size = androidx.compose.ui.geometry.Size(radius * 0.45f, radius * 0.19f))
        }
    }
}
