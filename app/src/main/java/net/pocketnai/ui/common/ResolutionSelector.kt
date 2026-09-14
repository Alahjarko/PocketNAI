package net.pocketnai.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CropLandscape
import androidx.compose.material.icons.outlined.CropPortrait
import androidx.compose.material.icons.outlined.CropSquare
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import net.pocketnai.R
import net.pocketnai.domain.image.ResolutionPlanner
import net.pocketnai.domain.model.CustomResolution
import net.pocketnai.domain.model.ImageOrientation
import net.pocketnai.domain.model.ResolutionTier
import net.pocketnai.domain.model.SizeConstraints

/**
 * Resolution 选择器：复刻官方网页版的信息结构，并加上自定义尺寸。
 *
 * ```
 * Resolution                    1920 × 1080
 * [ Normal ▾ ]  [ ▭ ] [ ▯ ] [ □ ]   [自定义]
 * ```
 *
 * 官方把尺寸拆成“档位 + 方向”两维，而不是给一个几十项的像素下拉：
 * 档位决定画面大小，方向用三个图标切换，右上角始终显示实际像素值，
 * 用户不必自己心算 832×1216 到底是不是竖图。
 *
 * 自定义是**第四个入口**而不是第三个档位：档位是"固定组合 × 三个方向"，
 * 自定义是任意宽高，两者的状态结构不同，硬塞进枚举只会制造一个假的档位。
 */
@Composable
fun ResolutionSelector(
    tier: ResolutionTier,
    size: net.pocketnai.domain.model.ImageSizePreset,
    availableTiers: List<ResolutionTier>,
    availableOrientations: List<ImageOrientation>,
    onTierChange: (ResolutionTier) -> Unit,
    onOrientationChange: (ImageOrientation) -> Unit,
    custom: CustomResolution?,
    plan: ResolutionPlanner.Plan?,
    customError: ResolutionPlanner.Reason?,
    constraints: SizeConstraints,
    onCustomEnabled: () -> Unit,
    onCustomDisabled: () -> Unit,
    onCustomWidthChange: (Int) -> Unit,
    onCustomHeightChange: (Int) -> Unit,
    onCustomSwap: () -> Unit,
    onExactOutputChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.generate_resolution),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = plan?.let { "${it.targetSize.width} × ${it.targetSize.height}" } ?: size.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            modifier = Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DropdownSelector(
                label = null,
                selectedText = tier.displayName,
                options = availableTiers,
                optionLabel = { it.displayName },
                onSelect = onTierChange,
                enabled = custom == null,
                modifier = Modifier.width(140.dp),
            )

            // 按钮顺序固定为官方网页版的 横 / 竖 / 方，不受枚举声明顺序影响。
            ORIENTATION_DISPLAY_ORDER.forEach { orientation ->
                val enabled = custom == null && orientation in availableOrientations
                FilledIconToggleButton(
                    checked = custom == null && orientation == size.orientation,
                    onCheckedChange = { if (enabled) onOrientationChange(orientation) },
                    enabled = enabled,
                    colors = IconButtonDefaults.filledIconToggleButtonColors(),
                ) {
                    Icon(
                        imageVector = orientation.icon,
                        contentDescription = orientation.label(),
                    )
                }
            }

            FilterChip(
                selected = custom != null,
                onClick = { if (custom == null) onCustomEnabled() else onCustomDisabled() },
                label = { Text(stringResource(R.string.resolution_custom)) },
            )
        }

        if (custom != null) {
            CustomResolutionEditor(
                custom = custom,
                plan = plan,
                error = customError,
                constraints = constraints,
                onWidthChange = onCustomWidthChange,
                onHeightChange = onCustomHeightChange,
                onSwap = onCustomSwap,
                onExactOutputChange = onExactOutputChange,
            )
        }
    }
}

/**
 * 自定义尺寸编辑器。
 *
 * 最重要的一条纪律是**不静默调整**：输入 `1920×1080` 时，界面必须同时写出
 * "最终输出 1920×1080 / NovelAI 生成 1920×1088 / 上下各裁 4 px / 计费按 1920×1088"。
 * 否则用户看到输入框是 1080、花的却是 1088 的钱、拿到的又是另一种尺寸，
 * 只会以为应用算错了。
 */
