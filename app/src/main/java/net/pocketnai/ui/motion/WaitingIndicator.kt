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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos

/** 无百分比的呼吸节奏；只表达等待，不预测服务端进度。 */
@Composable
internal fun WaitingIndicator(label: String, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "等待呼吸")
    val phase by transition.animateFloat(0f, 1f,
        infiniteRepeatable(tween(1_400, easing = LinearEasing)), label = "等待节奏")
    val color = MaterialTheme.colorScheme.primary
    Row(modifier.semantics(mergeDescendants = true) { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Canvas(Modifier.width(28.dp).height(12.dp)) {
            repeat(3) { index ->
                val pulse = ((1 - cos((phase - index / 6f) * 2 * PI)) / 2).toFloat()
                drawCircle(color.copy(alpha = 0.3f + pulse * 0.7f), 2.dp.toPx(),
                    Offset((4 + index * 10).dp.toPx(), size.height / 2))
            }
        }
    }
}
