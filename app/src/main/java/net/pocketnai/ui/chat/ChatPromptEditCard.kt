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
    var changesOpen by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().testTag("chat-prompt-edit")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(edit.title, style = MaterialTheme.typography.titleSmall)
            Text(if (edit.provider == "openai") "OpenAI 图片描述" else "NovelAI 提示词", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            edit.prompt?.let { Text(it, maxLines = 4, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
            edit.negative?.let { Text("排除词：$it", maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = { changesOpen = !changesOpen }) { Text(if (changesOpen) "收起修改对照" else "查看修改对照") }
            net.pocketnai.ui.common.AnimatedDisclosure(changesOpen) {
                edit.prompt?.let { PromptChangePreview("正向", edit.beforePrompt, it) }
                edit.negative?.let { PromptChangePreview("排除词", edit.beforeNegative.orEmpty(), it) }
            }
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
        var includePositive by remember { mutableStateOf(edit.prompt != null) }
        var includeNegative by remember { mutableStateOf(edit.negative != null) }
        var error by remember { mutableStateOf<String?>(null) }
        SettingsEditorSheet("编辑改词建议", Icons.Default.EditNote, onDismiss = { editing = false }, saveLabel = "应用到生成面板",
            saveEnabled = !state.second && (includePositive || includeNegative), onSave = {
                val ok = vm.applyPromptEdit(id, edit.copy(prompt = if (includePositive) positive else null, negative = if (includeNegative) negative else null,
                    beforePrompt = base.beforePrompt, beforeNegative = base.beforeNegative))
                if (ok) applied = true else error = "面板或服务已改变，请重新打开后核对"
                ok
            }) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (edit.prompt != null) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(includePositive, { includePositive = it }); Text("应用正向修改")
                }
                Text("当前正向内容", style = MaterialTheme.typography.labelLarge)
                Text(base.beforePrompt.ifBlank { "空" }, maxLines = 5, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(positive, { positive = it.take(16000) }, label = { Text("修改后") }, minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth())
            }
            if (edit.negative != null) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(includeNegative, { includeNegative = it }); Text("应用排除词修改")
                }
                Text("当前排除词", style = MaterialTheme.typography.labelLarge)
                Text(base.beforeNegative.orEmpty().ifBlank { "空" }, maxLines = 5, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(negative, { negative = it.take(16000) }, label = { Text("修改后排除词") }, minLines = 2, maxLines = 6, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** Linear comparison highlights the changed span without parsing prompt syntax. */
@Composable
private fun PromptChangePreview(label: String, before: String, after: String) {
    val (old, new) = remember(before, after) {
        var prefix = before.commonPrefixWith(after).length
        if (prefix > 0 && prefix < before.length && before[prefix - 1].isHighSurrogate()) prefix--
        var suffix = before.substring(prefix).commonSuffixWith(after.substring(prefix)).length
        if (suffix > 0 && suffix < before.length && before[before.length - suffix].isLowSurrogate()) suffix--
        (prefix until (before.length - suffix)) to (prefix until (after.length - suffix))
    }
    fun annotated(text: String, range: IntRange, color: androidx.compose.ui.graphics.Color) = androidx.compose.ui.text.buildAnnotatedString {
        append(text)
        if (!range.isEmpty()) addStyle(androidx.compose.ui.text.SpanStyle(background = color), range.first, range.last + 1)
    }
    Text("$label · 修改前", style = MaterialTheme.typography.labelMedium)
    Text(annotated(before.ifEmpty { "空" }, old, MaterialTheme.colorScheme.errorContainer), maxLines = 5,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
    Text("$label · 修改后", style = MaterialTheme.typography.labelMedium)
    Text(annotated(after.ifEmpty { "空" }, new, MaterialTheme.colorScheme.primaryContainer), maxLines = 5,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
}
