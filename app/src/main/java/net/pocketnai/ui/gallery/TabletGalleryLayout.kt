package net.pocketnai.ui.gallery

import androidx.compose.animation.core.Transition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.pocketnai.ui.motion.LocalImageMotion
import net.pocketnai.ui.motion.LocalWorkbenchSharedTransition

internal val galleryGridGap = 8.dp
internal fun tabletGalleryColumns(width: Dp) = (width.value / 168f).toInt().coerceAtLeast(2)

internal class TabletGalleryLayout(
    val panel: Transition<Boolean>,
    val visibleFraction: State<Float>,
    val splitWidth: Dp,
    val fullWidth: Dp,
    val splitGrid: LazyStaggeredGridState,
    val fullGrid: LazyStaggeredGridState,
) {
    val moving get() = panel.isRunning || panel.currentState != panel.targetState

    fun prepareToggle(collapsed: Boolean) {
        if (moving || collapsed != panel.targetState) return
        val source = if (collapsed) fullGrid else splitGrid
        val target = if (collapsed) splitGrid else fullGrid
        val index = source.firstVisibleItemIndex
        val offset = source.firstVisibleItemScrollOffset
        if (source.isScrollInProgress) source.requestScrollToItem(index, offset)
        target.requestScrollToItem(index, offset)
    }
}

/** 面板运动只改变裁剪边缘和两层的混合比例。每层保持自己的列宽、滚动状态和稳定 item key。 */
@Composable
internal fun GalleryViewport(
    tablet: TabletGalleryLayout?,
    state: LazyStaggeredGridState,
    sections: List<GalleryMotionSection>,
    content: @Composable (LazyStaggeredGridState, Dp?, Boolean) -> Unit,
) {
    if (tablet == null) {
        content(state, null, true)
        return
    }
    val collapsed = tablet.panel.targetState
    val moving = tablet.moving
    Box(Modifier.fillMaxSize().clipToBounds()) {
        for (full in listOf(true, false)) {
            val active = full == collapsed
            if (!active && !moving) continue
            key(full) {
                val width = if (full) tablet.fullWidth else tablet.splitWidth
                val grid = if (full) tablet.fullGrid else tablet.splitGrid
                // 只在新挂载的目标层接手一次；反向时两层都在，沿用各自的锚点。
                val density = LocalDensity.current
                DisposableEffect(Unit) {
                    if (moving && active) {
                        val from = if (full) tablet.splitGrid else tablet.fullGrid
                        val index = from.firstVisibleItemIndex
                        val anchor = from.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
                        if (index > 0 && anchor != null) {
                            val slotIndex = sections.asSequence().flatMap { it.slots.asSequence() }
                                .indexOfFirst { it.key == anchor.key }
                            if (slotIndex >= 0) {
                                // 长图若只露出一角，新列更窄时旧像素偏移可能越过整张图。
                                val columns = tabletGalleryColumns(width)
                                val cellWidth = with(density) {
                                    (width.roundToPx() - (columns + 1) * galleryGridGap.roundToPx()) / columns
                                }
                                val newHeight = (anchor.size.height * (cellWidth.toFloat() / anchor.size.width.coerceAtLeast(1))).toInt()
                                val offset = from.firstVisibleItemScrollOffset.coerceAtMost((newHeight - 1).coerceAtLeast(0))
                                grid.requestScrollToItem(slotIndex + 1, offset)
                            }
                        }
                    }
                    onDispose { }
                }
                Box(
                    Modifier
                        .layout { measurable, constraints ->
                            val px = width.roundToPx()
                            val child = measurable.measure(constraints.copy(minWidth = px, maxWidth = px))
                            layout(constraints.maxWidth, child.height) { child.placeRelative(0, 0) }
                        }
                        .graphicsLayer {
                            // 宽层始终垫底，窄层以同一弹簧淡化。中途反向不会交换图层或改变亮度。
                            alpha = if (moving && !full) tablet.visibleFraction.value else 1f
                        }
                        .background(MaterialTheme.colorScheme.background)
                        .then(if (active) Modifier else Modifier.clearAndSetSemantics { }),
                ) {
                    // 退出层只负责绘制。返回定位、共享元素和生成完成回调都只交给目标层。
                    CompositionLocalProvider(
                        LocalImageMotion provides LocalImageMotion.current.takeIf { active },
                        LocalWorkbenchSharedTransition provides LocalWorkbenchSharedTransition.current.takeIf { active },
                    ) { content(grid, width, active) }
                }
            }
        }
        // 两种列布局重叠时没有唯一的卡片命中位置。顶栏、分隔条和面板仍可立即接管。
        if (moving) Box(Modifier.matchParentSize().pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            }
        })
    }
}
