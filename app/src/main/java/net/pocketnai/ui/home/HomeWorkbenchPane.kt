package net.pocketnai.ui.home

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import net.pocketnai.data.repo.GenerationRepository.GalleryPage
import kotlinx.coroutines.launch
import net.pocketnai.R
import net.pocketnai.domain.model.GalleryFilter
import net.pocketnai.domain.model.GalleryItem
import net.pocketnai.domain.model.GalleryModels
import net.pocketnai.domain.model.GallerySearch
import net.pocketnai.domain.model.GenerationStatus
import net.pocketnai.domain.model.ReferenceRole
import net.pocketnai.domain.safety.SfwPolicy
import net.pocketnai.ui.Routes
import net.pocketnai.ui.LocalAppContainer
import net.pocketnai.ui.common.CenteredHint
import net.pocketnai.ui.common.ImageShareManager
import net.pocketnai.ui.common.mouseImageDragSource
import net.pocketnai.ui.common.onMouseWheel
import net.pocketnai.ui.common.onSecondaryClick
import net.pocketnai.ui.gallery.GalleryItemActionSheet
import net.pocketnai.ui.gallery.GalleryItemContextMenu
import net.pocketnai.ui.gallery.GalleryViewModel
import net.pocketnai.ui.gallery.copyToClipboard
import net.pocketnai.ui.gallery.saveToSystemGallery
import net.pocketnai.ui.generate.ReferenceRoleChooserDialog
import net.pocketnai.ui.motion.GenerationImageReveal
import net.pocketnai.ui.motion.ImageOriginPreview
import net.pocketnai.ui.motion.LocalImageMotion
import net.pocketnai.ui.motion.rememberPocketHaptics
import net.pocketnai.ui.motion.sharedImage
import net.pocketnai.ui.safety.SfwAsyncImage
import java.io.File
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 首页平板大图预览工作台（灵感源自 WinNAI 桌面端）：
 * - 中央大舞台：自适应完整大图，支持 `Ctrl+滚轮` 缩放、左键拖动画布、双击复位、普通滚轮切图；
 * - 键鼠导航：无文本聚焦时 `A`/`D` 与 `←`/`→` 切图、`F` 收藏、`Space` 打开详情大图；
 * - 右侧竖向胶片缩略图栏：实时显示生成中占位卡片、支持鼠标拖拽任意缩略图至右侧生成面板作为参考图；
 * - 实时生成同步：处于最新图时自动切入新图，浏览历史图时不强制跳回并在顶部弹出可点击的「任务完成 · 查看」胶囊；
 * - 触控与键鼠双轨：长按打开 [GalleryItemActionSheet]，鼠标右键打开桌面 [GalleryItemContextMenu]。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeWorkbenchPane(
    galleryViewModel: GalleryViewModel,
    onOpenImage: (String) -> Unit,
    onInpaintImage: (GalleryItem) -> Unit,
    onReuseItem: (GalleryItem) -> Unit,
    modifier: Modifier = Modifier,
    onUseAsReference: ((GalleryItem, ReferenceRole) -> Unit)? = null,
    supportsDirectorReference: Boolean = false,
    supportsVibeTransfer: Boolean = false,
    inFlight: Boolean = false,
    selectedImageId: String? = null,
    onSelectedImageIdChange: ((String?) -> Unit)? = null,
) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberPocketHaptics()
    val imageMotion = LocalImageMotion.current

    val syncFilter = container.settingsStore.tabletWorkbenchSyncFilter.collectAsStateWithLifecycle().value
    val generations by galleryViewModel.generations.collectAsStateWithLifecycle()
    val previews by container.generationPreviewStore.previews.collectAsStateWithLifecycle()
    val undoGenerationIds by galleryViewModel.undoGenerationIds.collectAsStateWithLifecycle()

    var unfilteredLimit by rememberSaveable { mutableIntStateOf(120) }
    val source = remember(galleryViewModel, syncFilter, unfilteredLimit) {
        if (syncFilter) galleryViewModel.filteredPage.filterNotNull()
        else galleryViewModel.observePage(GalleryFilter.None, unfilteredLimit)
    }
    val entryImageId = remember {
        imageMotion?.returning?.takeIf { it.source == Routes.HOME }?.imageId ?: selectedImageId
    }
    var displayedPage by remember {
        mutableStateOf(galleryViewModel.filteredPage.value?.takeIf { page ->
            syncFilter && (entryImageId == null || page.items.any { it.imageId == entryImageId })
        })
    }
    var displayedSync by remember { mutableStateOf(syncFilter) }
    val items = displayedPage?.items.orEmpty()
    val filter = displayedPage?.filter ?: GalleryFilter.None
    val hasMore = displayedPage?.hasMore == true && displayedSync == syncFilter
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    val activeGenerations = remember(generations, filter) {
        generations.filter { summary ->
            summary.generation.isActive && GallerySearch.matches(summary.generation, filter)
        }
    }
    val hasGeneratingSlot = activeGenerations.isNotEmpty() || inFlight

    var viewingGenerating by remember { mutableStateOf(false) }
    var showNewImageBanner by remember { mutableStateOf(false) }
    val initialPage = remember {
        items.indexOfFirst { it.imageId == entryImageId }.coerceAtLeast(0)
    }
    val stagePagerState = rememberPagerState(initialPage) { items.size }
    val currentIndex = stagePagerState.currentPage.coerceIn(0, items.lastIndex.coerceAtLeast(0))
    val activeItem = if (viewingGenerating && hasGeneratingSlot) null else items.getOrNull(currentIndex)
    var targetId by remember { mutableStateOf<String?>(null) }
    val pageRequests = remember { Channel<String>(Channel.CONFLATED) }
    val savedSelection by rememberUpdatedState(selectedImageId)
    val pageSlideSpec = remember { spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 520f) }

    // 查询、收藏状态和分页窗口一起提交。等 Room 首次发值时保留旧画面，真空结果才显示空态。
    LaunchedEffect(source, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            source.collectLatest { page ->
                val old = displayedPage
                val oldIndex = stagePagerState.currentPage
                val oldTop = old?.items?.firstOrNull()
                val newTop = page.items.firstOrNull()
                val sameSource = old != null && displayedSync == syncFilter && old.filter == page.filter
                val newArrival = sameSource && newTop != null && oldTop != null &&
                    newTop.imageId != oldTop.imageId && newTop.createdAt >= oldTop.createdAt &&
                    page.items.any { it.imageId == oldTop.imageId }
                val atLatest = viewingGenerating || (oldIndex == 0 && targetId == null) || targetId == oldTop?.imageId
                val followTop = (newArrival && atLatest) ||
                    (sameSource && oldTop == null && newTop != null && viewingGenerating)
                val anchor = imageMotion?.returning?.takeIf { it.source == Routes.HOME }?.imageId
                    ?: old?.items?.getOrNull(oldIndex)?.imageId ?: (if (old == null) entryImageId else savedSelection)
                val anchorIndex = page.items.indexOfFirst { it.imageId == anchor }
                // 稀疏筛选中的旧图可能落在全部图库的下一窗口。只查 ID 顺序，命中后一次扩窗。
                if (anchor != null && anchorIndex < 0 && page.hasMore && (!sameSource || imageMotion?.returning != null)) {
                    val rank = container.generationRepository.galleryOrder(page.filter).indexOf(anchor)
                    if (rank >= page.items.size) {
                        if (syncFilter) galleryViewModel.includeIndex(page.filter, rank)
                        else unfilteredLimit = rank + 1
                        return@collectLatest
                    }
                }
                val animateNewArrival = followTop && newArrival && !viewingGenerating && anchorIndex > 0 && newTop != null
                val index = when {
                    animateNewArrival -> anchorIndex
                    followTop -> 0
                    anchorIndex >= 0 -> anchorIndex
                    else -> oldIndex.coerceIn(0, page.items.lastIndex.coerceAtLeast(0))
                }
                displayedPage = page
                displayedSync = syncFilter
                if (animateNewArrival) {
                    // 先把 Pager 静默锚在旧图的新索引，待新列表完成首帧测量后再平滑滑入顶部新图，避免 0 帧硬切。
                    stagePagerState.requestScrollToPage(anchorIndex)
                    val arrivalId = newTop.imageId
                    targetId = arrivalId
                    scope.launch {
                        withFrameNanos { }
                        if (targetId == arrivalId) {
                            pageRequests.trySend(arrivalId)
                        }
                    }
                } else if (!sameSource || followTop || index != oldIndex || anchorIndex < 0) {
                    targetId = null
                    if (page.items.isNotEmpty()) stagePagerState.requestScrollToPage(index)
                }
                if (!sameSource || followTop) showNewImageBanner = false
                else if (newArrival) showNewImageBanner = true
                if (followTop) viewingGenerating = false
            }
        }
    }

    // 详情中翻过页后，把返回共享元素定位到实际正在看的图。
    val returning = imageMotion?.returning
    LaunchedEffect(returning, displayedPage) {
        if (returning?.source == Routes.HOME) {
            val index = items.indexOfFirst { it.imageId == returning.imageId }
            if (index >= 0) {
                targetId = null
                viewingGenerating = false
                stagePagerState.requestScrollToPage(index)
                imageMotion.consume(returning)
            }
        }
    }
    LaunchedEffect(activeItem?.imageId, displayedPage != null) {
        if (displayedPage != null) onSelectedImageIdChange?.invoke(activeItem?.imageId)
    }

    // 单个翻页协程完成当前动画再取最新目标。高频滚轮不会逐帧重启弹簧，反向也不会排长队。
    LaunchedEffect(stagePagerState) {
        for (id in pageRequests) {
            if (id != targetId) continue
            val index = displayedPage?.items?.indexOfFirst { it.imageId == id } ?: -1
            if (index >= 0) {
                // 手指可抢占 Pager 的滚动互斥锁；只取消这一段动画，不终止目标处理循环。
                launch { stagePagerState.animateScrollToPage(index, animationSpec = pageSlideSpec) }.join()
            }
            if (targetId == id) targetId = null
        }
    }
    LaunchedEffect(stagePagerState) {
        stagePagerState.interactionSource.interactions.collect { if (it is DragInteraction.Start) targetId = null }
    }

    val filmstripState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val stageFocusRequester = remember { FocusRequester() }

    var actionTarget by remember { mutableStateOf<GalleryItem?>(null) }
    var referenceTarget by remember { mutableStateOf<GalleryItem?>(null) }
    var pendingSave by remember { mutableStateOf<GalleryItem?>(null) }
    var stageContextMenuExpanded by remember { mutableStateOf(false) }
    var openingImage by remember { mutableStateOf(false) }

    // 首次进入工作台尝试获取焦点，确保键盘 A/D/F/Space 立即可用。
    LaunchedEffect(Unit) {
        runCatching { stageFocusRequester.requestFocus() }
    }

    val topImageId = items.firstOrNull()?.imageId
    LaunchedEffect(showNewImageBanner, topImageId) {
        if (showNewImageBanner) {
            delay(4_000L)
            showNewImageBanner = false
        }
    }

    // 当所有进行中任务都结束时，自动退出占位查看态。
    LaunchedEffect(hasGeneratingSlot) {
        if (!hasGeneratingSlot && viewingGenerating) {
            viewingGenerating = false
        }
    }

    // 选中项变化时平滑保持胶片栏可见，接近末尾自动加载更多。
    LaunchedEffect(currentIndex, activeItem?.imageId, hasGeneratingSlot, displayedSync) {
        if (activeItem != null) {
            val listIdx = currentIndex + if (hasGeneratingSlot) 1 else 0
            val thumb = filmstripState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == activeItem.imageId }
            if (thumb == null) filmstripState.animateScrollToItem(listIdx)
            else if (thumb.index != listIdx) filmstripState.requestScrollToItem(listIdx, -thumb.offset)
        }
    }

    LaunchedEffect(filmstripState, displayedPage, hasMore, hasGeneratingSlot) {
        if (!hasMore) return@LaunchedEffect
        snapshotFlow {
            val last = filmstripState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            maxOf(stagePagerState.currentPage, last - if (hasGeneratingSlot) 1 else 0) >= items.size - 16
        }.collect { nearEnd ->
            if (nearEnd) {
                if (syncFilter) galleryViewModel.loadMore()
                else if (displayedPage?.limit == unfilteredLimit) {
                    unfilteredLimit = (unfilteredLimit.toLong() + 120).coerceAtMost(Int.MAX_VALUE - 1L).toInt()
                }
            }
        }
    }

    // 撤销删除提示
    val deletedMessage = stringResource(R.string.action_delete)
    val undoLabel = stringResource(R.string.action_cancel)
    LaunchedEffect(undoGenerationIds) {
        if (undoGenerationIds.isEmpty()) return@LaunchedEffect
        val message = if (undoGenerationIds.size > 1) "已删除 ${undoGenerationIds.size} 项" else deletedMessage
        val result = snackbarHostState.showSnackbar(
            message = message,
            actionLabel = undoLabel,
            withDismissAction = true,
        )
        if (result == SnackbarResult.ActionPerformed) {
            galleryViewModel.undoDelete()
        } else {
            galleryViewModel.clearUndo()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val target = pendingSave
        pendingSave = null
        if (granted && target != null) {
            saveToSystemGallery(
                scope = scope,
                context = context,
                repository = container.generationRepository,
                exporter = container.mediaStoreExporter,
                snackbarHostState = snackbarHostState,
                item = target,
            )
        }
    }

    fun selectIndex(index: Int) {
        val target = items.getOrNull(index) ?: return
        viewingGenerating = false
        targetId = target.imageId
        pageRequests.trySend(target.imageId)
        if (index == 0) showNewImageBanner = false
    }

    fun stepPages(steps: Int) {
        if (items.isEmpty()) return
        // 同一帧可能送达多个滚轮刻度，直接读最新目标，不能从尚在动画中的 currentPage 起算。
        val base = if (viewingGenerating) -1 else targetId?.let { id -> items.indexOfFirst { it.imageId == id } }
            ?.takeIf { it >= 0 } ?: stagePagerState.currentPage
        if (base < 0 && steps < 0) return
        val index = (base + steps).coerceIn(0, items.lastIndex)
        if (index != base) {
            haptics.tick()
            selectIndex(index)
        }
    }

    fun openDetail(item: GalleryItem) {
        if (openingImage) return
        openingImage = true
        val file = container.generationRepository.fileOf(item)
        imageMotion?.preview = ImageOriginPreview(item.imageId, file, item.aspectRatio, item.title, item.sensitivePrompt)
        imageMotion?.open(Routes.HOME, item.imageId)
        scope.launch {
            try {
                val order = container.generationRepository.galleryOrder(filter)
                container.galleryOrderSnapshot.publish(order)
                onOpenImage(item.imageId)
            } catch (e: CancellationException) {
                imageMotion?.cancelPendingOpen()
                throw e
            } catch (_: Exception) {
                imageMotion?.cancelPendingOpen()
                snackbarHostState.showSnackbar("无法读取图片列表，请稍后再试")
            } finally {
                openingImage = false
            }
        }
    }

    fun triggerUseAsReference(item: GalleryItem) {
        if (onUseAsReference == null || item.relativePath.endsWith(".svg", ignoreCase = true)) return
        haptics.tick()
        if (supportsDirectorReference || supportsVibeTransfer) {
            referenceTarget = item
        } else {
            onUseAsReference(item, ReferenceRole.IMG2IMG)
        }
    }

    fun triggerReuse(item: GalleryItem) {
        haptics.tick()
        onReuseItem(item)
        scope.launch { snackbarHostState.showSnackbar("已复用参数到右侧生成面板") }
    }

    // 舞台缩放与平移状态（切换图片时自动重置至 100%）
    val transformScope = androidx.compose.runtime.key(activeItem?.imageId) { rememberCoroutineScope() }
    val transform = remember(transformScope) { StageTransform(transformScope) }
    var stageSize by remember { mutableStateOf(Size.Zero) }
    var wheelRemainder by remember(syncFilter) { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val stageInset = with(density) { 12.dp.roundToPx() * 2 }
    val wheelPanPx = with(density) { 48.dp.toPx() }

    fun clampOffset(candidate: Offset, scale: Float, item: GalleryItem): Offset {
        val aspect = item.aspectRatio.coerceIn(0.25f, 4f)
        val width = (stageSize.width - stageInset).coerceAtLeast(0f)
        val height = (stageSize.height - stageInset).coerceAtLeast(0f)
        val maxX = ((minOf(width, height * aspect) * scale - stageSize.width) / 2f).coerceAtLeast(0f)
        val maxY = ((minOf(height, width / aspect) * scale - stageSize.height) / 2f).coerceAtLeast(0f)
        return Offset(candidate.x.coerceIn(-maxX, maxX), candidate.y.coerceIn(-maxY, maxY))
    }

    fun zoomAt(scale: Float, pointer: Offset, item: GalleryItem) {
        val pivot = pointer - Offset(stageSize.width / 2f, stageSize.height / 2f)
        val offset = pivot + (transform.targetOffset - pivot) * (scale / transform.targetScale)
        transform.animateTo(scale, clampOffset(offset, scale, item))
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .focusRequester(stageFocusRequester)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown ||
                    event.isCtrlPressed || event.isAltPressed || event.isMetaPressed ||
                    actionTarget != null || referenceTarget != null || stageContextMenuExpanded
                ) {
                    return@onPreviewKeyEvent false
                }
                when (event.key) {
                    Key.A, Key.DirectionLeft -> {
                        stepPages(-1)
                        true
                    }
                    Key.D, Key.DirectionRight -> {
                        stepPages(1)
                        true
                    }
                    Key.F -> {
                        activeItem?.let {
                            if (!it.favorite) haptics.confirm() else haptics.tick()
                            galleryViewModel.toggleFavorite(it)
                        }
                        true
                    }
                    Key.Spacebar -> {
                        activeItem?.let { openDetail(it) }
                        true
                    }
                    else -> false
                }
            }
            .focusable(),
    ) {
        // ---- 上半区：中央大舞台 + 右侧竖向胶片缩略图栏 ----
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            // 中央大舞台
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                    .clipToBounds()
                    .onSizeChanged {
                        stageSize = Size(it.width.toFloat(), it.height.toFloat())
                        activeItem?.let { item ->
                            val bounded = clampOffset(transform.offset, transform.scale, item)
                            if (bounded != transform.offset) transform.snapTo(transform.scale, bounded)
                        }
                    }
                    .onMouseWheel(PointerEventPass.Initial) { delta, ctrl, position ->
                        val item = activeItem
                        when {
                            ctrl && item != null -> {
                                wheelRemainder = 0f
                                val scale = (transform.targetScale * 1.15f.pow(-delta)).coerceIn(1f, 6f)
                                zoomAt(scale, position, item)
                            }
                            item != null && (transform.targetScale > 1.02f || transform.scale > 1.02f) -> {
                                wheelRemainder = 0f
                                val pan = transform.targetOffset - Offset(0f, delta * wheelPanPx)
                                transform.animateTo(transform.targetScale, clampOffset(pan, transform.targetScale, item))
                            }
                            !ctrl -> {
                                if (wheelRemainder * delta < 0f) wheelRemainder = 0f
                                wheelRemainder += delta
                                val steps = wheelRemainder.toInt()
                                if (steps != 0) {
                                    wheelRemainder -= steps
                                    stepPages(steps.coerceIn(-items.size, items.size))
                                }
                            }
                        }
                    }
                    .onSecondaryClick(enabled = activeItem != null) {
                        runCatching { stageFocusRequester.requestFocus() }
                        stageContextMenuExpanded = true
                    }
                    .pointerInput(activeItem?.imageId) {
                        detectTapGestures(
                            onTap = {
                                runCatching { stageFocusRequester.requestFocus() }
                            },
                            onDoubleTap = { tap ->
                                val item = activeItem ?: return@detectTapGestures
                                runCatching { stageFocusRequester.requestFocus() }
                                haptics.tick()
                                if (transform.targetScale > 1.02f) transform.animateTo(1f, Offset.Zero)
                                else zoomAt(2.2f, tap, item)
                            },
                            onLongPress = {
                                val item = activeItem ?: return@detectTapGestures
                                haptics.longPress()
                                actionTarget = item
                            },
                        )
                    }
                    .pointerInput(activeItem?.imageId) {
                        val item = activeItem ?: return@pointerInput
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.buttons.isSecondaryPressed) break
                                val pressedCount = event.changes.count { it.pressed }
                                if (pressedCount >= 2) {
                                    val zoomChange = event.calculateZoom()
                                    val panChange = event.calculatePan()
                                    val nextScale = (transform.scale * zoomChange).coerceIn(1f, 6f)
                                    val nextOffset = if (nextScale <= 1.02f) {
                                        Offset.Zero
                                    } else {
                                        clampOffset(
                                            transform.offset + panChange,
                                            nextScale,
                                            item,
                                        )
                                    }
                                    transform.snapTo(nextScale, nextOffset)
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                } else if (pressedCount == 1 && transform.scale > 1.02f) {
                                    val pan = event.calculatePan()
                                    if (pan != Offset.Zero) {
                                        val nextOffset = clampOffset(
                                            transform.offset + pan,
                                            transform.scale,
                                            item,
                                        )
                                        transform.snapTo(transform.scale, nextOffset)
                                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                                    }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                val stageMode = when {
                    (viewingGenerating || items.isEmpty()) && hasGeneratingSlot -> StageContentMode.GENERATING
                    displayedPage == null -> StageContentMode.LOADING
                    activeItem == null -> StageContentMode.EMPTY
                    else -> StageContentMode.PAGER
                }
                AnimatedContent(
                    targetState = stageMode,
                    label = "stage-content-mode",
                    transitionSpec = {
                        if (targetState == StageContentMode.PAGER && initialState == StageContentMode.GENERATING) {
                            (fadeIn(tween(260, easing = FastOutSlowInEasing)) +
                                scaleIn(tween(260, easing = FastOutSlowInEasing), initialScale = 0.96f)) togetherWith
                                fadeOut(tween(180, easing = FastOutSlowInEasing))
                        } else {
                            fadeIn(tween(180, easing = FastOutSlowInEasing)) togetherWith
                                fadeOut(tween(140, easing = FastOutSlowInEasing))
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                ) { mode ->
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        when (mode) {
                            StageContentMode.GENERATING -> {
                                val activeGen = activeGenerations.firstOrNull()?.generation
                                val aspect = activeGen?.params?.targetSize?.let { it.width.toFloat() / it.height } ?: (832f / 1216f)
                                val preview = activeGen?.id?.let { previews[it] }
                                val sensitive = activeGen?.let(SfwPolicy::isSensitive) ?: false
                                Box(
                                    modifier = Modifier
                                        .padding(24.dp)
                                        .fillMaxHeight(0.82f)
                                        .aspectRatio(aspect.coerceIn(0.45f, 2.2f), matchHeightConstraintsFirst = true)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                ) {
                                    GenerationImageReveal(
                                        ready = false,
                                        failed = false,
                                        animate = true,
                                        startedAt = activeGen?.createdAt ?: System.currentTimeMillis(),
                                        onRevealed = {},
                                        queued = activeGen?.status == GenerationStatus.QUEUED,
                                        modifier = Modifier.fillMaxSize(),
                                    ) {
                                        if (preview != null) {
                                            SfwAsyncImage(
                                                file = File(preview.path),
                                                contentDescription = null,
                                                contentScale = ContentScale.Crop,
                                                sensitive = sensitive,
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .graphicsLayer { alpha = 0.35f },
                                            )
                                        }
                                    }
                                }
                            }

                            StageContentMode.LOADING -> Unit

                            StageContentMode.EMPTY -> {
                                CenteredHint(
                                    text = stringResource(
                                        if (displayedSync && filter.isActive) R.string.gallery_no_match else R.string.gallery_empty,
                                    ),
                                    modifier = Modifier.align(Alignment.Center),
                                )
                            }

                            StageContentMode.PAGER -> {
                                VerticalPager(
                                    state = stagePagerState,
                                    beyondViewportPageCount = 1,
                                    userScrollEnabled = transform.scale <= 1.02f,
                                    key = { idx -> items.getOrNull(idx)?.imageId ?: "empty-$idx" },
                                    modifier = Modifier.fillMaxSize(),
                                ) { page ->
                                    val stageItem = items.getOrNull(page) ?: return@VerticalPager
                                    val isCurrentPage = page == stagePagerState.currentPage
                                    val imageFile = container.generationRepository.fileOf(stageItem)
                                    val itemAspect = remember(stageItem.width, stageItem.height) {
                                        (if (stageItem.height > 0) stageItem.width.toFloat() / stageItem.height else 1f)
                                            .coerceIn(0.25f, 4f)
                                    }
                                    BoxWithConstraints(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(12.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        val containerAspect = if (maxHeight > 0.dp) maxWidth.value / maxHeight.value else itemAspect
                                        val fittedModifier = if (containerAspect > itemAspect) {
                                            Modifier.fillMaxHeight().aspectRatio(itemAspect, matchHeightConstraintsFirst = true)
                                        } else {
                                            Modifier.fillMaxWidth().aspectRatio(itemAspect, matchHeightConstraintsFirst = false)
                                        }
                                        val isStageSvg = stageItem.relativePath.endsWith(".svg", ignoreCase = true)
                                        Box(
                                            modifier = fittedModifier
                                                .graphicsLayer {
                                                    scaleX = if (isCurrentPage) transform.scale else 1f
                                                    scaleY = scaleX
                                                    val pan = if (isCurrentPage) clampOffset(transform.offset, scaleX, stageItem) else Offset.Zero
                                                    translationX = pan.x
                                                    translationY = pan.y
                                                }
                                                .clip(RoundedCornerShape(12.dp))
                                                .then(
                                                    if (!isStageSvg) {
                                                        // 未放大状态下支持鼠标左键直接拖动大图到右侧参考图区；放大后让位给画布平移，不影响触屏切图。
                                                        Modifier.mouseImageDragSource(
                                                            localPath = stageItem.relativePath,
                                                            enabled = isCurrentPage && transform.scale <= 1.02f,
                                                        )
                                                    } else Modifier,
                                                ),
                                        ) {
                                            SfwAsyncImage(
                                                file = imageFile,
                                                contentDescription = stageItem.title,
                                                sensitive = stageItem.sensitivePrompt,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .then(
                                                        if (isCurrentPage) {
                                                            Modifier.sharedImage(stageItem.imageId, stageItem.sensitivePrompt)
                                                        } else Modifier,
                                                    ),
                                            )
                                        }
                                    }
                                }

                                // 缩放倍率轻提示（大于 105% 时显示，点击一键复原）
                                if (transform.scale > 1.05f) {
                                    Surface(
                                        shape = CircleShape,
                                        color = Color.Black.copy(alpha = 0.62f),
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(12.dp)
                                            .clip(CircleShape)
                                            .clickable { transform.animateTo(1f, Offset.Zero) },
                                    ) {
                                        Text(
                                            text = "${(transform.scale * 100f).roundToInt()}% · 点击复原",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.White,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                        )
                                    }
                                }

                                // 桌面鼠标右键菜单
                                activeItem?.let { current ->
                                    val isSvg = current.relativePath.endsWith(".svg", ignoreCase = true)
                                    GalleryItemContextMenu(
                                        item = current,
                                        expanded = stageContextMenuExpanded,
                                        onDismiss = { stageContextMenuExpanded = false },
                                        onToggleFavorite = {
                                            if (!current.favorite) haptics.confirm() else haptics.tick()
                                            galleryViewModel.toggleFavorite(current)
                                        },
                                        onReuseParams = { triggerReuse(current) },
                                        onUseAsReference = if (onUseAsReference != null && !isSvg) {
                                            { role -> onUseAsReference(current, role) }
                                        } else null,
                                        supportsDirectorReference = supportsDirectorReference,
                                        supportsVibeTransfer = supportsVibeTransfer,
                                        onInpaint = if (!isSvg) ({ onInpaintImage(current) }) else null,
                                        onCopyPrompt = {
                                            copyToClipboard(context, current.prompt)
                                            scope.launch { snackbarHostState.showSnackbar("已复制提示词") }
                                        },
                                        onDelete = { galleryViewModel.deleteGeneration(current.generationId) },
                                    )
                                }
                            }
                        }
                    }
                }

                // 浏览旧图时新图完成的浮动胶囊提示（4 秒自动消失，点击直达最新）
                androidx.compose.animation.AnimatedVisibility(
                    visible = showNewImageBanner && items.isNotEmpty(),
                    enter = fadeIn(tween(180)) + slideInVertically(tween(200)) { -it },
                    exit = fadeOut(tween(150)) + slideOutVertically(tween(150)) { -it },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        tonalElevation = 6.dp,
                        shadowElevation = 6.dp,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable {
                                haptics.tick()
                                selectIndex(0)
                            },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = "任务完成 · 查看",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }

                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }

            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))

            // 右侧竖向胶片缩略图栏（80dp）
            Column(
                modifier = Modifier
                    .width(82.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = if (items.isEmpty()) "0 / 0" else "${currentIndex + 1} / ${items.size}",
                    style = MaterialTheme.typography.labelSmall.merge(TextStyle(fontFeatureSettings = "tnum")),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))

                LazyColumn(
                    state = filmstripState,
                    contentPadding = PaddingValues(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    if (hasGeneratingSlot) {
                        item(key = "workbench-generating-placeholder") {
                            val activeGen = activeGenerations.firstOrNull()?.generation
                            val preview = activeGen?.id?.let { previews[it] }
                            val sensitive = activeGen?.let(SfwPolicy::isSensitive) ?: false
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .border(
                                        width = if (viewingGenerating) 2.5.dp else 1.dp,
                                        color = if (viewingGenerating) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                    )
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable {
                                        haptics.tick()
                                        targetId = null
                                        viewingGenerating = true
                                        runCatching { stageFocusRequester.requestFocus() }
                                    },
                            ) {
                                GenerationImageReveal(
                                    ready = false,
                                    failed = false,
                                    animate = true,
                                    startedAt = activeGen?.createdAt ?: System.currentTimeMillis(),
                                    onRevealed = {},
                                    queued = activeGen?.status == GenerationStatus.QUEUED,
                                    modifier = Modifier.fillMaxSize(),
                                ) {
                                    if (preview != null) {
                                        SfwAsyncImage(
                                            file = File(preview.path),
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            sensitive = sensitive,
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .graphicsLayer { alpha = 0.3f },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    itemsIndexed(items, key = { _, item -> item.imageId }) { index, item ->
                        val selected = !viewingGenerating && index == currentIndex
                        val isSvg = item.relativePath.endsWith(".svg", ignoreCase = true)
                        var thumbMenuExpanded by remember { mutableStateOf(false) }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .border(
                                    width = if (selected) 2.5.dp else 1.dp,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                )
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .onSecondaryClick { thumbMenuExpanded = true }
                                .then(
                                    if (!isSvg) {
                                        Modifier.mouseImageDragSource(localPath = item.relativePath)
                                    } else Modifier,
                                )
                                .clickable {
                                    haptics.tick()
                                    selectIndex(index)
                                    runCatching { stageFocusRequester.requestFocus() }
                                },
                        ) {
                            SfwAsyncImage(
                                file = container.generationRepository.fileOf(item),
                                contentDescription = item.title,
                                sensitive = item.sensitivePrompt,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                            if (item.favorite) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(4.dp)
                                        .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                                        .padding(2.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Favorite,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(10.dp),
                                    )
                                }
                            }
                            GalleryItemContextMenu(
                                item = item,
                                expanded = thumbMenuExpanded,
                                onDismiss = { thumbMenuExpanded = false },
                                onToggleFavorite = {
                                    if (!item.favorite) haptics.confirm() else haptics.tick()
                                    galleryViewModel.toggleFavorite(item)
                                },
                                onReuseParams = { triggerReuse(item) },
                                onUseAsReference = if (onUseAsReference != null && !isSvg) {
                                    { role -> onUseAsReference(item, role) }
                                } else null,
                                supportsDirectorReference = supportsDirectorReference,
                                supportsVibeTransfer = supportsVibeTransfer,
                                onInpaint = if (!isSvg) ({ onInpaintImage(item) }) else null,
                                onCopyPrompt = {
                                    copyToClipboard(context, item.prompt)
                                    scope.launch { snackbarHostState.showSnackbar("已复制提示词") }
                                },
                                onDelete = { galleryViewModel.deleteGeneration(item.generationId) },
                            )
                        }
                    }
                }
            }
        }

        // ---- 底部极简单排工具栏：左侧精简信息，右侧高频操作 ----
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            val hasActive = activeItem != null
            val activeIsSvg = activeItem?.relativePath?.endsWith(".svg", ignoreCase = true) == true
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 左侧信息组（空间不足时允许轻扫滚动，不挤压右侧主操作）
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = if (syncFilter && filter.isActive) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable {
                                haptics.tick()
                                container.settingsStore.setTabletWorkbenchSyncFilter(!syncFilter)
                            },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.FilterList,
                                contentDescription = "同步应用画廊筛选条件",
                                tint = if (syncFilter) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                text = if (syncFilter) "同步筛选" else "全部图库",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }

                    if (activeItem != null) {
                        Text(
                            text = "${activeItem.width}×${activeItem.height} · ${GalleryModels.labelOf(activeItem.modelApiId)}",
                            style = MaterialTheme.typography.labelSmall.merge(TextStyle(fontFeatureSettings = "tnum")),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                        activeItem.seed?.let { seed ->
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .clickable {
                                        haptics.tick()
                                        copyToClipboard(context, seed.toString())
                                        scope.launch { snackbarHostState.showSnackbar("已复制 Seed：$seed") }
                                    },
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        text = "$seed",
                                        style = MaterialTheme.typography.labelSmall.merge(TextStyle(fontFeatureSettings = "tnum")),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = "复制 Seed",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(12.dp),
                                    )
                                }
                            }
                        }
                    } else if (hasGeneratingSlot) {
                        Text(
                            text = "正在生成中…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                        )
                    }
                }

                // 右侧操作组：次要动作纯圆形图标，高频创作动作紧凑胶囊
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    WorkbenchIconAction(
                        icon = if (activeItem?.favorite == true) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "收藏 (F)",
                        highlighted = activeItem?.favorite == true,
                        enabled = hasActive,
                        onClick = {
                            activeItem?.let {
                                if (!it.favorite) haptics.confirm() else haptics.tick()
                                galleryViewModel.toggleFavorite(it)
                            }
                        },
                    )
                    WorkbenchIconAction(
                        icon = Icons.Default.OpenInFull,
                        contentDescription = "查看详情 (Space)",
                        enabled = hasActive,
                        onClick = { activeItem?.let { openDetail(it) } },
                    )
                    WorkbenchIconAction(
                        icon = Icons.Default.Brush,
                        contentDescription = "局部重绘",
                        enabled = hasActive && !activeIsSvg,
                        onClick = { activeItem?.let { onInpaintImage(it) } },
                    )
                    if (onUseAsReference != null) {
                        WorkbenchActionChip(
                            icon = Icons.Default.AddPhotoAlternate,
                            label = "参考",
                            enabled = hasActive && !activeIsSvg,
                            onClick = { activeItem?.let { triggerUseAsReference(it) } },
                        )
                    }
                    WorkbenchActionChip(
                        icon = Icons.Default.Replay,
                        label = "复用",
                        enabled = hasActive,
                        onClick = { activeItem?.let { triggerReuse(it) } },
                    )
                }
            }
        }
    }

    // 触屏长按：复用既有 GalleryItemActionSheet
    actionTarget?.let { target ->
        GalleryItemActionSheet(
            target = target,
            imageFile = container.generationRepository.fileOf(target),
            onDismiss = { actionTarget = null },
            onToggleFavorite = { galleryViewModel.toggleFavorite(target) },
            onSave = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    saveToSystemGallery(
                        scope = scope,
                        context = context,
                        repository = container.generationRepository,
                        exporter = container.mediaStoreExporter,
                        snackbarHostState = snackbarHostState,
                        item = target,
                    )
                } else {
                    pendingSave = target
                    permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            },
            onShare = {
                val file = container.generationRepository.fileOf(target)
                ImageShareManager.shareSingle(
                    context = context,
                    file = file,
                    title = target.title,
                )
            },
            onCopyPrompt = {
                copyToClipboard(context, target.prompt)
                scope.launch { snackbarHostState.showSnackbar("已复制提示词") }
            },
            onReuseParams = { triggerReuse(target) },
            onUseAsReference = if (onUseAsReference != null && !target.relativePath.endsWith(".svg", ignoreCase = true)) {
                { triggerUseAsReference(target) }
            } else null,
            onInpaint = if (!target.relativePath.endsWith(".svg", ignoreCase = true)) {
                { onInpaintImage(target) }
            } else null,
            onEnterBatchMode = null,
            onDelete = { galleryViewModel.deleteGeneration(target.generationId) },
        )
    }

    referenceTarget?.let { target ->
        ReferenceRoleChooserDialog(
            supportsDirector = supportsDirectorReference,
            supportsVibe = supportsVibeTransfer,
            onSelect = { role ->
                referenceTarget = null
                onUseAsReference?.invoke(target, role)
            },
            onDismiss = { referenceTarget = null },
        )
    }
}

