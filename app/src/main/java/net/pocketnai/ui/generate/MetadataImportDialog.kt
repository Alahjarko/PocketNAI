package net.pocketnai.ui.generate

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.pocketnai.R
import net.pocketnai.domain.metadata.MetadataImportNote
import net.pocketnai.domain.metadata.MetadataImportPlan
import net.pocketnai.domain.metadata.MetadataImportSelection
import net.pocketnai.domain.metadata.NovelAiImageMetadata

/**
 * "这张图带 NovelAI 元数据，要不要导入参数"对话框。
 *
 * 与官方网页版的对照（`image2image` 上传后那个面板）：
 * - 勾选项：Prompt / Undesired Content / Settings / Seed / Clean Imports（官方还有
 *   Characters 与 Append，我们**不显示** —— 多角色提示词还没实现，摆一个勾不掉的东西
 *   比没有更糟）；
 * - Seed 与 Clean Imports 默认不勾（官方也是）；
 * - "实际提示词"只在元数据里真的有 Randomizer 展开值时才出现。
 *
 * ## 这个对话框不做的事
 * - 不自动生成：导入只是把参数填进编辑区；
 * - 不隐藏代价：会覆盖当前提示词、Clean Imports 会改变权重、哪些项被跳过，
 *   全部写在预览里。
 */
@Composable
fun MetadataImportDialog(
    metadata: NovelAiImageMetadata,
    selection: MetadataImportSelection,
    plan: MetadataImportPlan?,
    currentPromptIsNotEmpty: Boolean,
    onSelectionChange: (MetadataImportSelection) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.metadata_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stringResource(R.string.metadata_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = buildString {
                        metadata.source?.let { append(stringResource(R.string.metadata_source, it)) }
                        metadata.generationTimeSeconds?.let {
                            if (isNotEmpty()) append(" · ")
                            append(stringResource(R.string.metadata_generation_time, it))
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                ImportToggle(
                    checked = selection.prompt,
                    label = stringResource(R.string.metadata_prompt),
                    onChange = { onSelectionChange(selection.copy(prompt = it)) },
                )
                ImportToggle(
                    checked = selection.negativePrompt,
                    label = stringResource(R.string.metadata_uc),
                    onChange = { onSelectionChange(selection.copy(negativePrompt = it)) },
                )
                ImportToggle(
                    checked = selection.settings,
                    label = stringResource(R.string.metadata_settings),
                    onChange = { onSelectionChange(selection.copy(settings = it)) },
                )
                ImportToggle(
                    checked = selection.seed,
                    label = stringResource(R.string.metadata_seed),
                    onChange = { onSelectionChange(selection.copy(seed = it)) },
                )
                ImportToggle(
                    checked = selection.cleanImports,
                    label = stringResource(R.string.metadata_clean_imports),
                    onChange = { onSelectionChange(selection.copy(cleanImports = it)) },
                )
                Text(
                    text = stringResource(R.string.metadata_clean_imports_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 40.dp),
                )

                // 官方也是"只在真的有 Randomizer 展开值时"才给这个选项。
                if (!metadata.actualPrompt.isNullOrBlank()) {
                    ImportToggle(
                        checked = selection.useActualPrompt,
                        label = stringResource(R.string.metadata_actual_prompt),
                        onChange = { onSelectionChange(selection.copy(useActualPrompt = it)) },
                    )
                    Text(
                        text = stringResource(R.string.metadata_actual_prompt_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 40.dp),
                    )
                }

                if (currentPromptIsNotEmpty && selection.prompt) {
                    Text(
                        text = stringResource(R.string.metadata_overwrites_prompt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                plan?.let { MetadataPlanSummary(it) }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = plan?.changesAnything == true) {
                Text(stringResource(R.string.metadata_import))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.metadata_skip))
            }
        },
    )
}

/** 导入预览：会改什么（勾了才显示），以及跳过了什么。 */
@Composable
private fun MetadataPlanSummary(plan: MetadataImportPlan) {
    val changes = buildList {
        plan.model?.let { add(stringResource(R.string.metadata_change_model, it.displayName)) }
        plan.size?.let { add(stringResource(R.string.metadata_change_size, it.label)) }
        plan.steps?.let { add(stringResource(R.string.metadata_change_steps, it)) }
        plan.guidance?.let { add(stringResource(R.string.metadata_change_guidance, it)) }
        plan.sampler?.let { add(stringResource(R.string.metadata_change_sampler, it.displayName)) }
        plan.seed?.let { add(stringResource(R.string.metadata_change_seed, it.toString())) }
    }
    if (changes.isNotEmpty()) {
        Text(
            text = stringResource(R.string.metadata_will_change, changes.joinToString("、")),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    plan.notes.forEach { note ->
        Text(
            text = note.text(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 逐项跳过/无法恢复的说明。文案集中在这里，与 `ParamViolation` 的处理方式一致。 */
@Composable
private fun MetadataImportNote.text(): String = when (this) {
    is MetadataImportNote.CharactersNotImportable ->
        stringResource(R.string.metadata_note_characters, count)

    is MetadataImportNote.ModelUnsupported ->
        stringResource(R.string.metadata_note_model, source ?: stringResource(R.string.metadata_note_unknown))

    is MetadataImportNote.SizeImportedAsCustom ->
        stringResource(R.string.metadata_note_size, width, height)

    is MetadataImportNote.SizeNotImportable ->
        stringResource(R.string.metadata_note_size_invalid, width, height)

    is MetadataImportNote.StepsClamped ->
        stringResource(R.string.metadata_note_steps, requested, applied)

    is MetadataImportNote.GuidanceClamped ->
        stringResource(R.string.metadata_note_guidance, requested, applied)

    is MetadataImportNote.CfgRescaleClamped ->
        stringResource(R.string.metadata_note_cfg, requested, applied)

    is MetadataImportNote.SamplerUnsupported ->
        stringResource(R.string.metadata_note_sampler, raw ?: stringResource(R.string.metadata_note_unknown))

    is MetadataImportNote.NoiseScheduleUnsupported ->
        stringResource(R.string.metadata_note_schedule, raw ?: stringResource(R.string.metadata_note_unknown))

    is MetadataImportNote.SeedOutOfRange ->
        stringResource(R.string.metadata_note_seed, raw)

    is MetadataImportNote.QualityTagsDetected ->
        stringResource(R.string.metadata_note_quality_tags, option.displayName)

    MetadataImportNote.QualityTagsKeptVerbatim ->
        stringResource(R.string.metadata_note_quality_tags_kept)

    MetadataImportNote.VibeReferencesNotRestorable ->
        stringResource(R.string.metadata_note_vibe)

    MetadataImportNote.DirectorReferencesNotRestorable ->
        stringResource(R.string.metadata_note_director)

    MetadataImportNote.BaseImageNotRestorable ->
        stringResource(R.string.metadata_note_base_image)

    MetadataImportNote.ActualPromptUnavailable ->
        stringResource(R.string.metadata_note_actual_prompt)

    MetadataImportNote.NothingToImport ->
        stringResource(R.string.metadata_note_nothing)

    MetadataImportNote.UndesiredContentPresetNotRestorable ->
        stringResource(R.string.metadata_note_uc_preset)
}

@Composable
private fun ImportToggle(checked: Boolean, label: String, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}
