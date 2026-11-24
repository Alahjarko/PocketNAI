package net.pocketnai.ui.common

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

const val POCKETNAI_DRAG_LOCAL_PATH_PREFIX = "pocketnai-local-path:"
private const val POCKETNAI_DRAG_CLIP_LABEL = "pocketnai-local-path"

/**
 * 监听鼠标右键按下事件，不拦截普通触摸点击、滚动或长按。
 */
fun Modifier.onSecondaryClick(
    enabled: Boolean = true,
    onSecondaryClick: (Offset) -> Unit,
): Modifier = composed {
    val currentEnabled by rememberUpdatedState(enabled)
    val currentCallback by rememberUpdatedState(onSecondaryClick)

    pointerInput(Unit) {
        awaitPointerEventScope {
            var activeSecondaryPointer: PointerId? = null
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (!currentEnabled) {
                    activeSecondaryPointer = null
                    continue
                }
                when (event.type) {
                    PointerEventType.Press -> {
                        if (event.buttons.isSecondaryPressed) {
                            val press = event.changes.firstOrNull { it.pressed && !it.previousPressed }
                                ?: event.changes.firstOrNull()
                            if (press != null) {
                                activeSecondaryPointer = press.id
                                event.changes.forEach { it.consume() }
                                currentCallback(press.position)
                            }
                        } else {
                            activeSecondaryPointer = null
                        }
                    }

                    PointerEventType.Release -> {
                        val tracked = activeSecondaryPointer
                        if (tracked != null) {
                            val release = event.changes.firstOrNull { it.id == tracked && !it.pressed }
                            if (release != null) {
                                release.consume()
                            }
                            if (!event.buttons.isSecondaryPressed) {
                                activeSecondaryPointer = null
                            }
                        }
                    }

                    else -> {
                        if (activeSecondaryPointer != null && !event.buttons.isSecondaryPressed) {
                            activeSecondaryPointer = null
                        }
                    }
                }
            }
        }
    }
}

/**
 * 监听鼠标滚轮事件，透传纵向滚动增量与 Ctrl 修饰键状态。
 */
fun Modifier.onMouseWheel(
    enabled: Boolean = true,
    onWheel: (deltaY: Float, isCtrlPressed: Boolean) -> Unit,
): Modifier = composed {
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOnWheel by rememberUpdatedState(onWheel)

    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                if (!currentEnabled || event.type != PointerEventType.Scroll) continue
                if (event.changes.none { !it.isConsumed }) continue

                val deltaY = event.changes.fold(0f) { acc, change ->
                    if (change.isConsumed) acc else acc + change.scrollDelta.y
                }
                if (deltaY != 0f) {
                    event.changes.forEach { it.consume() }
                    currentOnWheel(deltaY, event.keyboardModifiers.isCtrlPressed)
                }
            }
        }
    }
}

private class MouseDragStateHolder {
    var active: Boolean = false
}

