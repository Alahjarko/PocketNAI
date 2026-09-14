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
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
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
import net.pocketnai.ui.generate.GenerateViewModel
import java.io.File

/**
 * 局部重绘的蒙版编辑器（全屏）。
 *
 * ## 为什么是全屏
 * 涂改需要精度，而生成悬浮层展开后也只有半屏。官方把它做成一个独立 Canvas 也是同样的理由。
 *
 * ## 编辑器显示的就是提交图
 * 底图在进入重绘时已经被裁切到输出尺寸，因此"手指涂的坐标"与"提交的蒙版像素"一一对应。
 * 代价是重绘模式下不能改 Resolution（改了就要重新裁底图，蒙版会整体错位）。
 *
 * ## 撤销按笔画重放，不存位图快照
 * 1216×832 的位图每张约 4 MB（ARGB_8888），二十步就是几十 MB。笔画数据只有几十个点，
 * 撤销时从头重放一遍即可。扩张滑块能实时预览，也正是因为"重放"足够便宜。
 *
 * ## 离开即保存
 * 「完成」与返回都会渲染并保存蒙版 —— 否则用户涂了半天、随手一返就白涂了。
 * 要丢弃必须显式点「清空」。
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

    val strokes = remember { mutableStateListOf<MaskStroke>() }
    val redoStack = remember { mutableStateListOf<MaskStroke>() }
    var isErase by remember { mutableStateOf(false) }
    var brushRadius by remember { mutableStateOf(24f) }
    var dilation by remember { mutableStateOf(viewModel.inpaintDilation.value) }
    var currentStroke by remember { mutableStateOf<MaskStroke?>(null) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var clearConfirm by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    // 同一进程内来回导航时恢复上次的笔画。
    LaunchedEffect(Unit) {
        if (strokes.isEmpty()) strokes.addAll(viewModel.inpaintStrokes.value)
    }

    val baseBitmap = remember(base.id) { decode(base.relativePath, container.fileStore::resolve) }
    val maskSize = PixelSize(
        baseBitmap?.width ?: base.width,
        baseBitmap?.height ?: base.height,
    )
    val transform = MaskGeometry.fit(
        bitmapWidth = maskSize.width,
        bitmapHeight = maskSize.height,
        viewWidth = viewSize.width.toFloat(),
        viewHeight = viewSize.height.toFloat(),
    )

    val inkBitmap = remember(maskSize.width, maskSize.height) {
        Bitmap.createBitmap(maskSize.width, maskSize.height, Bitmap.Config.ARGB_8888)
    }
    // 笔画或扩张一变就整层重画（笔画很少，成本可接受）。
    LaunchedEffect(strokes.toList(), dilation) {
        renderInk(inkBitmap, strokes.toList(), dilation)
    }

    val overlayColor = MaterialTheme.colorScheme.primary.toArgb()

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
            // 失败不在这里打断：错误会在生成页的参考图错误区显示出来。
            onDone()
        }
    }

    Scaffold(
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
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .onSizeChanged { viewSize = it },
            ) {
                if (baseBitmap == null) {
                    CenteredHint(stringResource(R.string.error_reference_decode_failed))
                } else {
                    ComposeCanvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(transform, isErase, brushRadius) {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        currentStroke = MaskStroke(
                                            isErase = isErase,
                                            radius = MaskGeometry.bitmapRadius(brushRadius, transform),
                                            points = listOf(
                                                transform.toBitmap(
                                                    offset.x, offset.y,
                                                    maskSize.width, maskSize.height,
                                                ),
                                            ),
                                        )
                                    },
                                    onDrag = { change, _ ->
                                        currentStroke = currentStroke?.let { stroke ->
                                            stroke.copy(
                                                points = stroke.points + transform.toBitmap(
                                                    change.position.x, change.position.y,
                                                    maskSize.width, maskSize.height,
                                                ),
                                            )
                                        }
                                    },
                                    onDragEnd = {
                                        currentStroke?.takeIf { it.isDrawable }?.let { stroke ->
                                            strokes.add(stroke)
                                            redoStack.clear()
                                        }
                                        currentStroke = null
                                    },
                                    onDragCancel = { currentStroke = null },
                                )
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
                            native.drawBitmap(
                                baseBitmap,
                                null,
                                destination,
                                Paint(Paint.FILTER_BITMAP_FLAG),
                            )

                            // 墨迹染色成主题色后半透明叠加：既看得清涂在哪儿，又看得见底图。
                            val overlay = Paint().apply {
                                alpha = (255 * OVERLAY_ALPHA).toInt()
                                colorFilter = PorterDuffColorFilter(
                                    overlayColor,
                                    PorterDuff.Mode.SRC_IN,
                                )
                            }
                            native.drawBitmap(inkBitmap, null, destination, overlay)

                            // 正在画的这一笔单独画，避免整层重绘带来的延迟。
                            currentStroke?.let { stroke ->
                                val live = Paint().apply {
                                    isAntiAlias = false
                                    style = Paint.Style.STROKE
                                    strokeCap = Paint.Cap.ROUND
                                    strokeJoin = Paint.Join.ROUND
                                    strokeWidth = MaskGeometry.effectiveRadius(stroke, dilation) * SCALE
                                    color = overlayColor
                                    alpha = (255 * OVERLAY_ALPHA).toInt()
                                }
                                native.drawPath(stroke.toViewPath(transform), live)
                            }
                        }
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
                        onClick = { isErase = false },
                        label = { Text(stringResource(R.string.inpaint_brush)) },
                        leadingIcon = { Icon(Icons.Default.Brush, contentDescription = null) },
                    )
                    FilterChip(
                        selected = isErase,
                        onClick = { isErase = true },
                        label = { Text(stringResource(R.string.inpaint_eraser)) },
                    )
                    IconButton(
                        onClick = { if (strokes.isNotEmpty()) redoStack.add(strokes.removeAt(strokes.lastIndex)) },
                        enabled = strokes.isNotEmpty(),
                    ) {
                        Icon(Icons.Default.Undo, contentDescription = stringResource(R.string.inpaint_undo))
                    }
                    IconButton(
                        onClick = { if (redoStack.isNotEmpty()) strokes.add(redoStack.removeAt(redoStack.lastIndex)) },
                        enabled = redoStack.isNotEmpty(),
                    ) {
                        Icon(Icons.Default.Redo, contentDescription = stringResource(R.string.inpaint_redo))
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
                    onChange = { brushRadius = it },
                )
                LabeledSliderRow(
                    label = stringResource(R.string.inpaint_dilation, dilation.toInt()),
                    value = dilation,
                    range = MaskImageProcessor.DILATION_RANGE,
                    onChange = { dilation = it },
                )

                Text(
                    text = stringResource(R.string.inpaint_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (strokes.isEmpty()) {
                    Text(
                        text = stringResource(R.string.inpaint_empty_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
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

/**
 * 底图还在导入中。
 *
 * 进入编辑器是一次异步导入（解码 + 裁切 + 落盘），在低端设备上可能几百毫秒，
 * 因此必须有这个中间态 —— 否则用户会看到一闪而过的空白编辑器。
 */
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

