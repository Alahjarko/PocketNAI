package net.pocketnai.ui.gallery

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import java.io.File
import net.pocketnai.ui.common.ImageShareManager
import net.pocketnai.ui.common.MotionIconButton
import net.pocketnai.ui.common.PillSearchField
import net.pocketnai.ui.motion.pressFeedback
import net.pocketnai.ui.safety.SfwAsyncImage
import net.pocketnai.ui.settings.SettingsCard
import net.pocketnai.ui.settings.SettingsIcon
import net.pocketnai.ui.settings.settingsClickable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.pocketnai.R
import net.pocketnai.core.ErrorCode
import net.pocketnai.core.Outcome
import net.pocketnai.data.export.MediaStoreExporter
import net.pocketnai.data.repo.GenerationRepository
import net.pocketnai.domain.model.GalleryFilter
import net.pocketnai.domain.model.GalleryItem
import net.pocketnai.domain.model.GenerationMode
import net.pocketnai.domain.model.GalleryModels
import net.pocketnai.domain.model.ReferenceRole
import net.pocketnai.domain.prompt.PromptTitle
import net.pocketnai.ui.LocalAppContainer
import net.pocketnai.ui.generate.ReferenceRoleChooserDialog
import net.pocketnai.ui.motion.imageMotionChrome
import net.pocketnai.ui.common.CenteredHint
import net.pocketnai.ui.common.labelRes
import net.pocketnai.ui.common.messageRes

