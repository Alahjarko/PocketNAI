package net.pocketnai.ui.gallery

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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

/** Observe only the visibility threshold, never recompose the gallery on every scroll pixel. */
@Composable
internal fun GalleryScrollToTop(state: LazyStaggeredGridState, modifier: Modifier = Modifier) {
    val visible by remember(state) { derivedStateOf { state.firstVisibleItemIndex > 0 } }
    val scope = rememberCoroutineScope()
    var returning by remember { mutableStateOf(false) }
    AnimatedVisibility(visible, modifier.imageMotionChrome(), enter = fadeIn(), exit = fadeOut()) {
        SmallFloatingActionButton(onClick = {
            if (!returning) scope.launch {
                returning = true
                try {
                    // Bound the animation work even in a gallery with thousands of images.
                    if (state.firstVisibleItemIndex > 20) state.scrollToItem(20)
                    state.animateScrollToItem(0)
                } finally {
                    returning = false
                }
            }
        }, modifier = Modifier.testTag("gallery-scroll-to-top")) {
            Icon(Icons.Default.VerticalAlignTop, contentDescription = "返回顶部")
        }
    }
}