/** 底图导入失败：说明原因并让用户返回，而不是留一个空画布。 */
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

/** 画笔宽度是半径，`strokeWidth` 要的是直径。 */
private const val SCALE = 2f

private fun decode(relativePath: String, resolve: (String) -> File): Bitmap? = runCatching {
    val file = resolve(relativePath)
    if (!file.isFile) return@runCatching null
    BitmapFactory.decodeFile(file.absolutePath)
}.getOrNull()

/**
 * 把笔画重放进墨迹层。
 *
 * 扩张通过"半径偏移"实现（见 [MaskGeometry.effectiveRadius]），因此这里不做任何形态学运算 ——
 * 这也是滑块能实时预览的原因。
 *
 * 用 `xfermode` 而不是 `blendMode`：后者是 API 29+，而本项目 minSdk 是 26。
 */
private fun renderInk(bitmap: Bitmap, strokes: List<MaskStroke>, dilation: Float) {
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

    val paint = Paint().apply {
        isAntiAlias = false
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.BLACK
    }
    strokes.forEach { stroke ->
        if (!stroke.isDrawable) return@forEach
        paint.strokeWidth = MaskGeometry.effectiveRadius(stroke, dilation) * SCALE
        paint.xfermode = if (stroke.isErase) {
            PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        } else {
            null
        }
        canvas.drawPath(stroke.toBitmapPath(), paint)
    }
}

private fun MaskStroke.toBitmapPath(): Path = Path().apply {
    if (points.isEmpty()) return@apply
    moveTo(points.first().x, points.first().y)
    if (points.size == 1) {
        // 单点：用一段极短的线配合圆头端点画出圆点。
        lineTo(points.first().x + 0.01f, points.first().y)
    } else {
        points.drop(1).forEach { lineTo(it.x, it.y) }
    }
}

private fun MaskStroke.toViewPath(transform: MaskGeometry.FitTransform): Path = Path().apply {
    if (points.isEmpty()) return@apply
    val first = transform.toView(points.first())
    moveTo(first.x, first.y)
    if (points.size == 1) {
        lineTo(first.x + 0.01f, first.y)
    } else {
        points.drop(1).forEach { point ->
            val scaled = transform.toView(point)
            lineTo(scaled.x, scaled.y)
        }
    }
}

@Composable
private fun LabeledSliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}
