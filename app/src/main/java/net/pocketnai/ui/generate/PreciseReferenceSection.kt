package net.pocketnai.ui.generate

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import net.pocketnai.R
import net.pocketnai.domain.image.ImageGeometry
import net.pocketnai.domain.image.PixelSize
import net.pocketnai.domain.image.ReferenceSource
import net.pocketnai.domain.model.DirectorReferenceKind
import net.pocketnai.domain.model.GenerationMode
import net.pocketnai.domain.model.ModelProfile
import net.pocketnai.domain.model.ReferenceImage
import net.pocketnai.ui.LocalAppContainer
import net.pocketnai.ui.common.LabeledSlider
import net.pocketnai.ui.common.MotionIconButton as IconButton
import net.pocketnai.ui.motion.rememberPocketHaptics

/**
 * Precise Reference 面板（官方界面里的 Precise Reference）。
 * 作为「参考图」卡片的一个页签，由 [ReferenceTabsSection] 承载，不再自带标题与卡片。
 *
 * - V4.5：支持角色、画风、角色+画风三种模式，包含 Strength / Fidelity / Information Extracted 三个滑块，提交时补黑边对齐画布。
 * - V5：新增独立「服装」参考与「保持同款服装」子开关，Strength 扩展至 0–5，提交时按 97 个分桶居中裁切（局部重绘时自动停用并保留草稿）。
 */
@Composable
fun PreciseReferencePanel(
    state: GenerateViewModel.UiState,
    connected: Boolean,
    viewModel: GenerateViewModel,
    modifier: Modifier = Modifier,
) {
    var historyPickerOpen by remember { mutableStateOf(false) }

    val pickImage = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            viewModel.onDirectorReferencePicked(ReferenceSource.PickedUri(uri.toString()))
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (!state.supportsDirectorReference) {
            InactiveReferences(state.directorReferences, viewModel::onDirectorReferenceRemoved)
        } else {
            val pausedForInpaint = state.mode == GenerationMode.INPAINT &&
                !state.profile.supportsDirectorInpaint &&
                state.directorReferences.isNotEmpty()
            if (pausedForInpaint) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.generate_director_v5_inpaint_paused),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
            }

            AnimatedReferenceList(state.directorReferences) { reference ->
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    DirectorEntry(
                        reference = reference,
                        profile = state.profile,
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
            }

            ReferenceSourcePickerRow(
                enabled = connected && !state.referenceBusy && state.remainingDirectorSlots > 0,
                compact = state.directorReferences.isNotEmpty(),
                onPickAlbum = {
                    pickImage.launch(
                        PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageOnly,
                        ),
                    )
                },
                onPickHistory = { historyPickerOpen = true },
            )

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

