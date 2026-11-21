package net.pocketnai.ui.generate

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.pocketnai.R
import net.pocketnai.domain.model.ReferenceRole
import net.pocketnai.ui.common.SectionHeader
import net.pocketnai.ui.motion.rememberPocketHaptics

/**
 * 参考图区：图生图 / Precise Reference / Vibe Transfer 三个面板合并成一张柔和卡片，
 * 顶部采用现代 Material 3 Tonal 滑动胶囊分段条切换。
 *
 * ## 互斥关系不由页签表达
 * 页签只是"看哪个面板"：图生图可以分别搭配 Precise Reference 或 Vibe。
 * Precise 与 Vibe 互斥，清理由 ViewModel 在挂图时做。
 * 胶囊滑块表示正在查看的面板；圆点与强调色表示该功能当前已挂图生效，可同时亮起。
 */
@Composable
fun ReferenceTabsSection(
    state: GenerateViewModel.UiState,
    connected: Boolean,
    viewModel: GenerateViewModel,
    onOpenInpaintEditor: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by remember {
        mutableStateOf(
            when {
                state.supportsDirectorReference && state.directorReferences.isNotEmpty() -> ReferenceTab.PRECISE
                state.supportsVibeTransfer && state.vibeReferences.isNotEmpty() -> ReferenceTab.VIBE
                else -> ReferenceTab.IMG2IMG
            },
        )
    }

    // 模型切到 V5 等不支持 Precise / Vibe 的版本时，立即退回图生图页签。
    LaunchedEffect(state.supportsDirectorReference, state.supportsVibeTransfer) {
        if ((tab == ReferenceTab.PRECISE && !state.supportsDirectorReference) ||
            (tab == ReferenceTab.VIBE && !state.supportsVibeTransfer)
        ) {
            tab = ReferenceTab.IMG2IMG
        }
    }

    // 从详情页/画廊一键「用作参考图」送入时，自动切到对应面板。
    LaunchedEffect(state.referenceTabSignal) {
        val target = state.referenceTabTarget ?: return@LaunchedEffect
        val next = when (target) {
            ReferenceRole.DIRECTOR -> if (state.supportsDirectorReference) ReferenceTab.PRECISE else ReferenceTab.IMG2IMG
            ReferenceRole.VIBE -> if (state.supportsVibeTransfer) ReferenceTab.VIBE else ReferenceTab.IMG2IMG
            else -> ReferenceTab.IMG2IMG
        }
        tab = next
    }

    val infoText = stringResource(R.string.generate_img2img_info) + "\n\n" +
        stringResource(R.string.generate_director_hint) + "\n\n" +
        stringResource(R.string.generate_vibe_hint)

    SectionHeader(
        text = stringResource(R.string.generate_reference_section),
        infoText = infoText,
        modifier = modifier,
    )

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val haptics = rememberPocketHaptics()
            val tabPosition by animateFloatAsState(
                targetValue = tab.ordinal.toFloat(),
                animationSpec = spring(dampingRatio = 1f, stiffness = 700f),
                label = "reference-tab",
            )
            val pillColor = MaterialTheme.colorScheme.surface
            val count = ReferenceTab.entries.size
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(4.dp)
                        .selectableGroup()
                        .drawBehind {
                            val slotWidth = size.width / count
                            val radius = size.height / 2f
                            drawRoundRect(
                                color = pillColor,
                                topLeft = Offset(slotWidth * tabPosition, 0f),
                                size = Size(slotWidth, size.height),
                                cornerRadius = CornerRadius(radius, radius),
                            )
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ReferenceTab.entries.forEach { option ->
                        val supported = when (option) {
                            ReferenceTab.IMG2IMG -> true
                            ReferenceTab.PRECISE -> state.supportsDirectorReference
                            ReferenceTab.VIBE -> state.supportsVibeTransfer
                        }
                        val isCurrent = tab == option && supported
                        val active = supported && state.activeReferences.any { it.role == option.role }
                        val status = when {
                            !supported -> "当前模型不支持"
                            active -> stringResource(R.string.generate_reference_active)
                            else -> stringResource(R.string.generate_reference_inactive)
                        }
                        val textColor by animateColorAsState(
                            targetValue = when {
                                !supported -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.36f)
                                active -> MaterialTheme.colorScheme.primary
                                isCurrent -> MaterialTheme.colorScheme.onSurface
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            animationSpec = tween(150),
                            label = "reference-tab-text",
                        )
                        val interaction = remember { MutableInteractionSource() }
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 38.dp)
                                .clip(CircleShape)
                                .semantics {
                                    selected = isCurrent
                                    stateDescription = status
                                }
                                .clickable(
                                    enabled = supported,
                                    interactionSource = interaction,
                                    indication = null,
                                    role = Role.Tab,
                                    onClick = {
                                        if (tab != option) {
                                            haptics.tick()
                                            tab = option
                                        }
                                    },
                                )
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (active) {
                                Box(
                                    Modifier
                                        .size(6.dp)
                                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                                )
                            }
                            Text(
                                text = option.label(state, supported),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (isCurrent || active) FontWeight.SemiBold else FontWeight.Medium,
                                color = textColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            AnimatedContent(
                targetState = tab,
                label = "reference-panel",
                transitionSpec = {
                    (fadeIn(tween(180)) togetherWith fadeOut(tween(90)))
                        .using(SizeTransform { _, _ -> tween(220) })
                },
            ) { shownTab ->
                val panelModifier = if (shownTab == tab) Modifier else Modifier
                    .clearAndSetSemantics { }
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                    .changes.forEach { it.consume() }
                            }
                        }
                    }
                Box(panelModifier) {
                    when (shownTab) {
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
    }
}

private enum class ReferenceTab(val role: ReferenceRole) {
    IMG2IMG(ReferenceRole.IMG2IMG), PRECISE(ReferenceRole.DIRECTOR), VIBE(ReferenceRole.VIBE)
}

/** 页签文字显示素材数量，实际启用状态由强调色与圆点表达。 */
@Composable
private fun ReferenceTab.label(state: GenerateViewModel.UiState, supported: Boolean): String = when (this) {
    ReferenceTab.IMG2IMG ->
        stringResource(R.string.generate_mode_img2img)

    ReferenceTab.PRECISE ->
        stringResource(R.string.generate_precise_tab) +
            if (supported && state.directorReferences.isNotEmpty()) " (${state.directorReferences.size})" else ""

    ReferenceTab.VIBE ->
        stringResource(R.string.generate_vibe_short) +
            if (supported && state.vibeReferences.isNotEmpty()) " (${state.vibeReferences.size})" else ""
}
