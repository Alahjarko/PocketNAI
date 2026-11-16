package net.pocketnai.ui.generate

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.hypot
import kotlin.math.roundToInt
import net.pocketnai.domain.model.CharacterPrompt
import net.pocketnai.domain.model.ImageSizePreset
import net.pocketnai.ui.motion.rememberPocketHaptics

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CharacterPositionDialog(
    characters: List<CharacterPrompt>,
    canvasSize: ImageSizePreset,
    onUpdate: (Int, CharacterPrompt) -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = rememberPocketHaptics()
    var selectedId by remember { mutableStateOf(characters.first().id) }
    var draggingIndex by remember { mutableIntStateOf(-1) }
    val selected = characters.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)

    val currentCharacters by rememberUpdatedState(characters)
    val currentSelected by rememberUpdatedState(selected)
    val currentOnUpdate by rememberUpdatedState(onUpdate)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("角色位置") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    characters.forEachIndexed { index, character ->
                        FilterChip(
                            selected = index == selected,
                            onClick = {
                                haptics.tick()
                                selectedId = character.id
                            },
                            label = { Text("角色 ${index + 1}") },
                        )
                    }
                }
                Text(
                    "拖拽圆点或点击画布放置 · ${canvasSize.label}",
                    style = MaterialTheme.typography.bodySmall,
                )
                BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val ratio = canvasSize.width.toFloat() / canvasSize.height
                    val width = minOf(maxWidth, 360.dp * ratio)
                    var pixels by remember { mutableStateOf(IntSize.Zero) }
                    val density = LocalDensity.current
                    val radius = with(density) { 16.dp.toPx() }
                    val grabRadius = with(density) { 26.dp.toPx() }
                    val primaryColor = MaterialTheme.colorScheme.primary

                    Box(
                        Modifier
                            .width(width)
                            .aspectRatio(ratio)
                            .clip(MaterialTheme.shapes.small)
                            .background(Color(0xFF101014))
                            .drawBehind {
                                // 5x5 坐标分区微弱参考线 + 三分构图线，辅助精准对齐角色站位。
                                val gridColor = Color.White.copy(alpha = 0.06f)
                                for (step in 1..4) {
                                    val x = size.width * (step / 5f)
                                    val y = size.height * (step / 5f)
                                    drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                                    drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                                }
                                val thirdsColor = Color.White.copy(alpha = 0.14f)
                                for (step in 1..2) {
                                    val x = size.width * (step / 3f)
                                    val y = size.height * (step / 3f)
                                    drawLine(thirdsColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.5f)
                                    drawLine(thirdsColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.5f)
                                }
                            }
                            .testTag("character-position-canvas")
                            .onSizeChanged { pixels = it }
                            .pointerInput(pixels) {
                                if (pixels.width <= 0 || pixels.height <= 0) return@pointerInput
                                val touchSlop = viewConfiguration.touchSlop
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    down.consume()
                                    val snapshot = currentCharacters
                                    val hitIdx = snapshot.indices.minByOrNull { idx ->
                                        val cx = (snapshot[idx].centerX * pixels.width).toFloat()
                                        val cy = (snapshot[idx].centerY * pixels.height).toFloat()
                                        hypot(down.position.x - cx, down.position.y - cy)
                                    }?.takeIf { idx ->
                                        val cx = (snapshot[idx].centerX * pixels.width).toFloat()
                                        val cy = (snapshot[idx].centerY * pixels.height).toFloat()
                                        hypot(down.position.x - cx, down.position.y - cy) <= grabRadius
                                    }

                                    val activeIndex = hitIdx ?: currentSelected.coerceIn(0, snapshot.lastIndex)
                                    selectedId = snapshot[activeIndex].id
                                    haptics.tick()

                                    var draggedPastSlop = false
                                    if (hitIdx == null) {
                                        // 点击空白处立即将当前角色移过去，继续滑动则无缝转为拖拽。
                                        val nx = (down.position.x / pixels.width).toDouble().coerceIn(0.0, 1.0)
                                        val ny = (down.position.y / pixels.height).toDouble().coerceIn(0.0, 1.0)
                                        currentOnUpdate(activeIndex, currentCharacters[activeIndex].copy(centerX = nx, centerY = ny))
                                    }

                                    try {
                                        do {
                                            val event = awaitPointerEvent()
                                            val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                                            if (!pointer.pressed) break
                                            if (!draggedPastSlop && (pointer.position - down.position).getDistance() > touchSlop) {
                                                draggedPastSlop = true
                                                draggingIndex = activeIndex
                                            }
                                            if (draggedPastSlop || hitIdx == null) {
                                                if (pointer.positionChanged()) {
                                                    val nx = (pointer.position.x / pixels.width).toDouble().coerceIn(0.0, 1.0)
                                                    val ny = (pointer.position.y / pixels.height).toDouble().coerceIn(0.0, 1.0)
                                                    val target = currentCharacters.getOrNull(activeIndex)
                                                    if (target != null) {
                                                        currentOnUpdate(activeIndex, target.copy(centerX = nx, centerY = ny))
                                                    }
                                                    pointer.consume()
                                                }
                                            }
                                        } while (true)
                                    } finally {
                                        draggingIndex = -1
                                    }
                                }
                            },
                    ) {
                        val moveSpring = spring<Float>(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMedium,
                        )
                        characters.forEachIndexed { index, character ->
                            val isSelected = index == selected
                            val isDragging = index == draggingIndex
                            val animX by animateFloatAsState(
                                targetValue = character.centerX.toFloat(),
                                animationSpec = moveSpring,
                                label = "char-x",
                            )
                            val animY by animateFloatAsState(
                                targetValue = character.centerY.toFloat(),
                                animationSpec = moveSpring,
                                label = "char-y",
                            )
                            val displayX = if (isDragging) character.centerX.toFloat() else animX
                            val displayY = if (isDragging) character.centerY.toFloat() else animY
                            Box(
                                Modifier
                                    .offset {
                                        IntOffset(
                                            (displayX * pixels.width - radius).roundToInt(),
                                            (displayY * pixels.height - radius).roundToInt(),
                                        )
                                    }
                                    .size(32.dp)
                                    .background(
                                        if (isSelected) Color.White else Color(0xFF555563),
                                        CircleShape,
                                    )
                                    .then(
                                        if (isSelected) Modifier.border(2.dp, primaryColor, CircleShape)
                                        else Modifier,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "${index + 1}",
                                    color = if (isSelected) Color.Black else Color.White,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成位置设置") } },
    )
}