/**
 * 瀑布流画廊内容（规划书 4.3）。
 *
 * 这是**内容**而不是整页：它由 `BottomSheetScaffold` 承载，
 * 生成悬浮层浮在它上面；正在生成的任务在顶部显示为占位卡片，
 * 因此把悬浮层收起来就能看着图片陆续出现。
 *
 * 顶部是筛选栏（关键词 / 模型 / 模式 / 仅收藏），长按图片弹出操作菜单：
 * 收藏、保存到系统相册、删除本地记录、复制正向提示词。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GalleryPane(
    onOpenImage: (String) -> Unit,
    /**
     * 对这张图做局部重绘。
     *
     * 官方文档明确说局部重绘可以从"任意一张已生成的图片"进入，因此这里是画廊的入口：
     * 用户看到一张构图不错但某处画坏的图，最自然的动作就是在这里直接改它。
     */
    onInpaintImage: (GalleryItem) -> Unit,
    modifier: Modifier = Modifier,
    onUseAsReference: ((GalleryItem, ReferenceRole) -> Unit)? = null,
    supportsDirectorReference: Boolean = false,
    supportsVibeTransfer: Boolean = false,
) {
    val container = LocalAppContainer.current
    val viewModel: GalleryViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                GalleryViewModel(
                    repository = container.generationRepository,
                    favorites = container.favoriteImageRepository,
                )
            }
        },
    )
    val items by viewModel.items.collectAsStateWithLifecycle()
    val hasMore by viewModel.hasMore.collectAsStateWithLifecycle()
    val generations by viewModel.generations.collectAsStateWithLifecycle()
    val previews by container.generationPreviewStore.previews.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val undoGenerationId by viewModel.undoGenerationId.collectAsStateWithLifecycle()
    val undoGenerationIds by viewModel.undoGenerationIds.collectAsStateWithLifecycle()
    val isSelectionMode by viewModel.isSelectionMode.collectAsStateWithLifecycle()
    val selectedImageIds by viewModel.selectedImageIds.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val gridState = androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState()
    val snackbarHostState = remember { SnackbarHostState() }
    var actionTarget by remember { mutableStateOf<GalleryItem?>(null) }
    var referenceTarget by remember { mutableStateOf<GalleryItem?>(null) }
    var pendingSave by remember { mutableStateOf<GalleryItem?>(null) }
    var pendingBatchSave by remember { mutableStateOf<List<GalleryItem>?>(null) }
    var confirmBatchDelete by remember { mutableStateOf(false) }
    var openingImage by remember { mutableStateOf(false) }
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.layoutInfo.let { info ->
            Triple(info.visibleItemsInfo.lastOrNull()?.index ?: -1, info.totalItemsCount, hasMore)
        } }.collect { (last, count, more) -> if (more && count > 0 && last >= count - 16) viewModel.loadMore() }
    }

    BackHandler(enabled = isSelectionMode) {
        viewModel.exitSelectionMode()
    }

    // Android 9 及以下需要写外部存储权限才能保存到相册；Android 10+ 走 MediaStore 不需要。
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val target = pendingSave
        pendingSave = null
        val batchTargets = pendingBatchSave
        pendingBatchSave = null
        if (granted) {
            if (target != null) {
                saveToSystemGallery(
                    scope = scope,
                    context = context,
                    repository = container.generationRepository,
                    exporter = container.mediaStoreExporter,
                    snackbarHostState = snackbarHostState,
                    item = target,
                )
            } else if (!batchTargets.isNullOrEmpty()) {
                saveMultipleToSystemGallery(
                    scope = scope,
                    context = context,
                    repository = container.generationRepository,
                    exporter = container.mediaStoreExporter,
                    snackbarHostState = snackbarHostState,
                    items = batchTargets,
                )
            }
        }
    }

    val deletedMessage = stringResource(R.string.action_delete)
    val undoLabel = stringResource(R.string.action_cancel)
    LaunchedEffect(undoGenerationIds) {
        if (undoGenerationIds.isEmpty()) return@LaunchedEffect
        val message = if (undoGenerationIds.size > 1) {
            "已删除 ${undoGenerationIds.size} 项"
        } else {
            deletedMessage
        }
        val result = snackbarHostState.showSnackbar(
            message = message,
            actionLabel = undoLabel,
            withDismissAction = true,
        )
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.undoDelete()
        } else {
            viewModel.clearUndo()
        }
    }

    var motionRevision by remember { mutableStateOf(0) }
    val sections = remember(items, generations, filter, isSelectionMode, motionRevision) {
        viewModel.motionSlots.sections(items, generations, !isSelectionMode, filter = filter)
    }
    Column(modifier = modifier.fillMaxSize()) {
        val haptics = net.pocketnai.ui.motion.rememberPocketHaptics()
        val sfw = net.pocketnai.ui.safety.LocalSfwDisplay.current
        AnimatedContent(
            targetState = isSelectionMode,
            label = "gallery-top-bar",
            transitionSpec = {
                (fadeIn(tween(150)) + slideInVertically(
                    spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 750f),
                ) { if (targetState) -it / 4 else it / 4 }) togetherWith fadeOut(tween(100))
            },
        ) { selectionActive ->
            if (selectionActive) {
                GallerySelectionTopBar(
                    selectedCount = selectedImageIds.size,
                    allSelected = items.isNotEmpty() && selectedImageIds.size == items.size,
                    onSelectAllToggle = {
                        haptics.tick()
                        if (selectedImageIds.size == items.size) {
                            viewModel.deselectAll()
                        } else {
                            viewModel.selectAllMatching()
                        }
                    },
                    onExit = {
                        haptics.tick()
                        viewModel.exitSelectionMode()
                    },
                )
            } else {
                GalleryFilterBar(
                    filter = filter,
                    onQueryChange = viewModel::onQueryChange,
                    onModelsChange = viewModel::onModelsChange,
                    onModeChange = viewModel::onModeChange,
                    onFavoritesOnlyChange = viewModel::onFavoritesOnlyChange,
                    onClearAll = viewModel::clearFilter,
                    onEnterSelectionMode = {
                        haptics.tick()
                        viewModel.enterSelectionMode()
                    },
                )
            }
        }
        GalleryTopBarDivider(gridState)

        Box(modifier = Modifier.fillMaxSize()) {
            if (sections.isEmpty()) {
                CenteredHint(
                    // 区分"还没有历史"与"筛掉了"：前者要告诉用户去生成，后者要告诉他是筛选在起作用。
                    text = stringResource(
                        if (filter.isActive) R.string.gallery_no_match else R.string.gallery_empty,
                    ),
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                GalleryMotionGrid(
                    state = gridState,
                    sections = sections,
                    fileOf = container.generationRepository::fileOf,
                    previews = previews,
                    isSelectionMode = isSelectionMode,
                    selectedIds = selectedImageIds,
                    onClick = { item ->
                        if (isSelectionMode) {
                            haptics.tick()
                            viewModel.toggleSelect(item.imageId)
                        } else {
                            if (!openingImage) {
                                if (sfw.masks(container.generationRepository.fileOf(item), item.sensitivePrompt)) {
                                    haptics.tick()
                                }
                                openingImage = true
                                scope.launch {
                                    try {
                                        container.galleryOrderSnapshot.publish(viewModel.fullOrder())
                                        onOpenImage(item.imageId)
                                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                    catch (_: Exception) { snackbarHostState.showSnackbar("无法读取图片列表，请稍后再试") }
                                    finally { openingImage = false }
                                }
                            }
                        }
                    },
                    onLongClick = { item ->
                        haptics.longPress()
                        if (isSelectionMode) viewModel.toggleSelect(item.imageId) else actionTarget = item
                    },
                    onRevealed = { viewModel.motionSlots.finish(it); motionRevision++ },
                    onDismissFailure = { viewModel.motionSlots.dismiss(it); motionRevision++ },
                    onDragSelect = viewModel::applyDragSelection,
                )
            }

            if (!isSelectionMode && sections.isNotEmpty()) {
                GalleryScrollToTop(gridState, Modifier.align(Alignment.BottomEnd).padding(16.dp))
            }

            androidx.compose.animation.AnimatedVisibility(
                visible = isSelectionMode,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(
                    spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 620f),
                ) { it } + fadeIn(tween(140)),
                exit = slideOutVertically(tween(120)) { it } + fadeOut(tween(100)),
            ) {
                GallerySelectionBottomBar(
                    selectedCount = selectedImageIds.size,
                    onSave = {
                        haptics.tick()
                        val selectedItems = items.filter { it.imageId in selectedImageIds }
                        if (selectedItems.isNotEmpty()) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
                                ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                                ) == PackageManager.PERMISSION_GRANTED
                            ) {
                                saveMultipleToSystemGallery(
                                    scope = scope,
                                    context = context,
                                    repository = container.generationRepository,
                                    exporter = container.mediaStoreExporter,
                                    snackbarHostState = snackbarHostState,
                                    items = selectedItems,
                                )
                            } else {
                                pendingBatchSave = selectedItems
                                permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            }
                        }
                    },
                    onFavorite = {
                        haptics.confirm()
                        viewModel.batchToggleFavorite(items)
                    },
                    onShare = {
                        haptics.tick()
                        val selectedItems = items.filter { it.imageId in selectedImageIds }
                        val files = selectedItems.map { container.generationRepository.fileOf(it) }
                        ImageShareManager.shareMultiple(
                            context = context,
                            files = files,
                            title = "PocketNAI",
                        )
                    },
                    onDelete = {
                        haptics.tick()
                        confirmBatchDelete = true
                    },
                )
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (isSelectionMode) 72.dp else 0.dp),
            )
        }
    }

    actionTarget?.let { target ->
        GalleryItemActionSheet(
            target = target,
            imageFile = container.generationRepository.fileOf(target),
            onDismiss = { actionTarget = null },
            onToggleFavorite = { viewModel.toggleFavorite(target) },
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
                // 只复制用户看到的正向提示词，绝不复制 Token 或隐藏请求字段（规划书第 10 节）。
                copyToClipboard(context, target.prompt)
                scope.launch { snackbarHostState.showSnackbar("已复制提示词") }
            },
            onUseAsReference = if (onUseAsReference != null && !target.relativePath.endsWith(".svg", ignoreCase = true)) {
                {
                    if (supportsDirectorReference || supportsVibeTransfer) {
                        referenceTarget = target
                    } else {
                        onUseAsReference(target, ReferenceRole.IMG2IMG)
                    }
                }
            } else null,
            onInpaint = if (!target.relativePath.endsWith(".svg", ignoreCase = true)) {
                { onInpaintImage(target) }
            } else null,
            onEnterBatchMode = { viewModel.enterSelectionMode(target.imageId) },
            onDelete = { viewModel.deleteGeneration(target.generationId) },
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

    if (confirmBatchDelete) {
        val count = selectedImageIds.size
        AlertDialog(
            onDismissRequest = { confirmBatchDelete = false },
            title = { Text(stringResource(R.string.batch_delete_confirm_title, count)) },
            text = { Text(stringResource(R.string.batch_delete_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmBatchDelete = false
                        viewModel.batchDelete(items)
                    },
                ) {
                    Text(
                        stringResource(R.string.action_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmBatchDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/**
 * 筛选栏：关键词 + 三个条件。
 *
 * 关键词框的内容存在本地 `TextFieldValue` 里，而不是直接绑 `filter.query`：
 * 每次重组都从筛选条件重建文本会把光标位置打回末尾，用户打第三个字时就会发现
 * 光标乱跳。两边只有在"清除筛选"时才需要显式同步。
 */
/**
 * 筛选栏：搜索框 + 一个筛选入口（带激活数量角标）。
 *
 * 早年是搜索框下一排四个 chip（多选/仅收藏/模型/模式），360dp 排不下、
 * 最后一个被右边缘裁掉，而且"能横滚"没有任何视觉提示（2026-09-21 界面减负）：
 * 模型/模式/仅收藏收进筛选对话框，多选收进同一对话框与长按菜单。
 */
@Composable
private fun GalleryTopBarDivider(state: androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState) {
    val scrolled by remember(state) {
        derivedStateOf { state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 0 }
    }
    val alpha by animateFloatAsState(
        targetValue = if (scrolled) 0.45f else 0f,
        animationSpec = tween(160),
        label = "gallery-top-divider",
    )
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier
            .imageMotionChrome()
            .graphicsLayer { this.alpha = alpha },
    )
}

@Composable
private fun GalleryFilterBar(
    filter: GalleryFilter,
    onQueryChange: (String) -> Unit,
    onModelsChange: (Set<String>) -> Unit,
    onModeChange: (GenerationMode?) -> Unit,
    onFavoritesOnlyChange: (Boolean) -> Unit,
    onClearAll: () -> Unit,
    onEnterSelectionMode: () -> Unit,
) {
    var queryField by remember { mutableStateOf(TextFieldValue(filter.query)) }
    val haptics = net.pocketnai.ui.motion.rememberPocketHaptics()

    var filterDialogOpen by remember { mutableStateOf(false) }
    LaunchedEffect(filter.query) { if (filter.query != queryField.text) queryField = TextFieldValue(filter.query) }

    // 胶囊右端计数只数"藏起来"的条件：关键词就写在搜索框里，不必再数一遍。
    val hiddenActiveCount = (if (filter.modelApiIds.isNotEmpty()) 1 else 0) +
        (if (filter.mode != null) 1 else 0) +
        (if (filter.favoritesOnly) 1 else 0)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .imageMotionChrome()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        PillSearchField(
            value = queryField,
            onValueChange = {
                queryField = it
                onQueryChange(it.text)
            },
            placeholder = stringResource(R.string.gallery_search_hint),
            clearContentDescription = stringResource(R.string.gallery_filter_clear),
            onClear = {
                queryField = TextFieldValue("")
                onQueryChange("")
            },
            modifier = Modifier.fillMaxWidth(),
            trailing = {
                val filterInteraction = remember { MutableInteractionSource() }
                val active = hiddenActiveCount > 0
                val containerColor by animateColorAsState(
                    targetValue = if (active) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                    animationSpec = tween(150),
                    label = "gallery-filter-pill-bg",
                )
                val contentColor by animateColorAsState(
                    targetValue = if (active) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    animationSpec = tween(150),
                    label = "gallery-filter-pill-fg",
                )
                val openFilterLabel = stringResource(R.string.gallery_filter_open)
                Surface(
                    shape = CircleShape,
                    color = containerColor,
                    modifier = Modifier
                        .height(36.dp)
                        .pressFeedback(filterInteraction, pressedScale = 0.92f)
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = filterInteraction,
                            indication = null,
                            role = Role.Button,
                            onClickLabel = openFilterLabel,
                            onClick = {
                                haptics.tick()
                                filterDialogOpen = true
                            },
                        ),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = if (active) 10.dp else 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = openFilterLabel,
                            tint = contentColor,
                            modifier = Modifier.size(20.dp),
                        )
                        androidx.compose.animation.AnimatedVisibility(
                            visible = active,
                            enter = fadeIn(tween(120)) + scaleIn(
                                spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 700f),
                                initialScale = 0.7f,
                            ),
                            exit = fadeOut(tween(90)) + scaleOut(tween(90), targetScale = 0.75f),
                        ) {
                            Text(
                                text = hiddenActiveCount.toString(),
                                style = MaterialTheme.typography.labelMedium.merge(TextStyle(fontFeatureSettings = "tnum")),
                                fontWeight = FontWeight.SemiBold,
                                color = contentColor,
                            )
                        }
                    }
                }
            },
        )
    }

    if (filterDialogOpen) {
        GalleryFilterDialog(filter, onModelsChange, onModeChange, onFavoritesOnlyChange,
            onClearAll = { queryField = TextFieldValue(""); onClearAll() },
            onEnterSelectionMode = { filterDialogOpen = false; onEnterSelectionMode() },
            onDismiss = { filterDialogOpen = false })
    }
}

@Composable
internal fun GalleryFilterDialog(
    filter: GalleryFilter,
    onModelsChange: (Set<String>) -> Unit,
    onModeChange: (GenerationMode?) -> Unit,
    onFavoritesOnlyChange: (Boolean) -> Unit,
    onClearAll: () -> Unit,
    onEnterSelectionMode: () -> Unit,
    onDismiss: () -> Unit,
) {
    val modeOptions = GenerationMode.entries
    val modeLabels = modeOptions.associateWith { stringResource(it.labelRes()) }
    AlertDialog(
        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(0.9f).testTag("gallery-filter-dialog"),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.gallery_filter_title)) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GalleryModelFilterChip(filter.modelApiIds, onModelsChange)
                FilterMenuChip(
                    allLabel = stringResource(R.string.gallery_filter_all_modes),
                    selectedLabel = filter.mode?.let { modeLabels[it] },
                    options = modeOptions,
                    optionLabel = { modeLabels.getValue(it) },
                    onSelect = onModeChange,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = filter.favoritesOnly,
                        onClick = { onFavoritesOnlyChange(!filter.favoritesOnly) },
                        label = { Text(stringResource(R.string.gallery_filter_favorites)) },
                        leadingIcon = { Icon(Icons.Default.Favorite, null, Modifier.size(FilterChipDefaults.IconSize)) },
                        shape = RoundedCornerShape(14.dp),
                        border = null,
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        ),
                    )
                    TextButton(onClick = onEnterSelectionMode) {
                        Icon(Icons.Default.Checklist, contentDescription = null)
                        Text(stringResource(R.string.action_batch_mode), Modifier.padding(start = 4.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) } },
        dismissButton = {
            TextButton(onClick = onClearAll, enabled = filter.isActive) {
                Text(stringResource(R.string.gallery_filter_clear))
            }
        },
    )
}

