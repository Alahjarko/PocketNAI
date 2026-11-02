package net.pocketnai.ui.motion

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** NAI、OpenAI 与抽卡共用的无限等待；仅图片解码成功后显示对勾并原位揭图。 */
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
    val completion = remember { Animatable(0f) }
    val imageAlpha = remember { Animatable(if (animate) 0f else 1f) }
    val indicatorAlpha = remember { Animatable(if (animate) 1f else 0f) }
    LaunchedEffect(ready, failed, animate) {
        when {
            !animate -> { imageAlpha.snapTo(1f); indicatorAlpha.snapTo(0f) }
            failed -> indicatorAlpha.snapTo(0f)
            ready -> {
                completion.animateTo(1f, tween(180, easing = FastOutSlowInEasing))
                delay(50)
                imageAlpha.animateTo(1f, tween(220, easing = FastOutSlowInEasing))
                indicatorAlpha.animateTo(0f, tween(100))
                onFinished()
            }
        }
    }
    Box(modifier) {
        content(imageAlpha.asState())
        if (animate && !failed && indicatorAlpha.value > 0f) {
            Box(Modifier.size(64.dp).align(Alignment.Center)
                .graphicsLayer { alpha = indicatorAlpha.value }
                .semantics { contentDescription = if (ready) "图片已就绪" else "生成中" }) {
                if (completion.value < 1f) GenerationOrb(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - completion.value }, description = null)
                if (completion.value > 0f) {
                    val ink = MaterialTheme.colorScheme.primary
                    Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = completion.value }) {
                        val center = Offset(size.width / 2, size.height / 2)
                        val radius = size.minDimension * 0.31f
                        val path = Path().apply {
                            moveTo(center.x - radius * 0.56f, center.y)
                            lineTo(center.x - radius * 0.12f, center.y + radius * 0.43f)
                            lineTo(center.x + radius * 0.64f, center.y - radius * 0.45f)
                        }
                        drawPath(path, ink, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
                    }
                }
            }
            if (!ready) Text("生成中", style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp))
        }
    }
}
