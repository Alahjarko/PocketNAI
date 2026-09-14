package net.pocketnai.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CropLandscape
import androidx.compose.material.icons.outlined.CropPortrait
import androidx.compose.material.icons.outlined.CropSquare
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.pocketnai.R
import net.pocketnai.domain.model.ImageOrientation
import net.pocketnai.domain.model.ImageSizePreset
import net.pocketnai.domain.model.ResolutionTier

/**
 * Resolution 选择器，复刻官方网页版的信息结构：
 *
 * ```
 * Resolution                    1216 × 832
 * [ Normal ▾ ]   [ ▭ ]  [ ▯ ]  [ □ ]
 * ```
 *
 * 官方把尺寸拆成“档位 + 方向”两维，而不是给一个几十项的像素下拉：
 * 档位决定画面大小，方向用三个图标切换，右上角始终显示实际像素值，
 * 用户不必自己心算 832×1216 到底是不是竖图。
 *
 * 只有当前模型确实提供该组合时才可点，避免构造服务端会拒绝的尺寸。
 */
@Composable
fun ResolutionSelector(
    tier: ResolutionTier,
    size: ImageSizePreset,
    availableTiers: List<ResolutionTier>,
    availableOrientations: List<ImageOrientation>,
    onTierChange: (ResolutionTier) -> Unit,
    onOrientationChange: (ImageOrientation) -> Unit,
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
                text = size.label,
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
                modifier = Modifier.width(140.dp),
            )

            // 按钮顺序固定为官方网页版的 横 / 竖 / 方，
            // 不受枚举声明顺序影响。
            ORIENTATION_DISPLAY_ORDER.forEach { orientation ->
                val enabled = orientation in availableOrientations
                FilledIconToggleButton(
                    checked = orientation == size.orientation,
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
        }
    }
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
