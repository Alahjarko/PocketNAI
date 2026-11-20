package net.pocketnai.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 与 GenerationOrb（生成中）、QueuedGenerationIndicator（排队中）同族的对话状态动效：
 * 生图阶段展示流动光核，思考/检索/回复阶段展示柔光轨道与连续正弦呼吸星点 + 扫光文字。
 */
@Composable
internal fun ChatReplyIndicator(label: String) {
    val transition = rememberInfiniteTransition(label = "reply-activity")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing)),
        label = "thinking-orbit",
    )
    val shimmer by transition.animateFloat(
        initialValue = -0.4f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing)),
        label = "thinking-shimmer",
    )
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val isGenerating = label.startsWith("正在生成")

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        if (isGenerating) {
            net.pocketnai.ui.motion.GenerationOrb(Modifier.size(28.dp), description = null)
        } else {
            ThinkingWaveOrb(
                phase = phase,
                primary = primary,
                accent = tertiary,
                modifier = Modifier.size(30.dp, 24.dp),
            )
        }
        AnimatedContent(
            targetState = label,
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
            label = "reply-phase-label",
        ) { currentLabel ->
            val highlightText = !isGenerating && (currentLabel.startsWith("正在思考") || currentLabel.startsWith("正在搜索"))
            val style = if (highlightText) {
                val mid = shimmer.coerceIn(0f, 1f)
                val peak = lerp(muted, primary, (1f - kotlin.math.abs(shimmer - mid) / 0.28f).coerceIn(0f, 1f))
                MaterialTheme.typography.labelMedium.copy(
                    brush = Brush.linearGradient(
                        0f to muted,
                        (shimmer - 0.28f).coerceIn(0f, 1f) to muted,
                        mid to peak,
                        (shimmer + 0.28f).coerceIn(0f, 1f) to muted,
                        1f to muted,
                    ),
                )
            } else {
                MaterialTheme.typography.labelMedium.copy(color = muted)
            }
            Text(currentLabel, style = style)
        }
    }
}

@Composable
private fun ThinkingWaveOrb(
    phase: Float,
    primary: Color,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.graphicsLayer()) {
        val t = (phase * 2.0 * PI).toFloat()
        val cx = size.width / 2f
        val cy = size.height / 2f
        val minDim = size.minDimension

        // 柔和呼吸底晕与轻倾椭圆轨迹，与 GenerationOrb / QueuedGenerationIndicator 保持同一视觉语言
        val auraScale = 1f + 0.08f * sin(t)
        drawCircle(
            color = primary.copy(alpha = 0.11f),
            radius = minDim * 0.48f * auraScale,
            center = Offset(cx, cy),
        )
        rotate(degrees = -14f, pivot = Offset(cx, cy)) {
            drawOval(
                color = primary.copy(alpha = 0.16f),
                topLeft = Offset(cx - size.width * 0.44f, cy - minDim * 0.22f),
                size = androidx.compose.ui.geometry.Size(size.width * 0.88f, minDim * 0.44f),
                style = Stroke(width = 1.dp.toPx()),
            )
        }

        // 三颗连续正弦波浪与呼吸光点（C∞ 平滑循环，无取模跳变）
        val spacing = size.width * 0.28f
        val ampY = minDim * 0.17f
        repeat(3) { index ->
            val wavePhase = t - index * 0.95f
            val lift = sin(wavePhase)
            val breathe = 0.5f + 0.5f * cos(wavePhase)
            val tint = lerp(primary, accent, (index / 2f) * 0.45f)
            val radius = (2.1f + 1.05f * breathe).dp.toPx()
            val alpha = 0.46f + 0.54f * breathe
            val x = cx + (index - 1) * spacing
            val y = cy - lift * ampY
            drawCircle(
                color = tint.copy(alpha = alpha * 0.24f),
                radius = radius * 1.55f,
                center = Offset(x, y),
            )
            drawCircle(
                color = tint.copy(alpha = alpha),
                radius = radius,
                center = Offset(x, y),
            )
        }
    }
}
