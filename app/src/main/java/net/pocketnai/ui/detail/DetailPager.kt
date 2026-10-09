package net.pocketnai.ui.detail

import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.*

/** The initial order is available before Room finishes binding; never clamp a later image to page 0. */
@Composable
internal fun rememberDetailPager(
    initialImageId: String,
    initialOrder: List<String>,
    loadedOrder: List<String>,
    selectedId: String?,
    onSelect: (String) -> Unit,
): PagerState {
    val order = loadedOrder.ifEmpty { initialOrder }
    val latestOrder by rememberUpdatedState(order)
    val select by rememberUpdatedState(onSelect)
    val pager = rememberPagerState(initialPage = initialOrder.indexOf(initialImageId).coerceAtLeast(0),
        pageCount = { order.size })
    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage }.collect { page -> latestOrder.getOrNull(page)?.let(select) }
    }
    LaunchedEffect(selectedId) {
        val target = order.indexOf(selectedId)
        if (target >= 0 && target != pager.currentPage && !pager.isScrollInProgress) pager.animateScrollToPage(target)
    }
    return pager
}
