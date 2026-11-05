package net.pocketnai.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import net.pocketnai.domain.chat.ChatImagePlan

@Composable
internal fun ChatImagePlanCard(plan: ChatImagePlan, onConfirm: () -> Unit, onCancel: () -> Unit, onRead: (String, String) -> Unit) {
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (plan.changed) "费用已变化，请重新确认" else "图片创作计划", style = MaterialTheme.typography.titleSmall)
            Text(plan.summary, style = MaterialTheme.typography.bodyMedium)
            if (plan.completed > 0) Text("已完成 ${plan.completed} 张，以下仅为剩余计划", style = MaterialTheme.typography.bodySmall)
            if (plan.items.size > 1) Text("上下滑动查看每张图片的计划", style = MaterialTheme.typography.labelSmall)
            Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                plan.items.forEachIndexed { index, item ->
                    val p = item.params
                    Text("${plan.completed + index + 1}. ${p.model.displayName} · ${p.size.label} · ${item.quote.label}", style = MaterialTheme.typography.labelLarge)
                    SelectionContainer { Text(p.prompt, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) }
                    TextButton(onClick = { onRead("第 ${plan.completed + index + 1} 张参数", buildString {
                        appendLine("模型：${p.model.displayName}　画布：${p.size.label}")
                        appendLine("步数：${p.steps}　CFG：${p.guidance}　采样器：${p.sampler}")
                        appendLine("Seed：${if (p.seedMode == net.pocketnai.domain.model.SeedMode.RANDOM) "随机" else p.baseSeed.toString()}")
                        appendLine("报价：${item.quote.label}\n\n正向提示词\n${p.prompt}\n\n逆向提示词\n${p.negativePrompt}")
                        p.characters.forEachIndexed { i, role -> appendLine("\n角色 ${i + 1}\n${role.prompt}\n逆向：${role.negativePrompt}\n位置：${if (p.useCharacterCoordinates) "${role.centerX}, ${role.centerY}" else "AI 安排"}") }
                    }) }) { Text("查看完整参数") }
                }
            }
            Text("确认后按顺序生成。可停止后续图片；当前图片会完成保存。失败不重试，费用以服务端为准。", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onConfirm, modifier = Modifier.testTag("chat-confirm-image")) { Text("确认生成 ${plan.items.size} 张") }
                TextButton(onClick = onCancel) { Text("取消") }
            }
        }
    }
}
