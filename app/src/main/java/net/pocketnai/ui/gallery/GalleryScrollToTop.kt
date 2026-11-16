package net.pocketnai.ui.gallery

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material3.Icon
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.launch
import net.pocketnai.ui.motion.imageMotionChrome
import net.pocketnai.ui.motion.pressFeedback
import net.pocketnai.ui.motion.rememberPocketHaptics

/** Observe only the visibility threshold, never recompose the gallery on every scroll pixel. */
@Composable
internal fun GalleryScrollToTop(state: LazyStaggeredGridState, modifier: Modifier = Modifier) {
    val visible by remember(state) { derivedStateOf { state.firstVisibleItemIndex > 0 } }
    val scope = rememberCoroutineScope()
    val haptics = rememberPocketHaptics()
    var returning by remember { mutableStateOf(false) }
    AnimatedVisibility(
        visible = visible,
        modifier = modifier.imageMotionChrome(),
        enter = fadeIn(tween(140)) + scaleIn(
            spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 650f),
            initialScale = 0.82f,
        ),
        exit = fadeOut(tween(110)) + scaleOut(tween(110), targetScale = 0.85f),
    ) {
        val interactionSource = remember { MutableInteractionSource() }
        SmallFloatingActionButton(
            onClick = {
                if (!returning) {
                    haptics.tick()
                    scope.launch {
                        returning = true
                        try {
                            // Bound the animation work even in a gallery with thousands of images.
                            if (state.firstVisibleItemIndex > 20) state.scrollToItem(20)
                            state.animateScrollToItem(0)
                        } finally {
                            returning = false
                        }
                    }
                }
            },
            interactionSource = interactionSource,
            modifier = Modifier
                .pressFeedback(interactionSource, pressedScale = 0.94f)
                .testTag("gallery-scroll-to-top"),
        ) {
            Icon(Icons.Default.VerticalAlignTop, contentDescription = "返回顶部")
        }
    }
}
