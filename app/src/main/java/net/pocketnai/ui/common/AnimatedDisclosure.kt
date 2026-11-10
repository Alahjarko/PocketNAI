package net.pocketnai.ui.common

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** Bounded local expansion; never animate the size of an entire scrolling page. */
@Composable
internal fun AnimatedDisclosure(visible: Boolean, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    AnimatedVisibility(visible, modifier, enter = expandVertically(tween(200), expandFrom = Alignment.Top) + fadeIn(tween(130)),
        exit = shrinkVertically(tween(180), shrinkTowards = Alignment.Top) + fadeOut(tween(100))) {
        Column(content = content)
    }
}
