package net.pocketnai.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PhotoSizeSelectLarge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import net.pocketnai.data.security.CredentialType
import net.pocketnai.domain.image.ReferenceSource
import net.pocketnai.ui.LocalAppContainer
import net.pocketnai.ui.common.ShortcutCheatsheetDialog
import net.pocketnai.ui.common.imageUriDropTarget
import net.pocketnai.ui.gallery.GalleryPane
import net.pocketnai.ui.gallery.GalleryViewModel
import net.pocketnai.ui.generate.GenerateSheet
import net.pocketnai.ui.generate.GenerateViewModel
import net.pocketnai.ui.motion.LocalWorkbenchSharedTransition
import net.pocketnai.ui.motion.WorkbenchSharedTransition
import net.pocketnai.ui.motion.imageMotionChrome
import net.pocketnai.ui.motion.rememberPocketHaptics
import net.pocketnai.ui.openai.OpenAiImageSheet
import net.pocketnai.ui.openai.OpenAiImageViewModel
import kotlin.math.roundToInt

/**
 * 首页：画廊 / 大图工作台 + 生成设置。
 *
 * 两种形态（对照 WinNAI 桌面端的三栏布局）：
 * - 手机（内容宽度 <720dp）：BottomSheetScaffold，生成设置是可上拉下滑的悬浮层；
 * - 平板/桌面级宽度（≥720dp）：左侧支持 `[ 大图预览 | 相册预览 ]` 滑动胶囊切换，
 *   右侧为可拖动调整宽度（320dp..560dp）且可一键收起/展开的常驻生成面板。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    generateViewModel: GenerateViewModel,
    state: GenerateViewModel.UiState,
    connected: Boolean,
    credentialType: CredentialType?,
    onOpenImage: (String) -> Unit,
    onInpaintImage: (net.pocketnai.domain.model.GalleryItem) -> Unit,
    onOpenInpaintEditor: () -> Unit,
    onRequestConnect: () -> Unit,
    modifier: Modifier = Modifier,
    openAiViewModel: OpenAiImageViewModel? = null,
    onOpenAiSettings: () -> Unit = {},
    onOpenStatistics: (() -> Unit)? = null,
    onUseAsReference: ((net.pocketnai.domain.model.GalleryItem, net.pocketnai.domain.model.ReferenceRole) -> Unit)? = null,
) {
    val container = LocalAppContainer.current
    val galleryViewModel: GalleryViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                GalleryViewModel(
                    repository = container.generationRepository,
                    favorites = container.favoriteImageRepository,
                )
            }
        },
    )
    val openAiState = openAiViewModel?.state?.collectAsStateWithLifecycle()?.value
    val supportsDirector = openAiViewModel == null && state.supportsDirectorReference
    val supportsVibe = openAiViewModel == null && state.supportsVibeTransfer
    val inFlight = state.inFlight || openAiState?.busy == true

    val homeViewMode by container.settingsStore.tabletHomeViewMode.collectAsStateWithLifecycle()
    val persistedPaneWidthDp by container.settingsStore.tabletGeneratePaneWidthDp.collectAsStateWithLifecycle()
    val chatEnterToSend by container.settingsStore.chatEnterToSend.collectAsStateWithLifecycle()

    val searchFocusRequester = remember { FocusRequester() }
    var pendingSearchFocus by remember { mutableStateOf(false) }
    var showShortcutDialog by remember { mutableStateOf(false) }

    LaunchedEffect(pendingSearchFocus, homeViewMode) {
        if (pendingSearchFocus && homeViewMode == HOME_MODE_GALLERY) {
            pendingSearchFocus = false
            runCatching { searchFocusRequester.requestFocus() }
        }
    }

    val keyShortcutModifier = Modifier.onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown || !(event.isCtrlPressed || event.isMetaPressed)) {
            return@onPreviewKeyEvent false
        }
        when (event.key) {
            Key.Enter, Key.NumPadEnter -> {
                if (openAiViewModel != null) {
                    openAiViewModel.generate()
                } else if (connected && state.canGenerate) {
                    generateViewModel.generate()
                }
                true
            }
            Key.F -> {
                if (homeViewMode != HOME_MODE_GALLERY) {
                    container.settingsStore.setTabletHomeViewMode(HOME_MODE_GALLERY)
                    pendingSearchFocus = true
                } else {
                    runCatching { searchFocusRequester.requestFocus() }
                }
                true
            }
            Key.Slash -> {
                showShortcutDialog = !showShortcutDialog
                true
            }
            else -> false
        }
    }

    BoxWithConstraints(modifier = modifier.then(keyShortcutModifier)) {
        if (maxWidth >= 720.dp) {
            // ---- 平板 / 桌面形态：可切换视图 + 可拖宽可收起的常驻右侧面板 ----
            val density = LocalDensity.current
            val haptics = rememberPocketHaptics()
            val maxAllowedPaneWidth = (maxWidth.value - 320f).coerceIn(MIN_PANE_WIDTH_DP, MAX_PANE_WIDTH_DP)
            var paneWidthDp by remember {
                mutableFloatStateOf(persistedPaneWidthDp.toFloat().coerceIn(MIN_PANE_WIDTH_DP, MAX_PANE_WIDTH_DP))
            }
            LaunchedEffect(persistedPaneWidthDp) {
                paneWidthDp = persistedPaneWidthDp.toFloat().coerceIn(MIN_PANE_WIDTH_DP, MAX_PANE_WIDTH_DP)
            }
            var rightPaneCollapsed by rememberSaveable { mutableStateOf(false) }
            val effectivePaneWidth = paneWidthDp.coerceIn(MIN_PANE_WIDTH_DP, maxAllowedPaneWidth).dp
            val galleryItems by galleryViewModel.items.collectAsStateWithLifecycle()
            var stageImageId by rememberSaveable { mutableStateOf<String?>(null) }
            val workbenchSharedImageId = remember(stageImageId, galleryItems) {
                stageImageId?.takeIf { id -> galleryItems.any { it.imageId == id } }
                    ?: galleryItems.firstOrNull()?.imageId
            }

            Row(modifier = Modifier.fillMaxSize()) {
                // 左侧工作区：顶部切换栏 + 大图工作台 / 相册瀑布流
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                ) {
                    TabletHomeTopBar(
                        viewMode = homeViewMode,
                        onViewModeChange = { mode ->
                            haptics.tick()
                            container.settingsStore.setTabletHomeViewMode(mode)
                        },
                        rightPaneCollapsed = rightPaneCollapsed,
                        onToggleRightPane = {
                            haptics.tick()
                            rightPaneCollapsed = !rightPaneCollapsed
                        },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    AnimatedContent(
                        targetState = homeViewMode == HOME_MODE_STAGE,
                        label = "tablet-home-view-mode",
                        transitionSpec = {
                            fadeIn(tween(240, easing = FastOutSlowInEasing)) togetherWith
                                fadeOut(tween(180, easing = FastOutSlowInEasing))
                        },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) { isStage ->
                        CompositionLocalProvider(
                            LocalWorkbenchSharedTransition provides WorkbenchSharedTransition(
                                visibility = this@AnimatedContent,
                                activeImageId = workbenchSharedImageId,
                            ),
                        ) {
                            if (isStage) {
                                HomeWorkbenchPane(
                                    galleryViewModel = galleryViewModel,
                                    onOpenImage = onOpenImage,
                                    onInpaintImage = onInpaintImage,
                                    onReuseItem = generateViewModel::reuseGalleryItem,
                                    onUseAsReference = onUseAsReference,
                                    supportsDirectorReference = supportsDirector,
                                    supportsVibeTransfer = supportsVibe,
                                    inFlight = inFlight,
                                    selectedImageId = stageImageId,
                                    onSelectedImageIdChange = { stageImageId = it },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                GalleryPane(
                                    onOpenImage = onOpenImage,
                                    onInpaintImage = onInpaintImage,
                                    onUseAsReference = onUseAsReference,
                                    supportsDirectorReference = supportsDirector,
                                    supportsVibeTransfer = supportsVibe,
                                    searchFocusRequester = searchFocusRequester,
                                    onReuseItem = generateViewModel::reuseGalleryItem,
                                    galleryViewModel = galleryViewModel,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                }

                // 中间可拖拽分栏手柄 + 右侧常驻生成面板（带展开/收起平滑物理动效）
                AnimatedVisibility(
                    visible = !rightPaneCollapsed,
                    enter = expandHorizontally(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = 460f,
                        ),
                        expandFrom = Alignment.End,
                    ) + fadeIn(tween(190, easing = FastOutSlowInEasing)),
                    exit = shrinkHorizontally(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = 520f,
                        ),
                        shrinkTowards = Alignment.End,
                    ) + fadeOut(tween(140, easing = FastOutSlowInEasing)),
                ) {
                    Row(modifier = Modifier.fillMaxHeight()) {
                        SplitPaneDragHandle(
                            onDragDeltaPx = { deltaPx ->
                                val deltaDp = deltaPx / density.density
                                paneWidthDp = (paneWidthDp - deltaDp).coerceIn(MIN_PANE_WIDTH_DP, maxAllowedPaneWidth)
                            },
                            onDragFinished = {
                                container.settingsStore.setTabletGeneratePaneWidthDp(paneWidthDp.roundToInt())
                            },
                        )
                        Surface(
                            modifier = Modifier
                                .width(effectivePaneWidth)
                                .fillMaxHeight()
                                .imageMotionChrome(zIndex = 2f),
                        ) {
                            if (openAiViewModel != null) {
                                OpenAiDropContainer(
                                    openAiViewModel = openAiViewModel,
                                    onOpenAiSettings = onOpenAiSettings,
                                )
                            } else {
                                GenerateSheet(
                                    viewModel = generateViewModel,
                                    state = state,
                                    expanded = true,
                                    connected = connected,
                                    credentialType = credentialType,
                                    onRequestConnect = onRequestConnect,
                                    onOpenInpaintEditor = onOpenInpaintEditor,
                                    onOpenStatistics = onOpenStatistics,
                                )
                            }
                        }
                    }
                }
            }
            if (showShortcutDialog) {
                ShortcutCheatsheetDialog(
                    chatEnterToSend = chatEnterToSend,
                    onDismiss = { showShortcutDialog = false },
                )
            }
            return@BoxWithConstraints
        }

        // ---- 手机形态：底部悬浮层 ----
        GenerationSheetScaffold(
            inFlight = inFlight,
            submissionRevision = openAiState?.submissionRevision ?: state.submissionRevision,
            expandSignal = state.expandSheetSignal,
            sheetContent = { expanded, headerDragModifier ->
                if (openAiViewModel != null) OpenAiImageSheet(openAiViewModel, expanded, onOpenAiSettings, headerDragModifier)
                else GenerateSheet(
                    viewModel = generateViewModel,
                    state = state,
                    expanded = expanded,
                    headerDragModifier = headerDragModifier,
                    connected = connected,
                    credentialType = credentialType,
                    onRequestConnect = onRequestConnect,
                    onOpenInpaintEditor = onOpenInpaintEditor,
                    onOpenStatistics = onOpenStatistics,
                )
            },
        ) { contentPadding ->
            GalleryPane(
                onOpenImage = onOpenImage,
                onInpaintImage = onInpaintImage,
                onUseAsReference = onUseAsReference,
                supportsDirectorReference = supportsDirector,
                supportsVibeTransfer = supportsVibe,
                searchFocusRequester = searchFocusRequester,
                onReuseItem = generateViewModel::reuseGalleryItem,
                galleryViewModel = galleryViewModel,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            )
        }
    }

    if (showShortcutDialog) {
        ShortcutCheatsheetDialog(
            chatEnterToSend = chatEnterToSend,
            onDismiss = { showShortcutDialog = false },
        )
    }
}

/**
 * 平板首页顶栏：采用与 `ReferenceTabsSection` 一致的滑动胶囊分段切换（`大图预览 | 相册预览`）
 * 以及右侧生成面板的折叠/展开开关。
 */