/**
 * 仅在鼠标左键拖动超过 touchSlop 后启动 Android 拖拽（携带应用内本地路径），
 * 绝不拦截手指触摸滑动、点击或长按。
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.mouseImageDragSource(
    localPath: String,
    enabled: Boolean = true,
    onDragStart: () -> Unit = {},
    onDragEnd: () -> Unit = {},
): Modifier = composed {
    val view = LocalView.current
    val currentLocalPath by rememberUpdatedState(localPath)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    val dragState = remember { MouseDragStateHolder() }

    DisposableEffect(Unit) {
        onDispose {
            if (dragState.active) {
                dragState.active = false
                currentOnDragEnd()
            }
        }
    }

    val endObserver = remember {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean = false

            override fun onEnded(event: DragAndDropEvent) {
                if (dragState.active) {
                    dragState.active = false
                    currentOnDragEnd()
                }
            }
        }
    }

    this
        .dragAndDropTarget(
            shouldStartDragAndDrop = { dragState.active },
            target = endObserver,
        )
        .pointerInput(view) {
            awaitPointerEventScope {
                while (true) {
                    val downEvent = awaitPointerEvent(PointerEventPass.Initial)
                    if (!currentEnabled || downEvent.type != PointerEventType.Press) continue
                    val path = currentLocalPath
                    if (path.isBlank()) continue

                    val down = downEvent.changes.firstOrNull { it.pressed && !it.previousPressed }
                        ?: continue
                    if (down.type != PointerType.Mouse) continue
                    if (!downEvent.buttons.isPrimaryPressed || downEvent.buttons.isSecondaryPressed) {
                        continue
                    }

                    val pointerId = down.id
                    val startPosition = down.position
                    val touchSlop = viewConfiguration.touchSlop

                    while (true) {
                        val moveEvent = awaitPointerEvent(PointerEventPass.Initial)
                        val change = moveEvent.changes.firstOrNull { it.id == pointerId } ?: break
                        if (!change.pressed || change.type != PointerType.Mouse) break

                        val distance = (change.position - startPosition).getDistance()
                        if (distance > touchSlop) {
                            change.consume()
                            val clipData = ClipData.newPlainText(
                                POCKETNAI_DRAG_CLIP_LABEL,
                                "$POCKETNAI_DRAG_LOCAL_PATH_PREFIX$path",
                            )
                            val shadowBuilder = ImageDragShadowBuilder(
                                localPath = path,
                                density = view.resources.displayMetrics.density,
                            )
                            dragState.active = true
                            val started = runCatching {
                                view.startDragAndDrop(clipData, shadowBuilder, null, 0)
                            }.getOrDefault(false)
                            if (started) {
                                currentOnDragStart()
                            } else {
                                dragState.active = false
                            }
                            break
                        }
                    }
                }
            }
        }
}

/**
 * 接收应用内拖拽图片（[POCKETNAI_DRAG_LOCAL_PATH_PREFIX]）与分屏跨应用拖入的图片 URI。
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.imageUriDropTarget(
    enabled: Boolean = true,
    onDragStateChange: (Boolean) -> Unit = {},
    onDropLocalPath: (String) -> Unit = {},
    onDropUri: (String) -> Unit = {},
): Modifier = composed {
    val context = LocalContext.current
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOnDragStateChange by rememberUpdatedState(onDragStateChange)
    val currentOnDropLocalPath by rememberUpdatedState(onDropLocalPath)
    val currentOnDropUri by rememberUpdatedState(onDropUri)

    DisposableEffect(enabled) {
        if (!enabled) currentOnDragStateChange(false)
        onDispose { currentOnDragStateChange(false) }
    }

    val target = remember(context) {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) {
                if (currentEnabled) currentOnDragStateChange(true)
            }

            override fun onExited(event: DragAndDropEvent) {
                currentOnDragStateChange(false)
            }

            override fun onEnded(event: DragAndDropEvent) {
                currentOnDragStateChange(false)
            }

            override fun onDrop(event: DragAndDropEvent): Boolean {
                currentOnDragStateChange(false)
                if (!currentEnabled) return false

                val dragEvent = event.toAndroidDragEvent()
                val clipData = dragEvent.clipData ?: return false
                if (clipData.itemCount <= 0) return false

                for (i in 0 until clipData.itemCount) {
                    val text = clipData.getItemAt(i)?.text?.toString()
                    if (text != null && text.startsWith(POCKETNAI_DRAG_LOCAL_PATH_PREFIX)) {
                        val localPath = text.removePrefix(POCKETNAI_DRAG_LOCAL_PATH_PREFIX).trim()
                        if (localPath.isNotEmpty()) {
                            currentOnDropLocalPath(localPath)
                            return true
                        }
                    }
                }

                var droppedUri: String? = null
                for (i in 0 until clipData.itemCount) {
                    val item = clipData.getItemAt(i) ?: continue
                    val directUri = item.uri?.toString()?.takeIf { it.isNotBlank() }
                    if (directUri != null) {
                        droppedUri = directUri
                        break
                    }
                    val textUri = item.text?.toString()
                        ?.lineSequence()
                        ?.map { it.trim() }
                        ?.firstOrNull { line ->
                            line.isNotEmpty() &&
                                !line.startsWith("#") &&
                                (line.startsWith("content://") || line.startsWith("file://"))
                        }
                    if (textUri != null) {
                        droppedUri = textUri
                        break
                    }
                }

                if (droppedUri != null) {
                    // Android 14+ 跨应用拖拽需要通过宿主 Activity 申请临时读取权限；
                    // 不在此处立即 release，避免异步 IO 读取 URI 时触发 SecurityException。
                    runCatching {
                        context.findActivity()?.requestDragAndDropPermissions(dragEvent)
                    }
                    currentOnDropUri(droppedUri)
                    return true
                }

                return false
            }
        }
    }

    this.dragAndDropTarget(
        shouldStartDragAndDrop = { event ->
            if (!currentEnabled) return@dragAndDropTarget false
            val description = event.toAndroidDragEvent().clipDescription
            val isInternalImage = description?.label?.toString() == POCKETNAI_DRAG_CLIP_LABEL
            if (isInternalImage) return@dragAndDropTarget true

            event.mimeTypes().any { mime ->
                mime.startsWith("image/", ignoreCase = true) ||
                    mime.equals(ClipDescription.MIMETYPE_TEXT_URILIST, ignoreCase = true)
            }
        },
        target = target,
    )
}

/**
 * 响应 `Ctrl + ↑` / `Ctrl + ↓` 调整提示词权重：
 * - 有选区时作用于当前选区（去首尾空白）；
 * - 光标折叠时自动定位当前逗号/换行分隔的词条；
 * - 增强：`[tag]` -> `tag`，`tag` -> `{tag}`；
 * - 减弱：`{tag}` -> `tag`，`tag` -> `[tag]`；
 * - 返回后将选区保持在更新后的词条上，便于连续按键增减。
 */