@Composable
internal fun GalleryModelFilterChip(modelApiIds: Set<String>, onSelect: (Set<String>) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val all = stringResource(R.string.gallery_filter_all_models)
    Box(Modifier.fillMaxWidth().testTag("gallery-model-anchor")) {
        FilterChip(
            selected = modelApiIds.isNotEmpty(),
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            border = null,
            colors = FilterChipDefaults.filterChipColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
            label = { FilterValue(when (modelApiIds.size) { 0 -> all; 1 -> GalleryModels.labelOf(modelApiIds.single()); else -> "${modelApiIds.size} 个模型" }) },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.testTag("gallery-model-menu")) {
            GalleryModelOptions(modelApiIds, onSelect, onDone = { expanded = false })
        }
    }
}

@Composable
internal fun GalleryModelOptions(modelApiIds: Set<String>, onSelect: (Set<String>) -> Unit, onDone: () -> Unit) {
    DropdownMenuItem(text = { Text(stringResource(R.string.gallery_filter_all_models)) }, onClick = { onSelect(emptySet()); onDone() })
    GalleryModels.apiIds.forEach { id ->
        DropdownMenuItem(text = { Text(GalleryModels.labelOf(id)) }, modifier = Modifier.testTag("gallery-model-$id"),
            leadingIcon = { Checkbox(checked = id in modelApiIds, onCheckedChange = null) },
            onClick = { onSelect(if (id in modelApiIds) modelApiIds - id else modelApiIds + id) })
    }
    DropdownMenuItem(text = { Text("完成") }, onClick = onDone)
}

