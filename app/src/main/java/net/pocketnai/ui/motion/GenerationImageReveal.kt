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
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private object OpaqueState : State<Float> {
    override val value: Float get() = 1f
}

private class ImageRevealAnimation {
    val completion = Animatable(0f)
    val imageAlpha = Animatable(0f)
    val indicatorAlpha = Animatable(1f)
    val fullCheckPath = Path()
    val segmentCheckPath = Path()
    val pathMeasure = PathMeasure()
    var cachedRadius = -1f
}

/** NAI、OpenAI 与抽卡共用的无限等待；仅图片解码成功后显示对勾并原位揭图。 */
@Composable
internal fun GenerationImageReveal(
    ready: Boolean,
    failed: Boolean,
    animate: Boolean,
    startedAt: Long,
    onRevealed: () -> Unit,
    modifier: Modifier = Modifier,
    queued: Boolean = false,
    showLabel: Boolean = true,
    indicatorSize: Dp = 64.dp,
    content: @Composable BoxScope.(State<Float>) -> Unit,
) {
    // Most cards are already finished: do not start effects or allocate animations/states
    // for every historical thumbnail entering the viewport. Keep content at one call site.
    val animation = if (animate) remember { ImageRevealAnimation() } else null
    if (animation != null) {
        val onFinished by rememberUpdatedState(onRevealed)
        LaunchedEffect(ready, failed, animation) {
            when {
                failed -> animation.indicatorAlpha.snapTo(0f)
                ready -> {
                    coroutineScope {
                        launch { animation.imageAlpha.animateTo(1f, tween(240, easing = FastOutSlowInEasing)) }
                        launch {
                            animation.completion.animateTo(1f, tween(130, easing = FastOutSlowInEasing))
                            animation.indicatorAlpha.animateTo(0f, tween(110))
                        }
                    }
                    onFinished()
                }
            }
        }
    }
    Box(modifier) {
        content(animation?.imageAlpha?.asState() ?: OpaqueState)
        if (animation != null) {
            val completion = animation.completion
            val indicatorAlpha = animation.indicatorAlpha
            val indicatorVisible by remember { derivedStateOf { indicatorAlpha.value > 0f } }
            val showOrb by remember { derivedStateOf { completion.value < 1f } }
            val showCheck by remember { derivedStateOf { completion.value > 0f } }
            if (!failed && indicatorVisible) {
                Box(Modifier.size(indicatorSize).align(Alignment.Center)
                    .graphicsLayer { alpha = indicatorAlpha.value }
                    .semantics { contentDescription = if (ready) "图片已就绪" else if (queued) "排队中" else "生成中" }) {
                    if (showOrb) {
                        val indicator = Modifier.fillMaxSize().graphicsLayer { alpha = 1f - completion.value }
                        if (queued) QueuedGenerationIndicator(indicator) else GenerationOrb(indicator, description = null)
                    }
                    if (showCheck) {
                        val ink = MaterialTheme.colorScheme.primary
                        Canvas(Modifier.fillMaxSize().graphicsLayer {
                            val s = 0.88f + 0.12f * completion.value
                            scaleX = s
                            scaleY = s
                        }) {
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val radius = size.minDimension * 0.31f
                            if (animation.cachedRadius != radius) {
                                animation.cachedRadius = radius
                                animation.fullCheckPath.reset()
                                animation.fullCheckPath.moveTo(center.x - radius * 0.56f, center.y)
                                animation.fullCheckPath.lineTo(center.x - radius * 0.12f, center.y + radius * 0.43f)
                                animation.fullCheckPath.lineTo(center.x + radius * 0.64f, center.y - radius * 0.45f)
                                animation.pathMeasure.setPath(animation.fullCheckPath, false)
                            }
                            animation.segmentCheckPath.reset()
                            animation.pathMeasure.getSegment(
                                0f,
                                animation.pathMeasure.length * completion.value,
                                animation.segmentCheckPath,
                                true,
                            )
                            drawPath(
                                animation.segmentCheckPath,
                                ink,
                                style = Stroke(4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                            )
                        }
                    }
                }
                if (showLabel && !ready) Text(if (queued) "排队中" else "生成中", style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp))
            }
        }
    }
}
