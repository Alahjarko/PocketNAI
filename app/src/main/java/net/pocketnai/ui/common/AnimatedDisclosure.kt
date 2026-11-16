package net.pocketnai.ui.common

import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize

/** Bounded local expansion; never animate the size of an entire scrolling page. */
@Composable
internal fun AnimatedDisclosure(visible: Boolean, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val sizeSpring = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
        visibilityThreshold = IntSize.VisibilityThreshold,
    )
    AnimatedVisibility(
        visible,
        modifier,
        enter = expandVertically(sizeSpring, expandFrom = Alignment.Top) + fadeIn(tween(120)),
        exit = shrinkVertically(sizeSpring, shrinkTowards = Alignment.Top) + fadeOut(tween(90)),
    ) {
        Column(content = content)
    }
}
