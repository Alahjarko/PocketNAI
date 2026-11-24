package net.pocketnai.ui.inpaint

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.pocketnai.R
import net.pocketnai.core.Outcome
import net.pocketnai.data.image.MaskImageProcessor
import net.pocketnai.domain.image.PixelSize
import net.pocketnai.domain.inpaint.MaskGeometry
import net.pocketnai.domain.inpaint.MaskStroke
import net.pocketnai.domain.model.ReferenceImage
import net.pocketnai.domain.model.ReferenceRole
import net.pocketnai.ui.LocalAppContainer
import net.pocketnai.ui.common.CenteredHint
import net.pocketnai.ui.common.messageRes
import net.pocketnai.ui.common.onMouseWheel
import net.pocketnai.ui.generate.GenerateViewModel
import net.pocketnai.ui.motion.rememberPocketHaptics

/**
 * 局部重绘的蒙版编辑器（全屏）。
 *
 * 支持单指精准涂抹/擦除与双指捏合缩放平移（第二根手指在触控容差内按下时自动取消单指误触墨点），
 * 并在拖动笔刷或扩张滑块时在画布中央实时显示等比例笔刷预览圈。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InpaintEditorScreen(
    viewModel: GenerateViewModel,
    base: ReferenceImage,
    onDone: () -> Unit,
) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberPocketHaptics()

    val strokes = remember { mutableStateListOf<MaskStroke>() }
    val redoStack = remember { mutableStateListOf<MaskStroke>() }
    var isErase by remember { mutableStateOf(false) }
    var brushRadius by remember { mutableFloatStateOf(24f) }
    var dilation by remember { mutableFloatStateOf(viewModel.inpaintDilation.value) }
    var currentStroke by remember { mutableStateOf<MaskStroke?>(null) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var viewZoom by remember { mutableFloatStateOf(1f) }
    var viewPan by remember { mutableStateOf(Offset.Zero) }
    var clearConfirm by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var hoverPosition by remember { mutableStateOf<Offset?>(null) }
    var hoverIsEraser by remember { mutableStateOf(false) }
    var keyPreviewPulse by remember { mutableIntStateOf(0) }
    val rootFocusRequester = remember { FocusRequester() }

    val brushInteraction = remember { MutableInteractionSource() }
    val dilationInteraction = remember { MutableInteractionSource() }
    val brushDragging by brushInteraction.collectIsDraggedAsState()
    val brushPressed by brushInteraction.collectIsPressedAsState()
    val dilationDragging by dilationInteraction.collectIsDraggedAsState()
    val dilationPressed by dilationInteraction.collectIsPressedAsState()
    var previewHold by remember { mutableStateOf(false) }
    val sliderActive = brushDragging || brushPressed || dilationDragging || dilationPressed
    LaunchedEffect(sliderActive) {
        if (sliderActive) {
            previewHold = true
        } else {
            delay(320)
            previewHold = false
        }
    }
    LaunchedEffect(keyPreviewPulse) {
        if (keyPreviewPulse > 0) {
            previewHold = true
            delay(420)
            if (!sliderActive) previewHold = false
        }
    }
    LaunchedEffect(clearConfirm) {
        if (!clearConfirm) runCatching { rootFocusRequester.requestFocus() }
    }
    val previewAlpha by animateFloatAsState(
        targetValue = if (previewHold) 1f else 0f,
        animationSpec = tween(if (previewHold) 80 else 180),
        label = "brush-preview-alpha",
    )

    fun adjustBrushRadius(delta: Float, showCenterPreview: Boolean = false) {
        val next = (brushRadius + delta).coerceIn(
            MaskImageProcessor.BRUSH_RANGE.start,
            MaskImageProcessor.BRUSH_RANGE.endInclusive,
        )
        if (next != brushRadius) {
            brushRadius = next
            haptics.frequentTick()
            if (showCenterPreview && hoverPosition == null) {
                keyPreviewPulse++
            }
        }
    }

    fun undoLastStroke() {
        if (strokes.isNotEmpty()) {
            haptics.tick()
            redoStack.add(strokes.removeAt(strokes.lastIndex))
        }
    }

    fun redoLastStroke() {
        if (redoStack.isNotEmpty()) {
            haptics.tick()
            strokes.add(redoStack.removeAt(redoStack.lastIndex))
        }
    }

    // 同一进程内来回导航时恢复上次的笔画。
    LaunchedEffect(Unit) {
        if (strokes.isEmpty()) strokes.addAll(viewModel.inpaintStrokes.value)
    }

    val baseBitmap = remember(base.id) { decode(base.relativePath, container.fileStore::resolve) }
    val maskSize = PixelSize(
        baseBitmap?.width ?: base.width,
        baseBitmap?.height ?: base.height,
    )
    val transform = MaskGeometry.zoomedFit(
        bitmapWidth = maskSize.width,
        bitmapHeight = maskSize.height,
        viewWidth = viewSize.width.toFloat(),
        viewHeight = viewSize.height.toFloat(),
        zoom = viewZoom,
        panX = viewPan.x,
        panY = viewPan.y,
    )

    val currentTransform by rememberUpdatedState(transform)
    val currentIsErase by rememberUpdatedState(isErase)
    val currentBrushRadius by rememberUpdatedState(brushRadius)
    val currentViewSize by rememberUpdatedState(viewSize)
    val currentZoom by rememberUpdatedState(viewZoom)
    val currentPan by rememberUpdatedState(viewPan)

    // 已完成路径缓存为不可变快照；松手、撤销和扩张都直接参与当前帧绘制。
    val committedPaths = remember(strokes.toList()) {
        strokes.map { it to it.toBitmapPath() }
    }
    val bitmapPaint = remember { Paint(Paint.FILTER_BITMAP_FLAG) }
    val inkPaint = remember {
        Paint().apply {
            isAntiAlias = false
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = Color.BLACK
        }
    }
    val primaryComposeColor = MaterialTheme.colorScheme.primary
    val overlayColor = primaryComposeColor.toArgb()
    val overlayPaint = remember(overlayColor) {
        Paint().apply {
            alpha = (255 * OVERLAY_ALPHA).toInt()
            colorFilter = PorterDuffColorFilter(overlayColor, PorterDuff.Mode.SRC_IN)
        }
    }

    val save: () -> Unit = save@{
        if (saving) return@save
        saving = true
        scope.launch {
            val outcome = withContext(Dispatchers.Default) {
                MaskImageProcessor(context, container.fileStore)
                    .render(strokes.toList(), maskSize, dilation)
            }
            if (outcome is Outcome.Success) {
                val prepared = outcome.value
                viewModel.onInpaintStrokesChange(strokes.toList())
                viewModel.onInpaintDilationChange(dilation)
                viewModel.onInpaintMaskRendered(
                    ReferenceImage(
                        id = prepared.sha256,
                        role = ReferenceRole.INPAINT_MASK,
                        ordinal = 0,
                        relativePath = prepared.relativePath,
                        width = prepared.width,
                        height = prepared.height,
                        byteSize = prepared.byteSize,
                        sha256 = prepared.sha256,
                        createdAt = System.currentTimeMillis(),
                    ),
                )
            }
            onDone()
        }
    }

    Scaffold(
        modifier = Modifier
            .focusRequester(rootFocusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || clearConfirm) return@onPreviewKeyEvent false
                val ctrlOrMeta = (event.isCtrlPressed || event.isMetaPressed) && !event.isAltPressed
                when {
                    ctrlOrMeta && event.key == Key.Z -> {
                        if (event.isShiftPressed) redoLastStroke() else undoLastStroke()
                        true
                    }
                    ctrlOrMeta && !event.isShiftPressed && event.key == Key.Y -> {
                        redoLastStroke()
                        true
                    }
                    !event.isCtrlPressed && !event.isMetaPressed && !event.isAltPressed && event.key == Key.LeftBracket -> {
                        adjustBrushRadius(-4f, showCenterPreview = true)
                        true
                    }
                    !event.isCtrlPressed && !event.isMetaPressed && !event.isAltPressed && event.key == Key.RightBracket -> {
                        adjustBrushRadius(4f, showCenterPreview = true)
                        true
                    }
                    else -> false
                }
            },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.inpaint_title)) },
                navigationIcon = {
                    IconButton(onClick = save) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.inpaint_back_hint),
                        )
                    }
                },
                actions = {
                    net.pocketnai.ui.common.InfoButton(stringResource(R.string.inpaint_hint))
                    TextButton(onClick = save, enabled = !saving) {
                        Text(stringResource(R.string.inpaint_done))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clipToBounds()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .onSizeChanged { viewSize = it },
            ) {
                if (baseBitmap == null) {
                    CenteredHint(stringResource(R.string.error_reference_decode_failed))
                } else {
                    ComposeCanvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .onMouseWheel { deltaY, isCtrlPressed ->
                                if (isCtrlPressed) {
                                    val factor = 1.15f.pow((-deltaY).coerceIn(-3f, 3f))
                                    val nextZoom = (currentZoom * factor).coerceIn(1f, MaskGeometry.MAX_VIEW_ZOOM)
                                    val vsize = currentViewSize
                                    val (clampedX, clampedY) = MaskGeometry.clampPan(
                                        bitmapWidth = maskSize.width,
                                        bitmapHeight = maskSize.height,
                                        viewWidth = vsize.width.toFloat(),
                                        viewHeight = vsize.height.toFloat(),
                                        zoom = nextZoom,
                                        panX = currentPan.x,
                                        panY = currentPan.y,
                                    )
                                    viewZoom = nextZoom
                                    viewPan = Offset(clampedX, clampedY)
                                } else {
                                    val step = when {
                                        currentBrushRadius < 24f -> 2f
                                        currentBrushRadius < 64f -> 4f
                                        else -> 6f
                                    }
                                    adjustBrushRadius(-deltaY.coerceIn(-3f, 3f) * step)
                                }
                            }
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        val change = event.changes.firstOrNull()
                                        val isHoverCapable = change != null && (
                                            change.type == PointerType.Mouse ||
                                                change.type == PointerType.Stylus ||
                                                change.type == PointerType.Eraser
                                            )
                                        when (event.type) {
                                            PointerEventType.Enter,
                                            PointerEventType.Move,
                                            PointerEventType.Press,
                                            -> {
                                                if (isHoverCapable && change != null) {
                                                    hoverPosition = change.position
                                                    hoverIsEraser = change.type == PointerType.Eraser || event.buttons.isSecondaryPressed
                                                } else {
                                                    hoverPosition = null
                                                    hoverIsEraser = false
                                                }
                                            }
                                            PointerEventType.Release -> {
                                                if (isHoverCapable && change != null) {
                                                    hoverPosition = change.position
                                                    hoverIsEraser = change.type == PointerType.Eraser
                                                } else {
                                                    hoverPosition = null
                                                    hoverIsEraser = false
                                                }
                                            }
                                            PointerEventType.Exit -> {
                                                hoverPosition = null
                                                hoverIsEraser = false
                                            }
                                            else -> Unit
                                        }
                                    }
                                }
                            }
                            .pointerInput(maskSize) {
                                val touchSlop = viewConfiguration.touchSlop
                                awaitEachGesture {
                                    val firstDown = awaitFirstDown(requireUnconsumed = false)
                                    val isSecondaryDrag = currentEvent.buttons.isSecondaryPressed
                                    val isStylusEraser = firstDown.type == PointerType.Eraser
                                    val effectiveErase = currentIsErase || isSecondaryDrag || isStylusEraser
                                    if (isSecondaryDrag) {
                                        firstDown.consume()
                                    }
                                    val startTransform = currentTransform
                                    val startPoint = startTransform.toBitmap(
                                        firstDown.position.x,
                                        firstDown.position.y,
                                        maskSize.width,
                                        maskSize.height,
                                    )
                                    currentStroke = MaskStroke(
                                        isErase = effectiveErase,
                                        radius = MaskGeometry.bitmapRadius(currentBrushRadius, startTransform),
                                        points = listOf(startPoint),
                                    )
                                    var multiTouch = false
                                    var paintPastSlop = false

                                    do {
                                        val event = awaitPointerEvent()
                                        val pressed = event.changes.filter { it.pressed }
                                        if (pressed.size >= 2 && !isSecondaryDrag) {
                                            if (!multiTouch) {
                                                multiTouch = true
                                                if (paintPastSlop) {
                                                    // 单指已画出有效长笔道后再落第二指：先保存已画笔道，再切换缩放。
                                                    currentStroke?.takeIf { it.isDrawable }?.let { committed ->
                                                        strokes.add(committed)
                                                        redoStack.clear()
                                                    }
                                                }
                                                // 若尚未跨过触控容差即落下第二指，直接丢弃起始墨点，杜绝双指缩放误留圆点。
                                                currentStroke = null
                                            }
                                            val zoomDelta = event.calculateZoom()
                                            val panDelta = event.calculatePan()
                                            val nextZoom = (currentZoom * zoomDelta).coerceIn(1f, MaskGeometry.MAX_VIEW_ZOOM)
                                            val vsize = currentViewSize
                                            val (clampedX, clampedY) = MaskGeometry.clampPan(
                                                bitmapWidth = maskSize.width,
                                                bitmapHeight = maskSize.height,
                                                viewWidth = vsize.width.toFloat(),
                                                viewHeight = vsize.height.toFloat(),
                                                zoom = nextZoom,
                                                panX = currentPan.x + panDelta.x,
                                                panY = currentPan.y + panDelta.y,
                                            )
                                            viewZoom = nextZoom
                                            viewPan = Offset(clampedX, clampedY)
                                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                                        } else if (pressed.size == 1 && !multiTouch) {
                                            val pointer = pressed.first()
                                            if (!paintPastSlop && (pointer.position - firstDown.position).getDistance() > touchSlop) {
                                                paintPastSlop = true
                                            }
                                            if (pointer.positionChanged()) {
                                                val tf = currentTransform
                                                val pt = tf.toBitmap(
                                                    pointer.position.x,
                                                    pointer.position.y,
                                                    maskSize.width,
                                                    maskSize.height,
                                                )
                                                currentStroke = currentStroke?.let { s -> s.copy(points = s.points + pt) }
                                                pointer.consume()
                                            }
                                        }
                                        if (isSecondaryDrag) {
                                            event.changes.forEach { it.consume() }
                                        }
                                    } while (event.changes.any { it.pressed })

                                    if (!multiTouch) {
                                        currentStroke?.takeIf { it.isDrawable }?.let { stroke ->
                                            strokes.add(stroke)
                                            redoStack.clear()
                                        }
                                    }
                                    currentStroke = null
                                }
                            },
                    ) {
                        drawIntoCanvas { canvas ->
                            val native = canvas.nativeCanvas
                            val destination = android.graphics.RectF(
                                transform.offsetX,
                                transform.offsetY,
                                transform.offsetX + maskSize.width * transform.scale,
                                transform.offsetY + maskSize.height * transform.scale,
                            )
                            native.drawBitmap(baseBitmap, null, destination, bitmapPaint)

                            // 仅在可见视口交集上分配离屏合成图层：即使放大到 5x，离屏纹理也不会超过屏幕尺寸。
                            val layerBounds = android.graphics.RectF(destination)
                            if (layerBounds.intersect(0f, 0f, size.width, size.height)) {
                                val layer = native.saveLayer(layerBounds, overlayPaint)
                                native.clipRect(layerBounds)
                                native.translate(transform.offsetX, transform.offsetY)
                                native.scale(transform.scale, transform.scale)
                                committedPaths.forEach { (stroke, path) ->
                                    native.drawInk(stroke, path, dilation, inkPaint)
                                }
                                currentStroke?.let { stroke ->
                                    native.drawInk(stroke, stroke.toBitmapPath(), dilation, inkPaint)
                                }
                                native.restoreToCount(layer)
                            }
                        }

                        // 滑块调参时的实时等比例笔刷预览圈。
                        if (previewAlpha > 0.01f) {
                            val bmpRadius = MaskGeometry.bitmapRadius(brushRadius, transform)
                            val effectiveBmpRadius = if (isErase) bmpRadius else bmpRadius + dilation.coerceAtLeast(0f)
                            val screenRadius = (effectiveBmpRadius * transform.scale).coerceAtLeast(2f)
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val fillTint = if (isErase) {
                                ComposeColor.White.copy(alpha = 0.25f * previewAlpha)
                            } else {
                                primaryComposeColor.copy(alpha = OVERLAY_ALPHA * previewAlpha)
                            }
                            drawCircle(color = fillTint, radius = screenRadius, center = center)
                            drawCircle(
                                color = ComposeColor.Black.copy(alpha = 0.45f * previewAlpha),
                                radius = screenRadius + 1.dp.toPx(),
                                center = center,
                                style = Stroke(width = 1.5.dp.toPx()),
                            )
                            drawCircle(
                                color = ComposeColor.White.copy(alpha = 0.9f * previewAlpha),
                                radius = screenRadius,
                                center = center,
                                style = Stroke(width = 1.5.dp.toPx()),
                            )
                        }

                        // 鼠标 / 手写笔悬停时的实时空心笔刷光标，严格匹配当前屏幕笔刷半径。
                        hoverPosition?.let { cursor ->
                            val cursorErase = currentStroke?.isErase ?: (isErase || hoverIsEraser)
                            val bmpRadius = MaskGeometry.bitmapRadius(brushRadius, transform)
                            val effectiveBmpRadius = if (cursorErase) bmpRadius else bmpRadius + dilation.coerceAtLeast(0f)
                            val screenRadius = (effectiveBmpRadius * transform.scale).coerceAtLeast(2f)
                            val strokeWidthPx = 1.5.dp.toPx()
                            drawCircle(
                                color = ComposeColor.Black.copy(alpha = 0.55f),
                                radius = screenRadius + 1.dp.toPx(),
                                center = cursor,
                                style = Stroke(width = strokeWidthPx),
                            )
                            drawCircle(
                                color = if (cursorErase) {
                                    ComposeColor.White.copy(alpha = 0.92f)
                                } else {
                                    primaryComposeColor.copy(alpha = 0.95f)
                                },
                                radius = screenRadius,
                                center = cursor,
                                style = Stroke(width = strokeWidthPx),
                            )
                            drawCircle(
                                color = ComposeColor.White.copy(alpha = 0.85f),
                                radius = 1.5.dp.toPx(),
                                center = cursor,
                            )
                        }
                    }

                    if (viewZoom > 1.02f) {
                        AssistChip(
                            onClick = {
                                haptics.tick()
                                viewZoom = 1f
                                viewPan = Offset.Zero
                            },
                            label = { Text("${(viewZoom * 100).roundToInt()}% · 重置视图") },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                            ),
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(12.dp),
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = !isErase,
                        onClick = {
                            if (isErase) haptics.tick()
                            isErase = false
                        },
                        label = { Text(stringResource(R.string.inpaint_brush)) },
                        leadingIcon = { Icon(Icons.Default.Brush, contentDescription = null) },
                    )
                    FilterChip(
                        selected = isErase,
                        onClick = {
                            if (!isErase) haptics.tick()
                            isErase = true
                        },
                        label = { Text(stringResource(R.string.inpaint_eraser)) },
                    )
                    IconButton(
                        onClick = { undoLastStroke() },
                        enabled = strokes.isNotEmpty(),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = stringResource(R.string.inpaint_undo))
                    }
                    IconButton(
                        onClick = { redoLastStroke() },
                        enabled = redoStack.isNotEmpty(),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = stringResource(R.string.inpaint_redo))
                    }
                    IconButton(onClick = { clearConfirm = true }, enabled = strokes.isNotEmpty()) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.inpaint_clear))
                    }
                }

                LabeledSliderRow(
                    label = stringResource(
                        R.string.inpaint_brush_size,
                        MaskGeometry.displayRadius(MaskGeometry.bitmapRadius(brushRadius, transform)),
                    ),
                    value = brushRadius,
                    range = MaskImageProcessor.BRUSH_RANGE,
                    interactionSource = brushInteraction,
                    onChange = { brushRadius = it },
                )
                LabeledSliderRow(
                    label = stringResource(R.string.inpaint_dilation, dilation.toInt()),
                    value = dilation,
                    range = MaskImageProcessor.DILATION_RANGE,
                    interactionSource = dilationInteraction,
                    onChange = { dilation = it },
                )

                // 始终保留同一行高度，否则第一笔落下后底图会重新 Fit，整幅画面跳动。
                Text(
                    text = stringResource(R.string.inpaint_empty_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = if (strokes.isEmpty()) Modifier else Modifier
                        .alpha(0f)
                        .clearAndSetSemantics { },
                )
            }
        }
    }

    if (clearConfirm) {
        AlertDialog(
            onDismissRequest = { clearConfirm = false },
            title = { Text(stringResource(R.string.inpaint_clear_confirm_title)) },
            text = { Text(stringResource(R.string.inpaint_clear_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        haptics.tick()
                        strokes.clear()
                        redoStack.clear()
                        clearConfirm = false
                    },
                ) {
                    Text(stringResource(R.string.inpaint_clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { clearConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
fun InpaintPreparing(onCancel: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text(
            text = stringResource(R.string.inpaint_preparing),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 16.dp),
        )
        TextButton(onClick = onCancel, modifier = Modifier.padding(top = 8.dp)) {
            Text(stringResource(R.string.action_cancel))
        }
    }
}

@Composable
fun InpaintUnavailable(messageRes: net.pocketnai.core.ErrorCode, onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(messageRes.messageRes()),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
        TextButton(onClick = onBack, modifier = Modifier.padding(top = 8.dp)) {
            Text(stringResource(R.string.action_confirm))
        }
    }
}

private const val OVERLAY_ALPHA = 0.45f
private const val SCALE = 2f

private fun decode(relativePath: String, resolve: (String) -> File): Bitmap? = runCatching {
    val file = resolve(relativePath)
    if (!file.isFile) return@runCatching null
    BitmapFactory.decodeFile(file.absolutePath)
}.getOrNull()

private fun Canvas.drawInk(stroke: MaskStroke, path: Path, dilation: Float, paint: Paint) {
    if (!stroke.isDrawable) return
    paint.strokeWidth = MaskGeometry.effectiveRadius(stroke, dilation) * SCALE
    paint.xfermode = if (stroke.isErase) PorterDuffXfermode(PorterDuff.Mode.CLEAR) else null
    drawPath(path, paint)
}

private fun MaskStroke.toBitmapPath(): Path = Path().apply {
    if (points.isEmpty()) return@apply
    moveTo(points.first().x, points.first().y)
    if (points.size == 1) {
        lineTo(points.first().x + 0.01f, points.first().y)
    } else {
        points.drop(1).forEach { lineTo(it.x, it.y) }
    }
}

@Composable
private fun LabeledSliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    interactionSource: MutableInteractionSource,
    onChange: (Float) -> Unit,
) {
    val haptics = rememberPocketHaptics()
    var lastInt by remember { mutableIntStateOf((value / 2f).roundToInt()) }
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = value,
            onValueChange = { next ->
                val bucket = (next / 2f).roundToInt()
                if (bucket != lastInt) {
                    lastInt = bucket
                    haptics.frequentTick()
                }
                onChange(next)
            },
            valueRange = range,
            interactionSource = interactionSource,
        )
    }
}
