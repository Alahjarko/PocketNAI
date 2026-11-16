package net.pocketnai.ui.detail

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import net.pocketnai.ui.motion.rememberPocketHaptics

/**
 * 全屏图片查看：双指缩放、带惯性衰减的边界拖动、双击弹簧放大/复原、1x 纵向拖拽关闭。
 *
 * 交互对齐系统相册：
 * - 双击**围绕点按处**平滑弹簧放大（不是瞬间跳变），再次双击平滑归零；
 * - 放大后的拖动松手带物理衰减惯性（`splineBasedDecay`），并严格约束在图片 Fit 后的边缘内；
 * - 1x 状态下纵向拖拽实时带动图片位移、微缩与黑色背景淡出，松手超过阈值关闭，否则弹簧回位。
 */
@Composable
fun FullscreenImageViewer(
    imageFile: File,
    contentDescription: String?,
    imageWidth: Int,
    imageHeight: Int,
    onDismiss: () -> Unit,
    revealed: Boolean = false,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            // 铺满全屏（含系统栏区域），图片查看器不该在系统栏前留白。
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val scope = rememberCoroutineScope()
        val density = LocalDensity.current
        val decay = remember(density) { splineBasedDecay<Float>(density) }
        val haptics = rememberPocketHaptics()

        var containerSize by remember { mutableStateOf(Size.Zero) }
        val scaleAnim = remember { Animatable(MIN_SCALE) }
        val offsetXAnim = remember { Animatable(0f) }
        val offsetYAnim = remember { Animatable(0f) }
        val dismissYAnim = remember { Animatable(0f) }

        val imageAspect = if (imageHeight > 0) imageWidth.toFloat() / imageHeight else 1f
        val zoomSpring = remember {
            spring<Float>(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow,
            )
        }

        fun maxPanBounds(forScale: Float): Offset {
            val container = containerSize
            if (container.width <= 0f || container.height <= 0f) return Offset.Zero
            val containerAspect = container.width / container.height
            val display = if (imageAspect > containerAspect) {
                Size(container.width, container.width / imageAspect)
            } else {
                Size(container.height * imageAspect, container.height)
            }
            val maxX = ((display.width * forScale - container.width) / 2f).coerceAtLeast(0f)
            val maxY = ((display.height * forScale - container.height) / 2f).coerceAtLeast(0f)
            return Offset(maxX, maxY)
        }

        fun clamped(candidate: Offset, forScale: Float): Offset {
            val max = maxPanBounds(forScale)
            return Offset(
                candidate.x.coerceIn(-max.x, max.x),
                candidate.y.coerceIn(-max.y, max.y),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { containerSize = Size(it.width.toFloat(), it.height.toFloat()) }
                .drawBehind {
                    val maxDismiss = (size.height * 0.42f).coerceAtLeast(1f)
                    val dismissFraction = (abs(dismissYAnim.value) / maxDismiss).coerceIn(0f, 1f)
                    val alpha = (1f - dismissFraction * 0.85f).coerceIn(0.15f, 1f)
                    drawRect(Color.Black.copy(alpha = alpha))
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onDismiss() },
                        onDoubleTap = { tap ->
                            haptics.tick()
                            offsetXAnim.updateBounds(null, null)
                            offsetYAnim.updateBounds(null, null)
                            if (scaleAnim.value > MIN_SCALE + 0.05f) {
                                scope.launch {
                                    coroutineScope {
                                        launch { scaleAnim.animateTo(MIN_SCALE, zoomSpring) }
                                        launch { offsetXAnim.animateTo(0f, zoomSpring) }
                                        launch { offsetYAnim.animateTo(0f, zoomSpring) }
                                        launch { dismissYAnim.animateTo(0f, zoomSpring) }
                                    }
                                }
                            } else {
                                val center = Offset(containerSize.width / 2f, containerSize.height / 2f)
                                val delta = tap - center
                                val target = clamped(
                                    Offset(
                                        -delta.x * (DOUBLE_TAP_SCALE - 1f),
                                        -delta.y * (DOUBLE_TAP_SCALE - 1f),
                                    ),
                                    DOUBLE_TAP_SCALE,
                                )
                                scope.launch {
                                    coroutineScope {
                                        launch { scaleAnim.animateTo(DOUBLE_TAP_SCALE, zoomSpring) }
                                        launch { offsetXAnim.animateTo(target.x, zoomSpring) }
                                        launch { offsetYAnim.animateTo(target.y, zoomSpring) }
                                    }
                                }
                            }
                        },
                    )
                }
                .pointerInput(Unit) {
                    val touchSlop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        val velocityTracker = VelocityTracker()
                        var totalPan = Offset.Zero
                        var gestureMode = ViewerGestureMode.UNDETERMINED

                        do {
                            val event = awaitPointerEvent()
                            val canceled = event.changes.any { it.isConsumed }
                            if (canceled && gestureMode == ViewerGestureMode.UNDETERMINED) break

                            val pressedCount = event.changes.count { it.pressed }
                            if (pressedCount >= 2) {
                                if (gestureMode != ViewerGestureMode.ZOOM_PAN) {
                                    gestureMode = ViewerGestureMode.ZOOM_PAN
                                    offsetXAnim.updateBounds(null, null)
                                    offsetYAnim.updateBounds(null, null)
                                    if (dismissYAnim.value != 0f) {
                                        scope.launch { dismissYAnim.snapTo(0f) }
                                    }
                                }
                                val zoomChange = event.calculateZoom()
                                val panChange = event.calculatePan()
                                val nextScale = (scaleAnim.value * zoomChange).coerceIn(MIN_SCALE, MAX_SCALE)
                                val nextOffset = clamped(
                                    Offset(offsetXAnim.value, offsetYAnim.value) + panChange,
                                    nextScale,
                                )
                                scope.launch {
                                    scaleAnim.snapTo(nextScale)
                                    offsetXAnim.snapTo(nextOffset.x)
                                    offsetYAnim.snapTo(nextOffset.y)
                                }
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            } else if (pressedCount == 1) {
                                val change = event.changes.first { it.pressed }
                                val pan = event.calculatePan()
                                if (gestureMode == ViewerGestureMode.UNDETERMINED) {
                                    totalPan += pan
                                    if (totalPan.getDistance() > touchSlop) {
                                        gestureMode = when {
                                            scaleAnim.value > 1.02f -> ViewerGestureMode.ZOOM_PAN
                                            abs(totalPan.y) > abs(totalPan.x) * 1.15f -> ViewerGestureMode.DISMISS
                                            else -> ViewerGestureMode.IGNORE
                                        }
                                        if (gestureMode != ViewerGestureMode.IGNORE) {
                                            offsetXAnim.updateBounds(null, null)
                                            offsetYAnim.updateBounds(null, null)
                                        }
                                    }
                                }
                                when (gestureMode) {
                                    ViewerGestureMode.ZOOM_PAN -> {
                                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                                        val nextOffset = clamped(
                                            Offset(offsetXAnim.value, offsetYAnim.value) + pan,
                                            scaleAnim.value,
                                        )
                                        scope.launch {
                                            offsetXAnim.snapTo(nextOffset.x)
                                            offsetYAnim.snapTo(nextOffset.y)
                                        }
                                        if (change.positionChanged()) change.consume()
                                    }

                                    ViewerGestureMode.DISMISS -> {
                                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                                        scope.launch { dismissYAnim.snapTo(dismissYAnim.value + pan.y) }
                                        if (change.positionChanged()) change.consume()
                                    }

                                    else -> Unit
                                }
                            }
                        } while (event.changes.any { it.pressed })

                        when (gestureMode) {
                            ViewerGestureMode.ZOOM_PAN -> {
                                if (scaleAnim.value > 1.02f) {
                                    val velocity = velocityTracker.calculateVelocity()
                                    val maxBounds = maxPanBounds(scaleAnim.value)
                                    scope.launch {
                                        offsetXAnim.updateBounds(-maxBounds.x, maxBounds.x)
                                        offsetYAnim.updateBounds(-maxBounds.y, maxBounds.y)
                                        coroutineScope {
                                            launch { offsetXAnim.animateDecay(velocity.x, decay) }
                                            launch { offsetYAnim.animateDecay(velocity.y, decay) }
                                        }
                                    }
                                } else {
                                    scope.launch {
                                        coroutineScope {
                                            launch { scaleAnim.animateTo(MIN_SCALE, zoomSpring) }
                                            launch { offsetXAnim.animateTo(0f, zoomSpring) }
                                            launch { offsetYAnim.animateTo(0f, zoomSpring) }
                                        }
                                    }
                                }
                            }

                            ViewerGestureMode.DISMISS -> {
                                val vy = velocityTracker.calculateVelocity().y
                                val threshold = containerSize.height * 0.18f
                                if (abs(dismissYAnim.value) > threshold || abs(vy) > 1100f) {
                                    haptics.gestureEnd()
                                    onDismiss()
                                } else {
                                    scope.launch {
                                        dismissYAnim.animateTo(
                                            0f,
                                            spring(
                                                dampingRatio = Spring.DampingRatioNoBouncy,
                                                stiffness = Spring.StiffnessMedium,
                                            ),
                                        )
                                    }
                                }
                            }

                            else -> Unit
                        }
                    }
                },
        ) {
            net.pocketnai.ui.safety.SfwAsyncImage(
                file = imageFile,
                revealed = revealed,
                animate = true,
                contentDescription = contentDescription,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val maxDismiss = (size.height * 0.42f).coerceAtLeast(1f)
                        val dismissFraction = (abs(dismissYAnim.value) / maxDismiss).coerceIn(0f, 1f)
                        val dismissScale = 1f - dismissFraction * 0.22f
                        val s = scaleAnim.value * dismissScale
                        scaleX = s
                        scaleY = s
                        translationX = offsetXAnim.value
                        translationY = offsetYAnim.value + dismissYAnim.value
                    },
            )
        }
    }
}

private enum class ViewerGestureMode {
    UNDETERMINED,
    ZOOM_PAN,
    DISMISS,
    IGNORE,
}

private const val MIN_SCALE = 1f
private const val MAX_SCALE = 8f
private const val DOUBLE_TAP_SCALE = 2.5f
