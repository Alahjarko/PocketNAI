package net.pocketnai.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import net.pocketnai.domain.chat.*
import net.pocketnai.ui.settings.*

/** Drafts and uncommitted keys stay local; leaving an editor cancels model discovery. */
@Composable
fun LlmProviderEditor(viewModel: ChatViewModel, initial: LlmProvider, onDismiss: () -> Unit) {
    var provider by remember(initial.id) { mutableStateOf(initial) }
    var key by remember(initial.id) { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var discovered by remember { mutableStateOf<List<String>?>(null) }
    var editingModel by remember { mutableStateOf<LlmModel?>(null) }
    var originalModelId by remember { mutableStateOf<String?>(null) }
    var discoveryJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val credentialRevision by viewModel.credentialRevision.collectAsStateWithLifecycle()
    val hasKey = remember(initial.id, credentialRevision) { viewModel.hasProviderKey(initial.id) }
    SettingsEditorSheet(if (initial.name.isBlank()) "添加提供商" else "编辑提供商", Icons.Default.Dns,
        onDismiss, saveEnabled = !loading && !state.busy && provider.name.isNotBlank(), scrollContent = false,
        onClosing = { discoveryJob?.cancel() },
        onSave = {
            val saved = viewModel.saveProvider(provider, key)
            if (saved) key = "" else error = viewModel.state.value.error
            saved
        }) {
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "connection-header") {
                SettingsSection("连接设置") {
                    net.pocketnai.ui.common.InfoButton("支持 OpenAI 兼容接口。填写服务的 API 根路径，不自动追加 /v1。每个提供商独立加密保存密钥；更换地址必须填写对应密钥。消息、人格和附件仅在发送时交给所选提供商。")
                }
            }
            item(key = "connection") {
                SettingsCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(provider.name, { provider = provider.copy(name = it.take(60)) }, Modifier.fillMaxWidth(), label = { Text("提供商名称") }, singleLine = true, enabled = !loading)
                        OutlinedTextField(provider.baseUrl, { provider = provider.copy(baseUrl = it); discovered = null }, Modifier.fillMaxWidth(), label = { Text("API Base URL") }, singleLine = true, enabled = !loading,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                        OutlinedTextField(key, { key = it; discovered = null }, Modifier.fillMaxWidth(), label = { Text(if (hasKey) "替换 API Key（留空保留）" else "API Key") },
                            singleLine = true, enabled = !loading, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                    }
                }
            }
            item(key = "models-header") {
                SettingsSection("模型 · ${provider.models.size}") {
                    TextButton(onClick = { originalModelId = null; editingModel = LlmModel(id = "") }, enabled = !loading) {
                        Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("手动添加")
                    }
                }
            }
            items(provider.models, key = { "model:${it.id}" }) { model ->
                SettingsCard(Modifier.animateItem()) {
                    SettingsNavigationRow(model.id.ifBlank { "未填写模型（点击补全）" },
                        "${dialectLabel(model.dialect)} · ${if (model.thinking) "思考已开启" else "思考已关闭"}", Icons.Default.Memory,
                        { originalModelId = model.id; editingModel = model }, enabled = !loading,
                        trailing = {
                            IconButton(onClick = { provider = provider.copy(models = provider.models.filterNot { it.id == model.id }) }, enabled = !loading) {
                                Icon(Icons.Default.Close, "从列表移除 ${model.id}", Modifier.size(20.dp))
                            }
                        })
                }
            }
            item(key = "discover") {
                OutlinedButton(onClick = {
                    loading = true; error = null
                    val request = provider
                    discoveryJob = scope.launch {
                        try {
                            val found = viewModel.providerModels(request, key)
                            if (found.isEmpty()) error = "没有返回模型，可手动添加模型 ID" else discovered = found
                        } catch (e: CancellationException) { throw e }
                        catch (e: ChatFailure) { error = e.userMessage }
                        catch (_: Exception) { error = "获取模型列表失败，请检查地址与密钥" }
                        finally { loading = false }
                    }
                }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                    if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.CloudDownload, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp)); Text(if (loading) "获取中…" else "获取模型列表")
                }
            }
            error?.let { message -> item(key = "error") { Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) } }
        }
    }
    editingModel?.let { model -> LlmModelEditor(model, onDismiss = { editingModel = null }) { next ->
        if (provider.models.any { it.id == next.id && it.id != originalModelId }) false
        else {
            provider = provider.copy(models = if (originalModelId == null) provider.models + next else provider.models.map { if (it.id == originalModelId) next else it })
            true
        }
    } }
    discovered?.let { found ->
        var query by remember { mutableStateOf("") }
        var selected by remember { mutableStateOf(provider.models.map { it.id }.toSet()) }
        val filtered = remember(found, query) { found.filter { it.contains(query, true) } }
        SettingsEditorSheet("添加模型", Icons.Default.Memory, { discovered = null }, saveLabel = "添加所选", scrollContent = false,
            onSave = {
                // Discovery is additive: deselecting a fetched item never removes an existing model.
                provider = provider.copy(models = provider.models + found.filter { it in selected && provider.models.none { model -> model.id == it } }.map { LlmModel(it) })
                true
            }) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("搜索模型") }, singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) })
            LazyColumn(Modifier.weight(1f)) {
                items(filtered, key = { it }) { id ->
                    ListItem(headlineContent = { Text(id) }, leadingContent = { Checkbox(id in selected, onCheckedChange = null) },
                        modifier = Modifier.settingsClickable { selected = if (id in selected) selected - id else selected + id },
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent))
                }
            }
        }
    }
}

