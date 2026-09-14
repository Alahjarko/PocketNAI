package net.pocketnai.ui.generate

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import net.pocketnai.R
import net.pocketnai.domain.image.ReferenceSource
import net.pocketnai.domain.model.ReferenceImage
import net.pocketnai.ui.LocalAppContainer
import net.pocketnai.ui.common.LabeledSlider
import net.pocketnai.ui.common.SectionHeader

/**
 * Vibe Transfer 面板。
 *
 * ## 与图生图 / Precise Reference 的关系：叠加而不是互斥
 * Vibe 是"风格条件"，加在别的模式之上使用 —— 官方界面里也是独立面板。
 * 因此这里挂图**不会**清空图生图或 Precise Reference（那两者彼此互斥，那是因为它们连
 * 请求的 `action` 都不同）。
 *
 * ## 编码发生在提交时
 * 官方先用 `encode-vibe` 把图编码成 `.vibe` 再放进 `reference_image_multiple`。
 * 导入这里只负责归一化与落盘；编码在提交生成时做，产物按"模型 + 图片 + 信息量"缓存，
 * 同一张图第二次使用不再编码。所以条目上显示的是"提交时编码"，而不是在这里转圈。
 *
 * ## 费用
 * Vibe 的价格尚未确认，因此只要挂了它，按钮上就是"费用待确认"。
 */
@Composable
fun VibeTransferSection(
    state: GenerateViewModel.UiState,
    connected: Boolean,
    viewModel: GenerateViewModel,
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    var historyPickerOpen by remember { mutableStateOf(false) }

    val pickImage = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            viewModel.onVibeReferencePicked(ReferenceSource.PickedUri(uri.toString()))
        }
    }

    SectionHeader(stringResource(R.string.generate_vibe_title), modifier = modifier)

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.generate_vibe_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (!state.supportsVibeTransfer) {
                Text(
                    text = stringResource(
                        R.string.generate_director_unsupported,
                        state.profile.displayName,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                state.vibeReferences.forEach { reference ->
                    VibeEntry(
                        reference = reference,
                        range = state.profile.img2imgStrengthRange.let { it.min..it.max },
                        onStrengthChange = { viewModel.onVibeStrengthChanged(reference.id, it) },
                        onInformationChange = {
                            viewModel.onVibeInformationExtractedChanged(reference.id, it)
                        },
                        onRemove = { viewModel.onVibeReferenceRemoved(reference.id) },
                    )
                    HorizontalDivider()
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            pickImage.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly,
                                ),
                            )
                        },
                        enabled = connected && !state.referenceBusy && state.remainingVibeSlots > 0,
                    ) {
                        Icon(Icons.Default.AddPhotoAlternate, contentDescription = null)
                        Text(
                            text = stringResource(R.string.generate_reference_pick),
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                    OutlinedButton(
                        onClick = { historyPickerOpen = true },
                        enabled = connected && !state.referenceBusy && state.remainingVibeSlots > 0,
                    ) {
                        Icon(Icons.Default.History, contentDescription = null)
                        Text(
                            text = stringResource(R.string.generate_reference_from_history),
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }

                Text(
                    text = stringResource(
                        R.string.generate_director_slots,
                        state.vibeReferences.size,
                        state.profile.maxVibeReferences,
                    ) + " · " + stringResource(R.string.generate_vibe_cost_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // 官方经验值：多张 vibe 的强度合计建议不超过 1.0。网页端有自动归一化开关，
                // 这里给一个同样的按钮（只在合计超限时才可点）。
                val totalStrength = state.vibeReferences.sumOf { it.strength ?: 0.0 }
                Text(
                    text = stringResource(R.string.generate_vibe_strength_total, totalStrength),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (totalStrength > 1.0) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                if (state.vibeReferences.size > 1) {
                    OutlinedButton(
                        onClick = viewModel::normalizeVibeStrengths,
                        enabled = totalStrength > 1.0,
                    ) {
                        Text(stringResource(R.string.generate_vibe_normalize))
                    }
                }
            }
        }
    }

    if (historyPickerOpen) {
        HistoryImagePickerDialog(
            onPick = { relativePath ->
                historyPickerOpen = false
                viewModel.onVibeReferencePicked(ReferenceSource.LocalPath(relativePath))
            },
            onDismiss = { historyPickerOpen = false },
        )
    }
}

/** 一张 Vibe 参考图：缩略图 + 两个滑块。 */
@Composable
private fun VibeEntry(
    reference: ReferenceImage,
    range: ClosedFloatingPointRange<Double>,
    onStrengthChange: (Double) -> Unit,
    onInformationChange: (Double) -> Unit,
    onRemove: () -> Unit,
) {
    val container = LocalAppContainer.current

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            AsyncImage(
                model = container.fileStore.resolve(reference.relativePath),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(width = 64.dp, height = 64.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surface),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        R.string.generate_reference_source_size,
                        reference.width,
                        reference.height,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.generate_vibe_encode_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.generate_reference_remove),
                )
            }
        }

        LabeledSlider(
            label = stringResource(R.string.generate_reference_strength),
            value = reference.strength ?: range.start,
            onValueChange = onStrengthChange,
            valueRange = range,
            decimals = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        LabeledSlider(
            label = stringResource(R.string.generate_reference_information),
            value = reference.informationExtracted ?: range.start,
            onValueChange = onInformationChange,
            valueRange = range,
            decimals = 2,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
