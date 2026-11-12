package net.pocketnai.ui.common

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Keep one accessible label while the two visual icon frames overlap. */
@Composable
internal fun FeedbackIcon(icon: ImageVector, description: String, tint: Color) {
    Box(Modifier.size(24.dp).semantics { contentDescription = description }) {
        AnimatedContent(icon, label = "action-icon", transitionSpec = {
            (fadeIn(tween(140)) + scaleIn(tween(180), initialScale = .8f)) togetherWith
                (fadeOut(tween(90)) + scaleOut(tween(90), targetScale = .85f))
        }) { displayed -> Icon(displayed, null, tint = tint) }
    }
}

@Composable
internal fun ActionStatusIcon(busy: Boolean, done: Boolean, idleIcon: ImageVector? = null) {
    // Fixed width prevents the label from moving when an operation completes.
    Box(Modifier.size(18.dp)) {
        AnimatedContent(if (busy) 1 else if (done) 2 else 0, label = "action-status",
            transitionSpec = { fadeIn(tween(120)) togetherWith fadeOut(tween(80)) }) { status ->
            when (status) {
                0 -> idleIcon?.let { Icon(it, null, Modifier.size(18.dp)) }
                1 -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                2 -> Icon(Icons.Default.Check, null, Modifier.size(18.dp))
            }
        }
    }
}

/** Large text gets full-width actions, rather than reflowing the page on success. */
@Composable
internal fun ActionPair(first: @Composable (Modifier) -> Unit, second: @Composable (Modifier) -> Unit) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (fontScale > 1.15f || maxWidth < 320.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                first(Modifier.fillMaxWidth())
                second(Modifier.fillMaxWidth())
            }
        } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            first(Modifier.weight(1f))
            second(Modifier.weight(1f))
        }
    }
}