@Composable
private fun WorkbenchActionChip(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    highlighted: Boolean = false,
) {
    val containerColor = when {
        !enabled -> MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.45f)
        highlighted -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        highlighted -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    Surface(
        shape = CircleShape,
        color = containerColor,
        border = if (highlighted && enabled) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
        } else null,
        modifier = Modifier
            .height(34.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Medium,
                color = contentColor,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun WorkbenchIconAction(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
    highlighted: Boolean = false,
) {
    val containerColor = when {
        !enabled -> MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.45f)
        highlighted -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        highlighted -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = CircleShape,
        color = containerColor,
        border = if (highlighted && enabled) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
        } else null,
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = contentColor,
                modifier = Modifier.size(17.dp),
            )
        }
    }
}


private enum class StageContentMode {
    GENERATING,
    LOADING,
    EMPTY,
    PAGER,
}

/** 每张图共用一条缩放/平移动画。滚轮基于目标累积，直接拖拽从当前绘制位置接管。 */
private class StageTransform(private val scope: CoroutineScope) {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set
    var targetScale = 1f
        private set
    var targetOffset = Offset.Zero
        private set
    private var animation: Job? = null
    private var scaleMotion = AnimationState(1f)
    private var panMotion = AnimationState(Offset.VectorConverter, Offset.Zero)

    fun snapTo(nextScale: Float, nextOffset: Offset) {
        animation?.cancel()
        targetScale = nextScale
        targetOffset = nextOffset
        scale = nextScale
        offset = nextOffset
    }

    fun animateTo(nextScale: Float, nextOffset: Offset) {
        if (targetScale == nextScale && targetOffset == nextOffset) return
        val continuing = animation?.isActive == true
        animation?.cancel()
        targetScale = nextScale
        targetOffset = nextOffset
        if (!continuing) {
            scaleMotion = AnimationState(scale)
            panMotion = AnimationState(Offset.VectorConverter, offset)
        }
        animation = scope.launch {
            // 连续刻度沿用上一帧的时间和速度，触控板持续输入也能在输入期间向目标移动。
            launch {
                scaleMotion.animateTo(nextScale, spring(dampingRatio = 1f, stiffness = 650f), continuing) {
                    scale = value.coerceIn(1f, 6f)
                }
            }
            panMotion.animateTo(nextOffset, spring(dampingRatio = 1f, stiffness = 650f), continuing) {
                offset = value
            }
        }
    }
}
