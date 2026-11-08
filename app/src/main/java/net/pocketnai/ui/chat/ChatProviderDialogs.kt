package net.pocketnai.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import net.pocketnai.domain.chat.*

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
    val scope = rememberCoroutineScope()
    val state by viewModel.state.collectAsStateWithLifecycle()
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (initial.name.isBlank()) "添加提供商" else "编辑提供商") }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(provider.name, { provider = provider.copy(name = it.take(60)) }, label = { Text("提供商名称") }, singleLine = true, enabled = !loading)
            OutlinedTextField(provider.baseUrl, { provider = provider.copy(baseUrl = it); discovered = null }, label = { Text("API Base URL") }, singleLine = true, enabled = !loading,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            OutlinedTextField(key, { key = it; discovered = null }, label = { Text(if (viewModel.hasProviderKey(initial.id)) "替换 API Key（留空保留）" else "API Key") },
                singleLine = true, enabled = !loading, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            net.pocketnai.ui.common.InfoButton("支持 OpenAI 兼容接口。填写服务的 API 根路径，不自动追加 /v1。每个提供商独立加密保存密钥；更换地址必须填写对应密钥。消息、人格和附件仅在发送时交给所选提供商。")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("模型（${provider.models.size}）", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { originalModelId = null; editingModel = LlmModel(id = "") }, enabled = !loading) { Text("手动添加") }
            }
            provider.models.forEach { model ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { originalModelId = model.id; editingModel = model }, modifier = Modifier.weight(1f), enabled = !loading) {
                        Text(model.id.ifBlank { "未填写模型（点击补全）" }, modifier = Modifier.fillMaxWidth())
                    }
                    IconButton(onClick = { provider = provider.copy(models = provider.models.filterNot { it.id == model.id }) }, enabled = !loading) {
                        Icon(Icons.Default.Close, "从列表移除 ${model.id}")
                    }
                }
            }
            OutlinedButton(onClick = {
                loading = true; error = null
                val request = provider
                scope.launch {
                    try {
                        val found = viewModel.providerModels(request, key)
                        if (found.isEmpty()) error = "没有返回模型，可手动添加模型 ID" else discovered = found
                    } catch (e: CancellationException) { throw e }
                    catch (e: ChatFailure) { error = e.userMessage }
                    catch (_: Exception) { error = "获取模型列表失败，请检查地址与密钥" }
                    finally { loading = false }
                }
            }, enabled = !loading) { Text(if (loading) "获取中…" else "获取模型列表") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        TextButton(onClick = {
            if (viewModel.saveProvider(provider, key)) { key = ""; onDismiss() } else error = viewModel.state.value.error
        }, enabled = !loading && !state.busy && provider.name.isNotBlank()) { Text("保存") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
    editingModel?.let { model -> LlmModelEditor(model, onDismiss = { editingModel = null }) { next ->
        if (provider.models.any { it.id == next.id && it.id != originalModelId }) false
        else {
            provider = provider.copy(models = if (originalModelId == null) provider.models + next else provider.models.map { if (it.id == originalModelId) next else it })
            editingModel = null
            true
        }
    } }
    discovered?.let { found ->
        var query by remember { mutableStateOf("") }
        var selected by remember { mutableStateOf(provider.models.map { it.id }.toSet()) }
        AlertDialog(onDismissRequest = { discovered = null }, title = { Text("添加模型") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, label = { Text("搜索模型") }, singleLine = true)
                LazyColumn(Modifier.heightIn(max = 330.dp)) {
                    items(found.filter { it.contains(query, true) }, key = { it }) { id ->
                        Row(Modifier.fillMaxWidth().clickable { selected = if (id in selected) selected - id else selected + id }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(id in selected, onCheckedChange = null)
                            Text(id, Modifier.weight(1f))
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = {
            // Discovery is additive: deselecting a fetched item never removes an existing model.
            provider = provider.copy(models = provider.models + found.filter { it in selected && provider.models.none { model -> model.id == it } }.map { LlmModel(it) })
            discovered = null
        }) { Text("添加所选") } }, dismissButton = { TextButton(onClick = { discovered = null }) { Text("取消") } })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LlmModelEditor(initial: LlmModel, onDismiss: () -> Unit, onSave: (LlmModel) -> Boolean) {
    var model by remember { mutableStateOf(initial) }
    var tokens by remember { mutableStateOf(initial.maxTokens.toString()) }
    var error by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("模型配置") }, text = {
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(model.id, { model = model.copy(id = it); error = false }, label = { Text("模型 ID") }, singleLine = true)
            Text("思考协议", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LlmDialect.entries.forEach { dialect ->
                    FilterChip(model.dialect == dialect, { model = model.copy(dialect = dialect) }, label = { Text(when (dialect) {
                        LlmDialect.AUTO -> "自动"; LlmDialect.KIMI -> "Kimi"; LlmDialect.DEEPSEEK -> "DeepSeek"; else -> "OpenAI 兼容"
                    }) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Text("开启思考", Modifier.weight(1f)); Switch(model.thinking, { model = model.copy(thinking = it) }) }
            OutlinedTextField(tokens, { tokens = it.filter(Char::isDigit).take(5) }, label = { Text("最大输出 Tokens（含思考）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
            if (error) Text("该模型 ID 已存在", color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(onClick = { error = !onSave(model.copy(id = model.id.trim(), maxTokens = tokens.toInt())) },
        enabled = model.id.isNotBlank() && tokens.toIntOrNull() in 1024..65536) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
fun ChatModelPicker(viewModel: ChatViewModel, onDismiss: () -> Unit) {
    val catalog by viewModel.providers.collectAsStateWithLifecycle()
    val keyRevision by viewModel.credentialRevision.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("切换模型") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query, { query = it }, label = { Text("搜索提供商或模型") }, singleLine = true)
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                catalog.providers.forEach { provider ->
                    val models = provider.models.filter { provider.name.contains(query, true) || it.id.contains(query, true) }
                    if (models.isNotEmpty()) {
                        item(key = "provider:${provider.id}") { Text(provider.name, Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.titleSmall) }
                        items(models, key = { "${provider.id}:${it.id}" }) { model ->
                            val ready = remember(provider, keyRevision) { viewModel.hasProviderKey(provider.id) }
                            ListItem(headlineContent = { Text(model.id) }, supportingContent = if (!ready) ({ Text("请在设置中添加密钥") }) else null,
                                leadingContent = { RadioButton(catalog.activeProviderId == provider.id && catalog.activeModelId == model.id, onClick = null) },
                                modifier = Modifier.clickable(enabled = ready && !state.busy) {
                                    if (viewModel.selectModel(provider.id, model.id)) onDismiss()
                                })
                        }
                    }
                }
                if (catalog.providers.all { it.models.isEmpty() }) item { Text("请到设置 → 连接与人格添加提供商和模型") }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}
