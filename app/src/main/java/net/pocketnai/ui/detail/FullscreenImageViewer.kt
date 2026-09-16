package net.pocketnai.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import java.io.File

/**
 * 全屏图片查看：双指缩放、拖动、双击放大/复原，单击或返回键关闭。
 *
 * 交互对齐系统相册：
 * - 双击**围绕点按处**放大（不是围绕图片中心），这样才能双击看某个角落的细节；
 * - 放大后的拖动被约束在图片边缘内。约束以"图片 Fit 后的显示尺寸"为基准而不是
 *   容器本身 —— 竖图两侧有留白，用容器宽当图片宽会让图片被拖出可视区；
 * - 缩回 1x 时平移自动归零（约束在 1x 下本来就是 0）。
 *
 * 只负责"看"：保存 / 分享 / 删除都留在详情页，避免这个浮层长成第二个详情页。
 */
@Composable
fun FullscreenImageViewer(
    imageFile: File,
    contentDescription: String?,
    imageWidth: Int,
    imageHeight: Int,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            // 铺满全屏（含系统栏区域），图片查看器不该在系统栏前留白。
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        var containerSize by remember { mutableStateOf(Size.Zero) }
        var scale by remember { mutableFloatStateOf(MIN_SCALE) }
        var offset by remember { mutableStateOf(Offset.Zero) }

        val imageAspect = if (imageHeight > 0) imageWidth.toFloat() / imageHeight else 1f

        // 手势回调可能在第一次组合时就被捕获，因此这里每次都从 state 读当前尺寸
        // 再计算，不能缓存 `remember(containerSize)` 的结果（旋转 / 分屏后会过期）。
        fun clamped(candidate: Offset, forScale: Float): Offset {
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
            return Offset(
                candidate.x.coerceIn(-maxX, maxX),
                candidate.y.coerceIn(-maxY, maxY),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onSizeChanged { containerSize = Size(it.width.toFloat(), it.height.toFloat()) }
                .pointerInput(Unit) {
                    // 单击与双击都要经过双击判定窗口，约 300ms 的关闭延迟是相册类界面的常态。
                    detectTapGestures(
                        onTap = { onDismiss() },
                        onDoubleTap = { tap ->
                            if (scale > MIN_SCALE) {
                                scale = MIN_SCALE
                                offset = Offset.Zero
                            } else {
                                val center = Offset(
                                    containerSize.width / 2f,
                                    containerSize.height / 2f,
                                )
                                val delta = tap - center
                                scale = DOUBLE_TAP_SCALE
                                // 让点按处保持在指针下：围绕中心缩放后它移动了
                                // delta * (scale - 1)，用同样大小的反向平移抵消。
                                offset = clamped(
                                    Offset(
                                        -delta.x * (DOUBLE_TAP_SCALE - 1f),
                                        -delta.y * (DOUBLE_TAP_SCALE - 1f),
                                    ),
                                    DOUBLE_TAP_SCALE,
                                )
                            }
                        },
                    )
                },
        ) {
            AsyncImage(
                model = imageFile,
                contentDescription = contentDescription,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
                    .transformable(
                        state = rememberTransformableState { zoomChange, panChange, _ ->
                            val nextScale = (scale * zoomChange).coerceIn(MIN_SCALE, MAX_SCALE)
                            scale = nextScale
                            offset = clamped(offset + panChange, nextScale)
                        },
                    ),
            )
        }
    }
}

private const val MIN_SCALE = 1f
private const val MAX_SCALE = 8f
private const val DOUBLE_TAP_SCALE = 2.5f
