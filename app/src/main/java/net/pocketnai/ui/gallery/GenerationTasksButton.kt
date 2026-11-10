package net.pocketnai.ui.gallery

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import net.pocketnai.domain.model.GenerationStatus
import net.pocketnai.domain.model.GenerationSummary
import net.pocketnai.ui.settings.*

@Composable
internal fun GenerationTasksButton(generations: List<GenerationSummary>, onLocate: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf<String?>(null) }
    val active = generations.filter { it.generation.status in setOf(GenerationStatus.QUEUED, GenerationStatus.GENERATING) }
    IconButton(onClick = { open = true }, enabled = generations.isNotEmpty(), modifier = Modifier.testTag("gallery-tasks")) {
        BadgedBox(badge = { if (active.isNotEmpty()) Badge { Text(active.size.toString()) } }) { Icon(Icons.Default.FormatListBulleted, "生成任务") }
    }
    if (open) SettingsEditorSheet("生成任务", Icons.Default.FormatListBulleted, onDismiss = {
        open = false; target?.let(onLocate); target = null
    }, scrollContent = false) {
        val close = LocalSettingsSheetDismiss.current
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(active.isNotEmpty()) { while (active.isNotEmpty()) { delay(1000); now = System.currentTimeMillis() } }
        Text(if (active.isEmpty()) "当前没有进行中的任务" else "进行中 · ${active.size}", style = MaterialTheme.typography.titleSmall)
        val recent = generations.filterNot { it in active }.sortedByDescending { it.generation.updatedAt }.take(8)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(active + recent, key = { it.generation.id }) { row ->
                val g = row.generation
                val working = g.status in setOf(GenerationStatus.QUEUED, GenerationStatus.GENERATING)
                val status = when {
                    g.status == GenerationStatus.QUEUED -> "排队中"
                    g.status == GenerationStatus.GENERATING -> "生成中"
                    g.errorCode == net.pocketnai.core.ErrorCode.TIMEOUT_UNCERTAIN -> "结果待确认"
                    g.status == GenerationStatus.FAILED -> "未完成"
                    g.status == GenerationStatus.PARTIAL -> "部分完成 · 已保存 ${row.imageCount} 张"
                    row.imageCount > 0 -> "已保存 ${row.imageCount} 张"
                    else -> "已结束"
                }
                val elapsed = ((now - g.createdAt).coerceAtLeast(0) / 1000)
                val time = if (working) "提交后 ${elapsed / 60}分${elapsed % 60}秒" else android.text.format.DateFormat.format("MM-dd HH:mm", g.createdAt).toString()
                SettingsCard { ListItem(headlineContent = { Text(g.openAi?.params?.modelLabel ?: g.params.model.displayName) },
                    supportingContent = { Text("$status · $time") },
                    trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    modifier = Modifier.settingsClickable { target = g.id; close() }) }
            }
        }
    }
}