fun adjustPromptWeightAtCursor(
    value: TextFieldValue,
    increase: Boolean,
): TextFieldValue {
    val text = value.text
    if (text.isEmpty()) return value

    val rawStart = minOf(value.selection.start, value.selection.end).coerceIn(0, text.length)
    val rawEnd = maxOf(value.selection.start, value.selection.end).coerceIn(0, text.length)

    val (targetStart, targetEnd) = if (rawStart < rawEnd) {
        trimRange(text, rawStart, rawEnd)
    } else {
        findTagRangeAtCursor(text, rawStart)
    } ?: return value

    val target = text.substring(targetStart, targetEnd)
    if (target.isEmpty()) return value

    var replaceStart = targetStart
    var replaceEnd = targetEnd
    val replacement = if (increase) {
        when {
            isWrappedBy(target, '[', ']') -> target.substring(1, target.length - 1)
            targetStart > 0 && targetEnd < text.length &&
                text[targetStart - 1] == '[' && text[targetEnd] == ']' -> {
                replaceStart = targetStart - 1
                replaceEnd = targetEnd + 1
                target
            }

            else -> "{$target}"
        }
    } else {
        when {
            isWrappedBy(target, '{', '}') -> target.substring(1, target.length - 1)
            targetStart > 0 && targetEnd < text.length &&
                text[targetStart - 1] == '{' && text[targetEnd] == '}' -> {
                replaceStart = targetStart - 1
                replaceEnd = targetEnd + 1
                target
            }

            else -> "[$target]"
        }
    }

    val updatedText = buildString(text.length + 2) {
        append(text, 0, replaceStart)
        append(replacement)
        append(text, replaceEnd, text.length)
    }
    return value.copy(
        text = updatedText,
        selection = TextRange(replaceStart, replaceStart + replacement.length),
    )
}

/**
 * PocketNAI 1.0 键鼠快捷键一览对话框。
 */
