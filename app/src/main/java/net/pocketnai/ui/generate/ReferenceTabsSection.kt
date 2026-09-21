package net.pocketnai.ui.generate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.pocketnai.R
import net.pocketnai.ui.common.SectionHeader

/**
 * 参考图区：图生图 / Precise Reference / Vibe Transfer 三个面板合并成一张卡片，
 * 顶部用分段按钮切换。合并前是三个各自带标题、卡片与整段说明文字的区块，
 * 在表单底部连占三屏（2026-09-21 界面减负）。
 *
 * ## 互斥关系不由页签表达
 * 页签只是"看哪个面板"：图生图与 Vibe 本来就允许同时挂，互斥的只有
 * "图生图 vs Precise Reference"（连请求的 action 都不同），清理由 ViewModel 在挂图时做。
 * 各页签上已挂内容的数量直接标在页签文字里，切走也能看到。
 *
 * ## 说明文字的归宿
 * 三个面板原来的整段说明收进标题旁的 ⓘ（见 `SectionHeader` 的 infoText），
 * 面板内部只留操作与状态。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReferenceTabsSection(
    state: GenerateViewModel.UiState,
    connected: Boolean,
    viewModel: GenerateViewModel,
    onOpenInpaintEditor: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 初始页签跟随"已有内容"的面板：复用参数 / 导入元数据挂上参考图时，第一眼看到的就是它。
    // 之后不再自动跳 —— 用户手动切页签不该被"别处还挂着别的图"拽回去。
    var tab by remember {
        mutableStateOf(
            when {
                state.directorReferences.isNotEmpty() -> ReferenceTab.PRECISE
                state.vibeReferences.isNotEmpty() -> ReferenceTab.VIBE
                else -> ReferenceTab.IMG2IMG
            },
        )
    }

    val infoText = stringResource(R.string.generate_img2img_info) + "\n\n" +
        stringResource(R.string.generate_director_hint) + "\n\n" +
        stringResource(R.string.generate_vibe_hint)

    SectionHeader(
        text = stringResource(R.string.generate_reference_section),
        infoText = infoText,
        modifier = modifier,
    )

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
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ReferenceTab.entries.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = tab == option,
                        onClick = { tab = option },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = ReferenceTab.entries.size,
                        ),
                        // 默认的选中对勾与文字里的"已挂 ✓"会撞车，关掉它。
                        icon = {},
                    ) {
                        Text(
                            text = option.label(state),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            when (tab) {
                ReferenceTab.IMG2IMG -> Img2ImgPanel(
                    state = state,
                    connected = connected,
                    viewModel = viewModel,
                    onOpenInpaintEditor = onOpenInpaintEditor,
                    modifier = Modifier.fillMaxWidth(),
                )

                ReferenceTab.PRECISE -> PreciseReferencePanel(
                    state = state,
                    connected = connected,
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxWidth(),
                )

                ReferenceTab.VIBE -> VibeTransferPanel(
                    state = state,
                    connected = connected,
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private enum class ReferenceTab { IMG2IMG, PRECISE, VIBE }

/** 页签文字：名称 + 已挂数量（图生图只有一张，用 ✓ 表示）。 */
@Composable
private fun ReferenceTab.label(state: GenerateViewModel.UiState): String = when (this) {
    ReferenceTab.IMG2IMG ->
        stringResource(R.string.generate_mode_img2img) +
            if (state.referenceSource != null) " ✓" else ""

    ReferenceTab.PRECISE ->
        stringResource(R.string.generate_precise_reference_short) +
            if (state.directorReferences.isNotEmpty()) " (${state.directorReferences.size})" else ""

    ReferenceTab.VIBE ->
        stringResource(R.string.generate_vibe_short) +
            if (state.vibeReferences.isNotEmpty()) " (${state.vibeReferences.size})" else ""
}