/**
 * 一个筛选条件下的拉菜单。
 *
 * chip 上直接显示"当前值"（未筛选时是 [allLabel]），不加维度前缀：
 * 三个 chip 与搜索框在 360dp 宽度里排不下，"模型：全部"这种写法会把最后一个挤到屏幕外。
 *
 * 只接收 [selectedLabel] 而不是选中项本身：选中项与候选项的类型常常不同
 * （模式用 `GenerationMode` 筛选，而候选项旁边要带预先生成好的名字），
 * 让它们共用一个泛型参数只会把类型推导绕死在这里。
 *
 * [selectedLabel] 为 null 表示"全部"，此时 chip 不高亮 —— 未筛选的控件不该看起来像已筛选。
 */
@Composable
private fun <T> FilterMenuChip(
    allLabel: String,
    selectedLabel: String?,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxWidth().testTag("gallery-mode-anchor")) {
        FilterChip(
            modifier = Modifier.fillMaxWidth(),
            selected = selectedLabel != null,
            onClick = { expanded = true },
            shape = RoundedCornerShape(14.dp),
            border = null,
            colors = FilterChipDefaults.filterChipColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
            label = { FilterValue(selectedLabel ?: allLabel) },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(allLabel) },
                onClick = {
                    expanded = false
                    onSelect(null)
                },
            )
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun FilterValue(text: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(Icons.Default.ArrowDropDown, null, Modifier.size(FilterChipDefaults.IconSize))
    }
}

