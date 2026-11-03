package net.pocketnai.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*
import net.pocketnai.domain.model.ThemeMode
import kotlin.math.hypot
import kotlin.math.max

internal val LocalThemeReveal = staticCompositionLocalOf<ThemeRevealState?> { null }

/** Layoutlib can supply its last rendered frame; phones always capture the recorded GPU layer. */
internal val LocalThemeSnapshotter = staticCompositionLocalOf<suspend (GraphicsLayer) -> ImageBitmap> {
    { it.toImageBitmap() }
}

/** Only draw/layer code observes the animation. The real palette is committed once. */
@Stable
internal class ThemeRevealState(private val scope: CoroutineScope) {
    private class Capture(val commit: () -> Unit) {
        val recorded = CompletableDeferred<Unit>()
        var committed = false
        var failed = false
        fun commitOnce() { if (!committed) { committed = true; commit() } }
    }
    private class Overlay(val image: ImageBitmap, val origin: Offset, val size: IntSize,
        val oldStatus: Boolean, val oldNavigation: Boolean)

    private val origins = mutableMapOf<ThemeMode, Offset>()
    private var capture by mutableStateOf<Capture?>(null)
    private var overlay by mutableStateOf<Overlay?>(null)
    private val progress = Animatable(0f)
    private val hole = Path()
    private var job: Job? = null
    private var generation = 0
    private var viewport = IntSize.Zero
    private var rootOffset = Offset.Zero
    private var dark = false
    private var systemDark = false
    private var lightBars = true
    private var statusLight = true
    private var navigationLight = true
    private var targetDark: Boolean? = null
    private var paletteReady: CompletableDeferred<Unit>? = null
    private var clearLayer = false
    var applyBars: ((Boolean, Boolean) -> Unit)? = null
    var statusProbe = Offset.Zero
    var navigationProbe = Offset.Zero

    fun register(mode: ThemeMode, centerInRoot: Offset) { origins[mode] = centerInRoot }
    fun center(mode: ThemeMode): Offset? = origins[mode]?.minus(rootOffset)
    fun hasPendingChange(): Boolean = capture != null

    fun viewport(size: IntSize, offset: Offset) {
        if (viewport != IntSize.Zero && (viewport != size || rootOffset != offset)) cancelVisual()
        viewport = size
        rootOffset = offset
    }

    fun palette(dark: Boolean, systemDark: Boolean, lightBars: Boolean) {
        if (targetDark != null && paletteReady?.isCompleted == true && targetDark != dark) cancelVisual()
        this.dark = dark
        this.systemDark = systemDark
        this.lightBars = lightBars
        if (targetDark == dark) paletteReady?.complete(Unit)
        if (overlay == null) setBars(lightBars, lightBars)
    }

    fun select(mode: ThemeMode, commit: () -> Unit) {
        val nextDark = when (mode) {
            ThemeMode.SYSTEM -> systemDark
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }
        if (nextDark == dark) {
            // During capture a reversal supersedes the uncommitted preference too.
            if (capture != null) cancelVisual(commitPending = false)
            commit()
            return
        }
        val center = center(mode)
        if (center == null || viewport == IntSize.Zero ||
            scope.coroutineContext[MotionDurationScale]?.scaleFactor == 0f) {
            cancelVisual(commitPending = false)
            commit()
            return
        }
        val token = ++generation
        job?.cancel()
        val request = Capture(commit)
        capture = request
        job = scope.launch {
            try {
                // A missing/stopped renderer must never delay saving the preference indefinitely.
                val image = withTimeoutOrNull(250) {
                    request.recorded.await()
                    yield() // toImageBitmap must run after GraphicsLayer.record has returned.
                    snapshotter?.invoke()
                }
                if (image == null) { request.commitOnce(); return@launch }
                overlay = Overlay(image, center, viewport, statusLight, navigationLight)
                progress.snapTo(0f)
                capture = null
                clearLayer = true
                targetDark = nextDark
                val ready = CompletableDeferred<Unit>()
                paletteReady = ready
                request.commitOnce()
                // Do not reveal an old-palette frame while the preference reaches composition.
                if (withTimeoutOrNull(250) { ready.await(); true } != true) return@launch
                progress.animateTo(1f, tween(400, easing = FastOutSlowInEasing))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Capture is cosmetic. Unsupported renderers still switch and save the theme.
                request.commitOnce()
            } catch (_: OutOfMemoryError) {
                request.commitOnce()
            } finally {
                if (token == generation) {
                    request.commitOnce()
                    clear()
                }
            }
        }
    }

    internal var snapshotter: (suspend () -> ImageBitmap)? = null

    fun cancelVisual(commitPending: Boolean = true) {
        ++generation
        job?.cancel()
        job = null
        if (commitPending) capture?.commitOnce()
        clear()
    }

    private fun clear() {
        job = null
        capture = null
        overlay = null
        targetDark = null
        paletteReady = null
        clearLayer = true
        setBars(lightBars, lightBars)
    }

    private fun setBars(status: Boolean, navigation: Boolean) {
        if (status != statusLight || navigation != navigationLight) {
            statusLight = status
            navigationLight = navigation
            applyBars?.invoke(status, navigation)
        }
    }

    fun draw(scope: ContentDrawScope, layer: GraphicsLayer) = with(scope) {
        val request = capture
        if (request != null) {
            if (!request.recorded.isCompleted) {
                try {
                    layer.record { this@with.drawComposite() }
                    request.recorded.complete(Unit)
                } catch (failure: Exception) {
                    request.failed = true
                    request.recorded.completeExceptionally(failure)
                } catch (failure: OutOfMemoryError) {
                    request.failed = true
                    request.recorded.completeExceptionally(failure)
                }
            }
            if (request.failed) drawComposite() else drawLayer(layer)
        } else {
            if (clearLayer) {
                layer.record { } // Release references to the captured page's child layers.
                clearLayer = false
            }
            drawComposite()
        }
    }

    private fun ContentDrawScope.drawComposite() {
        drawContent()
        val old = overlay ?: return
        val radius = revealRadius(old.origin, old.size) * progress.value
        hole.reset()
        hole.addOval(Rect(old.origin.x - radius, old.origin.y - radius,
            old.origin.x + radius, old.origin.y + radius))
        clipPath(hole, ClipOp.Difference) { drawImage(old.image) }
        fun covered(probe: Offset) = (probe - old.origin).getDistance() <= radius
        setBars(if (covered(statusProbe)) lightBars else old.oldStatus,
            if (covered(navigationProbe)) lightBars else old.oldNavigation)
    }
}

internal fun revealRadius(origin: Offset, size: IntSize): Float =
    hypot(max(origin.x, size.width - origin.x), max(origin.y, size.height - origin.y))

@Composable
internal fun ThemeRevealHost(state: ThemeRevealState, content: @Composable () -> Unit) {
    val layer = rememberGraphicsLayer()
    val snapshotter = LocalThemeSnapshotter.current
    SideEffect { state.snapshotter = { snapshotter(layer) } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(state, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) state.cancelVisual()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            state.cancelVisual()
            state.snapshotter = null
        }
    }
    CompositionLocalProvider(LocalThemeReveal provides state) {
        Box(Modifier.fillMaxSize().onGloballyPositioned {
            state.viewport(it.size, it.boundsInRoot().topLeft)
        }.drawWithContent { state.draw(this, layer) }) { content() }
    }
}
