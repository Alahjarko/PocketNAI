package net.pocketnai.ui.gallery

import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import net.pocketnai.ui.motion.imageRouteIsMoving
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import net.pocketnai.domain.model.GalleryItem
import net.pocketnai.ui.Routes
import net.pocketnai.ui.motion.LocalImageMotion
import net.pocketnai.ui.state.GenerationPreviewStore
import java.io.File

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
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Adaptive(160.dp), state = state,
        contentPadding = PaddingValues(8.dp), verticalItemSpacing = 8.dp,
        horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier.fillMaxSize(),
    ) {
        sections.firstOrNull()?.let { first ->
            item(key = "gallery-current-day", span = StaggeredGridItemSpan.FullLine) {
                Text(first.label, style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp))
            }
        }
        sections.forEachIndexed { dayIndex, section ->
            items(section.slots, key = { it.key }) { slot ->
                val image = slot.image
                GalleryMotionCard(slot, image?.let(fileOf), previews[slot.generation?.id],
                    isSelectionMode, image?.imageId in selectedIds,
                    onClick = { image?.let(onClick) }, onLongClick = { image?.let(onLongClick) },
                    onRevealed = { onRevealed(slot.key) },
                    onDismissFailure = { slot.generation?.id?.let(onDismissFailure) },
                    dateLabel = section.label.takeIf { dayIndex > 0 && slot.key == section.slots.first().key },
                    modifier = Modifier.testTag(slot.key)
                        .animateItem(fadeInSpec = null, placementSpec = if (moving || returning != null) null else tween(280), fadeOutSpec = null))
            }
        }
    }
}
