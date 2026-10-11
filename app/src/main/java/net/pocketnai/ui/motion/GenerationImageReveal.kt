package net.pocketnai.ui.motion

import android.os.SystemClock
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.*

/** 画廊与抽卡共用的等待、完成对勾和原位揭图；仅在图片解码成功后完成。 */
@Composable
internal fun GenerationImageReveal(
    ready: Boolean,
    failed: Boolean,
    animate: Boolean,
    startedAt: Long,
    onRevealed: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.(State<Float>) -> Unit,
) {
    val onFinished by rememberUpdatedState(onRevealed)
    val requestAge = remember(startedAt) {
        (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
    }
    val progress = remember { Animatable(0.93f * (1f - exp(-requestAge / 12_000f))) }
    val morph = remember { Animatable(0f) }
    val imageAlpha = remember { Animatable(if (animate) 0f else 1f) }
    val indicatorAlpha = remember { Animatable(if (animate) 1f else 0f) }
    val start = remember { SystemClock.elapsedRealtime() - requestAge }

    LaunchedEffect(ready, failed, animate) {
        when {
            !animate -> { imageAlpha.snapTo(1f); indicatorAlpha.snapTo(0f) }
            failed -> indicatorAlpha.snapTo(0f)
            ready -> {
                progress.animateTo(1f, tween(160, easing = FastOutSlowInEasing))
                morph.animateTo(1f, tween(150, easing = FastOutSlowInEasing))
                delay(70)
                imageAlpha.animateTo(1f, tween(220, easing = FastOutSlowInEasing))
                indicatorAlpha.animateTo(0f, tween(100))
                onFinished()
            }
            else -> while (true) {
                val frameStart = SystemClock.elapsedRealtime()
                val elapsed = (SystemClock.elapsedRealtime() - start) / 1000f
                val target = 0.93f * (1f - exp(-elapsed / 12f))
                progress.animateTo(max(progress.value, target), tween(200, easing = LinearEasing))
                // No pause between normal segments; throttle when animations are disabled.
                val minimumWait = 50L - (SystemClock.elapsedRealtime() - frameStart)
                if (minimumWait > 0) delay(minimumWait)
            }
        }
    }
    Box(modifier) {
        content(imageAlpha.asState())
        if (animate && !failed) {
            val ink = MaterialTheme.colorScheme.primary
            Canvas(Modifier.size(60.dp).align(Alignment.Center)
                .graphicsLayer { alpha = indicatorAlpha.value }
                .semantics { contentDescription = if (ready) "图片已就绪" else "生成中" }) {
                val stroke = 5.dp.toPx()
                val radius = (size.minDimension - stroke) / 2f
                val center = Offset(size.width / 2f, size.height / 2f)
                val blend = morph.value
                drawCircle(ink.copy(alpha = 0.12f * (1f - blend)), radius, center, style = Stroke(stroke))
                if (blend == 0f) {
                    drawArc(ink, -90f, max(2f, progress.value * 360f), false,
                        topLeft = center - Offset(radius, radius), size = Size(radius * 2, radius * 2),
                        style = Stroke(stroke, cap = StrokeCap.Round))
                } else {
                    val path = Path()
                    val a = center + Offset(-radius * 0.58f, 0f)
                    val b = center + Offset(-radius * 0.12f, radius * 0.43f)
                    val c = center + Offset(radius * 0.64f, -radius * 0.45f)
                    for (i in 0..48) {
                        val t = i / 48f
                        val angle = (-90f + 360f * t) * PI.toFloat() / 180f
                        val circle = center + Offset(cos(angle) * radius, sin(angle) * radius)
                        val check = if (t < 0.4f) a + (b - a) * (t / 0.4f) else b + (c - b) * ((t - 0.4f) / 0.6f)
                        val point = circle + (check - circle) * blend
                        if (i == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
                    }
                    drawPath(path, ink, style = Stroke(stroke, cap = StrokeCap.Round))
                }
            }
            if (!ready) Text("生成中", style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp))
        }
    }
}
