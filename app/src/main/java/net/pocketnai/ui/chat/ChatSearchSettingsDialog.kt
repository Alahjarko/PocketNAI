package net.pocketnai.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    var removing by remember { mutableStateOf<net.pocketnai.domain.chat.ChatSearchConfig?>(null) }
    val boundKey = hasKey && config.provider == stored.provider && config.baseUrl.trim().trimEnd('/') == stored.baseUrl.trim().trimEnd('/')
    AlertDialog(onDismissRequest = onDismiss, title = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("联网搜索", Modifier.weight(1f))
            net.pocketnai.ui.common.InfoButton("开启后，发送消息允许 agent 按需检索，每条消息最多 6 次。检索词发送至搜索服务，摘要发送至当前 LLM；可能消耗搜索服务额度。搜索密钥独立加密保存。\n\nDeepSeek 使用其平台 API Key，按搜索服务规则计费；不会自动使用聊天密钥。")
            Switch(config.enabled, { config = config.copy(enabled = it) }, modifier = Modifier.semantics { contentDescription = "启用联网搜索" })
        }
    }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChatSearchProvider.entries.forEach { provider ->
                    FilterChip(config.provider == provider, onClick = {
                        config = config.copy(provider = provider, baseUrl = provider.defaultUrl); key = ""
                    }, label = { Text(provider.label) })
                }
            }
            OutlinedTextField(config.baseUrl, { config = config.copy(baseUrl = it) }, label = { Text("搜索 API Base URL") }, singleLine = true)
            OutlinedTextField(key, { key = it }, label = { Text(if (boundKey) "替换搜索 API Key（留空保留）" else "${config.provider.label} API Key") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            if (hasKey) TextButton(onClick = { removing = stored }, enabled = !state.busy) { Text("移除搜索密钥") }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = { if (viewModel.saveSearchConfig(config, key)) { key = ""; onDismiss() } }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
    removing?.let { target ->
        net.pocketnai.ui.connect.AccountDisconnectConfirmation(
            title = "移除搜索密钥？",
            message = "将移除已保存的 ${target.provider.label} 搜索密钥。重新使用搜索需要再次填写；对话与图片历史不受影响。",
            confirmLabel = "确认移除",
            enabled = !state.busy,
            onConfirm = {
                if (viewModel.searchConfig.value != target) false
                else if (viewModel.clearSearchKey()) { hasKey = false; config = config.copy(enabled = false); true }
                else false
            },
            onDismiss = { removing = null },
        )
    }
}
