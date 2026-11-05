package net.pocketnai.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.pocketnai.domain.chat.ChatSearchProvider

@Composable
internal fun ChatSearchSettingsDialog(viewModel: ChatViewModel, onDismiss: () -> Unit) {
    val stored by viewModel.searchConfig.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    var config by remember { mutableStateOf(stored) }
    var key by remember { mutableStateOf("") }
    var hasKey by remember { mutableStateOf(viewModel.hasSearchKey()) }
    val boundKey = hasKey && config.provider == stored.provider && config.baseUrl.trim().trimEnd('/') == stored.baseUrl.trim().trimEnd('/')
    AlertDialog(onDismissRequest = onDismiss, title = { Text("联网搜索") }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text("允许 agent 搜索", Modifier.weight(1f))
                Switch(config.enabled, { config = config.copy(enabled = it) })
            }
            Text("用于查找角色、作品、服装与外貌设定，返回摘要和来源链接。可以与 DeepSeek、Kimi 等对话模型搭配。", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChatSearchProvider.entries.forEach { provider ->
                    FilterChip(config.provider == provider, onClick = {
                        config = config.copy(provider = provider, baseUrl = provider.defaultUrl); key = ""
                    }, label = { Text(provider.label) })
                }
            }
            OutlinedTextField(config.baseUrl, { config = config.copy(baseUrl = it) }, label = { Text("搜索 API Base URL") }, singleLine = true,
                supportingText = { Text("默认 ${config.provider.defaultUrl}；更换服务需填写对应密钥") })
            OutlinedTextField(key, { key = it }, label = { Text(if (boundKey) "替换搜索 API Key（留空保留）" else "${config.provider.label} API Key") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            Text("开启后，发送消息允许 agent 按需检索，每条消息最多 6 次。检索词发送至搜索服务，摘要发送至当前 LLM；可能消耗搜索服务额度。搜索密钥单独加密保存。", style = MaterialTheme.typography.bodySmall)
            if (config.provider == ChatSearchProvider.DEEPSEEK) Text("使用 DeepSeek 平台的 API Key，按其搜索服务规则计费。此处独立保存，不自动使用聊天服务密钥。", style = MaterialTheme.typography.bodySmall)
            if (hasKey) TextButton(onClick = { viewModel.clearSearchKey(); hasKey = false; config = config.copy(enabled = false) }) { Text("移除搜索密钥") }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = { if (viewModel.saveSearchConfig(config, key)) { key = ""; onDismiss() } }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
