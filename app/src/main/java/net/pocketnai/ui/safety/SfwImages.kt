package net.pocketnai.ui.safety

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.size.Size
import coil.transform.Transformation
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import net.pocketnai.domain.safety.GaussianBlur
import net.pocketnai.ui.LocalAppContainer
import net.pocketnai.ui.motion.sharedImageRequest
import java.io.File
import kotlin.math.roundToInt
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

data class SfwDisplayState(val enabled: Boolean = false, val ready: Boolean = false,
    val paths: Set<String> = emptySet(), val imageIds: Set<String> = emptySet(), val knownPaths: Set<String> = emptySet()) {
    fun masks(file: File?, knownSensitive: Boolean? = null, revealed: Boolean = false): Boolean {
        if (!enabled || revealed || file == null) return false
        if (knownSensitive == true || file.absolutePath in paths) return true
        if (knownSensitive == false) return false
        return file.invariantSeparatorsPath.contains("/generations/") && (!ready || file.absolutePath !in knownPaths)
    }
}

val LocalSfwDisplay = compositionLocalOf { SfwDisplayState() }

@Composable
internal fun SfwDisplayProvider(content: @Composable () -> Unit) {
    val container = LocalAppContainer.current
    val enabled by container.settingsStore.sfwMode.collectAsStateWithLifecycle()
    val index = if (enabled) {
        val flow = remember(container) { container.generationRepository.observeImageSafety().map { items ->
            val sensitive = items.filter { it.second }.map { it.first }
            Triple(sensitive.map { container.fileStore.resolve(it.relativePath).absolutePath }.toSet(), sensitive.map { it.imageId }.toSet(),
                items.map { container.fileStore.resolve(it.first.relativePath).absolutePath }.toSet())
        }.flowOn(Dispatchers.Default) }
        val state by flow.collectAsStateWithLifecycle(initialValue = null)
        state
    } else null
    val state = remember(enabled, index) { SfwDisplayState(enabled, index != null, index?.first.orEmpty(), index?.second.orEmpty(), index?.third.orEmpty()) }
    CompositionLocalProvider(LocalSfwDisplay provides state, content = content)
}

/** 保留低分辨率遮罩，由绘制端缩放；不能再放大成随卡片/屏幕尺寸增长的纹理。 */
class StrongSfwBlur : Transformation {
    override val cacheKey = CACHE_KEY
    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val ratio = minOf(1f, WORK_SIZE.toFloat() / maxOf(input.width, input.height))
        val width = (input.width * ratio).roundToInt().coerceAtLeast(1)
        val height = (input.height * ratio).roundToInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(input, width, height, true)
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        val small = Bitmap.createBitmap(GaussianBlur.apply(pixels, width, height), width, height, Bitmap.Config.ARGB_8888)
        if (scaled !== input) scaled.recycle()
        return small
    }

    companion object {
        const val WORK_SIZE = 48
        const val CACHE_KEY = "pocketnai-sfw-gaussian-48-8-small-v2"
    }
}

/** 切换掩码时重新创建 painter，避免原图在模糊副本解码期间作为旧帧闪现。 */
@Composable
fun SfwAsyncImage(
    file: File?, contentDescription: String?, modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit, sensitive: Boolean? = null, revealed: Boolean = false,
    onSuccess: (AsyncImagePainter.State.Success) -> Unit = {}, onError: (AsyncImagePainter.State.Error) -> Unit = {},
    animate: Boolean = false,
) {
    val masked = LocalSfwDisplay.current.masks(file, sensitive, revealed)
    if (animate && !masked && file?.extension.equals("gif", ignoreCase = true)) {
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
        var visible by remember(file) { mutableStateOf(false) }
        DisposableEffect(lifecycle) {
            val observer = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }
        val playing = resumed && visible
        key(file?.absolutePath, playing) {
            AsyncImage(model = file?.let { sharedImageRequest(it, sensitive, revealed, playing) }, contentDescription = contentDescription,
                modifier = modifier.onGloballyPositioned { val bounds = it.boundsInWindow(); visible = bounds.width > 0 && bounds.height > 0 },
                contentScale = contentScale, onSuccess = onSuccess, onError = onError)
        }
    } else key(file?.absolutePath, masked) {
        AsyncImage(model = file?.let { sharedImageRequest(it, sensitive, revealed) }, contentDescription = contentDescription,
            modifier = modifier, contentScale = contentScale, onSuccess = onSuccess, onError = onError)
    }
}