@Composable
fun ShortcutCheatsheetDialog(
    chatEnterToSend: Boolean,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Keyboard,
                contentDescription = null,
                modifier = Modifier.size(26.dp),
            )
        },
        title = {
            Text(
                text = "键鼠快捷键一览",
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ShortcutGroup(
                    title = "画廊与大图预览",
                    items = listOf(
                        "A / D · ← / →" to "切换上一张 / 下一张图片",
                        "鼠标滚轮" to "切换图片",
                        "Ctrl + 滚轮" to "缩放当前画布",
                        "F" to "收藏 / 取消收藏",
                        "Space" to "打开 / 退出全屏查看",
                        "鼠标右键" to "打开快捷操作菜单",
                        "左键拖拽图片" to "拖入生成面板用作参考图",
                        "Ctrl + F" to "聚焦画廊搜索框",
                    ),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                ShortcutGroup(
                    title = "生成与提示词",
                    items = listOf(
                        "Ctrl + Enter" to "立即开始生成",
                        "Ctrl + ↑ / ↓" to "增减当前词条权重（{} / []）",
                    ),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                ShortcutGroup(
                    title = "对话工作台",
                    items = buildList {
                        if (chatEnterToSend) {
                            add("Enter" to "发送消息（当前模式）")
                            add("Shift + Enter" to "插入换行")
                        } else {
                            add("Ctrl + Enter" to "发送消息（当前模式）")
                            add("Enter" to "插入换行")
                        }
                        add("Ctrl + N" to "新建对话")
                        add("鼠标右键" to "打开消息操作菜单")
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                ShortcutGroup(
                    title = "局部重绘与全局",
                    items = listOf(
                        "[ / ] · 滚轮" to "调节画笔大小",
                        "Ctrl + Z" to "撤销上一笔",
                        "右键拖动 / 橡皮" to "临时擦除蒙版",
                        "Esc" to "关闭全屏或弹窗",
                        "Ctrl + /" to "打开 / 关闭此快捷键速查",
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("知道了")
            }
        },
    )
}

@Composable
private fun ShortcutGroup(
    title: String,
    items: List<Pair<String, String>>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        items.forEach { (keys, description) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 10.dp),
                )
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                    ),
                ) {
                    Text(
                        text = keys,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(2.dp))
    }
}

private fun trimRange(text: String, start: Int, end: Int): Pair<Int, Int>? {
    var s = start
    var e = end
    while (s < e && text[s].isWhitespace()) s++
    while (e > s && text[e - 1].isWhitespace()) e--
    return if (s < e) s to e else null
}

private fun findTagRangeAtCursor(text: String, cursor: Int): Pair<Int, Int>? {
    var segStart = 0
    var depth = 0
    var candidate: Pair<Int, Int>? = null

    for (i in text.indices) {
        when (text[i]) {
            '{', '[' -> depth++
            '}', ']' -> if (depth > 0) depth--
            ',', '\n' -> if (depth == 0) {
                if (cursor in segStart..i) {
                    val trimmed = trimRange(text, segStart, i)
                    if (trimmed != null) return trimmed
                    candidate = null
                }
                segStart = i + 1
            }
        }
    }

    if (cursor in segStart..text.length) {
        return trimRange(text, segStart, text.length)
    }
    return candidate
}

private fun isWrappedBy(text: String, open: Char, close: Char): Boolean {
    if (text.length < 2 || text.first() != open || text.last() != close) return false
    var depth = 0
    for (i in 0 until text.length - 1) {
        when (text[i]) {
            open -> depth++
            close -> {
                depth--
                if (depth == 0) return false
            }
        }
    }
    return depth == 1
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private class ImageDragShadowBuilder(
    private val localPath: String,
    private val density: Float,
) : View.DragShadowBuilder() {
    private val sizePx = (88f * density).toInt().coerceAtLeast(96)
    private val cornerRadius = 14f * density

    override fun onProvideShadowMetrics(outShadowSize: Point, outShadowTouchPoint: Point) {
        outShadowSize.set(sizePx, sizePx)
        outShadowTouchPoint.set(sizePx / 2, sizePx / 2)
    }

    override fun onDrawShadow(canvas: Canvas) {
        val rect = RectF(2f, 2f, sizePx - 2f, sizePx - 2f)
        val clipPath = Path().apply {
            addRoundRect(rect, cornerRadius, cornerRadius, Path.Direction.CW)
        }

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(224, 34, 38, 46)
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)

        val bitmap = decodeThumbnail(localPath, sizePx)
        if (bitmap != null) {
            try {
                val save = canvas.save()
                canvas.clipPath(clipPath)
                val side = minOf(bitmap.width, bitmap.height)
                val srcLeft = (bitmap.width - side) / 2
                val srcTop = (bitmap.height - side) / 2
                val src = Rect(srcLeft, srcTop, srcLeft + side, srcTop + side)
                val dst = Rect(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt())
                canvas.drawBitmap(bitmap, src, dst, Paint(Paint.FILTER_BITMAP_FLAG))
                canvas.restoreToCount(save)
            } finally {
                bitmap.recycle()
            }
        }

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(210, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = (2f * density).coerceAtLeast(2f)
        }
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, borderPaint)
    }

    private fun decodeThumbnail(path: String, targetSize: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

        var sample = 1
        val minDim = minOf(bounds.outWidth, bounds.outHeight)
        while (minDim / (sample * 2) >= targetSize) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeFile(path, opts)
    }.getOrNull()
}
