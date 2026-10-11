package net.pocketnai.ui.generate

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import net.pocketnai.domain.model.ReferenceRole
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
 * 页签只是"看哪个面板"：图生图可以分别搭配 Precise Reference 或 Vibe。
 * Precise 与 Vibe 互斥，清理由 ViewModel 在挂图时做。
 * 底色表示该功能当前是否生效，可同时亮起；短下划线表示正在查看的面板。
 * 保留但不受当前模型支持的素材仍显示数量，不点亮。
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
                    val active = state.activeReferences.any { it.role == option.role }
                    val background = if (active) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surface
                    val foreground = if (active) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    val status = stringResource(if (active) R.string.generate_reference_active
                        else R.string.generate_reference_inactive)
                    SegmentedButton(
                        selected = tab == option,
                        onClick = { tab = option },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = ReferenceTab.entries.size,
                        ),
                        // 查看哪个面板与启用了哪些功能是两个状态，底色只跟随后者。
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = background,
                            inactiveContainerColor = background,
                            activeContentColor = foreground,
                            inactiveContentColor = foreground,
                        ),
                        modifier = Modifier.semantics { stateDescription = status },
                        icon = {},
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                text = option.label(state),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Box(Modifier.width(18.dp).height(2.dp).background(
                                if (tab == option) foreground else Color.Transparent,
                                RoundedCornerShape(1.dp),
                            ))
                        }
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

private enum class ReferenceTab(val role: ReferenceRole) {
    IMG2IMG(ReferenceRole.IMG2IMG), PRECISE(ReferenceRole.DIRECTOR), VIBE(ReferenceRole.VIBE)
}

/** 页签文字显示素材数量，实际启用状态由底色表达。 */
@Composable
private fun ReferenceTab.label(state: GenerateViewModel.UiState): String = when (this) {
    ReferenceTab.IMG2IMG ->
        stringResource(R.string.generate_mode_img2img)

    ReferenceTab.PRECISE ->
        stringResource(R.string.generate_precise_tab) +
            if (state.directorReferences.isNotEmpty()) " (${state.directorReferences.size})" else ""

    ReferenceTab.VIBE ->
        stringResource(R.string.generate_vibe_short) +
            if (state.vibeReferences.isNotEmpty()) " (${state.vibeReferences.size})" else ""
}