@Composable
private fun TabletHomeTopBar(
    viewMode: String,
    onViewModeChange: (String) -> Unit,
    rightPaneCollapsed: Boolean,
    onToggleRightPane: () -> Unit,
) {
    val isStage = viewMode == HOME_MODE_STAGE
    val tabPosition by animateFloatAsState(
        targetValue = if (isStage) 0f else 1f,
        animationSpec = spring(dampingRatio = 1f, stiffness = 700f),
        label = "tablet-home-mode-pill",
    )
    val pillColor = MaterialTheme.colorScheme.surface

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.width(236.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(3.dp)
                    .selectableGroup()
                    .drawBehind {
                        val slotWidth = size.width / 2f
                        val radius = size.height / 2f
                        drawRoundRect(
                            color = pillColor,
                            topLeft = Offset(slotWidth * tabPosition, 0f),
                            size = Size(slotWidth, size.height),
                            cornerRadius = CornerRadius(radius, radius),
                        )
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ModeSegmentButton(
                    label = "大图预览",
                    icon = Icons.Default.PhotoSizeSelectLarge,
                    selected = isStage,
                    onClick = { if (!isStage) onViewModeChange(HOME_MODE_STAGE) },
                    modifier = Modifier.weight(1f),
                )
                ModeSegmentButton(
                    label = "相册预览",
                    icon = Icons.Default.GridView,
                    selected = !isStage,
                    onClick = { if (isStage) onViewModeChange(HOME_MODE_GALLERY) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Spacer(Modifier.weight(1f))

        Surface(
            shape = CircleShape,
            color = if (rightPaneCollapsed) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
            modifier = Modifier
                .clip(CircleShape)
                .clickable(
                    role = Role.Button,
                    onClickLabel = if (rightPaneCollapsed) "展开生成面板" else "收起生成面板",
                    onClick = onToggleRightPane,
                ),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = if (rightPaneCollapsed) Icons.Default.ChevronLeft else Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = if (rightPaneCollapsed) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = if (rightPaneCollapsed) "展开面板" else "收起面板",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (rightPaneCollapsed) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (rightPaneCollapsed) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ModeSegmentButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(150),
        label = "home-mode-text",
    )
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .heightIn(min = 34.dp)
            .clip(CircleShape)
            .semantics { this.selected = selected }
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(15.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 可拖拽调宽分栏手柄：10dp 宽触控/鼠标热区，中央绘制细分割线与悬浮/拖拽高亮胶囊握把。
 */
@Composable
private fun SplitPaneDragHandle(
    onDragDeltaPx: (Float) -> Unit,
    onDragFinished: () -> Unit,
) {
    val hoverInteraction = remember { MutableInteractionSource() }
    val hovered by hoverInteraction.collectIsHoveredAsState()
    var dragging by remember { mutableStateOf(false) }
    val active = hovered || dragging

    val dividerColor by animateColorAsState(
        targetValue = if (active) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
        } else {
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
        },
        animationSpec = tween(120),
        label = "split-divider-color",
    )
    val gripColor by animateColorAsState(
        targetValue = if (active) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.outlineVariant
        },
        animationSpec = tween(120),
        label = "split-grip-color",
    )

    Box(
        modifier = Modifier
            .width(10.dp)
            .fillMaxHeight()
            .hoverable(hoverInteraction)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = true },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        onDragDeltaPx(dragAmount)
                    },
                    onDragEnd = {
                        dragging = false
                        onDragFinished()
                    },
                    onDragCancel = {
                        dragging = false
                        onDragFinished()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(if (active) 2.dp else 1.dp)
                .fillMaxHeight()
                .background(dividerColor),
        )
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(34.dp)
                .clip(CircleShape)
                .background(gripColor),
        )
    }
}

/**
 * OpenAI 模式右侧面板拖拽投放包装器：支持直接从画廊/胶片栏或分屏外部应用拖入参考图。
 */
@Composable
private fun OpenAiDropContainer(
    openAiViewModel: OpenAiImageViewModel,
    onOpenAiSettings: () -> Unit,
) {
    var draggingOver by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (draggingOver) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                } else Modifier,
            )
            .imageUriDropTarget(
                onDragStateChange = { draggingOver = it },
                onDropLocalPath = { path ->
                    draggingOver = false
                    openAiViewModel.addReference(ReferenceSource.LocalPath(path))
                },
                onDropUri = { uri ->
                    draggingOver = false
                    openAiViewModel.addReference(ReferenceSource.PickedUri(uri))
                },
            ),
    ) {
        OpenAiImageSheet(openAiViewModel, true, onOpenAiSettings)
        AnimatedVisibility(
            visible = draggingOver,
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(100)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.95f),
                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
                tonalElevation = 6.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                ) {
                    Icon(
                        imageVector = Icons.Default.AddPhotoAlternate,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "松手添加为 OpenAI 参考图",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
    }
}

private const val HOME_MODE_GALLERY = "gallery"
private const val HOME_MODE_STAGE = "stage"
private const val MIN_PANE_WIDTH_DP = 320f
private const val MAX_PANE_WIDTH_DP = 560f
