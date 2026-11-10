package net.pocketnai.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import net.pocketnai.domain.chat.PromptEdit
import net.pocketnai.ui.settings.SettingsEditorSheet
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
internal fun ChatPromptEditCard(id: String, edit: PromptEdit, vm: ChatViewModel) {
    val state by remember(vm) { vm.state.map { it.appliedEditId to it.busy }.distinctUntilChanged() }
        .collectAsState(initial = vm.state.value.appliedEditId to vm.state.value.busy)
    var editing by remember { mutableStateOf(false) }
    var applied by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().testTag("chat-prompt-edit")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(edit.title, style = MaterialTheme.typography.titleSmall)
            Text(if (edit.provider == "openai") "OpenAI 图片描述" else "NovelAI 提示词", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            edit.prompt?.let { Text(it, maxLines = 4, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
            edit.negative?.let { Text("排除词：$it", maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { if (vm.applyPromptEdit(id, edit)) applied = true else editing = true }, enabled = !state.second && state.first != id) { Text("应用到生成面板") }
                TextButton(onClick = { editing = true }, enabled = !state.second) { Text("编辑") }
            }
            if (state.first == id) Row {
                Text("已应用", Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
                TextButton(onClick = { vm.undoPromptEdit(); applied = false }) { Text("撤销应用") }
            }
        }
    }
    if (editing) {
        val base = remember { vm.promptSnapshot() }
        var positive by remember { mutableStateOf(edit.prompt.orEmpty()) }
        var negative by remember { mutableStateOf(edit.negative.orEmpty()) }
        var error by remember { mutableStateOf<String?>(null) }
        SettingsEditorSheet("编辑改词建议", Icons.Default.EditNote, onDismiss = { editing = false }, saveLabel = "应用到生成面板",
            saveEnabled = !state.second, onSave = {
                val ok = vm.applyPromptEdit(id, edit.copy(prompt = edit.prompt?.let { positive }, negative = edit.negative?.let { negative },
                    beforePrompt = base.beforePrompt, beforeNegative = base.beforeNegative))
                if (ok) applied = true else error = "面板或服务已改变，请重新打开后核对"
                ok
            }) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (edit.prompt != null) {
                Text("当前正向内容", style = MaterialTheme.typography.labelLarge)
                Text(base.beforePrompt.ifBlank { "空" }, maxLines = 5, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(positive, { positive = it.take(16000) }, label = { Text("修改后") }, minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth())
            }
            if (edit.negative != null) {
                Text("当前排除词", style = MaterialTheme.typography.labelLarge)
                Text(base.beforeNegative.orEmpty().ifBlank { "空" }, maxLines = 5, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(negative, { negative = it.take(16000) }, label = { Text("修改后排除词") }, minLines = 2, maxLines = 6, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