private fun dialectLabel(dialect: LlmDialect): String = when (dialect) {
    LlmDialect.AUTO -> "自动"; LlmDialect.KIMI -> "Kimi"; LlmDialect.DEEPSEEK -> "DeepSeek"; else -> "OpenAI 兼容"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LlmModelEditor(initial: LlmModel, onDismiss: () -> Unit, onSave: (LlmModel) -> Boolean) {
    var model by remember { mutableStateOf(initial) }
    var tokens by remember { mutableStateOf(initial.maxTokens.toString()) }
    var error by remember { mutableStateOf(false) }
    SettingsEditorSheet("模型配置", Icons.Default.Memory, onDismiss, saveLabel = "确定",
        saveEnabled = model.id.isNotBlank() && tokens.toIntOrNull() in 1024..65536,
        onSave = {
            val saved = onSave(model.copy(id = model.id.trim(), maxTokens = tokens.toInt()))
            error = !saved
            saved
        }) {
        SettingsCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(model.id, { model = model.copy(id = it); error = false }, Modifier.fillMaxWidth(), label = { Text("模型 ID") }, singleLine = true)
                Text("思考协议", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LlmDialect.entries.forEach { dialect ->
                        FilterChip(model.dialect == dialect, { model = model.copy(dialect = dialect) }, label = { Text(dialectLabel(dialect)) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("开启思考", Modifier.weight(1f)); Switch(model.thinking, { model = model.copy(thinking = it) })
                }
                OutlinedTextField(tokens, { tokens = it.filter(Char::isDigit).take(5) }, Modifier.fillMaxWidth(), label = { Text("最大输出 Tokens（含思考）") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
            }
        }
        if (error) Text("该模型 ID 已存在", color = MaterialTheme.colorScheme.error)
    }
}

@Composable
fun ChatModelPicker(viewModel: ChatViewModel, onDismiss: () -> Unit) {
    val catalog by viewModel.providers.collectAsStateWithLifecycle()
    val keyRevision by viewModel.credentialRevision.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    val readyProviders = remember(catalog.providers, keyRevision) { catalog.providers.filter { viewModel.hasProviderKey(it.id) }.map { it.id }.toSet() }
    SettingsEditorSheet("切换模型", Icons.Default.SwapHoriz, onDismiss, scrollContent = false) {
        val close = LocalSettingsSheetDismiss.current
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("搜索提供商或模型") }, singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null) })
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            catalog.providers.forEach { provider ->
                val models = provider.models.filter { provider.name.contains(query, true) || it.id.contains(query, true) }
                if (models.isNotEmpty()) {
                    item(key = "provider:${provider.id}") { SettingsSection(provider.name) }
                    items(models, key = { "${provider.id}:${it.id}" }) { model ->
                        val selected = catalog.activeProviderId == provider.id && catalog.activeModelId == model.id
                        SettingsCard {
                            SettingsNavigationRow(model.id, if (provider.id !in readyProviders) "请在设置中添加密钥" else "",
                                Icons.Default.Memory, { if (viewModel.selectModel(provider.id, model.id)) close() },
                                enabled = provider.id in readyProviders && !state.busy, active = selected,
                                trailing = { RadioButton(selected, onClick = null) })
                        }
                    }
                }
            }
            if (catalog.providers.none { provider -> provider.models.any { provider.name.contains(query, true) || it.id.contains(query, true) } }) {
                item(key = "empty") { Text("没有匹配的模型", Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}
