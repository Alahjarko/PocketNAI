package net.pocketnai.ui.openai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.pocketnai.domain.openai.ImageProvider
import net.pocketnai.ui.LocalAppContainer

@Composable
fun OpenAiImageSettingsRow() {
    val container = LocalAppContainer.current
    val settings = container.openAiImageSettings
    val provider by settings.provider.collectAsStateWithLifecycle()
    val hasKey by settings.hasKey.collectAsStateWithLifecycle()
    val generation by container.generateViewModel.state.collectAsStateWithLifecycle()
    val busy = generation.inFlight || generation.artistLabActive || generation.externalGenerationActive
    var open by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().clickable { open = true }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("生图服务", style = MaterialTheme.typography.titleMedium)
            Text(provider.label + if (provider == ImageProvider.OPEN_AI) {
                if (hasKey) " · 已保存 API 密钥" else " · 未配置 API 密钥"
            } else "", style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (open) {
        var choice by remember { mutableStateOf(provider) }
        var key by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(onDismissRequest = { open = false }, title = {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("生图服务", Modifier.weight(1f))
                net.pocketnai.ui.common.InfoButton("OpenAI 使用你自己的 Platform 额度，支持 GPT Image 2.6 与 2.5 的 Flare 和 Sunburst。")
            }
        }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ImageProvider.entries.forEach { item ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { choice = item }) {
                        RadioButton(selected = choice == item, onClick = { choice = item }, enabled = !busy)
                        Text(item.label, Modifier.padding(top = 12.dp))
                    }
                }
                if (choice == ImageProvider.OPEN_AI) {
                    OutlinedTextField(value = key, onValueChange = { key = it; error = null },
                        label = { Text(if (hasKey) "替换 API 密钥（留空保留）" else "API 密钥") },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth())
                    if (hasKey) TextButton(onClick = { settings.clearKey(); key = "" }, enabled = !busy) { Text("移除已保存密钥") }
                }
                if (busy) Text("图片任务完成后可切换服务", style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = {
            TextButton(enabled = !busy, onClick = {
                try {
                    if (key.isNotBlank()) settings.saveKey(key)
                    if (choice == ImageProvider.OPEN_AI && !settings.hasKey.value) error = "请先填写 API 密钥"
                    else { settings.setProvider(choice); key = ""; open = false }
                } catch (_: Exception) { error = "配置未保存，请检查密钥格式后重试" }
            }) { Text("保存") }
        }, dismissButton = { TextButton(onClick = { open = false }) { Text("取消") } })
    }
}
