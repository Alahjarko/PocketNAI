package net.pocketnai.ui.chat

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp

@Composable
internal fun ChatReplyIndicator(label: String) {
    val transition = rememberInfiniteTransition(label = "reply-activity")
    val phase by transition.animateFloat(0f, 3f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "activity-dots")
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Canvas(Modifier.size(28.dp, 18.dp)) {
            repeat(3) { index ->
                val distance = ((phase - index + 3f) % 3f)
                val alpha = (1f - distance / 3f).coerceIn(0.25f, 1f)
                drawCircle(color.copy(alpha = alpha), radius = 2.5.dp.toPx(), center = Offset((4 + index * 10).dp.toPx(), size.height / 2))
            }
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}