/** 时间轴的日期组头：跨两列（FullLine），像相册那样把图片按天隔开。 */
private fun saveToSystemGallery(
    scope: CoroutineScope,
    context: Context,
    repository: GenerationRepository,
    exporter: MediaStoreExporter,
    snackbarHostState: SnackbarHostState,
    item: GalleryItem,
) {
    scope.launch {
        val source = repository.fileOf(item)
        val displayName = PromptTitle.exportFileName(
            title = item.title,
            timestampMillis = item.createdAt,
            ordinal = item.ordinal,
        )
        when (val outcome = exporter.export(source, displayName)) {
            is Outcome.Success -> {
                repository.markExported(item.imageId, outcome.value.toString())
                snackbarHostState.showSnackbar(if (source.extension.equals("svg", ignoreCase = true)) "SVG 已保存到下载目录"
                    else context.getString(R.string.detail_saved_to_gallery))
            }

            is Outcome.Failure -> {
                snackbarHostState.showSnackbar(messageFor(context, outcome.error.code))
            }
        }
    }
}

private fun messageFor(context: Context, code: ErrorCode): String =
    context.getString(code.messageRes())

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("PocketNAI prompt", text))
}

@Composable
private fun GallerySelectionTopBar(
    selectedCount: Int,
    allSelected: Boolean,
    onSelectAllToggle: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        tonalElevation = 2.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MotionIconButton(onClick = onExit) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.action_exit_selection),
                    )
                }
                AnimatedContent(
                    targetState = selectedCount,
                    label = "gallery-selected-count",
                    transitionSpec = {
                        val up = targetState > initialState
                        (fadeIn(tween(120)) + slideInVertically(tween(140)) { if (up) it / 3 else -it / 3 }) togetherWith
                            (fadeOut(tween(90)) + slideOutVertically(tween(110)) { if (up) -it / 3 else it / 3 })
                    },
                ) { count ->
                    Text(
                        text = stringResource(R.string.batch_selected_count, count),
                        style = MaterialTheme.typography.titleMedium.merge(TextStyle(fontFeatureSettings = "tnum")),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            net.pocketnai.ui.common.MotionTextButton(onClick = onSelectAllToggle) {
                Text(
                    stringResource(
                        if (allSelected) R.string.action_deselect_all else R.string.action_select_all
                    )
                )
            }
        }
    }
}

