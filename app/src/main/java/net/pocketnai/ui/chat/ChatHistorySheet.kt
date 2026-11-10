package net.pocketnai.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.pocketnai.data.local.ChatSummary
import net.pocketnai.ui.settings.*

@Composable
internal fun ChatHistorySheet(state: ChatViewModel.UiState, vm: ChatViewModel, onDelete: (String) -> Unit, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var rename by remember { mutableStateOf<ChatSummary?>(null) }
    var chosen by remember { mutableStateOf<String?>(null) }
    SettingsEditorSheet("历史对话", Icons.Default.History, onDismiss = {
        chosen?.let(vm::selectConversation); onDismiss()
    }, scrollContent = false) {
        val close = LocalSettingsSheetDismiss.current
        OutlinedTextField(query, { query = it }, label = { Text("搜索对话标题") }, singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null) }, modifier = Modifier.fillMaxWidth().testTag("chat-history-search"))
        val rows = remember(state.conversations, query) { state.conversations.filter { it.title.contains(query.trim(), ignoreCase = true) } }
        if (rows.isEmpty()) Text(if (query.isBlank()) "还没有历史对话" else "没有匹配的对话", Modifier.padding(16.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(rows, key = { it.id }) { row ->
                var menu by remember { mutableStateOf(false) }
                ListItem(headlineContent = { Text(row.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text((if (row.hasDraft) "草稿 · " else "") + android.text.format.DateFormat.format("MM-dd HH:mm", row.updatedAt)) },
                    leadingContent = { if (row.pinnedAt != null) Icon(Icons.Default.PushPin, "已置顶") },
                    modifier = Modifier.animateItem().settingsClickable(!state.busy && !state.switchingConversation) { chosen = row.id; close() },
                    trailingContent = { Box {
                        IconButton(onClick = { menu = true }, enabled = !state.busy) { Icon(Icons.Default.MoreVert, "对话操作") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text(if (row.pinnedAt == null) "置顶" else "取消置顶") }, onClick = { menu = false; vm.pinConversation(row.id, row.pinnedAt == null) })
                            DropdownMenuItem(text = { Text("重命名") }, onClick = { menu = false; rename = row })
                            DropdownMenuItem(text = { Text("删除") }, onClick = { menu = false; onDelete(row.id) })
                        }
                    } })
            }
        }
    }
    rename?.let { row ->
        var title by remember(row.id) { mutableStateOf(row.title) }
        SettingsEditorSheet("重命名对话", Icons.Default.Edit, onDismiss = { rename = null }, saveEnabled = title.isNotBlank(),
            onSave = { vm.renameConversation(row.id, title); true }) {
            OutlinedTextField(title, { title = it.take(120) }, label = { Text("标题") }, modifier = Modifier.fillMaxWidth())
        }
    }
}
