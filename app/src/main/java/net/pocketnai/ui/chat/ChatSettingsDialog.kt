package net.pocketnai.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.pocketnai.domain.chat.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatSettingsDialog(viewModel: ChatViewModel, onDismiss: () -> Unit) {
    val stored by viewModel.config.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    var config by remember { mutableStateOf(stored) }
    // API Key 不使用 rememberSaveable，不进入 SavedState 或崩溃恢复数据。
    var key by remember { mutableStateOf("") }
    var chooseModel by remember { mutableStateOf(false) }
    var tokensText by remember { mutableStateOf(config.maxTokens.toString()) }
    var editFile by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("对话连接") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(config.assistantName, { config = config.copy(assistantName = it.take(24)) }, label = { Text("伙伴名字") }, singleLine = true)
                OutlinedTextField(config.baseUrl, { config = config.copy(baseUrl = it) }, label = { Text("API Base URL") }, singleLine = true,
                    supportingText = { Text("例如 https://api.deepseek.com/v1；也支持带路径前缀的兼容网关") })
                OutlinedTextField(key, { key = it }, label = { Text(if (viewModel.hasKey()) "替换 API Key（留空保留）" else "API Key") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { viewModel.fetchModels(config, key) }, enabled = !state.loadingModels) {
                        Text(if (state.loadingModels) "获取中…" else "获取模型列表")
                    }
                    if (state.models.isNotEmpty()) TextButton(onClick = { chooseModel = true }) { Text("选择模型") }
                }
                OutlinedTextField(config.model, { config = config.copy(model = it) }, label = { Text("模型 ID") }, singleLine = true)
                Text("思考协议", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LlmDialect.entries.forEach { dialect ->
                        FilterChip(selected = config.dialect == dialect, onClick = { config = config.copy(dialect = dialect) },
                            label = { Text(when (dialect) { LlmDialect.AUTO -> "自动"; LlmDialect.KIMI -> "Kimi"; LlmDialect.DEEPSEEK -> "DeepSeek"; else -> "OpenAI 兼容" }) })
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("开启思考与思考内容回传", modifier = Modifier.weight(1f))
                    Switch(config.thinking, { config = config.copy(thinking = it) })
                }
                Text("返回的思考内容可在消息中展开。支持情况取决于模型；代理模型名不能识别时手动选择协议。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(tokensText, { value -> tokensText = value.filter(Char::isDigit).take(5); tokensText.toIntOrNull()?.let { config = config.copy(maxTokens = it) } },
                    label = { Text("最大输出 Tokens（含思考）") }, singleLine = true)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("自动执行图片工具", modifier = Modifier.weight(1f))
                    Switch(config.autoGenerate, { config = config.copy(autoGenerate = it) })
                }
                Text("开启后，点击发送即授权模型在本条消息内最多生成一张图片；可能消耗 Anlas。关闭时先展示生成卡片。", style = MaterialTheme.typography.bodySmall)
                Text("人格与工具说明", style = MaterialTheme.typography.labelLarge)
                Row {
                    TextButton(onClick = { editFile = "soul.md" }) { Text("编辑 soul.md") }
                    TextButton(onClick = { editFile = "tools.md" }) { Text("编辑 tools.md") }
                }
                if (viewModel.hasKey()) TextButton(onClick = viewModel::clearKey) { Text("移除已保存的 API Key") }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Text("消息、人格和工具结果会发送至你填写的 LLM 服务。API Key 在本机使用 Keystore 加密保存。", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { if (viewModel.saveConfig(config, key)) { key = ""; onDismiss() } }, enabled = tokensText.toIntOrNull() in 1024..65536) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
    if (chooseModel) {
        var search by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { chooseModel = false }, title = { Text("选择模型") }, text = {
            Column {
                OutlinedTextField(search, { search = it }, label = { Text("筛选模型") }, singleLine = true)
                LazyColumn(Modifier.heightIn(max = 330.dp)) {
                    items(state.models.filter { it.contains(search, ignoreCase = true) }) { model ->
                        TextButton(onClick = { config = config.copy(model = model); chooseModel = false }) { Text(model) }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { chooseModel = false }) { Text("关闭") } })
    }
    editFile?.let { name -> AgentFileEditor(viewModel, name) { editFile = null } }
}

@Composable
fun AgentFileEditor(viewModel: ChatViewModel, name: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember(name) { mutableStateOf(viewModel.agentText(name)) }
    var error by remember { mutableStateOf<String?>(null) }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use {
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (out.size() <= 128 * 1024) {
                        val count = it.read(buffer, 0, minOf(buffer.size, 128 * 1024 + 1 - out.size()))
                        if (count < 0) break
                        out.write(buffer, 0, count)
                    }
                    val bytes = out.toByteArray()
                    require(bytes.size <= 128 * 1024)
                    bytes.toString(Charsets.UTF_8)
                } ?: throw java.io.IOException("无法读取")
            } }.onSuccess { text = it; error = null }.onFailure { error = "文件无法读取或超过 128 KiB" }
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: throw java.io.IOException("无法写入")
            } }.onFailure { error = "无法导出文件" }
        }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(name) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 350.dp), maxLines = 20)
            Row {
                TextButton(onClick = { import.launch(arrayOf("text/*", "application/octet-stream")) }) { Text("导入 .md") }
                TextButton(onClick = { export.launch(name) }) { Text("导出 .md") }
                TextButton(onClick = { text = viewModel.defaultAgentText(name) }) { Text("恢复默认") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = { if (viewModel.saveAgent(name, text)) onDismiss() else error = "保存失败或文本超过 128 KiB" }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
