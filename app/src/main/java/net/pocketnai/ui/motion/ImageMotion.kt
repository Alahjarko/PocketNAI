package net.pocketnai.ui.motion

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import coil.request.ImageRequest
import java.io.File

data class ImageReturnRequest(val source: String, val imageId: String, val sequence: Int)
data class ImageOriginPreview(val imageId: String, val file: File, val aspectRatio: Float, val title: String)

@Stable
class ImageMotionState {
    var source: String? by mutableStateOf(null)
    var currentImageId: String? by mutableStateOf(null)
        private set
    var preview: ImageOriginPreview? by mutableStateOf(null)
    var returning: ImageReturnRequest? by mutableStateOf(null)
        private set
    private var sequence = 0
    private var closing = false

    fun open(source: String, imageId: String) {
        if (preview?.imageId != imageId) preview = null
        this.source = source
        currentImageId = imageId
        returning = null
        closing = false
    }
    fun selectImage(imageId: String) { if (!closing) currentImageId = imageId }
    fun returnToSource() {
        val origin = source ?: return
        val id = currentImageId ?: return
        closing = true
        returning = ImageReturnRequest(origin, id, ++sequence)
    }
    fun consume(request: ImageReturnRequest) {
        if (returning == request) returning = null
    }
}

val LocalImageMotion = staticCompositionLocalOf<ImageMotionState?> { null }
@OptIn(ExperimentalSharedTransitionApi::class)
private val LocalSharedImages = staticCompositionLocalOf<SharedTransitionScope?> { null }
private val LocalImageVisibility = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }
private val LocalImageSource = staticCompositionLocalOf<String?> { null }
private val LocalChromeViewport = staticCompositionLocalOf<Rect?> { null }

/** Overlay controls must obey the same content viewport as their normal drawing. */
@Composable
fun ImageMotionViewport(modifier: Modifier, content: @Composable () -> Unit) {
    var bounds by remember { mutableStateOf<Rect?>(null) }
    Box(modifier.clipToBounds().onGloballyPositioned { bounds = it.boundsInRoot() }) {
        CompositionLocalProvider(LocalChromeViewport provides bounds) { content() }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun ImageMotionRoot(content: @Composable () -> Unit) {
    val state = rememberSaveable(saver = listSaver<ImageMotionState, String>(
        save = { listOf(it.source.orEmpty(), it.currentImageId.orEmpty()) },
        restore = { saved -> ImageMotionState().apply {
            source = saved[0].ifEmpty { null }
            saved[1].takeIf { it.isNotEmpty() }?.let { selectImage(it) }
        } },
    )) { ImageMotionState() }
    SharedTransitionLayout {
        CompositionLocalProvider(LocalImageMotion provides state, LocalSharedImages provides this) { content() }
    }
}

@Composable
fun ImageMotionRoute(scope: AnimatedVisibilityScope, source: String, content: @Composable () -> Unit) {
    val motion = LocalImageMotion.current
    val returning = motion?.returning
    val settled = !scope.transition.isRunning && scope.transition.currentState == EnterExitState.Visible &&
        scope.transition.targetState == EnterExitState.Visible
    // Missing/deleted/filtered sources also finish cleanly, allowing later gallery rearrangements.
    LaunchedEffect(settled, returning, source) {
        if (settled && returning?.source == source) motion?.consume(returning)
    }
    CompositionLocalProvider(LocalImageVisibility provides scope, LocalImageSource provides source) { content() }
}

private fun imageEnterAlpha() = keyframes<Float> {
    durationMillis = 300
    0f at 0
    0f at 60 using FastOutSlowInEasing
    1f at 260
    1f at 300
}

private fun imageExitAlpha() = keyframes<Float> {
    durationMillis = 300
    1f at 0 using FastOutSlowInEasing
    0f at 110
    0f at 300
}

fun imagePageEnter() = fadeIn(imageEnterAlpha())
fun imagePageExit() = fadeOut(imageExitAlpha())

@Composable
fun imageRouteIsMoving(): Boolean = LocalImageVisibility.current?.transition?.let {
    it.currentState != it.targetState
} ?: false

/** Foreground controls keep their z-order above the travelling picture, with the page fade. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.imageMotionChrome(zIndex: Float = 1f): Modifier {
    val shared = LocalSharedImages.current ?: return this
    val visibility = LocalImageVisibility.current ?: return this
    val viewport = LocalChromeViewport.current
    // Register before navigation starts. Adding this animation only after a match restarts
    // the controls' fade late and leaves them floating over an already faded page.
    val opacity = visibility.transition.animateFloat(
        transitionSpec = { if (targetState == EnterExitState.Visible) imageEnterAlpha() else imageExitAlpha() },
        label = "image-chrome",
    ) { if (it == EnterExitState.Visible) 1f else 0f }
    return with(shared) {
        this@imageMotionChrome.renderInSharedTransitionScopeOverlay(
            renderInOverlay = { isTransitionActive }, zIndexInOverlay = zIndex,
            clipInOverlayDuringTransition = { _, _ -> viewport?.let { Path().apply { addRect(it) } } },
        ).graphicsLayer { alpha = if (shared.isTransitionActive) opacity.value else 1f }
    }
}

/** Keep the visible thumbnail while the destination decodes its larger image. */
@Composable
fun sharedImageRequest(file: File): ImageRequest {
    val context = LocalContext.current
    return remember(context, file) {
        ImageRequest.Builder(context).data(file)
            .memoryCacheKey(file.absolutePath)
            .placeholderMemoryCacheKey(file.absolutePath)
            .build()
    }
}

/** 共享的是图片身份；当前位置和边界由布局实时测量，不持久化屏幕坐标。 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedImage(imageId: String): Modifier {
    // Pager neighbours must never match their thumbnails and fly alongside the selected image.
    if (LocalImageMotion.current?.currentImageId != imageId) return this
    val shared = LocalSharedImages.current ?: return this
    val visibility = LocalImageVisibility.current ?: return this
    val source = LocalImageSource.current ?: return this
    return with(shared) {
        this@sharedImage.testTag("shared-image:$source:$imageId").sharedElement(
            state = rememberSharedContentState("$source:$imageId"),
            animatedVisibilityScope = visibility,
            boundsTransform = { _, _ -> tween(300, easing = FastOutSlowInEasing) },
            clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(12.dp)),
        )
    }
}
