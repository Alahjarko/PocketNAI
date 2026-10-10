package net.pocketnai.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import net.pocketnai.ui.motion.imageMotionChrome

@Composable
internal fun BoxScope.ChatJumpToLatest(visible: Boolean, onClick: () -> Unit) {
    AnimatedVisibility(visible, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 12.dp),
        enter = fadeIn(tween(140)) + scaleIn(tween(140), initialScale = 0.9f),
        exit = fadeOut(tween(100)) + scaleOut(tween(100), targetScale = 0.9f)) {
        Surface(onClick = onClick, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 3.dp, modifier = Modifier.size(40.dp).testTag("chat-follow-latest").imageMotionChrome(zIndex = 2f)) {
            androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.KeyboardArrowDown, "回到最新", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
            }
        }
    }
}
