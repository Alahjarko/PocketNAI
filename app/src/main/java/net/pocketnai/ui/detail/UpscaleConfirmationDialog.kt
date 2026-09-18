package net.pocketnai.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.pocketnai.R

@Composable
fun UpscaleConfirmationDialog(
    sourceWidth: Int,
    sourceHeight: Int,
    upscaling: Boolean,
    onConfirm: (scale: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedScale by remember { mutableIntStateOf(2) }

    val targetWidth = sourceWidth * selectedScale
    val targetHeight = sourceHeight * selectedScale

    AlertDialog(
        onDismissRequest = { if (!upscaling) onDismiss() },
        title = { Text(stringResource(R.string.upscale_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.upscale_source_size, sourceWidth, sourceHeight),
                    style = MaterialTheme.typography.bodyMedium,
                )

                Text(
                    text = stringResource(R.string.upscale_target_size, targetWidth, targetHeight),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(vertical = 4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.upscale_scale_factor),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    FilterChip(
                        selected = selectedScale == 2,
                        onClick = { if (!upscaling) selectedScale = 2 },
                        label = { Text("2x") },
                    )
                    FilterChip(
                        selected = selectedScale == 4,
                        onClick = { if (!upscaling) selectedScale = 4 },
                        label = { Text("4x") },
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
                onClick = { onConfirm(selectedScale) },
                enabled = !upscaling,
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