@Composable
private fun GallerySelectionBottomBar(
    selectedCount: Int,
    onSave: () -> Unit,
    onFavorite: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        modifier = modifier
            .padding(horizontal = 24.dp, vertical = 12.dp)
            .fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp, horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val enabled = selectedCount > 0

            // 保存到相册
            MotionIconButton(onClick = onSave, enabled = enabled) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = stringResource(R.string.batch_action_save),
                )
            }

            // 收藏
            MotionIconButton(onClick = onFavorite, enabled = enabled) {
                Icon(
                    imageVector = Icons.Default.Favorite,
                    contentDescription = stringResource(R.string.batch_action_favorite),
                )
            }

            // 分享
            MotionIconButton(onClick = onShare, enabled = enabled) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = stringResource(R.string.batch_action_share),
                )
            }

            // 删除
            MotionIconButton(
                onClick = onDelete,
                enabled = enabled,
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.batch_action_delete),
                    tint = if (enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GalleryItemActionSheet(
    target: GalleryItem,
    imageFile: File,
    onDismiss: () -> Unit,
    onToggleFavorite: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onCopyPrompt: () -> Unit,
    onUseAsReference: (() -> Unit)?,
    onInpaint: (() -> Unit)?,
    onEnterBatchMode: () -> Unit,
    onDelete: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val haptics = net.pocketnai.ui.motion.rememberPocketHaptics()
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var closing by remember { mutableStateOf(false) }

    fun dismissWith(action: () -> Unit, immediate: Boolean = false) {
        if (closing) return
        closing = true
        if (immediate) {
            action()
        } else {
            pendingAction = action
        }
        scope.launch {
            try {
                sheet.hide()
            } finally {
                closing = false
            }
            if (!sheet.isVisible) {
                onDismiss()
                pendingAction?.invoke()
                pendingAction = null
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            onDismiss()
            pendingAction?.invoke()
            pendingAction = null
        },
        sheetState = sheet,
        sheetMaxWidth = 560.dp,
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = { WindowInsets.safeDrawing },
        dragHandle = {
            Box(Modifier.fillMaxWidth().height(20.dp), contentAlignment = Alignment.Center) {
                Surface(
                    Modifier.size(32.dp, 4.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.outlineVariant,
                ) {}
            }
        },
    ) {
        val cleanTitle = remember(target.title) {
            target.title.trimEnd(',', ' ', '，', '、', ';').ifBlank { "生成图片" }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 预览头部：缩略图 + 干净标题 + 模型与尺寸摘要
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SfwAsyncImage(
                    file = imageFile,
                    contentDescription = cleanTitle,
                    sensitive = target.sensitivePrompt,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = cleanTitle,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${GalleryModels.labelOf(target.modelApiId)} · ${target.width} × ${target.height}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // 顶排高频胶囊：收藏 / 保存相册 / 分享
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                QuickActionTile(
                    icon = if (target.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    label = stringResource(if (target.favorite) R.string.action_unfavorite else R.string.action_favorite),
                    highlighted = target.favorite,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        if (!target.favorite) haptics.confirm() else haptics.tick()
                        dismissWith(onToggleFavorite, immediate = true)
                    },
                )
                QuickActionTile(
                    icon = Icons.Default.Download,
                    label = if (target.relativePath.endsWith(".svg", ignoreCase = true)) {
                        "保存 SVG"
                    } else {
                        stringResource(R.string.batch_action_save)
                    },
                    modifier = Modifier.weight(1f),
                    onClick = {
                        haptics.tick()
                        dismissWith(onSave, immediate = true)
                    },
                )
                QuickActionTile(
                    icon = Icons.Default.Share,
                    label = stringResource(R.string.action_share_image),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        haptics.tick()
                        dismissWith(onShare)
                    },
                )
            }

            // 分组操作列表
            SettingsCard {
                SheetActionRow(
                    icon = Icons.Default.ContentCopy,
                    label = stringResource(R.string.action_copy_prompt),
                    onClick = {
                        haptics.tick()
                        dismissWith(onCopyPrompt, immediate = true)
                    },
                )
                if (onUseAsReference != null) {
                    SheetActionDivider()
                    SheetActionRow(
                        icon = Icons.Default.AddPhotoAlternate,
                        label = "用作参考图",
                        onClick = {
                            haptics.tick()
                            dismissWith(onUseAsReference)
                        },
                    )
                }
                if (onInpaint != null) {
                    SheetActionDivider()
                    SheetActionRow(
                        icon = Icons.Default.Brush,
                        label = if (target.relativePath.endsWith(".gif", ignoreCase = true)) {
                            "编辑首帧"
                        } else {
                            stringResource(R.string.action_inpaint)
                        },
                        onClick = {
                            haptics.tick()
                            dismissWith(onInpaint)
                        },
                    )
                }
                SheetActionDivider()
                SheetActionRow(
                    icon = Icons.Default.Checklist,
                    label = stringResource(R.string.action_batch_mode),
                    onClick = {
                        haptics.tick()
                        dismissWith(onEnterBatchMode, immediate = true)
                    },
                )
                SheetActionDivider()
                SheetActionRow(
                    icon = Icons.Default.DeleteOutline,
                    label = stringResource(R.string.action_delete),
                    destructive = true,
                    onClick = {
                        haptics.tick()
                        dismissWith(onDelete)
                    },
                )
            }
        }
    }
}

@Composable
private fun QuickActionTile(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (highlighted) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        modifier = modifier.settingsClickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (highlighted) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.size(22.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = if (highlighted) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SheetActionRow(
    icon: ImageVector,
    label: String,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val contentColor = if (destructive) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    ListItem(
        headlineContent = {
            Text(
                text = label,
                color = contentColor,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = {
            Surface(
                shape = CircleShape,
                color = if (destructive) {
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
            ) {
                Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (destructive) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.settingsClickable(onClick = onClick),
    )
}

@Composable
private fun SheetActionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 70.dp, end = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
    )
}

private fun saveMultipleToSystemGallery(
    scope: CoroutineScope,
    context: Context,
    repository: GenerationRepository,
    exporter: MediaStoreExporter,
    snackbarHostState: SnackbarHostState,
    items: List<GalleryItem>,
) {
    scope.launch {
        var successCount = 0
        for (item in items) {
            val source = repository.fileOf(item)
            val displayName = PromptTitle.exportFileName(
                title = item.title,
                timestampMillis = item.createdAt,
                ordinal = item.ordinal,
            )
            when (val outcome = exporter.export(source, displayName)) {
                is Outcome.Success -> {
                    repository.markExported(item.imageId, outcome.value.toString())
                    successCount++
                }
                is Outcome.Failure -> Unit
            }
        }
        if (successCount > 0) {
            snackbarHostState.showSnackbar(if (items.any { it.relativePath.endsWith(".svg", ignoreCase = true) })
                "已保存 $successCount 个文件，SVG 位于下载目录" else context.getString(R.string.batch_save_success, successCount))
        } else {
            snackbarHostState.showSnackbar(context.getString(R.string.common_unknown))
        }
    }
}
