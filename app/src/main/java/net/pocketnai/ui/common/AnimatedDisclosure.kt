package net.pocketnai.ui.common

import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
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

/** Layer-only chevron rotation: zero layout or icon-swap jank, zero idle frames when settled. */
@Composable
internal fun ExpandingChevron(
    expanded: Boolean,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = 700f,
        ),
        label = "disclosure-chevron",
    )
    Icon(
        imageVector = Icons.Default.ExpandMore,
        contentDescription = contentDescription,
        modifier = modifier.graphicsLayer { rotationZ = rotation },
    )
}
