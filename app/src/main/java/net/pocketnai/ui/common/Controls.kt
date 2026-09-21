package net.pocketnai.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import net.pocketnai.R

/**
 * 通用下拉选择器。
 *
 * 用 [OutlinedButton] + [DropdownMenu] 而不是 `ExposedDropdownMenuBox`：
 * 后者在近期版本里 `menuAnchor` 的签名反复变动，这个组合的 API 更稳定，
 * 而且能直接控制“哪些选项可选、哪些要禁用”。
 *
 * [label] 为 null 时渲染成紧凑形态（无上方标题），用于官方式的两列控件行。
 */
@Composable
fun <T> DropdownSelector(
    label: String?,
    selectedText: String,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = selectedText,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
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
}

/** 带数值显示的滑杆。数值显示统一保留 [decimals] 位小数。 */
@Composable
fun LabeledSlider(
    label: String,
    value: Double,
    onValueChange: (Double) -> Unit,
    valueRange: ClosedFloatingPointRange<Double>,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    decimals: Int = 1,
) {
    Column(modifier = modifier) {
        Text(
            text = "$label  ${formatValue(value, decimals)}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toDouble()) },
            valueRange = valueRange.start.toFloat()..valueRange.endInclusive.toFloat(),
            steps = steps,
        )
    }
}

private fun formatValue(value: Double, decimals: Int): String {
    if (decimals <= 0) return value.roundToInt().toString()
    var factor = 1.0
    repeat(decimals) { factor *= 10 }
    val rounded = (value * factor).roundToInt() / factor
    return rounded.toString()
}

/** 小节标题，用于在长表单里区分“基本参数”和“高级参数”。 */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, infoText: String? = null) {
    Row(
        modifier = modifier.padding(top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        if (infoText != null) {
            InfoButton(dialogText = infoText)
        }
    }
}

/**
 * ⓘ 说明按钮：长篇解释文字的统一收容处。
 *
 * 说明散文默认不占表单行 —— 它们在 99% 的使用次数里都是噪音；
 * 需要的人点一下就能在对话框里看到完整说明。
 */
@Composable
fun InfoButton(dialogText: String, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = modifier.size(28.dp)) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = stringResource(R.string.info_button_hint),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = { open = false }) {
                    Text(stringResource(R.string.action_confirm))
                }
            },
            text = { Text(dialogText) },
        )
    }
}

/** 居中的提示文案，用于空列表或未连接状态。 */
@Composable
fun CenteredHint(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
