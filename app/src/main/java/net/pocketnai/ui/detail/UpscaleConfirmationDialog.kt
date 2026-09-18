package net.pocketnai.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.pocketnai.R
import net.pocketnai.domain.billing.UpscaleCost

/**
 * 超分确认框。
 *
 * 官方超分是**固定 4 倍**、按源图面积收 1-4 Anlas（`UpscaleCost`，技术决策记录 §30.1），
 * 所以这里不给倍数选项，而是把确切价格写在确认前 —— 费用已知的调用才谈得上"用户确认"。
 * 源图超出官方上限（1536×2048）时直接禁用确认，而不是发一个注定失败的请求。
 */
@Composable
fun UpscaleConfirmationDialog(
    sourceWidth: Int,
    sourceHeight: Int,
    upscaling: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val estimatedAnlas = UpscaleCost.anlas(sourceWidth, sourceHeight)
    val supported = estimatedAnlas != null

    AlertDialog(
        onDismissRequest = { if (!upscaling) onDismiss() },
        title = { Text(stringResource(R.string.upscale_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.upscale_source_size, sourceWidth, sourceHeight),
                    style = MaterialTheme.typography.bodyMedium,
                )

                if (supported) {
                    Text(
                        text = stringResource(
                            R.string.upscale_target_size,
                            sourceWidth * UpscaleCost.SCALE_FACTOR,
                            sourceHeight * UpscaleCost.SCALE_FACTOR,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(R.string.upscale_cost_estimate, estimatedAnlas!!),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.upscale_too_large),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Text(
                    text = stringResource(R.string.upscale_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (upscaling) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Text(
                            text = stringResource(R.string.upscale_in_progress),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !upscaling && supported,
            ) {
                Text(stringResource(R.string.upscale_confirm))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !upscaling,
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
