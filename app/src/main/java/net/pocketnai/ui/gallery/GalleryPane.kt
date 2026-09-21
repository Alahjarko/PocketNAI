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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import net.pocketnai.ui.common.ImageShareManager
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.pocketnai.R
import net.pocketnai.core.ErrorCode
import net.pocketnai.core.Outcome
import net.pocketnai.data.export.MediaStoreExporter
import net.pocketnai.data.repo.GenerationRepository
import net.pocketnai.domain.model.GalleryFilter
import net.pocketnai.domain.model.GalleryItem
import net.pocketnai.domain.model.GalleryTimeline
import net.pocketnai.domain.model.GenerationMode
import net.pocketnai.domain.model.ImageModel
import net.pocketnai.domain.model.ModelCatalog
import net.pocketnai.domain.prompt.PromptTitle
import net.pocketnai.ui.LocalAppContainer
import net.pocketnai.ui.common.CenteredHint
import net.pocketnai.ui.common.labelRes
import net.pocketnai.ui.common.messageRes
import net.pocketnai.ui.state.GenerationPreviewStore
import java.io.File
import java.time.ZoneId
import kotlin.math.roundToInt

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
    val generating by viewModel.generatingCards.collectAsStateWithLifecycle()
    val previews by container.generationPreviewStore.previews.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val undoGenerationId by viewModel.undoGenerationId.collectAsStateWithLifecycle()
    val undoGenerationIds by viewModel.undoGenerationIds.collectAsStateWithLifecycle()
    val isSelectionMode by viewModel.isSelectionMode.collectAsStateWithLifecycle()
    val selectedImageIds by viewModel.selectedImageIds.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var actionTarget by remember { mutableStateOf<GalleryItem?>(null) }
    var pendingSave by remember { mutableStateOf<GalleryItem?>(null) }
    var pendingBatchSave by remember { mutableStateOf<List<GalleryItem>?>(null) }
    var confirmBatchDelete by remember { mutableStateOf(false) }

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

    // 筛选生效时不显示"生成中"占位卡：那些任务还没有图片，关键词/收藏筛选对它们没有意义，
    // 留着会让"仅看收藏"里冒出一张不属于任何收藏的卡片。
    val visibleGenerating = if (filter.isActive || isSelectionMode) emptyList() else generating

    // 图片按生成日期分组（像系统相册）。顺序契约由 GalleryTimeline 保证，这里只负责画。
    val sections = remember(items) {
        GalleryTimeline.group(items, System.currentTimeMillis(), ZoneId.systemDefault())
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (isSelectionMode) {
            GallerySelectionTopBar(
                selectedCount = selectedImageIds.size,
                allSelected = items.isNotEmpty() && selectedImageIds.size == items.size,
                onSelectAllToggle = {
                    if (selectedImageIds.size == items.size) {
                        viewModel.deselectAll()
                    } else {
                        viewModel.selectAll(items.map { it.imageId })
                    }
                },
                onExit = viewModel::exitSelectionMode,
            )
        } else {
            GalleryFilterBar(
                filter = filter,
                onQueryChange = viewModel::onQueryChange,
                onModelChange = viewModel::onModelChange,
                onModeChange = viewModel::onModeChange,
                onFavoritesOnlyChange = viewModel::onFavoritesOnlyChange,
                onClearAll = viewModel::clearFilter,
                onEnterSelectionMode = { viewModel.enterSelectionMode() },
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            if (items.isEmpty() && visibleGenerating.isEmpty()) {
                CenteredHint(
                    // 区分"还没有历史"与"筛掉了"：前者要告诉用户去生成，后者要告诉他是筛选在起作用。
                    text = stringResource(
                        if (filter.isActive) R.string.gallery_no_match else R.string.gallery_empty,
                    ),
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                LazyVerticalStaggeredGrid(
                    // 自适应列数：手机上约两列，横屏/平板自然变成三四列（规划书 4.3 允许两者）。
                    columns = StaggeredGridCells.Adaptive(160.dp),
                    contentPadding = PaddingValues(8.dp),
                    verticalItemSpacing = 8.dp,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(
                        items = visibleGenerating,
                        key = { summary -> "generating-${summary.generation.id}" },
                    ) { summary ->
                        GeneratingCard(
                            title = summary.generation.title,
                            preview = previews[summary.generation.id],
                        )
                    }

                    sections.forEach { section ->
                        item(
                            key = "day-${section.epochDay}",
                            span = StaggeredGridItemSpan.FullLine,
                        ) {
                            DayHeader(label = section.label)
                        }

                        items(
                            items = section.items,
                            key = { item -> item.imageId },
                        ) { item ->
                            val isSelected = selectedImageIds.contains(item.imageId)
                            GalleryCard(
                                item = item,
                                imageFile = container.generationRepository.fileOf(item),
                                isSelectionMode = isSelectionMode,
                                isSelected = isSelected,
                                onClick = {
                                    if (isSelectionMode) {
                                        viewModel.toggleSelect(item.imageId)
                                    } else {
                                        // 先记下"这次浏览的顺序"，详情页才能左右滑动切换。
                                        container.galleryOrderSnapshot.publish(items.map { it.imageId })
                                        onOpenImage(item.imageId)
                                    }
                                },
                                onLongClick = {
                                    if (isSelectionMode) {
                                        viewModel.toggleSelect(item.imageId)
                                    } else {
                                        actionTarget = item
                                    }
                                },
                            )
                        }
                    }
                }
            }

            if (isSelectionMode) {
                GallerySelectionBottomBar(
                    selectedCount = selectedImageIds.size,
                    onSave = {
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
                        viewModel.batchToggleFavorite(items)
                    },
                    onShare = {
                        val selectedItems = items.filter { it.imageId in selectedImageIds }
                        val files = selectedItems.map { container.generationRepository.fileOf(it) }
                        ImageShareManager.shareMultiple(
                            context = context,
                            files = files,
                            title = "PocketNAI",
                        )
                    },
                    onDelete = {
                        confirmBatchDelete = true
                    },
                    modifier = Modifier.align(Alignment.BottomCenter),
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
        AlertDialog(
            onDismissRequest = { actionTarget = null },
            title = { Text(target.title) },
            text = {
                Column {
                    TextButton(
                        onClick = {
                            actionTarget = null
                            viewModel.toggleFavorite(target)
                        },
                    ) {
                        Text(
                            stringResource(
                                if (target.favorite) {
                                    R.string.action_unfavorite
                                } else {
                                    R.string.action_favorite
                                },
                            ),
                        )
                    }

                    TextButton(
                        onClick = {
                            actionTarget = null
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
                    ) {
                        Text(stringResource(R.string.action_save_to_gallery))
                    }

                    TextButton(
                        onClick = {
                            actionTarget = null
                            val file = container.generationRepository.fileOf(target)
                            ImageShareManager.shareSingle(
                                context = context,
                                file = file,
                                title = target.title,
                            )
                        },
                    ) {
                        Text(stringResource(R.string.action_share_image))
                    }

                    TextButton(
                        onClick = {
                            actionTarget = null
                            // 只复制用户看到的正向提示词，绝不复制 Token 或隐藏请求字段（规划书第 10 节）。
                            copyToClipboard(context, target.prompt)
                            scope.launch { snackbarHostState.showSnackbar("已复制提示词") }
                        },
                    ) {
                        Text(stringResource(R.string.action_copy_prompt))
                    }

                    TextButton(
                        onClick = {
                            actionTarget = null
                            onInpaintImage(target)
                        },
                    ) {
                        Text(stringResource(R.string.action_inpaint))
                    }

                    TextButton(
                        onClick = {
                            actionTarget = null
                            viewModel.deleteGeneration(target.generationId)
                        },
                    ) {
                        Text(stringResource(R.string.action_delete))
                    }

                    TextButton(
                        onClick = {
                            val id = target.imageId
                            actionTarget = null
                            viewModel.enterSelectionMode(id)
                        },
                    ) {
                        Text(stringResource(R.string.action_batch_mode))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { actionTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
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
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GalleryFilterBar(
    filter: GalleryFilter,
    onQueryChange: (String) -> Unit,
    onModelChange: (ImageModel?) -> Unit,
    onModeChange: (GenerationMode?) -> Unit,
    onFavoritesOnlyChange: (Boolean) -> Unit,
    onClearAll: () -> Unit,
    onEnterSelectionMode: () -> Unit,
) {
    var queryField by remember { mutableStateOf(TextFieldValue(filter.query)) }

    // 模式名要先算成字符串：optionLabel 不是 @Composable，里面调不了 stringResource。
    val modeOptions = GenerationMode.entries
    val modeLabels = modeOptions.associateWith { stringResource(it.labelRes()) }

    var filterDialogOpen by remember { mutableStateOf(false) }

    // 角标只数"藏起来"的条件：关键词就写在搜索框里，不必再数一遍。
    val hiddenActiveCount = (if (filter.model != null) 1 else 0) +
        (if (filter.mode != null) 1 else 0) +
        (if (filter.favoritesOnly) 1 else 0)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = queryField,
            onValueChange = {
                queryField = it
                onQueryChange(it.text)
            },
            placeholder = { Text(stringResource(R.string.gallery_search_hint)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (queryField.text.isNotEmpty()) {
                    IconButton(
                        onClick = {
                            queryField = TextFieldValue("")
                            onQueryChange("")
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.gallery_filter_clear),
                        )
                    }
                }
            },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Box {
            IconButton(onClick = { filterDialogOpen = true }) {
                Icon(
                    imageVector = Icons.Default.FilterList,
                    contentDescription = stringResource(R.string.gallery_filter_open),
                    tint = if (hiddenActiveCount > 0) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (hiddenActiveCount > 0) {
                Badge(modifier = Modifier.align(Alignment.TopEnd)) {
                    Text(hiddenActiveCount.toString())
                }
            }
        }
    }

    if (filterDialogOpen) {
        AlertDialog(
            onDismissRequest = { filterDialogOpen = false },
            title = { Text(stringResource(R.string.gallery_filter_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = filter.favoritesOnly,
                            onClick = { onFavoritesOnlyChange(!filter.favoritesOnly) },
                            label = { Text(stringResource(R.string.gallery_filter_favorites)) },
                            leadingIcon = if (filter.favoritesOnly) {
                                {
                                    Icon(
                                        imageVector = Icons.Default.Favorite,
                                        contentDescription = null,
                                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                                    )
                                }
                            } else {
                                null
                            },
                        )
                        FilterMenuChip(
                            allLabel = stringResource(R.string.gallery_filter_all_models),
                            selectedLabel = filter.model?.let { ModelCatalog.profileOf(it).displayName },
                            options = ModelCatalog.models,
                            optionLabel = { ModelCatalog.profileOf(it).displayName },
                            onSelect = onModelChange,
                        )
                        FilterMenuChip(
                            allLabel = stringResource(R.string.gallery_filter_all_modes),
                            selectedLabel = filter.mode?.let { modeLabels[it] },
                            options = modeOptions,
                            optionLabel = { modeLabels.getValue(it) },
                            onSelect = onModeChange,
                        )
                    }
                    TextButton(
                        onClick = {
                            filterDialogOpen = false
                            onEnterSelectionMode()
                        },
                    ) {
                        Icon(Icons.Default.Checklist, contentDescription = null)
                        Text(
                            text = stringResource(R.string.action_batch_mode),
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { filterDialogOpen = false }) {
                    Text(stringResource(R.string.action_done))
                }
            },
            dismissButton = if (filter.isActive) {
                {
                    TextButton(
                        onClick = {
                            queryField = TextFieldValue("")
                            onClearAll()
                        },
                    ) {
                        Text(stringResource(R.string.gallery_filter_clear))
                    }
                }
            } else {
                null
            },
        )
    }
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

    Box {
        FilterChip(
            selected = selectedLabel != null,
            onClick = { expanded = true },
            label = { Text(selectedLabel ?: allLabel) },
            trailingIcon = {
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                )
            },
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

/** 时间轴的日期组头：跨两列（FullLine），像相册那样把图片按天隔开。 */
@Composable
private fun DayHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GalleryCard(
    item: GalleryItem,
    imageFile: File,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Card(
        border = if (isSelectionMode && isSelected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else null,
        modifier = Modifier
            .fillMaxWidth()
            // 用记录的宽高先占位，图片解码完成前就能排版，避免滚动时跳动。
            .aspectRatio(item.aspectRatio)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = imageFile,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            // 收藏标记垫一层半透明黑底：图片颜色不可预测，纯白的心在白底图上看不见。
            if (item.favorite) {
                Box(
                    modifier = Modifier
                        .align(if (isSelectionMode) Alignment.BottomStart else Alignment.TopEnd)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.35f), CircleShape)
                        .padding(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = stringResource(R.string.action_favorite),
                        tint = Color.White,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }

            if (isSelectionMode) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.35f), CircleShape)
                        .padding(2.dp),
                ) {
                    Icon(
                        imageVector = if (isSelected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/**
 * 生成中的占位卡片。
 *
 * 有流式预览时显示"正在长出来的画面"（中间图铺满卡片，进度叠在左下角）；
 * 没有预览（普通传输、预览尚未到达、或流式已失败）时退回纯文字状态 ——
 * 不编造进度，也不用转圈动画假装正在发生什么。
 */
@Composable
private fun GeneratingCard(
    title: String,
    preview: GenerationPreviewStore.Preview?,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (preview != null) {
                AsyncImage(
                    model = File(preview.path),
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                // 状态标签垫一层半透明黑底：图片颜色不可预测，白字直接放可能看不见。
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = preview.progress?.let { progress ->
                            stringResource(R.string.gallery_status_generating) +
                                " ${(progress * 100).roundToInt()}%"
                        } ?: stringResource(R.string.gallery_status_generating),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.HourglassTop,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(R.string.gallery_status_generating),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

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
                snackbarHostState.showSnackbar(context.getString(R.string.detail_saved_to_gallery))
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
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onExit) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.action_exit_selection),
                    )
                }
                Text(
                    text = stringResource(R.string.batch_selected_count, selectedCount),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            TextButton(onClick = onSelectAllToggle) {
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
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        modifier = modifier
            .padding(horizontal = 24.dp, vertical = 12.dp)
            .fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp, horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val enabled = selectedCount > 0

            // 保存到相册
            IconButton(onClick = onSave, enabled = enabled) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = stringResource(R.string.batch_action_save),
                )
            }

            // 收藏
            IconButton(onClick = onFavorite, enabled = enabled) {
                Icon(
                    imageVector = Icons.Default.Favorite,
                    contentDescription = stringResource(R.string.batch_action_favorite),
                )
            }

            // 分享
            IconButton(onClick = onShare, enabled = enabled) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = stringResource(R.string.batch_action_share),
                )
            }

            // 删除
            IconButton(
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
            snackbarHostState.showSnackbar(context.getString(R.string.batch_save_success, successCount))
        } else {
            snackbarHostState.showSnackbar(context.getString(R.string.common_unknown))
        }
    }
}
