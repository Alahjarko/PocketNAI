package net.pocketnai.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.pocketnai.domain.chat.ChatImageContext

@Composable
internal fun ChatImageContextPanel(context: ChatImageContext, modifier: Modifier = Modifier) {
    val data = context.bounded()
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("随图片附带的生成参数", style = MaterialTheme.typography.titleSmall)
            Text(listOfNotNull(data.provider, data.model,
                if (data.width != null && data.height != null) "${data.width} × ${data.height}" else null).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("提示词", style = MaterialTheme.typography.labelLarge)
                    Text(data.prompt.ifBlank { "未记录" }, style = MaterialTheme.typography.bodyMedium)
                    if (data.negativePrompt.isNotBlank()) {
                        Text("排除词", style = MaterialTheme.typography.labelLarge)
                        Text(data.negativePrompt, style = MaterialTheme.typography.bodyMedium)
                    }
                    val parameters = listOfNotNull(data.steps?.let { "$it 步" }, data.guidance?.let { "CFG $it" },
                        data.seed?.let { "Seed $it" }, data.quality).joinToString(" · ")
                    if (parameters.isNotBlank()) Text(parameters, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    data.characters.forEachIndexed { index, character ->
                        Text("角色 ${index + 1}：${character.prompt}", style = MaterialTheme.typography.bodySmall)
                        if (character.negativePrompt.isNotBlank()) Text("排除词：${character.negativePrompt}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (data.truncated) Text("参数较长，已附带摘要", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
