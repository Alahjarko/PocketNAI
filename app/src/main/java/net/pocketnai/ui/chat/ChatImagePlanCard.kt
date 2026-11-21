package net.pocketnai.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import net.pocketnai.domain.chat.ChatImagePlan
import net.pocketnai.domain.model.ReferenceImage
import net.pocketnai.domain.model.ReferenceRole
import net.pocketnai.domain.model.shortDisplayName
import net.pocketnai.ui.common.InfoButton
import java.io.File
import kotlin.math.roundToInt

@Composable
internal fun ChatImagePlanCard(
    plan: ChatImagePlan,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onRead: (String, String) -> Unit,
    resolveImage: ((String) -> File)? = null,
) {
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (plan.changed) Text("费用已变化", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(plan.summary, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                InfoButton("确认后按顺序生成。可停止后续图片；当前图片会完成保存。失败不重试，费用以服务端为准。")
            }
            if (plan.completed > 0) Text("已完成 ${plan.completed} 张", style = MaterialTheme.typography.labelMedium)
            Column {
                plan.items.forEachIndexed { index, item ->
                    val p = item.params
                    val openAi = item.openAi
                    val refs = item.references
                    val ordinal = plan.completed + index + 1
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.fillMaxWidth().testTag("chat-plan-image-$ordinal")
                        .clickable(role = Role.Button, onClickLabel = "查看第 $ordinal 张完整参数") { onRead("第 $ordinal 张参数", buildString {
                        if (openAi != null) {
                            appendLine("服务：OpenAI（${openAi.modelLabel}）")
                            appendLine("尺寸：${openAi.size}　质量：${openAi.quality}　格式：${openAi.outputFormat.uppercase()}")
                            if (openAi.supportsComplexity) appendLine("复杂度：${openAi.complexityLabel}")
                            if (refs.isNotEmpty()) appendLine("参考图：${refs.size} 张")
                            appendLine("报价：${item.quote.label}\n\n图片描述\n${openAi.prompt}")
                        } else {
                            appendLine("模型：${p.model.displayName}　画布：${p.size.label}")
                            if (p.model == net.pocketnai.domain.model.ImageModel.V5_FULL) appendLine("Effort：${p.effort.label}")
                            appendLine("步数：${p.steps}　CFG：${p.guidance}　采样器：${p.sampler}")
                            appendLine("Seed：${if (p.seedMode == net.pocketnai.domain.model.SeedMode.RANDOM) "随机" else p.baseSeed.toString()}")
                            if (refs.isNotEmpty()) {
                                appendLine("参考图：${refs.joinToString("、") { referenceSummaryLabel(it, isOpenAi = false) }}")
                            }
                            appendLine("报价：${item.quote.label}\n\n正向提示词\n${p.prompt}\n\n逆向提示词\n${p.negativePrompt}")
                            p.characters.forEachIndexed { i, role -> appendLine("\n角色 ${i + 1}\n${role.prompt}\n逆向：${role.negativePrompt}\n位置：${if (p.useCharacterCoordinates) "${role.centerX}, ${role.centerY}" else "AI 安排"}") }
                        }
                    }) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(ordinal.toString(), style = MaterialTheme.typography.titleMedium)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(openAi?.prompt ?: p.prompt, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium)
                            val subtitle = if (openAi != null) {
                                "${openAi.modelLabel} · ${openAi.size} · ${openAi.quality} · ${item.quote.label}"
                            } else {
                                "${p.model.shortDisplayName} · ${p.size.label} · ${item.quote.label}"
                            }
                            Text(subtitle,
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (refs.isNotEmpty()) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    if (resolveImage != null) {
                                        refs.take(3).forEach { ref ->
                                            net.pocketnai.ui.safety.SfwAsyncImage(
                                                file = resolveImage(ref.relativePath),
                                                contentDescription = "参考图",
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier
                                                    .size(28.dp)
                                                    .clip(RoundedCornerShape(7.dp)),
                                            )
                                        }
                                    }
                                    val badge = if (openAi != null) {
                                        "参考图 ×${refs.size}"
                                    } else {
                                        refs.joinToString(" + ") { referenceSummaryLabel(it, isOpenAi = false) }
                                    }
                                    Text(
                                        text = badge,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(20.dp))
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onConfirm, modifier = Modifier.testTag("chat-confirm-image")) { Text("确认生成 ${plan.items.size} 张") }
                TextButton(onClick = onCancel) { Text("取消") }
            }
        }
    }
}

private fun referenceSummaryLabel(ref: ReferenceImage, isOpenAi: Boolean): String {
    if (isOpenAi) return "参考图"
    return when (ref.role) {
        ReferenceRole.IMG2IMG -> {
            val pct = ((ref.strength ?: 0.7) * 100).roundToInt()
            "图生图 $pct%"
        }
        ReferenceRole.DIRECTOR -> "Precise"
        ReferenceRole.VIBE -> "Vibe"
        ReferenceRole.INPAINT_MASK -> "蒙版"
    }
}
