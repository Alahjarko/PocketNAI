package net.pocketnai.ui.detail

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.outlined.Share
import net.pocketnai.ui.common.ImageShareManager
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import net.pocketnai.R
import net.pocketnai.domain.model.DirectorReferenceKind
import net.pocketnai.domain.model.GeneratedImage
import net.pocketnai.domain.model.Generation
import net.pocketnai.domain.model.ReferenceImage
import net.pocketnai.domain.model.ReferenceRole
import net.pocketnai.ui.LocalAppContainer
import net.pocketnai.ui.common.CenteredHint
import net.pocketnai.ui.common.labelRes
import net.pocketnai.ui.common.messageRes
import java.text.DateFormat
import java.util.Date

/**
 * 图片详情页。
 *
 * 展示完整参数，并提供规划书 4.3 要求的四项操作：保存到相册、复制提示词、删除、复用参数。
 *
 * **左右滑动切换图片**：滑动顺序是进入详情页那一刻画廊列表的快照
 * （[net.pocketnai.ui.state.GalleryOrderSnapshot]），因此"下一张"就是用户在瀑布流里
 * 看到的下一张；快照只有一张时退化成旧行为（不能滑）。顶栏与操作按钮始终作用于
 * 当前页，超分产物会插到当前页后面。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    imageId: String,
    onBack: () -> Unit,
    onParamsReused: () -> Unit,
    onInpaint: (String) -> Unit,
) {
    val container = LocalAppContainer.current
    val viewModel: DetailViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                DetailViewModel(
                    repository = container.generationRepository,
                    exporter = container.mediaStoreExporter,
                    draftStore = container.draftStore,
                    favorites = container.favoriteImageRepository,
                    accountBalanceRepository = container.accountBalanceRepository,
                    credentialStore = container.credentialStore,
                    anlasLedgerRepository = container.anlasLedgerRepository,
                )
            }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 顺序在打开时定死：详情页里新生成/删除不应让滑动顺序"自己跳走"。
    val pagerIds = remember(imageId) { container.galleryOrderSnapshot.orderAround(imageId) }
    LaunchedEffect(imageId) { viewModel.bind(imageId, pagerIds) }

    // 删除完成后立刻返回列表，避免停在已经不存在的记录上。
    LaunchedEffect(state.deleted) {
        if (state.deleted) onBack()
    }

    val savedMessage = stringResource(R.string.detail_saved_to_gallery)
    var pendingSave by remember { mutableStateOf(false) }
    var showUpscaleDialog by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && pendingSave) viewModel.saveToSystemGallery()
        pendingSave = false
    }

    val pagerState = rememberPagerState(
        initialPage = pagerIds.indexOf(imageId).coerceAtLeast(0),
        pageCount = { state.pagerIds.size.coerceAtLeast(1) },
    )

    // 用户滑动 → 换当前页（顶栏收藏、操作按钮跟着走）。
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            viewModel.state.value.pagerIds.getOrNull(page)?.let { viewModel.selectPage(it) }
        }
    }
    // 代码换页（超分结果插进来）→ pager 跟过去；用户自己滑时两者已一致，不会重复动。
    LaunchedEffect(state.currentId) {
        val target = state.pagerIds.indexOf(state.currentId)
        if (target >= 0 && target != pagerState.currentPage) {
            pagerState.animateScrollToPage(target)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_cancel),
                        )
                    }
                },
                actions = {
                    val currentImage = state.image
                    val currentGeneration = state.generation
                    if (currentImage != null && currentGeneration != null) {
                        IconButton(
                            onClick = {
                                val file = container.generationRepository.fileOfRelativePath(currentImage.privateFilePath)
                                ImageShareManager.shareSingle(
                                    context = context,
                                    file = file,
                                    title = currentGeneration.title,
                                )
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Share,
                                contentDescription = stringResource(R.string.action_share),
                            )
                        }
                    }
                    // 收藏放在顶栏：详情页是"这张图我喜不喜欢"的判断现场，
                    // 而收藏只是个开关，不值得在下面再占一整行按钮。
                    IconButton(onClick = viewModel::toggleFavorite) {
                        Icon(
                            imageVector = if (state.favorite) {
                                Icons.Filled.Favorite
                            } else {
                                Icons.Filled.FavoriteBorder
                            },
                            contentDescription = stringResource(
                                if (state.favorite) {
                                    R.string.action_unfavorite
                                } else {
                                    R.string.action_favorite
                                },
                            ),
                            tint = if (state.favorite) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                LocalContentColor.current
                            },
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (state.pagerIds.isEmpty()) {
            CenteredHint(text = "正在载入…", modifier = Modifier.padding(padding))
            return@Scaffold
        }

        HorizontalPager(
            state = pagerState,
            // 预载左右各一页：滑过去时详情已在缓存里，不会闪"正在载入"。
            beyondViewportPageCount = 1,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) { page ->
            val pageId = state.pagerIds.getOrElse(page) { imageId }
            LaunchedEffect(pageId) { viewModel.ensureLoaded(pageId) }
            val detail = state.details[pageId]
            when {
                detail != null -> DetailPageContent(
                    image = detail.image,
                    generation = detail.generation,
                    isCurrentPage = pageId == state.currentId,
                    errorCode = state.errorCode,
                    savedToGallery = state.savedToGallery,
                    savedMessage = savedMessage,
                    onOpenFullscreen = { fullscreen = true },
                    onSaveClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            viewModel.saveToSystemGallery()
                        } else {
                            pendingSave = true
                            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        }
                    },
                    onCopyPrompt = { copyToClipboard(context, detail.generation.params.prompt) },
                    onReuseParams = {
                        viewModel.reuseParams()
                        onParamsReused()
                    },
                    onInpaint = { onInpaint(detail.image.privateFilePath) },
                    onOpenUpscaleDialog = { showUpscaleDialog = true },
                    onDelete = viewModel::deleteGeneration,
                    onDismissError = viewModel::dismissError,
                )

                pageId in state.failedIds -> CenteredHint(
                    text = stringResource(R.string.error_unknown),
                    modifier = Modifier.fillMaxSize(),
                )

                else -> CenteredHint(text = "正在载入…", modifier = Modifier.fillMaxSize())
            }
        }

        val image = state.image
        val generation = state.generation

        if (showUpscaleDialog && image != null) {
            UpscaleConfirmationDialog(
                sourceWidth = image.width,
                sourceHeight = image.height,
                upscaling = state.upscaling,
                onConfirm = {
                    viewModel.upscaleImage { newImageId ->
                        showUpscaleDialog = false
                        viewModel.showUpscaleResult(newImageId)
                    }
                },
                onDismiss = { showUpscaleDialog = false },
            )
        }

        if (fullscreen && image != null && generation != null) {
            FullscreenImageViewer(
                imageFile = container.generationRepository.fileOfRelativePath(image.privateFilePath),
                contentDescription = generation.title,
                imageWidth = image.width,
                imageHeight = image.height,
                onDismiss = { fullscreen = false },
            )
        }
    }
}

/**
 * 一页的正文：图片 + 参数表 + 提示词卡 + 操作按钮。
 *
 * [isCurrentPage] 为假的页（滑动时露出的邻页）不显示错误卡与"已保存"提示 ——
 * 那两条属于当前页的状态，跟着邻页一起画会让用户以为"上一张保存失败了"。
 *
 * **宽屏分两栏**（≥720dp，与 HomeScreen 的"宽屏档"同一个阈值）：左列整幅大图、
 * 右列参数与操作可滚动。窄屏保持单列 —— 平板竖屏/横屏下把图压成半屏宽会让
 * 细节全丢，而参数列固定在 380dp 一行读起来才不费劲。
 */
