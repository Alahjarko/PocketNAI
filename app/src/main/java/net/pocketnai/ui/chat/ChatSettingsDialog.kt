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
import net.pocketnai.ui.settings.*
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
    val providerKeys = remember(catalog.providers, keyRevision) {
        catalog.providers.filter { viewModel.hasProviderKey(it.id) }.map { it.id }.toSet()
    }
    ChatConnectionOverview(catalog, providerKeys, config.assistantName, state.busy,
        configurationError ?: state.error, onBack,
        onAdd = { editing = LlmProvider(UUID.randomUUID().toString(), "") },
        onEdit = { editing = it }, onDelete = { deleting = it },
        onPick = { picker = true }, onPersona = { persona = true }, onSearch = { search = true })
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
    SettingsEditorSheet("人格与工具权限", Icons.Default.Face, onDismiss,
        saveEnabled = !state.busy, onSave = { viewModel.savePreferences(config) }) {
        SettingsSection("对话伙伴")
        SettingsCard {
            Column(Modifier.padding(16.dp)) {
                OutlinedTextField(config.assistantName, { config = config.copy(assistantName = it.take(24)) },
                    Modifier.fillMaxWidth(), label = { Text("伙伴名字") }, singleLine = true)
            }
        }
        SettingsSection("工具权限") {
            net.pocketnai.ui.common.InfoButton("每次确认：生成前核对计划。单图自动：仅第一张单图自动执行。完全允许：发送消息即授权本条最多12张图片及已开启的搜索，可能产生Anlas和搜索费用，报价变化或未知时也不再询问。可随时停止后续任务；失败不重试，重启不续跑。")
        }
        SettingsCard {
            ChatToolPermission.entries.forEachIndexed { index, permission ->
                val selected = config.effectiveToolPermission == permission
                Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).selectable(selected,
                    role = androidx.compose.ui.semantics.Role.RadioButton,
                    onClick = { config = config.copy(toolPermission = permission) }).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RadioButton(selected, onClick = null)
                    Text(when (permission) { ChatToolPermission.ASK -> "每次确认"; ChatToolPermission.SINGLE_IMAGE -> "单图自动"; ChatToolPermission.FULL -> "完全允许" })
                }
                if (index < ChatToolPermission.entries.lastIndex) HorizontalDivider(Modifier.padding(start = 56.dp, end = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
            }
        }
        SettingsSection("人格与工具说明")
        SettingsCard {
            SettingsNavigationRow("人格设定", "soul.md", Icons.Default.Description, { editFile = "soul.md" })
            HorizontalDivider(Modifier.padding(start = 72.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
            SettingsNavigationRow("工具说明", "tools.md", Icons.Default.Construction, { editFile = "tools.md" })
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    editFile?.let { name -> AgentFileEditor(viewModel, name) { editFile = null } }
}

@OptIn(ExperimentalLayoutApi::class)
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
    SettingsEditorSheet(if (name == "soul.md") "人格设定" else "工具说明", Icons.Default.Description,
        onDismiss, scrollContent = false, onSave = {
            val saved = viewModel.saveAgent(name, text)
            if (!saved) error = "保存失败或文本超过 128 KiB"
            saved
        }) {
        OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth().weight(1f), label = { Text(name) })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { import.launch(arrayOf("text/*", "application/octet-stream")) }) { Text("导入 .md") }
            TextButton(onClick = { export.launch(name) }) { Text("导出 .md") }
            TextButton(onClick = { text = viewModel.defaultAgentText(name) }) { Text("恢复默认") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
