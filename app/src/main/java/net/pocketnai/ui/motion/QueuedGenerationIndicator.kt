package net.pocketnai.ui.motion

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Three quiet satellites wait their turn along an elliptical track. No bitmap/effect layers. */
@Composable
internal fun QueuedGenerationIndicator(modifier: Modifier = Modifier, phaseOverride: Float? = null) {
    val phase = phaseOverride ?: rememberInfiniteTransition(label = "queued-image").animateFloat(
        0f, 1f, infiniteRepeatable(tween(3200, easing = LinearEasing)), label = "waiting-satellites").value
    val ink = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val unit = size.minDimension
        val center = Offset(size.width / 2, size.height / 2)
        drawOval(ink.copy(alpha = 0.16f), topLeft = Offset(center.x - unit * .36f, center.y - unit * .18f),
            size = androidx.compose.ui.geometry.Size(unit * .72f, unit * .36f), style = Stroke(unit * .018f))
        repeat(3) { index ->
            val angle = 2 * PI * (phase - index * .16f)
            val breathe = (.75 + .25 * sin(angle)).toFloat()
            drawCircle(ink.copy(alpha = .35f + .6f * breathe), radius = unit * (.045f + .018f * breathe),
                center = Offset(center.x + unit * .36f * cos(angle).toFloat(), center.y + unit * .18f * sin(angle).toFloat()))
        }
    }
}
