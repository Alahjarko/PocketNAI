package net.pocketnai.ui.generate

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import net.pocketnai.domain.model.CharacterPrompt
import net.pocketnai.domain.model.ImageSizePreset
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CharacterPositionDialog(
    characters: List<CharacterPrompt>,
    canvasSize: ImageSizePreset,
    onUpdate: (Int, CharacterPrompt) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedId by remember { mutableStateOf(characters.first().id) }
    val selected = characters.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("角色位置") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    characters.forEachIndexed { index, character ->
                        FilterChip(selected = index == selected, onClick = { selectedId = character.id },
                            label = { Text("角色 ${index + 1}") })
                    }
                }
                Text("选中角色后点击生成画布放置 · ${canvasSize.label}", style = MaterialTheme.typography.bodySmall)
                BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val ratio = canvasSize.width.toFloat() / canvasSize.height
                    val width = minOf(maxWidth, 360.dp * ratio)
                    var pixels by remember { mutableStateOf(IntSize.Zero) }
                    val radius = with(LocalDensity.current) { 16.dp.toPx() }
                    Box(
                        Modifier.width(width).aspectRatio(ratio).clip(MaterialTheme.shapes.small)
                            .background(Color(0xFF101014)).testTag("character-position-canvas")
                            .onSizeChanged { pixels = it }
                            .pointerInput(selected, characters, pixels) {
                                detectTapGestures { point ->
                                    if (pixels.width > 0 && pixels.height > 0) {
                                        onUpdate(selected, characters[selected].copy(
                                            centerX = (point.x / pixels.width).toDouble().coerceIn(0.0, 1.0),
                                            centerY = (point.y / pixels.height).toDouble().coerceIn(0.0, 1.0),
                                        ))
                                    }
                                }
                            },
                    ) {
                        characters.forEachIndexed { index, character ->
                            Box(
                                Modifier.offset {
                                    IntOffset(
                                        (character.centerX * pixels.width - radius).roundToInt(),
                                        (character.centerY * pixels.height - radius).roundToInt(),
                                    )
                                }.size(32.dp).background(
                                    if (index == selected) Color.White else Color(0xFF555563), CircleShape,
                                ).clickable { selectedId = character.id },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("${index + 1}", color = if (index == selected) Color.Black else Color.White)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成位置设置") } },
    )
}