/** 一张 Precise Reference：缩略图、四类主用途选择、V5 同款服装子开关与参数滑块。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DirectorEntry(
    reference: ReferenceImage,
    profile: ModelProfile,
    onKindChange: (DirectorReferenceKind) -> Unit,
    onStrengthChange: (Double) -> Unit,
    onFidelityChange: (Double) -> Unit,
    onInformationChange: (Double) -> Unit,
    onRemove: () -> Unit,
) {
    val container = LocalAppContainer.current
    val haptics = rememberPocketHaptics()
    val rawKind = reference.directorKind ?: DirectorReferenceKind.DEFAULT
    val effectiveKind = rawKind.normalizeFor(profile)
    val primaryCategory = DirectorPrimaryCategory.from(rawKind)
    val strengthRange = profile.directorStrengthRange.let { it.min..it.max }
    val fidelityRange = profile.directorReferenceRange.let { it.min..it.max }

    val sourceSize = PixelSize(reference.width.coerceAtLeast(1), reference.height.coerceAtLeast(1))
    val targetSize = ImageGeometry.directorTargetSize(sourceSize, profile.usesBucketDirectorReference)
    val sizeDescription = if (profile.usesBucketDirectorReference) {
        if (sourceSize == targetSize) {
            stringResource(R.string.generate_director_bucket_size_same, targetSize.width, targetSize.height)
        } else {
            stringResource(
                R.string.generate_director_bucket_size,
                reference.width,
                reference.height,
                targetSize.width,
                targetSize.height,
            )
        }
    } else {
        if (sourceSize == targetSize) {
            stringResource(R.string.generate_director_padded_size, targetSize.width, targetSize.height)
        } else {
            stringResource(
                R.string.generate_director_canvas_size,
                reference.width,
                reference.height,
                targetSize.width,
                targetSize.height,
            )
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = summaryLabelFor(rawKind, effectiveKind, profile),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (effectiveKind == null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Text(
                    text = sizeDescription,
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

        ReferenceOriginLink(reference)

        val visibleCategories = remember(profile.supportsOutfitReference, primaryCategory) {
            DirectorPrimaryCategory.entries.filter { category ->
                category != DirectorPrimaryCategory.OUTFIT ||
                    profile.supportsOutfitReference ||
                    primaryCategory == DirectorPrimaryCategory.OUTFIT
            }
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            visibleCategories.forEach { category ->
                val isSelected = primaryCategory == category
                val unsupportedSelection = isSelected && effectiveKind == null
                AssistChip(
                    onClick = {
                        if (primaryCategory != category) {
                            haptics.tick()
                            onKindChange(category.defaultKind)
                        }
                    },
                    label = { Text(stringResource(category.labelRes)) },
                    shape = RoundedCornerShape(12.dp),
                    border = null,
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = when {
                            unsupportedSelection -> MaterialTheme.colorScheme.errorContainer
                            isSelected -> MaterialTheme.colorScheme.secondaryContainer
                            else -> MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        labelColor = when {
                            unsupportedSelection -> MaterialTheme.colorScheme.onErrorContainer
                            isSelected -> MaterialTheme.colorScheme.onSecondaryContainer
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    ),
                )
            }
        }

        val showPreferOutfitToggle = profile.supportsOutfitReference && rawKind.supportsPreferSameOutfit
        AnimatedVisibility(
            visible = showPreferOutfitToggle,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            val preferSame = rawKind.prefersSameOutfit
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (preferSame) {
                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .toggleable(
                        value = preferSame,
                        role = Role.Switch,
                        onValueChange = { next ->
                            haptics.tick()
                            onKindChange(rawKind.withPreferSameOutfit(next))
                        },
                    ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.generate_reference_prefer_same_outfit),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(
                                if (preferSame) {
                                    R.string.generate_reference_prefer_same_outfit_on_desc
                                } else {
                                    R.string.generate_reference_prefer_same_outfit_off_desc
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = preferSame,
                        onCheckedChange = null,
                    )
                }
            }
        }

        if (rawKind == DirectorReferenceKind.OUTFIT) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (profile.supportsOutfitReference) {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                } else {
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(
                        if (profile.supportsOutfitReference) {
                            R.string.generate_reference_outfit_active_hint
                        } else {
                            R.string.generate_reference_outfit_v45_inactive_hint
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (profile.supportsOutfitReference) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onErrorContainer
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }

        LabeledSlider(
            label = stringResource(R.string.generate_reference_strength),
            value = (reference.strength ?: profile.defaultDirectorStrength)
                .coerceIn(profile.directorStrengthRange.min, profile.directorStrengthRange.max),
            onValueChange = onStrengthChange,
            valueRange = strengthRange,
            decimals = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        if (profile.supportsDirectorFidelity) {
            LabeledSlider(
                label = stringResource(R.string.generate_reference_fidelity),
                value = reference.secondaryStrength ?: profile.defaultDirectorFidelity,
                onValueChange = onFidelityChange,
                valueRange = fidelityRange,
                decimals = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            LabeledSlider(
                label = stringResource(R.string.generate_reference_information),
                value = reference.informationExtracted ?: profile.defaultDirectorInfoExtracted,
                onValueChange = onInformationChange,
                valueRange = fidelityRange,
                decimals = 2,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun summaryLabelFor(
    rawKind: DirectorReferenceKind,
    effectiveKind: DirectorReferenceKind?,
    profile: ModelProfile,
): String {
    if (effectiveKind == null) {
        return stringResource(R.string.generate_reference_summary_outfit_skipped)
    }
    return when (effectiveKind) {
        DirectorReferenceKind.CHARACTER_AND_STYLE ->
            if (profile.supportsOutfitReference) {
                stringResource(R.string.generate_reference_summary_char_style_same_outfit)
            } else {
                stringResource(R.string.generate_reference_kind_character_style_short)
            }
        DirectorReferenceKind.CHARACTER_AND_STYLE_ANY_OUTFIT ->
            stringResource(R.string.generate_reference_summary_char_style_any_outfit)
        DirectorReferenceKind.CHARACTER ->
            if (profile.supportsOutfitReference) {
                stringResource(R.string.generate_reference_summary_char_any_outfit)
            } else {
                stringResource(R.string.generate_reference_kind_character_short)
            }
        DirectorReferenceKind.CHARACTER_MAIN_OUTFIT ->
            stringResource(R.string.generate_reference_summary_char_same_outfit)
        DirectorReferenceKind.OUTFIT ->
            stringResource(R.string.generate_reference_summary_outfit)
        DirectorReferenceKind.STYLE ->
            stringResource(R.string.generate_reference_summary_style)
    }
}

/** 四类主用途按钮，把底层 6 个 `base_caption` 收拢为清晰的「主用途 + 同款服装开关」两层交互。 */
private enum class DirectorPrimaryCategory(
    val defaultKind: DirectorReferenceKind,
    val labelRes: Int,
) {
    CHARACTER_STYLE(
        defaultKind = DirectorReferenceKind.CHARACTER_AND_STYLE,
        labelRes = R.string.generate_reference_kind_character_style_short,
    ),
    CHARACTER(
        defaultKind = DirectorReferenceKind.CHARACTER,
        labelRes = R.string.generate_reference_kind_character_short,
    ),
    OUTFIT(
        defaultKind = DirectorReferenceKind.OUTFIT,
        labelRes = R.string.generate_reference_kind_outfit_short,
    ),
    STYLE(
        defaultKind = DirectorReferenceKind.STYLE,
        labelRes = R.string.generate_reference_kind_style_short,
    ),
    ;

    companion object {
        fun from(kind: DirectorReferenceKind): DirectorPrimaryCategory = when (kind) {
            DirectorReferenceKind.CHARACTER_AND_STYLE,
            DirectorReferenceKind.CHARACTER_AND_STYLE_ANY_OUTFIT,
            -> CHARACTER_STYLE
            DirectorReferenceKind.CHARACTER,
            DirectorReferenceKind.CHARACTER_MAIN_OUTFIT,
            -> CHARACTER
            DirectorReferenceKind.OUTFIT -> OUTFIT
            DirectorReferenceKind.STYLE -> STYLE
        }
    }
}
