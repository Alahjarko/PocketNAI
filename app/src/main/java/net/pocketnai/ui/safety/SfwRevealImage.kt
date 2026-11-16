package net.pocketnai.ui.safety

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import net.pocketnai.ui.motion.sharedImageRequest
import java.io.File

private const val REVEAL_FADE_MS = 220

/**
 * 仅供详情页揭开使用：揭开方向让原图在模糊小图上方淡入，原图解码期间模糊图垫底，
 * 不出现空白帧；重新遮罩方向立即移除原图节点（不淡出），保持原有“绝不残留原图”规则。
 * 画廊等列表不走此路径，不增加额外布局节点。
 */
@Composable
internal fun SfwRevealImage(
    file: File, contentDescription: String?, modifier: Modifier, contentScale: ContentScale,
    sensitive: Boolean?, revealed: Boolean, masked: Boolean,
    onSuccess: (AsyncImagePainter.State.Success) -> Unit, onError: (AsyncImagePainter.State.Error) -> Unit,
) {
    val path = file.absolutePath
    val blurRequest = sharedImageRequest(file, sensitive, revealed = false)
    val clearRequest = sharedImageRequest(file, sensitive, revealed)
    val reveal = remember(path) { Animatable(if (masked) 0f else 1f) }
    var loaded by remember(path, masked) { mutableStateOf(false) }
    LaunchedEffect(path, masked, loaded) {
        if (masked) reveal.snapTo(0f)
        else if (loaded && reveal.value < 1f) reveal.animateTo(1f, tween(REVEAL_FADE_MS, easing = FastOutSlowInEasing))
    }
    val underlay by remember(reveal) { derivedStateOf { reveal.value < 1f } }
    Box(modifier) {
        if (masked || underlay) key(path, true) {
            AsyncImage(model = blurRequest, contentDescription = if (masked) contentDescription else null,
                modifier = Modifier.matchParentSize(), contentScale = contentScale,
                onSuccess = { if (masked) onSuccess(it) }, onError = { if (masked) onError(it) })
        }
        if (!masked) key(path, false) {
            AsyncImage(model = clearRequest, contentDescription = contentDescription,
                modifier = Modifier.matchParentSize().graphicsLayer { alpha = reveal.value }, contentScale = contentScale,
                onSuccess = { loaded = true; onSuccess(it) }, onError = { loaded = true; onError(it) })
        }
    }
}