@Composable
private fun CustomResolutionEditor(
    custom: CustomResolution,
    plan: ResolutionPlanner.Plan?,
    error: ResolutionPlanner.Reason?,
    constraints: SizeConstraints,
    onWidthChange: (Int) -> Unit,
    onHeightChange: (Int) -> Unit,
    onSwap: () -> Unit,
    onExactOutputChange: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier.padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DimensionField(
                label = stringResource(R.string.resolution_width),
                value = custom.width,
                onValueChange = onWidthChange,
                modifier = Modifier.weight(1f),
            )
            DimensionField(
                label = stringResource(R.string.resolution_height),
                value = custom.height,
                onValueChange = onHeightChange,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onSwap) {
                Icon(
                    imageVector = Icons.Outlined.SwapHoriz,
                    contentDescription = stringResource(R.string.resolution_swap),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.resolution_exact),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.resolution_exact_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = custom.exactOutput, onCheckedChange = onExactOutputChange)
        }

        if (error != null) {
            Text(
                text = error.text(constraints),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        } else if (plan != null) {
            ResolutionSummary(plan = plan, side = constraints.dimensionStep)
        }
    }
}

/** 尺寸对照说明：最终 / 生成 / 裁切 / 计费依据。 */
@Composable
private fun ResolutionSummary(plan: ResolutionPlanner.Plan, side: Int) {
    val target = plan.targetSize
    val generation = plan.generationSize

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = stringResource(R.string.resolution_final_output, target.width, target.height),
            style = MaterialTheme.typography.bodySmall,
        )
        if (plan.needsCrop) {
            Text(
                text = stringResource(
                    R.string.resolution_generation_cropped,
                    generation.width,
                    generation.height,
                ) + "（" + plan.cropDescription() + "）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = stringResource(
                    R.string.resolution_generation_native,
                    generation.width,
                    generation.height,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (target.width % side != 0 || target.height % side != 0) {
                // 仿官方模式：不裁切，最终文件就是那个被对齐过的画布。必须说清楚。
                Text(
                    text = stringResource(R.string.resolution_native_rounding, side),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = stringResource(
                R.string.resolution_billing_basis,
                generation.width,
                generation.height,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 裁掉多少：只在真的裁了某一轴时才提那一轴。
 *
 * "上下各裁 0 px、左右各裁 4 px" 这种句子会让人以为哪里算错了 ——
 * 尺寸这件事上多余的精确等于噪音。
 */
@Composable
private fun ResolutionPlanner.Plan.cropDescription(): String {
    val vertical = croppedHeight / 2
    val horizontal = croppedWidth / 2
    return when {
        horizontal == 0 -> stringResource(R.string.resolution_crop_vertical, vertical)
        vertical == 0 -> stringResource(R.string.resolution_crop_horizontal, horizontal)
        else -> stringResource(R.string.resolution_crop_both, vertical, horizontal)
    }
}

@Composable
private fun ResolutionPlanner.Reason.text(constraints: SizeConstraints): String = when (this) {
    ResolutionPlanner.Reason.SIDE_TOO_SMALL ->
        stringResource(R.string.resolution_error_too_small, ResolutionPlanner.MIN_DIMENSION)

    ResolutionPlanner.Reason.SIDE_TOO_LARGE ->
        stringResource(R.string.resolution_error_too_large, constraints.maxDimension)

    ResolutionPlanner.Reason.AREA_TOO_LARGE ->
        stringResource(
            R.string.resolution_error_area,
            SizeConstraints.OFFICIAL_MAX_TOTAL_PIXELS,
        )
}

/**
 * 宽 / 高输入框。
 *
 * 本地文本状态是显示的**唯一来源**：用户删空或只输入两位数时不会被强行改回旧值
 * （那会让"改到一半"变得不可能），只有解析出的合法数字才推给上层；
 * 上层拒绝（超限）时值不变，错误提示出现在下方。
 */
@Composable
private fun DimensionField(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf(value.toString()) }
    // 外部变化（切预设、导入元数据、交换宽高）时同步回来，正在输入时不打断。
    LaunchedEffect(value) {
        if (text.toIntOrNull() != value) text = value.toString()
    }

    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val digits = raw.filter { it.isDigit() }.take(5)
            text = digits
            digits.toIntOrNull()?.let(onValueChange)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

private val ORIENTATION_DISPLAY_ORDER = listOf(
    ImageOrientation.LANDSCAPE,
    ImageOrientation.PORTRAIT,
    ImageOrientation.SQUARE,
)

private val ImageOrientation.icon: ImageVector
    get() = when (this) {
        ImageOrientation.LANDSCAPE -> Icons.Outlined.CropLandscape
        ImageOrientation.PORTRAIT -> Icons.Outlined.CropPortrait
        ImageOrientation.SQUARE -> Icons.Outlined.CropSquare
    }

@Composable
private fun ImageOrientation.label(): String = stringResource(
    when (this) {
        ImageOrientation.LANDSCAPE -> R.string.orientation_landscape
        ImageOrientation.PORTRAIT -> R.string.orientation_portrait
        ImageOrientation.SQUARE -> R.string.orientation_square
    },
)
