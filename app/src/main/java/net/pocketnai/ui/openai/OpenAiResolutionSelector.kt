package net.pocketnai.ui.openai

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import net.pocketnai.domain.openai.OpenAiImageParams
import kotlin.math.roundToInt

/** Size remains the persisted/requested value; adjusting one edge never rewrites the other. */
@Composable
internal fun OpenAiResolutionSelector(size: String, custom: Boolean, enabled: Boolean,
    onCustomChange: (Boolean) -> Unit, onSizeChange: (String) -> Unit) {
    val presets = OpenAiImageParams.SIZE_PRESETS
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OpenAiOption("尺寸", if (custom) "custom" else size, presets + "custom", {
            when (it) {
                "auto" -> "自动"
                "custom" -> "自定义"
                else -> it.replace("x", " × ")
            }
        }, enabled) {
            onCustomChange(it == "custom")
            if (it != "custom") onSizeChange(it)
            else if (size == "auto") onSizeChange("1024x1024")
        }
        if (custom) {
            val parts = size.split('x')
            val width = parts.getOrElse(0) { "" }
            val height = parts.getOrElse(1) { "" }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text("$width × $height", style = MaterialTheme.typography.titleSmall)
                IconButton(onClick = { onSizeChange("${height}x$width") }, enabled = enabled) {
                    Icon(Icons.Outlined.SwapHoriz, contentDescription = "交换宽高")
                }
            }
            OpenAiDimensionSlider("宽度", width, enabled) { onSizeChange("${it}x$height") }
            OpenAiDimensionSlider("高度", height, enabled) { onSizeChange("${width}x$it") }
        }
        val error = OpenAiImageParams.sizeError(size)
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        else if (size != "auto") {
            val (w, h) = size.split('x').map(String::toInt)
            if (w.toLong() * h > 2560L * 1440) Text("实验性高分辨率 · 尺寸越大，用量和等待时间可能越高。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun OpenAiDimensionSlider(label: String, value: String, enabled: Boolean, onChange: (String) -> Unit) {
    val min = OpenAiImageParams.MIN_SLIDER_DIMENSION
    val max = OpenAiImageParams.MAX_DIMENSION
    val step = OpenAiImageParams.DIMENSION_STEP
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            OutlinedTextField(value, { onChange(it.filter(Char::isDigit).take(4)) },
                modifier = Modifier.width(128.dp), singleLine = true, enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                suffix = { Text("px") }, isError = value.toIntOrNull()?.let { it !in min..max || it % step != 0 } ?: true)
        }
        Slider(value = (value.toIntOrNull() ?: 1024).coerceIn(min, max).toFloat(),
            onValueChange = { onChange(((it / step).roundToInt() * step).coerceIn(min, max).toString()) },
            valueRange = min.toFloat()..max.toFloat(), steps = (max - min) / step - 1, enabled = enabled,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "$label（像素）" })
    }
}
