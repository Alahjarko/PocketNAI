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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
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
import net.pocketnai.domain.model.DirectorReferenceKind
import net.pocketnai.domain.model.ReferenceImage
import net.pocketnai.ui.LocalAppContainer
import net.pocketnai.ui.common.DropdownSelector
import net.pocketnai.ui.common.LabeledSlider
import net.pocketnai.ui.common.SectionHeader

/**
 * Precise Reference 面板（官方界面里的 Precise Reference）。
 *
 * ## 与图生图的区别必须写在界面上
 * 图生图把源图当作**起点**（整张图重新长出来），Precise Reference 把参考图当作**条件**
 * （角色或画风），生成仍从空白开始。两者用不同的请求字段、甚至不同的 `action`，
 * 因此界面上一挂上其中一类就清掉另一类 —— 一个标题下混着两种语义最容易让人误解。
 *
 * ## 缩略图就是提交图
 * 导入时已经按官方要求补齐黑边（1024×1536 / 1536×1024 / 1472×1472），
 * 所以用户看到的缩略图与真正发出去的图是同一张。
 *
 * ## 模型支持
 * 目前只有 V4.5 支持；V5 上这里显示原因并且不给添加按钮 —— 与其让用户提交后
 * 收到服务端拒绝，不如在本地就说明白。
 */
@Composable
fun PreciseReferenceSection(
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
            viewModel.onDirectorReferencePicked(ReferenceSource.PickedUri(uri.toString()))
        }
    }

    SectionHeader(stringResource(R.string.generate_director_title), modifier = modifier)

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
                text = stringResource(R.string.generate_director_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (!state.supportsDirectorReference) {
                // 不支持时只说明原因，不放任何会失败的操作入口。
                Text(
                    text = stringResource(
                        R.string.generate_director_unsupported,
                        state.profile.displayName,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                state.directorReferences.forEach { reference ->
                    DirectorEntry(
                        reference = reference,
                        range = state.profile.directorReferenceRange.let { it.min..it.max },
                        onKindChange = { viewModel.onDirectorKindChanged(reference.id, it) },
                        onStrengthChange = { viewModel.onDirectorStrengthChanged(reference.id, it) },
                        onFidelityChange = { viewModel.onDirectorFidelityChanged(reference.id, it) },
                        onInformationChange = {
                            viewModel.onDirectorInformationExtractedChanged(reference.id, it)
                        },
                        onRemove = { viewModel.onDirectorReferenceRemoved(reference.id) },
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
                        enabled = connected && !state.referenceBusy && state.remainingDirectorSlots > 0,
                    ) {
                        Icon(Icons.Default.AddPhotoAlternate, contentDescription = null)
                        Text(
                            text = stringResource(R.string.generate_reference_pick),
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                    OutlinedButton(
                        onClick = { historyPickerOpen = true },
                        enabled = connected && !state.referenceBusy && state.remainingDirectorSlots > 0,
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
                        state.directorReferences.size,
                        state.profile.maxDirectorReferences,
                    ) + " · " + stringResource(R.string.generate_director_cost_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.referenceBusy) {
                Text(
                    text = stringResource(R.string.generate_reference_importing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (historyPickerOpen) {
        HistoryImagePickerDialog(
            onPick = { relativePath ->
                historyPickerOpen = false
                viewModel.onDirectorReferencePicked(ReferenceSource.LocalPath(relativePath))
            },
            onDismiss = { historyPickerOpen = false },
        )
    }
}

/** 一张 Precise Reference：缩略图、取用方式、三个滑块。 */
@Composable
private fun DirectorEntry(
    reference: ReferenceImage,
    range: ClosedFloatingPointRange<Double>,
    onKindChange: (DirectorReferenceKind) -> Unit,
    onStrengthChange: (Double) -> Unit,
    onFidelityChange: (Double) -> Unit,
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
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(width = 64.dp, height = 64.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surface),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        R.string.generate_director_padded_size,
                        reference.width,
                        reference.height,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // 两种取用方式直接对应请求里的 caption，不是纯粹的界面偏好。
                    DirectorKind.entries.forEach { kind ->
                        AssistChip(
                            onClick = { onKindChange(kind.value) },
                            label = { Text(stringResource(kind.labelRes)) },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = if (reference.directorKind == kind.value) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surface
                                },
                            ),
                        )
                    }
                }
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
            label = stringResource(R.string.generate_reference_fidelity),
            value = reference.secondaryStrength ?: range.start,
            onValueChange = onFidelityChange,
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

/** 取用方式：界面枚举与请求取值、文案的对应关系集中在这里。 */
private enum class DirectorKind(val value: DirectorReferenceKind, val labelRes: Int) {
    CHARACTER(DirectorReferenceKind.CHARACTER, R.string.generate_reference_kind_character_short),
    CHARACTER_STYLE(
        DirectorReferenceKind.CHARACTER_AND_STYLE,
        R.string.generate_reference_kind_character_style_short,
    ),
}
