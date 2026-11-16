package net.pocketnai.ui.gallery

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.flow.first
import net.pocketnai.domain.model.GalleryItem
import net.pocketnai.ui.Routes
import net.pocketnai.ui.motion.LocalImageMotion
import net.pocketnai.ui.motion.imageRouteIsMoving
import net.pocketnai.ui.motion.rememberPocketHaptics
import net.pocketnai.ui.state.GenerationPreviewStore

/** Date boundaries do not split the masonry: later dates label their first picture. */
@Composable
internal fun GalleryMotionGrid(
    sections: List<GalleryMotionSection>,
    fileOf: (GalleryItem) -> File,
    previews: Map<String, GenerationPreviewStore.Preview>,
    isSelectionMode: Boolean,
    selectedIds: Set<String>,
    onClick: (GalleryItem) -> Unit,
    onLongClick: (GalleryItem) -> Unit,
    onRevealed: (String) -> Unit,
    onDismissFailure: (String) -> Unit,
    modifier: Modifier = Modifier,
    state: LazyStaggeredGridState = rememberLazyStaggeredGridState(),
    onDragSelect: ((anchorIndex: Int, currentIndex: Int, orderedIds: List<String>, baseSelection: Set<String>, selectTarget: Boolean) -> Unit)? = null,
) {
    val motion = LocalImageMotion.current
    val returning = motion?.returning
    val moving = imageRouteIsMoving()

    LaunchedEffect(returning, sections) {
        if (returning?.source != Routes.HOME) return@LaunchedEffect
        val cards = sections.flatMap { it.slots }
        val target = cards.indexOfFirst { it.image?.imageId == returning.imageId }
        if (target >= 0) {
            val index = target + 1 // a single leading date header
            // Saveable scroll restoration and the first measure must finish before deciding to scroll.
            val layout = snapshotFlow { state.layoutInfo }.first { it.visibleItemsInfo.isNotEmpty() }
            val key = cards[target].key
            if (layout.visibleItemsInfo.none { it.key == key }) {
                state.scrollToItem(index)
                snapshotFlow { state.layoutInfo }.first { info -> info.visibleItemsInfo.any { it.key == key } }
            }
            motion.consume(returning)
        }
    }

    val placementSpring = remember {
        spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
            visibilityThreshold = IntOffset.VisibilityThreshold,
        )
    }

    val dragSelectModifier = if (isSelectionMode && onDragSelect != null) {
        rememberGalleryDragSelectModifier(
            sections = sections,
            selectedIds = selectedIds,
            state = state,
            onDragSelect = onDragSelect,
        )
    } else {
        Modifier
    }

    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Adaptive(160.dp),
        state = state,
        contentPadding = PaddingValues(8.dp),
        verticalItemSpacing = 8.dp,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxSize().then(dragSelectModifier),
    ) {
        sections.firstOrNull()?.let { first ->
            item(key = "gallery-current-day", span = StaggeredGridItemSpan.FullLine) {
                Text(
                    first.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                )
            }
        }
        sections.forEachIndexed { dayIndex, section ->
            items(section.slots, key = { it.key }) { slot ->
                val image = slot.image
                GalleryMotionCard(
                    slot,
                    image?.let(fileOf),
                    previews[slot.generation?.id],
                    isSelectionMode,
                    image?.imageId in selectedIds,
                    onClick = { image?.let(onClick) },
                    onLongClick = { image?.let(onLongClick) },
                    onRevealed = { onRevealed(slot.key) },
                    onDismissFailure = { slot.generation?.id?.let(onDismissFailure) },
                    dateLabel = section.label.takeIf { dayIndex > 0 && slot.key == section.slots.first().key },
                    modifier = Modifier
                        .testTag(slot.key)
                        .animateItem(
                            fadeInSpec = null,
                            placementSpec = if (moving || returning != null) null else placementSpring,
                            fadeOutSpec = null,
                        ),
                )
            }
        }
    }
}

