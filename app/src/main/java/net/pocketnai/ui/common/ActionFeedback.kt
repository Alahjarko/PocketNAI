package net.pocketnai.ui.common

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
internal fun ActionStatusIcon(busy: Boolean, done: Boolean) {
    // Fixed width prevents the label from moving when an operation completes.
    Box(Modifier.size(18.dp)) {
        AnimatedContent(if (busy) 1 else if (done) 2 else 0, label = "action-status",
            transitionSpec = { fadeIn(tween(120)) togetherWith fadeOut(tween(80)) }) { status ->
            when (status) {
                1 -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                2 -> Icon(Icons.Default.Check, null, Modifier.size(18.dp))
            }
        }
    }
}
