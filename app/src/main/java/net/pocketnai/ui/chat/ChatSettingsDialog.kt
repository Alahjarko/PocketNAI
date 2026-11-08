package net.pocketnai.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.pocketnai.domain.chat.*
import java.util.UUID

/** Settings owns all connection/persona editors; chat only offers model selection. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatSettingsScreen(viewModel: ChatViewModel, onBack: () -> Unit) {
    val catalog by viewModel.providers.collectAsStateWithLifecycle()
    val keyRevision by viewModel.credentialRevision.collectAsStateWithLifecycle()
    val configurationError by viewModel.configurationError.collectAsStateWithLifecycle()
    val config by viewModel.config.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<LlmProvider?>(null) }
    var deleting by remember { mutableStateOf<LlmProvider?>(null) }
    var persona by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    val selectedProvider = catalog.providers.firstOrNull { it.id == catalog.activeProviderId }
    Scaffold(topBar = {
        TopAppBar(title = { Text("连接与人格") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回设置") }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.busy) item { Text("正在回复，结束或停止后可修改连接配置", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item {
                Card {
                    ListItem(headlineContent = { Text("当前模型") }, supportingContent = { Text(if (selectedProvider != null && config.model.isNotBlank()) "${selectedProvider.name} · ${config.model}" else "尚未选择模型") },
                        trailingContent = { Icon(Icons.Default.SwapHoriz, null) }, modifier = Modifier.clickable(enabled = !state.busy) { picker = true })
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("提供商", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { editing = LlmProvider(UUID.randomUUID().toString(), "") }, enabled = !state.busy) { Icon(Icons.Default.Add, null); Text("添加提供商") }
                }
            }
            if (catalog.providers.isEmpty()) item { Text("添加提供商后，可保存多个模型并在对话中快速切换。", style = MaterialTheme.typography.bodyMedium) }
            items(catalog.providers, key = { it.id }) { provider ->
                val hasKey = remember(provider, keyRevision) { viewModel.hasProviderKey(provider.id) }
                Card {
                    ListItem(headlineContent = { Text(provider.name) }, supportingContent = {
                        Text("${provider.models.size} 个模型 · ${if (hasKey) "密钥已保存" else "未配置密钥"}")
                    }, trailingContent = {
                        Row {
                            IconButton(onClick = { editing = provider }, enabled = !state.busy) { Icon(Icons.Default.Edit, "编辑 ${provider.name}") }
                            IconButton(onClick = { deleting = provider }, enabled = !state.busy) { Icon(Icons.Default.DeleteOutline, "删除 ${provider.name}") }
                        }
                    }, modifier = Modifier.clickable(enabled = !state.busy) { editing = provider })
                }
            }
            item {
                Card {
                    ListItem(headlineContent = { Text("人格与工具权限") }, supportingContent = { Text(config.assistantName) },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) }, modifier = Modifier.clickable(enabled = !state.busy) { persona = true })
                    HorizontalDivider()
                    ListItem(headlineContent = { Text("联网搜索") }, supportingContent = { Text("DeepSeek / Tavily") },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) }, modifier = Modifier.clickable(enabled = !state.busy) { search = true })
                }
            }
            (configurationError ?: state.error)?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
        }
    }
    editing?.let { provider -> LlmProviderEditor(viewModel, provider) { editing = null } }
    deleting?.let { provider ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除提供商？") },
            text = { Text("将移除“${provider.name}”的连接、已保存密钥和模型列表。对话历史、图片和人格文件会保留。若正在使用此提供商，需要重新选择模型。") },
            confirmButton = { TextButton(onClick = { if (viewModel.deleteProvider(provider.id)) deleting = null }, enabled = !state.busy) { Text("确认删除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } })
    }
    if (persona) ChatPreferencesDialog(viewModel) { persona = false }
    if (search) ChatSearchSettingsDialog(viewModel) { search = false }
    if (picker) ChatModelPicker(viewModel) { picker = false }
}

@Composable
fun ChatPreferencesDialog(viewModel: ChatViewModel, onDismiss: () -> Unit) {
    val stored by viewModel.config.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    var config by remember { mutableStateOf(stored) }
    var editFile by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("人格与工具权限") }, text = {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(config.assistantName, { config = config.copy(assistantName = it.take(24)) }, label = { Text("伙伴名字") }, singleLine = true)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("工具权限", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                net.pocketnai.ui.common.InfoButton("每次确认：生成前核对计划。单图自动：仅第一张单图自动执行。完全允许：发送消息即授权本条最多12张图片及已开启的搜索，可能产生Anlas和搜索费用，报价变化或未知时也不再询问。可随时停止后续任务；失败不重试，重启不续跑。")
            }
            ChatToolPermission.entries.forEach { permission ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(config.effectiveToolPermission == permission,
                    role = androidx.compose.ui.semantics.Role.RadioButton, onClick = { config = config.copy(toolPermission = permission) }).padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RadioButton(config.effectiveToolPermission == permission, onClick = null)
                    Text(when (permission) { ChatToolPermission.ASK -> "每次确认"; ChatToolPermission.SINGLE_IMAGE -> "单图自动"; ChatToolPermission.FULL -> "完全允许" })
                }
            }
            Text("人格与工具说明", style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = { editFile = "soul.md" }) { Text("编辑 soul.md") }
            TextButton(onClick = { editFile = "tools.md" }) { Text("编辑 tools.md") }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = { if (viewModel.savePreferences(config)) onDismiss() }, enabled = !state.busy) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
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