@Composable
private fun DetailPageContent(
    image: GeneratedImage,
    generation: Generation,
    isCurrentPage: Boolean,
    errorCode: net.pocketnai.core.ErrorCode?,
    savedToGallery: Boolean,
    savedMessage: String,
    onOpenFullscreen: () -> Unit,
    onSaveClick: () -> Unit,
    onCopyPrompt: () -> Unit,
    onReuseParams: () -> Unit,
    onInpaint: () -> Unit,
    onOpenUpscaleDialog: () -> Unit,
    onDelete: () -> Unit,
    onDismissError: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        if (maxWidth >= DETAIL_TWO_PANE_MIN_WIDTH) {
            Row(modifier = Modifier.fillMaxSize()) {
                DetailImagePane(
                    image = image,
                    generation = generation,
                    onOpenFullscreen = onOpenFullscreen,
                    // 宽屏交给 ContentScale.Fit 适配整块左栏，不再按宽高比撑高。
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(16.dp),
                )
                VerticalDivider()
                Column(
                    modifier = Modifier
                        .width(DETAIL_INFO_PANE_WIDTH)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    DetailInfoPane(
                        image = image,
                        generation = generation,
                        isCurrentPage = isCurrentPage,
                        errorCode = errorCode,
                        savedToGallery = savedToGallery,
                        savedMessage = savedMessage,
                        onSaveClick = onSaveClick,
                        onCopyPrompt = onCopyPrompt,
                        onReuseParams = onReuseParams,
                        onInpaint = onInpaint,
                        onOpenUpscaleDialog = onOpenUpscaleDialog,
                        onDelete = onDelete,
                        onDismissError = onDismissError,
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DetailImagePane(
                    image = image,
                    generation = generation,
                    onOpenFullscreen = onOpenFullscreen,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(
                            if (image.height > 0) image.width.toFloat() / image.height else 1f,
                        ),
                )
                DetailInfoPane(
                    image = image,
                    generation = generation,
                    isCurrentPage = isCurrentPage,
                    errorCode = errorCode,
                    savedToGallery = savedToGallery,
                    savedMessage = savedMessage,
                    onSaveClick = onSaveClick,
                    onCopyPrompt = onCopyPrompt,
                    onReuseParams = onReuseParams,
                    onInpaint = onInpaint,
                    onOpenUpscaleDialog = onOpenUpscaleDialog,
                    onDelete = onDelete,
                    onDismissError = onDismissError,
                )
            }
        }
    }
}

/** 点图进入全屏查看（双指缩放 / 拖动），单击或返回键退出。 */
@Composable
private fun DetailImagePane(
    image: GeneratedImage,
    generation: Generation,
    onOpenFullscreen: () -> Unit,
    modifier: Modifier,
) {
    val container = LocalAppContainer.current
    AsyncImage(
        model = container.generationRepository.fileOfRelativePath(image.privateFilePath),
        contentDescription = generation.title,
        contentScale = ContentScale.Fit,
        modifier = modifier.clickable(onClick = onOpenFullscreen),
    )
}

/**
 * 参数表 + 提示词卡 + 状态提示 + 操作按钮。
 *
 * 刻意**不带滚动与内边距**：两套布局（单列 / 双栏右栏）都把它放进自己的滚动容器里，
 * 滚动的位置与内边距由容器决定，这里只负责内容。
 */
@Composable
private fun DetailInfoPane(
    image: GeneratedImage,
    generation: Generation,
    isCurrentPage: Boolean,
    errorCode: net.pocketnai.core.ErrorCode?,
    savedToGallery: Boolean,
    savedMessage: String,
    onSaveClick: () -> Unit,
    onCopyPrompt: () -> Unit,
    onReuseParams: () -> Unit,
    onInpaint: () -> Unit,
    onOpenUpscaleDialog: () -> Unit,
    onDelete: () -> Unit,
    onDismissError: () -> Unit,
) {
    Text(text = generation.title, style = MaterialTheme.typography.titleMedium)

    val time = DateFormat.getDateTimeInstance().format(Date(generation.createdAt))
    DetailRow(stringResource(R.string.common_time), time)
    DetailRow(stringResource(R.string.common_model), generation.params.model.displayName)
    DetailRow(stringResource(R.string.common_size), generation.params.size.label)
    DetailRow(stringResource(R.string.generate_count), "${generation.params.sampleCount} 张（本张序号 ${image.ordinal}）")
    DetailRow(stringResource(R.string.generate_steps), generation.params.steps.toString())
    DetailRow(stringResource(R.string.generate_guidance), generation.params.guidance.toString())
    DetailRow(stringResource(R.string.generate_cfg_rescale), generation.params.cfgRescale.toString())
    DetailRow(stringResource(R.string.generate_sampler), generation.params.sampler.displayName)
    DetailRow(stringResource(R.string.generate_noise_schedule), generation.params.noiseSchedule.displayName)
    DetailRow(
        stringResource(R.string.generate_seed),
        image.seed?.toString() ?: "由 PNG 元数据决定（首版未解析）",
    )
    if (generation.errorCode != null) {
        DetailRow("错误", generation.errorMessage ?: generation.errorCode.name)
    }

    // 生成模式必须显示：图生图的尺寸、Strength 都只有在"这是一次改图"的前提下才说得通，
    // 否则用户回看历史时会以为那次生成的参数配错了。
    DetailRow(
        stringResource(R.string.common_mode),
        stringResource(generation.mode.labelRes()),
    )
    generation.references.forEach { reference ->
        ReferenceRow(reference = reference)
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("正向提示词", style = MaterialTheme.typography.labelMedium)
            Text(generation.params.prompt, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "Undesired Content",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(generation.params.negativePrompt, style = MaterialTheme.typography.bodyMedium)
        }
    }

    if (isCurrentPage && errorCode != null) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = stringResource(errorCode.messageRes()),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismissError) {
                    Text(stringResource(R.string.action_confirm))
                }
            }
        }
    }

    if (isCurrentPage && savedToGallery) {
        Text(
            text = savedMessage,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }

    // 操作分级（2026-09-21 界面减负）：唯一主按钮是"复用参数"（继续创作最常用的动作），
    // 四个次要操作两两并排，删除降级为红色文字按钮放在最后。
    Button(onClick = onReuseParams, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.action_reuse_params))
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onSaveClick, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.action_save_to_gallery))
        }
        OutlinedButton(onClick = onCopyPrompt, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.action_copy_prompt))
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // 局部重绘：官方文档说可以从"任意一张已生成的图片"进入，
        // 而"这张图某处画坏了"正是用户点进详情页的常见理由。
        OutlinedButton(onClick = onInpaint, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.action_inpaint))
        }

        OutlinedButton(onClick = onOpenUpscaleDialog, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.action_upscale))
        }
    }

    TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.action_delete),
            color = MaterialTheme.colorScheme.error,
        )
    }

    Text(
        text = "删除只影响 PocketNAI 的本地副本与记录；已经保存到系统相册的图片不会被删除。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 详情页进入双栏的宽度阈值：与 HomeScreen 的"宽屏档"一致（屏幕约 800dp 起）。 */
private val DETAIL_TWO_PANE_MIN_WIDTH = 720.dp

/** 双栏时右侧参数列的宽度：够一行放"标签 + 值"，又不至于把图挤窄。 */
private val DETAIL_INFO_PANE_WIDTH = 380.dp

/**
 * 一条参考图：缩略图 + 它的逐条参数。
 *
 * 缩略图直接是**提交时用的那张本地文件**，因此用户看到的就是当时真正发出去的图
 * （图生图的起点图在提交时会被裁切，这里显示的是原始素材，两者的裁切关系在生成页已说明）。
 */
@Composable
private fun ReferenceRow(reference: ReferenceImage) {
    val container = LocalAppContainer.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = container.fileStore.resolve(reference.relativePath),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(
                text = when (reference.role) {
                    ReferenceRole.IMG2IMG -> stringResource(R.string.detail_reference_img2img)
                    ReferenceRole.VIBE -> stringResource(R.string.detail_reference_vibe)
                    ReferenceRole.DIRECTOR -> stringResource(R.string.detail_reference_director)
                    ReferenceRole.INPAINT_MASK -> stringResource(R.string.detail_reference_inpaint_mask)
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "${reference.width} × ${reference.height}",
                style = MaterialTheme.typography.bodyMedium,
            )
            reference.strength?.let { strength ->
                Text(
                    text = stringResource(R.string.generate_reference_strength) + "  $strength",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            reference.secondaryStrength?.let { fidelity ->
                Text(
                    text = stringResource(R.string.generate_reference_fidelity) + "  $fidelity",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            reference.directorKind?.let { kind ->
                Text(
                    text = stringResource(
                        when (kind) {
                            DirectorReferenceKind.CHARACTER -> R.string.generate_reference_kind_character
                            DirectorReferenceKind.STYLE -> R.string.generate_reference_kind_style
                            DirectorReferenceKind.CHARACTER_AND_STYLE ->
                                R.string.generate_reference_kind_character_style
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("PocketNAI prompt", text))
}