@Composable
private fun rememberGalleryDragSelectModifier(
    sections: List<GalleryMotionSection>,
    selectedIds: Set<String>,
    state: LazyStaggeredGridState,
    onDragSelect: (anchorIndex: Int, currentIndex: Int, orderedIds: List<String>, baseSelection: Set<String>, selectTarget: Boolean) -> Unit,
): Modifier {
    val haptics = rememberPocketHaptics()
    val density = LocalDensity.current

    val keyToImageIndex = remember(sections) {
        buildMap {
            var idx = 0
            sections.forEach { sec ->
                sec.slots.forEach { slot ->
                    if (slot.image != null) {
                        put(slot.key, idx)
                        idx++
                    }
                }
            }
        }
    }
    val orderedIds = remember(sections) {
        sections.flatMap { sec -> sec.slots.mapNotNull { it.image?.imageId } }
    }

    val currentKeyMap by rememberUpdatedState(keyToImageIndex)
    val currentOrderedIds by rememberUpdatedState(orderedIds)
    val currentSelectedIds by rememberUpdatedState(selectedIds)
    val currentOnDragSelect by rememberUpdatedState(onDragSelect)

    var dragActive by remember { mutableStateOf(false) }
    var dragPointer by remember { mutableStateOf(Offset.Zero) }
    var dragAnchorIndex by remember { mutableIntStateOf(-1) }
    var dragLastIndex by remember { mutableIntStateOf(-1) }
    var dragBaseSelection by remember { mutableStateOf<Set<String>>(emptySet()) }
    var dragSelectTarget by remember { mutableStateOf(true) }

    fun findImageIndexAt(point: Offset): Int? {
        val visible = state.layoutInfo.visibleItemsInfo
        val exact = visible.firstOrNull { item ->
            point.x >= item.offset.x && point.x <= item.offset.x + item.size.width &&
                point.y >= item.offset.y && point.y <= item.offset.y + item.size.height
        }
        if (exact != null) return currentKeyMap[exact.key]
        val rowCandidate = visible.filter { item ->
            item.key in currentKeyMap &&
                point.y >= item.offset.y - 16 && point.y <= item.offset.y + item.size.height + 16
        }.minByOrNull { item ->
            val cx = item.offset.x + item.size.width / 2f
            kotlin.math.abs(point.x - cx)
        }
        return rowCandidate?.let { currentKeyMap[it.key] }
    }

    val edgeZonePx = with(density) { 60.dp.toPx() }
    val maxAutoScrollPx = with(density) { 12.dp.toPx() }
    LaunchedEffect(dragActive) {
        if (!dragActive) return@LaunchedEffect
        while (dragActive) {
            withFrameNanos { }
            val viewportHeight = state.layoutInfo.viewportSize.height.toFloat()
            if (viewportHeight <= 0f) continue
            val py = dragPointer.y
            val delta = when {
                py < edgeZonePx && state.canScrollBackward -> {
                    val factor = ((edgeZonePx - py) / edgeZonePx).coerceIn(0f, 1f)
                    -maxAutoScrollPx * factor
                }
                py > viewportHeight - edgeZonePx && state.canScrollForward -> {
                    val factor = ((py - (viewportHeight - edgeZonePx)) / edgeZonePx).coerceIn(0f, 1f)
                    maxAutoScrollPx * factor
                }
                else -> 0f
            }
            if (delta != 0f) {
                state.scrollBy(delta)
                val idx = findImageIndexAt(dragPointer)
                if (idx != null && idx != dragLastIndex && dragAnchorIndex >= 0) {
                    dragLastIndex = idx
                    currentOnDragSelect(
                        dragAnchorIndex,
                        idx,
                        currentOrderedIds,
                        dragBaseSelection,
                        dragSelectTarget,
                    )
                    haptics.frequentTick()
                }
            }
        }
    }

    return Modifier.pointerInput(Unit) {
        detectDragGesturesAfterLongPress(
            onDragStart = { startOffset ->
                val hit = findImageIndexAt(startOffset) ?: return@detectDragGesturesAfterLongPress
                val ids = currentOrderedIds
                if (hit !in ids.indices) return@detectDragGesturesAfterLongPress
                val base = currentSelectedIds
                val targetSelect = ids[hit] !in base
                dragAnchorIndex = hit
                dragLastIndex = hit
                dragBaseSelection = base
                dragSelectTarget = targetSelect
                dragPointer = startOffset
                dragActive = true
                haptics.longPress()
                currentOnDragSelect(hit, hit, ids, base, targetSelect)
            },
            onDrag = { change, _ ->
                if (!dragActive || dragAnchorIndex < 0) return@detectDragGesturesAfterLongPress
                change.consume()
                dragPointer = change.position
                val hit = findImageIndexAt(change.position)
                if (hit != null && hit != dragLastIndex) {
                    dragLastIndex = hit
                    currentOnDragSelect(
                        dragAnchorIndex,
                        hit,
                        currentOrderedIds,
                        dragBaseSelection,
                        dragSelectTarget,
                    )
                    haptics.frequentTick()
                }
            },
            onDragEnd = {
                dragActive = false
                dragAnchorIndex = -1
                dragLastIndex = -1
            },
            onDragCancel = {
                dragActive = false
                dragAnchorIndex = -1
                dragLastIndex = -1
            },
        )
    }
}
