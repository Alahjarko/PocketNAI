package net.pocketnai.ui.home

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import net.pocketnai.R
import net.pocketnai.domain.model.GalleryFilter
import net.pocketnai.domain.model.GalleryItem
import net.pocketnai.domain.model.GalleryModels
import net.pocketnai.domain.model.GallerySearch
import net.pocketnai.domain.model.GenerationStatus
import net.pocketnai.domain.model.ReferenceRole
import net.pocketnai.domain.safety.SfwPolicy
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

    val syncFilter by container.settingsStore.tabletWorkbenchSyncFilter.collectAsStateWithLifecycle()
    val filteredItems by galleryViewModel.items.collectAsStateWithLifecycle()
    val hasMoreFiltered by galleryViewModel.hasMore.collectAsStateWithLifecycle()
    val filter by galleryViewModel.filter.collectAsStateWithLifecycle()
    val generations by galleryViewModel.generations.collectAsStateWithLifecycle()
    val previews by container.generationPreviewStore.previews.collectAsStateWithLifecycle()
    val undoGenerationIds by galleryViewModel.undoGenerationIds.collectAsStateWithLifecycle()

    // 关闭「同步应用画廊筛选条件」时读取全量画廊流，互不干扰；同步开启时不订阅冗余查询。
    var unfilteredLimit by remember { mutableIntStateOf(120) }
    val unfilteredPage by remember(container, unfilteredLimit, syncFilter) {
        if (syncFilter) {
            flowOf(false to emptyList())
        } else {
            combine(
                container.generationRepository.observeGalleryWindow(GalleryFilter.None, unfilteredLimit),
                container.favoriteImageRepository.observeFavoriteIds(),
            ) { page, favIds ->
                page.hasMore to page.items.map { item -> item.copy(favorite = item.imageId in favIds) }
            }
        }
    }.collectAsStateWithLifecycle(initialValue = false to emptyList())
    val hasMoreUnfiltered = unfilteredPage.first
    val items = if (syncFilter) filteredItems else unfilteredPage.second
    val hasMore = if (syncFilter) hasMoreFiltered else hasMoreUnfiltered

    val activeGenerations = remember(generations, filter, syncFilter) {
        generations.filter { summary ->
            summary.generation.isActive && (!syncFilter || GallerySearch.matches(summary.generation, filter))
        }
    }
    val hasGeneratingSlot = activeGenerations.isNotEmpty() || inFlight

    var activeImageId by rememberSaveable { mutableStateOf(selectedImageId) }
    var slideDirection by remember { mutableIntStateOf(1) }
    var viewingGenerating by remember { mutableStateOf(false) }
    var previousTopImageId by remember { mutableStateOf<String?>(null) }
    var showNewImageBanner by remember { mutableStateOf(false) }

    LaunchedEffect(selectedImageId) {
        if (selectedImageId != null && selectedImageId != activeImageId) {
            activeImageId = selectedImageId
        }
    }

    val currentIndex = remember(items, activeImageId) {
        if (items.isEmpty()) 0
        else items.indexOfFirst { it.imageId == activeImageId }.let { if (it >= 0) it else 0 }
    }
    val activeItem = if (viewingGenerating && hasGeneratingSlot) null else items.getOrNull(currentIndex)
    LaunchedEffect(activeItem?.imageId) {
        onSelectedImageIdChange?.invoke(activeItem?.imageId)
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

    // 实时生成同步：位于第 1 张（或正在看占位卡片）时自动切换至新图；浏览旧图时弹出轻提示。
    val topImageId = items.firstOrNull()?.imageId
    LaunchedEffect(topImageId) {
        if (topImageId == null) {
            previousTopImageId = null
            return@LaunchedEffect
        }
        val prevTop = previousTopImageId
        if (prevTop == null) {
            if (activeImageId == null) activeImageId = topImageId
        } else if (topImageId != prevTop) {
            val wasAtLatest = activeImageId == null || activeImageId == prevTop || viewingGenerating
            if (wasAtLatest) {
                activeImageId = topImageId
                viewingGenerating = false
                showNewImageBanner = false
                filmstripState.animateScrollToItem(0)
            } else {
                showNewImageBanner = true
            }
        }
        previousTopImageId = topImageId
    }

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
    LaunchedEffect(currentIndex, items.size) {
        if (items.isNotEmpty() && !viewingGenerating) {
            val listIdx = currentIndex + if (hasGeneratingSlot) 1 else 0
            val visible = filmstripState.layoutInfo.visibleItemsInfo
            if (visible.none { it.index == listIdx }) {
                filmstripState.animateScrollToItem(listIdx.coerceAtLeast(0))
            }
            if (hasMore && currentIndex >= items.size - 16) {
                if (syncFilter) galleryViewModel.loadMore()
                else unfilteredLimit = (unfilteredLimit + 120).coerceAtMost(Int.MAX_VALUE - 1)
            }
        }
    }

    LaunchedEffect(filmstripState, syncFilter, hasMore) {
        snapshotFlow {
            filmstripState.layoutInfo.let { info ->
                Triple(info.visibleItemsInfo.lastOrNull()?.index ?: -1, info.totalItemsCount, hasMore)
            }
        }.collect { (last, count, more) ->
            if (more && count > 0 && last >= count - 12) {
                if (syncFilter) galleryViewModel.loadMore()
                else unfilteredLimit = (unfilteredLimit + 120).coerceAtMost(Int.MAX_VALUE - 1)
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
        slideDirection = if (index < currentIndex) -1 else 1
        viewingGenerating = false
        activeImageId = target.imageId
        if (index == 0) showNewImageBanner = false
    }

    fun stepPrev() {
        if (items.isEmpty()) return
        if (viewingGenerating) return
        if (currentIndex > 0) {
            haptics.tick()
            selectIndex(currentIndex - 1)
        }
    }

    fun stepNext() {
        if (items.isEmpty()) return
        if (viewingGenerating) {
            haptics.tick()
            selectIndex(0)
            return
        }
        if (currentIndex < items.lastIndex) {
            haptics.tick()
            selectIndex(currentIndex + 1)
        }
    }

    fun openDetail(item: GalleryItem) {
        if (openingImage) return
        openingImage = true
        val file = container.generationRepository.fileOf(item)
        imageMotion?.preview = ImageOriginPreview(item.imageId, file, item.aspectRatio, item.title, item.sensitivePrompt)
        scope.launch {
            try {
                val order = if (syncFilter) galleryViewModel.fullOrder()
                else container.generationRepository.galleryOrder(GalleryFilter.None)
                container.galleryOrderSnapshot.publish(order)
                onOpenImage(item.imageId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
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

    // 舞台缩放、平移与未放大时的竖向滑动切图状态（切换图片时自动重置至 100%）
    val density = LocalDensity.current
    val swipeThresholdPx = with(density) { 64.dp.toPx() }
    val scaleAnim = remember(activeItem?.imageId) { Animatable(1f) }
    val offsetXAnim = remember(activeItem?.imageId) { Animatable(0f) }
    val offsetYAnim = remember(activeItem?.imageId) { Animatable(0f) }
    val swipeOffsetYAnim = remember(activeItem?.imageId) { Animatable(0f) }
    var stageSize by remember { mutableStateOf(Size.Zero) }
    var lastWheelStepMs by remember { mutableLongStateOf(0L) }
    val zoomSpring = remember {
        spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
    }

    fun maxPanBounds(forScale: Float, imageWidth: Int, imageHeight: Int): Offset {
        if (stageSize.width <= 0f || stageSize.height <= 0f) return Offset.Zero
        val imageAspect = if (imageHeight > 0) imageWidth.toFloat() / imageHeight else 1f
        val containerAspect = stageSize.width / stageSize.height
        val display = if (imageAspect > containerAspect) {
            Size(stageSize.width, stageSize.width / imageAspect)
        } else {
            Size(stageSize.height * imageAspect, stageSize.height)
        }
        val maxX = ((display.width * forScale - stageSize.width) / 2f).coerceAtLeast(0f)
        val maxY = ((display.height * forScale - stageSize.height) / 2f).coerceAtLeast(0f)
        return Offset(maxX, maxY)
    }

    fun clampOffset(candidate: Offset, forScale: Float, item: GalleryItem): Offset {
        val max = maxPanBounds(forScale, item.width, item.height)
        return Offset(candidate.x.coerceIn(-max.x, max.x), candidate.y.coerceIn(-max.y, max.y))
    }

    fun resetZoom() {
        scope.launch {
            coroutineScope {
                launch { scaleAnim.animateTo(1f, zoomSpring) }
                launch { offsetXAnim.animateTo(0f, zoomSpring) }
                launch { offsetYAnim.animateTo(0f, zoomSpring) }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .focusRequester(stageFocusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown ||
                    event.isCtrlPressed || event.isAltPressed || event.isMetaPressed ||
                    actionTarget != null || referenceTarget != null || stageContextMenuExpanded
                ) {
                    return@onPreviewKeyEvent false
                }
                when (event.key) {
                    Key.A, Key.DirectionLeft -> {
                        stepPrev()
                        true
                    }
                    Key.D, Key.DirectionRight -> {
                        stepNext()
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
            },
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
                    .onSizeChanged { stageSize = Size(it.width.toFloat(), it.height.toFloat()) }
                    .onMouseWheel { deltaY, isCtrlPressed ->
                        if (isCtrlPressed) {
                            val item = activeItem ?: return@onMouseWheel
                            val factor = if (deltaY < 0f) 1.15f else 1f / 1.15f
                            val nextScale = (scaleAnim.value * factor).coerceIn(1f, 6f)
                            val nextOffset = if (nextScale <= 1.02f) {
                                Offset.Zero
                            } else {
                                clampOffset(Offset(offsetXAnim.value, offsetYAnim.value), nextScale, item)
                            }
                            scope.launch {
                                scaleAnim.snapTo(if (nextScale <= 1.02f) 1f else nextScale)
                                offsetXAnim.snapTo(nextOffset.x)
                                offsetYAnim.snapTo(nextOffset.y)
                            }
                        } else if (deltaY != 0f) {
                            val now = System.currentTimeMillis()
                            if (now - lastWheelStepMs >= 110L) {
                                lastWheelStepMs = now
                                if (deltaY > 0f) stepNext() else stepPrev()
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
                                if (scaleAnim.value > 1.05f) {
                                    resetZoom()
                                } else {
                                    val targetScale = 2.2f
                                    val center = Offset(stageSize.width / 2f, stageSize.height / 2f)
                                    val delta = tap - center
                                    val target = clampOffset(
                                        Offset(-delta.x * (targetScale - 1f), -delta.y * (targetScale - 1f)),
                                        targetScale,
                                        item,
                                    )
                                    scope.launch {
                                        coroutineScope {
                                            launch { scaleAnim.animateTo(targetScale, zoomSpring) }
                                            launch { offsetXAnim.animateTo(target.x, zoomSpring) }
                                            launch { offsetYAnim.animateTo(target.y, zoomSpring) }
                                        }
                                    }
                                }
                            },
                            onLongPress = {
                                val item = activeItem ?: return@detectTapGestures
                                haptics.longPress()
                                actionTarget = item
                            },
                        )
                    }
                    .pointerInput(activeItem?.imageId, currentIndex, items.size) {
                        val item = activeItem ?: return@pointerInput
                        val touchSlop = viewConfiguration.touchSlop
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var totalVerticalDrag = 0f
                            var totalHorizontalDrag = 0f
                            var isVerticalSwipe = false
                            var lastDragTimeMs = down.uptimeMillis
                            var dragVelocityY = 0f
                            do {
                                val event = awaitPointerEvent()
                                if (event.buttons.isSecondaryPressed) break
                                val pressedCount = event.changes.count { it.pressed }
                                if (pressedCount >= 2) {
                                    if (isVerticalSwipe) {
                                        isVerticalSwipe = false
                                        scope.launch { swipeOffsetYAnim.snapTo(0f) }
                                    }
                                    val zoomChange = event.calculateZoom()
                                    val panChange = event.calculatePan()
                                    val nextScale = (scaleAnim.value * zoomChange).coerceIn(1f, 6f)
                                    val nextOffset = if (nextScale <= 1.02f) {
                                        Offset.Zero
                                    } else {
                                        clampOffset(
                                            Offset(offsetXAnim.value, offsetYAnim.value) + panChange,
                                            nextScale,
                                            item,
                                        )
                                    }
                                    scope.launch {
                                        scaleAnim.snapTo(nextScale)
                                        offsetXAnim.snapTo(nextOffset.x)
                                        offsetYAnim.snapTo(nextOffset.y)
                                    }
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                } else if (pressedCount == 1 && scaleAnim.value > 1.02f) {
                                    val pan = event.calculatePan()
                                    if (pan != Offset.Zero) {
                                        val nextOffset = clampOffset(
                                            Offset(offsetXAnim.value, offsetYAnim.value) + pan,
                                            scaleAnim.value,
                                            item,
                                        )
                                        scope.launch {
                                            offsetXAnim.snapTo(nextOffset.x)
                                            offsetYAnim.snapTo(nextOffset.y)
                                        }
                                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                                    }
                                } else if (pressedCount == 1 && scaleAnim.value <= 1.02f) {
                                    val change = event.changes.firstOrNull { it.pressed }
                                    val pan = event.calculatePan()
                                    if (pan != Offset.Zero) {
                                        totalHorizontalDrag += pan.x
                                        totalVerticalDrag += pan.y
                                        if (!isVerticalSwipe &&
                                            kotlin.math.abs(totalVerticalDrag) > touchSlop &&
                                            kotlin.math.abs(totalVerticalDrag) > kotlin.math.abs(totalHorizontalDrag) * 1.1f
                                        ) {
                                            isVerticalSwipe = true
                                        }
                                        if (isVerticalSwipe) {
                                            val nowMs = change?.uptimeMillis ?: lastDragTimeMs
                                            val dt = (nowMs - lastDragTimeMs).coerceAtLeast(1L)
                                            dragVelocityY = (pan.y / dt) * 1000f
                                            lastDragTimeMs = nowMs
                                            // 首尾边界柔和阻尼，非边界跟随手指移动。
                                            val atTopEdge = totalVerticalDrag > 0f && currentIndex <= 0
                                            val atBottomEdge = totalVerticalDrag < 0f && currentIndex >= items.lastIndex
                                            val damped = if (atTopEdge || atBottomEdge) totalVerticalDrag * 0.28f else totalVerticalDrag * 0.72f
                                            scope.launch { swipeOffsetYAnim.snapTo(damped) }
                                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                                        }
                                    }
                                }
                            } while (event.changes.any { it.pressed })

                            if (isVerticalSwipe) {
                                val fastSwipeUp = dragVelocityY < -650f && totalVerticalDrag < -touchSlop
                                val fastSwipeDown = dragVelocityY > 650f && totalVerticalDrag > touchSlop
                                val triggerNext = (totalVerticalDrag <= -swipeThresholdPx || fastSwipeUp) && currentIndex < items.lastIndex
                                val triggerPrev = (totalVerticalDrag >= swipeThresholdPx || fastSwipeDown) && currentIndex > 0
                                when {
                                    triggerNext -> {
                                        scope.launch { swipeOffsetYAnim.snapTo(0f) }
                                        stepNext()
                                    }
                                    triggerPrev -> {
                                        scope.launch { swipeOffsetYAnim.snapTo(0f) }
                                        stepPrev()
                                    }
                                    else -> {
                                        scope.launch {
                                            swipeOffsetYAnim.animateTo(
                                                0f,
                                                spring(
                                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                                    stiffness = Spring.StiffnessMediumLow,
                                                ),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                when {
                    (viewingGenerating || items.isEmpty()) && hasGeneratingSlot -> {
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

                    activeItem == null -> {
                        CenteredHint(
                            text = stringResource(
                                if (syncFilter && filter.isActive) R.string.gallery_no_match else R.string.gallery_empty,
                            ),
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }

                    else -> {
                        AnimatedContent(
                            targetState = activeItem,
                            contentKey = { it.imageId },
                            label = "workbench-stage-slide",
                            transitionSpec = {
                                val dir = slideDirection
                                (slideInVertically(
                                    animationSpec = tween(240, easing = FastOutSlowInEasing),
                                ) { fullHeight -> dir * (fullHeight / 3) } +
                                    fadeIn(tween(220, easing = FastOutSlowInEasing)))
                                    .togetherWith(
                                        slideOutVertically(
                                            animationSpec = tween(200, easing = FastOutSlowInEasing),
                                        ) { fullHeight -> -dir * (fullHeight / 3) } +
                                            fadeOut(tween(160, easing = FastOutSlowInEasing)),
                                    )
                            },
                            modifier = Modifier.fillMaxSize(),
                        ) { stageItem ->
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
                                Box(
                                    modifier = fittedModifier
                                        .graphicsLayer {
                                            scaleX = scaleAnim.value
                                            scaleY = scaleAnim.value
                                            translationX = offsetXAnim.value
                                            translationY = offsetYAnim.value + swipeOffsetYAnim.value
                                        }
                                        .clip(RoundedCornerShape(12.dp)),
                                ) {
                                    SfwAsyncImage(
                                        file = imageFile,
                                        contentDescription = stageItem.title,
                                        sensitive = stageItem.sensitivePrompt,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .sharedImage(stageItem.imageId, stageItem.sensitivePrompt),
                                    )
                                }
                            }
                        }

                        // 缩放倍率轻提示（大于 105% 时显示，点击一键复原）
                        if (scaleAnim.value > 1.05f) {
                            Surface(
                                shape = CircleShape,
                                color = Color.Black.copy(alpha = 0.62f),
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(12.dp)
                                    .clip(CircleShape)
                                    .clickable { resetZoom() },
                            ) {
                                Text(
                                    text = "${(scaleAnim.value * 100f).roundToInt()}% · 点击复原",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                )
                            }
                        }

                        // 桌面鼠标右键菜单
                        val isSvg = activeItem.relativePath.endsWith(".svg", ignoreCase = true)
                        GalleryItemContextMenu(
                            item = activeItem,
                            expanded = stageContextMenuExpanded,
                            onDismiss = { stageContextMenuExpanded = false },
                            onToggleFavorite = {
                                if (!activeItem.favorite) haptics.confirm() else haptics.tick()
                                galleryViewModel.toggleFavorite(activeItem)
                            },
                            onReuseParams = { triggerReuse(activeItem) },
                            onUseAsReference = if (onUseAsReference != null && !isSvg) {
                                { role -> onUseAsReference(activeItem, role) }
                            } else null,
                            supportsDirectorReference = supportsDirectorReference,
                            supportsVibeTransfer = supportsVibeTransfer,
                            onInpaint = if (!isSvg) ({ onInpaintImage(activeItem) }) else null,
                            onCopyPrompt = {
                                copyToClipboard(context, activeItem.prompt)
                                scope.launch { snackbarHostState.showSnackbar("已复制提示词") }
                            },
                            onDelete = { galleryViewModel.deleteGeneration(activeItem.generationId) },
                        )
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
                                scope.launch { filmstripState.animateScrollToItem(0) }
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

        // ---- 底部紧凑信息与快捷操作栏 ----
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Row 1: 同步画廊筛选条件 + 元数据摘要（含点击复制 Seed）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (syncFilter && filter.isActive) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                haptics.tick()
                                container.settingsStore.setTabletWorkbenchSyncFilter(!syncFilter)
                            },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                imageVector = if (syncFilter) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                                contentDescription = null,
                                tint = if (syncFilter) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = "同步应用画廊筛选条件",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                            )
                        }
                    }

                    if (activeItem != null) {
                        Text(
                            text = "${currentIndex + 1} / ${items.size} · ${activeItem.width}×${activeItem.height}",
                            style = MaterialTheme.typography.labelMedium.merge(TextStyle(fontFeatureSettings = "tnum")),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                        activeItem.seed?.let { seed ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        haptics.tick()
                                        copyToClipboard(context, seed.toString())
                                        scope.launch { snackbarHostState.showSnackbar("已复制 Seed：$seed") }
                                    },
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        text = "Seed $seed",
                                        style = MaterialTheme.typography.labelSmall.merge(TextStyle(fontFeatureSettings = "tnum")),
                                        color = MaterialTheme.colorScheme.onSurface,
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
                        Text(
                            text = GalleryModels.labelOf(activeItem.modelApiId),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    } else if (hasGeneratingSlot) {
                        Text(
                            text = "正在生成中…",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                        )
                    }
                }

                // Row 2: 紧凑快捷操作栏
                val hasActive = activeItem != null
                val activeIsSvg = activeItem?.relativePath?.endsWith(".svg", ignoreCase = true) == true
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    WorkbenchActionChip(
                        icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        label = "上一张 (A)",
                        enabled = items.isNotEmpty() && !viewingGenerating && currentIndex > 0,
                        onClick = { stepPrev() },
                    )
                    WorkbenchActionChip(
                        icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        label = "下一张 (D)",
                        enabled = items.isNotEmpty() && (viewingGenerating || currentIndex < items.lastIndex),
                        onClick = { stepNext() },
                    )
                    WorkbenchActionChip(
                        icon = if (activeItem?.favorite == true) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        label = "收藏 (F)",
                        highlighted = activeItem?.favorite == true,
                        enabled = hasActive,
                        onClick = {
                            activeItem?.let {
                                if (!it.favorite) haptics.confirm() else haptics.tick()
                                galleryViewModel.toggleFavorite(it)
                            }
                        },
                    )
                    WorkbenchActionChip(
                        icon = Icons.Default.OpenInFull,
                        label = "查看详情",
                        enabled = hasActive,
                        onClick = { activeItem?.let { openDetail(it) } },
                    )
                    WorkbenchActionChip(
                        icon = Icons.Default.Replay,
                        label = "复用参数",
                        enabled = hasActive,
                        onClick = { activeItem?.let { triggerReuse(it) } },
                    )
                    if (onUseAsReference != null) {
                        WorkbenchActionChip(
                            icon = Icons.Default.AddPhotoAlternate,
                            label = "用作参考图",
                            enabled = hasActive && !activeIsSvg,
                            onClick = { activeItem?.let { triggerUseAsReference(it) } },
                        )
                    }
                    WorkbenchActionChip(
                        icon = Icons.Default.Brush,
                        label = "局部重绘",
                        enabled = hasActive && !activeIsSvg,
                        onClick = { activeItem?.let { onInpaintImage(it) } },
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
        shape = RoundedCornerShape(10.dp),
        color = containerColor,
        border = if (highlighted && enabled) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
        } else null,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
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
