package net.pocketnai.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.pocketnai.data.local.ChatSummary
import net.pocketnai.ui.settings.*

@Composable
internal fun ChatHistorySheet(state: ChatViewModel.UiState, vm: ChatViewModel, onDelete: (String) -> Unit, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var rename by remember { mutableStateOf<ChatSummary?>(null) }
    var chosen by remember { mutableStateOf<String?>(null) }
    var startNew by remember { mutableStateOf(false) }
    val haptics = net.pocketnai.ui.motion.rememberPocketHaptics()
    SettingsEditorSheet("历史对话", Icons.Default.History, onDismiss = {
        when {
            startNew -> vm.newConversation()
            chosen != null -> chosen?.let(vm::selectConversation)
        }
        onDismiss()
    }, scrollContent = false) {
        val close = LocalSettingsSheetDismiss.current
        FilledTonalButton(
            onClick = {
                haptics.tick()
                startNew = true
                close()
            },
            enabled = !state.busy && !state.switchingConversation,
            shape = CircleShape,
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
            modifier = Modifier.fillMaxWidth().height(46.dp).testTag("chat-new-conversation"),
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("新建对话", style = MaterialTheme.typography.labelLarge)
        }
        var queryField by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(query)) }
        LaunchedEffect(query) {
            if (query != queryField.text) queryField = androidx.compose.ui.text.input.TextFieldValue(query)
        }
        net.pocketnai.ui.common.PillSearchField(
            value = queryField,
            onValueChange = { queryField = it; query = it.text },
            placeholder = "搜索对话标题",
            clearContentDescription = "清除搜索",
            onClear = { queryField = androidx.compose.ui.text.input.TextFieldValue(""); query = "" },
            modifier = Modifier.fillMaxWidth(),
            inputModifier = Modifier.testTag("chat-history-search"),
        )
        val rows = remember(state.conversations, query) { state.conversations.filter { it.title.contains(query.trim(), ignoreCase = true) } }
        val activeId = state.current?.id
        if (rows.isEmpty()) Text(
            if (query.isBlank()) "还没有历史对话" else "没有匹配的对话",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(rows, key = { it.id }) { row ->
                val isCurrent = row.id == activeId
                var menu by remember { mutableStateOf(false) }
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (isCurrent) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f) else Color.Transparent,
                    modifier = Modifier.animateItem().fillMaxWidth(),
                ) {
                    ListItem(
                        headlineContent = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    row.title,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                if (isCurrent) {
                                    Text(
                                        "当前",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        },
                        supportingContent = {
                            Text((if (row.hasDraft) "草稿 · " else "") + android.text.format.DateFormat.format("MM-dd HH:mm", row.updatedAt))
                        },
                        leadingContent = if (row.pinnedAt != null) {
                            { Icon(Icons.Default.PushPin, "已置顶", modifier = Modifier.size(18.dp)) }
                        } else null,
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.settingsClickable(!state.busy && !state.switchingConversation) {
                            if (!isCurrent) chosen = row.id
                            close()
                        },
                        trailingContent = {
                            Box {
                                IconButton(onClick = { menu = true }, enabled = !state.busy) {
                                    Icon(Icons.Default.MoreVert, "对话操作")
                                }
                                DropdownMenu(menu, { menu = false }) {
                                    DropdownMenuItem(text = { Text(if (row.pinnedAt == null) "置顶" else "取消置顶") }, onClick = { menu = false; vm.pinConversation(row.id, row.pinnedAt == null) })
                                    DropdownMenuItem(text = { Text("重命名") }, onClick = { menu = false; rename = row })
                                    DropdownMenuItem(text = { Text("删除") }, onClick = { menu = false; onDelete(row.id) })
                                }
                            }
                        },
                    )
                }
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
