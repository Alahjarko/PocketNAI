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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import net.pocketnai.domain.chat.ChatImagePlan
import net.pocketnai.domain.model.shortDisplayName
import net.pocketnai.ui.common.InfoButton

@Composable
internal fun ChatImagePlanCard(plan: ChatImagePlan, onConfirm: () -> Unit, onCancel: () -> Unit, onRead: (String, String) -> Unit) {
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
                    val ordinal = plan.completed + index + 1
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.fillMaxWidth().testTag("chat-plan-image-$ordinal")
                        .clickable(role = Role.Button, onClickLabel = "查看第 $ordinal 张完整参数") { onRead("第 $ordinal 张参数", buildString {
                        appendLine("模型：${p.model.displayName}　画布：${p.size.label}")
                        if (p.model == net.pocketnai.domain.model.ImageModel.V5_FULL) appendLine("Effort：${p.effort.label}")
                        appendLine("步数：${p.steps}　CFG：${p.guidance}　采样器：${p.sampler}")
                        appendLine("Seed：${if (p.seedMode == net.pocketnai.domain.model.SeedMode.RANDOM) "随机" else p.baseSeed.toString()}")
                        appendLine("报价：${item.quote.label}\n\n正向提示词\n${p.prompt}\n\n逆向提示词\n${p.negativePrompt}")
                        p.characters.forEachIndexed { i, role -> appendLine("\n角色 ${i + 1}\n${role.prompt}\n逆向：${role.negativePrompt}\n位置：${if (p.useCharacterCoordinates) "${role.centerX}, ${role.centerY}" else "AI 安排"}") }
                    }) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(ordinal.toString(), style = MaterialTheme.typography.titleMedium)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(p.prompt, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium)
                            Text("${p.model.shortDisplayName} · ${p.size.label} · ${item.quote.label}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
