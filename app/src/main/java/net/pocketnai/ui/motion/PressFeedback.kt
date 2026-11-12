package net.pocketnai.ui.motion

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/** Observe the control's own press stream: no extra gesture detector, click or hit target. */
@Composable
internal fun Modifier.pressFeedback(source: InteractionSource, enabled: Boolean = true, pressedScale: Float = .97f): Modifier {
    val pressed by source.collectIsPressedAsState()
    val scale = animateFloatAsState(
        if (pressed && enabled) pressedScale else 1f,
        spring(dampingRatio = 1f, stiffness = 1100f), label = "control-press",
    )
    return graphicsLayer { scaleX = scale.value; scaleY = scale.value }
}
