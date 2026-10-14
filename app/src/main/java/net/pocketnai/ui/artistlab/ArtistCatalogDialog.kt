package net.pocketnai.ui.artistlab

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import net.pocketnai.data.artistlab.ArtistCatalogReader
import net.pocketnai.domain.artistlab.ArtistCatalogSelection
import net.pocketnai.data.export.ArtistCatalogExporter
import net.pocketnai.data.export.ArtistCatalogExportFormat
import net.pocketnai.domain.artistlab.ArtistCatalogEntry
import net.pocketnai.domain.artistlab.ArtistArtworkPreview

@Composable
fun ArtistCatalogDialog(vm: ArtistLabViewModel, onDismiss: () -> Unit) {
    val entries by vm.catalogEntries.collectAsStateWithLifecycle()
    val excluded by vm.excludedArtists.collectAsStateWithLifecycle()
    val importing by vm.catalogImporting.collectAsStateWithLifecycle()
    val catalogMessage by vm.catalogMessage.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var removed by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var focused by remember { mutableStateOf<Pair<ArtistCatalogEntry, ArtistArtworkPreview>?>(null) }
    var details by remember { mutableStateOf<ArtistCatalogEntry?>(null) }
    var pending by remember { mutableStateOf<ArtistCatalogSelection?>(null) }
    var reading by remember { mutableStateOf(false) }
    var importMessage by remember { mutableStateOf("") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exporter = remember(context) { ArtistCatalogExporter(context) }
    val reader = remember(context) { ArtistCatalogReader(context) }
    var exportMenu by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var exportMessage by remember { mutableStateOf("") }
    fun export(uri: Uri?, format: ArtistCatalogExportFormat) {
        if (uri == null || exporting) return
        val snapshot = entries.toList(); val excludedSnapshot = excluded.toSet()
        exporting = true; exportMessage = ""
        scope.launch {
            try { exporter.export(uri, format, snapshot, excludedSnapshot); exportMessage = "已导出" }
            catch (_: Exception) { exportMessage = "导出未完成，请重试" }
            finally { exporting = false }
        }
    }
    val textExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { export(it, ArtistCatalogExportFormat.TEXT) }
    val jsonExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { export(it, ArtistCatalogExportFormat.JSON) }
    val zipExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { export(it, ArtistCatalogExportFormat.ZIP) }
    val templateExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { export(it, ArtistCatalogExportFormat.TEMPLATE) }
    val fileImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !reading && !importing && !busy) {
            reading = true; importMessage = ""
            scope.launch {
                try { pending = reader.read(uri) }
                catch (e: CancellationException) { throw e }
                catch (e: IllegalArgumentException) { importMessage = e.message ?: "文件格式不正确" }
                catch (_: Exception) { importMessage = "无法读取画师文件，请重新选择" }
                finally { reading = false }
            }
        }
    }
    val removedCount = remember(entries, excluded) { entries.count { it.tag in excluded } }
    val existingTags = remember(entries) { entries.map { it.tag }.toSet() }
    val visible = remember(entries, excluded, removed, query) { entries.filter {
        (it.tag in excluded) == removed && (it.name.contains(query, true) || it.tag.contains(query, true))
    } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(0.92f).padding(horizontal = 12.dp),
            shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("画师库", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { fileImport.launch(arrayOf("text/plain", "application/json", "application/octet-stream")) },
                        enabled = !reading && !importing && !exporting && !busy) { Text(if (reading || importing) "导入中" else "导入") }
                    Box {
                        TextButton(onClick = { exportMenu = true }, enabled = !exporting && !reading && !importing) { Text(if (exporting) "导出中" else "导出") }
                        DropdownMenu(exportMenu, { exportMenu = false }) {
                            DropdownMenuItem(text = { Text("画师名单 TXT") }, onClick = { exportMenu = false; textExport.launch("PocketNAI-画师名单.txt") })
                            DropdownMenuItem(text = { Text("自定义配置 JSON") }, onClick = { exportMenu = false; jsonExport.launch("PocketNAI-画师配置.json") })
                            DropdownMenuItem(text = { Text("含缩略图 ZIP") }, onClick = { exportMenu = false; zipExport.launch("PocketNAI-画师库.zip") })
                            DropdownMenuItem(text = { Text("供 AI 填写的来源模板") }, onClick = { exportMenu = false; templateExport.launch("PocketNAI-画师来源模板.json") })
                        }
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "关闭画师库") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!removed, { removed = false }, { Text("已启用 ${entries.size - removedCount}") })
                    FilterChip(removed, { removed = true }, { Text("已移出 $removedCount") })
                }
                Text("只影响新实验，已有批次和收藏保留。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (exportMessage.isNotEmpty()) Text(exportMessage, style = MaterialTheme.typography.bodySmall)
                if (importMessage.isNotEmpty() || catalogMessage.isNotEmpty()) Text(importMessage.ifEmpty { catalogMessage }, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(query, { query = it }, label = { Text("搜索画师") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                if (visible.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(if (removed) "没有已移出的画师" else "没有匹配的画师")
                } else LazyVerticalGrid(GridCells.Adaptive(140.dp), Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(visible, key = { it.tag }) { artist ->
                        var sample by rememberSaveable(artist.tag) { mutableIntStateOf(0) }
                        Card {
                            val preview = artist.previews.getOrNull(sample.coerceAtMost(artist.previews.lastIndex).coerceAtLeast(0))
                            Box(Modifier.fillMaxWidth()) {
                                if (preview != null) AsyncImage("file:///android_asset/artist-lab/previews/${preview.asset}", "${artist.name} 代表作",
                                    Modifier.fillMaxWidth().aspectRatio(1f).clickable { focused = artist to preview }, contentScale = ContentScale.Fit)
                                else Box(Modifier.fillMaxWidth().aspectRatio(1f).clickable { details = artist }, contentAlignment = Alignment.Center) {
                                    Text("暂无代表作\n查看来源", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (artist.previews.size > 1) FilledTonalIconButton(onClick = { sample = (sample + 1) % artist.previews.size },
                                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).size(32.dp)) {
                                    Icon(Icons.Default.SwapHoriz, "切换 ${artist.name} 代表作", Modifier.size(18.dp))
                                }
                            }
                            Text(artist.name, Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(artist.tag.removePrefix("artist: "), Modifier.padding(horizontal = 8.dp),
                                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (artist.sourceName.isNotBlank()) Text(artist.sourceName, Modifier.padding(horizontal = 8.dp),
                                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            TextButton(onClick = { vm.setArtistEnabled(artist.tag, removed) }, enabled = !importing,
                                modifier = Modifier.align(Alignment.End)) {
                                Text(if (removed) "重新启用" else "移出抽卡")
                            }
                        }
                    }
                }
            }
        }
    }
    pending?.let { selection ->
        val added = selection.artists.filterNot { it.tag in existingTags }
        AlertDialog(onDismissRequest = { pending = null }, title = { Text("导入画师来源") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("新增 ${added.size} 位，已有 ${selection.artists.size - added.size} 位。已有画师的启用状态会保留。")
                Text("${added.count { it.tag in selection.excluded }} 位新画师按文件设置保持移出。")
                Text(selection.artists.take(8).joinToString("\n") { it.name })
                Text("导入名单不会自动核实作者身份、活跃度、人气或模型效果。")
            }
        }, confirmButton = { TextButton(onClick = { vm.importCatalog(selection); pending = null; importMessage = "" },
            enabled = !busy && !importing && added.isNotEmpty()) { Text("加入画师库") } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("取消") } })
    }
    details?.let { artist ->
        AlertDialog(onDismissRequest = { details = null }, title = { Text(artist.name) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(artist.tag)
                if (artist.sourceName.isNotBlank()) Text("来源：${artist.sourceName}")
                if (artist.notes.isNotBlank()) Text(artist.notes)
            }
        }, confirmButton = { if (artist.profileUrl.isNotBlank()) TextButton(onClick = {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(artist.profileUrl))) }
                .onFailure { importMessage = "没有可打开此链接的应用" }
        }) { Text("打开画师主页") } }, dismissButton = { TextButton(onClick = { details = null }) { Text("关闭") } })
    }
    focused?.let { (artist, preview) ->
        AlertDialog(onDismissRequest = { focused = null }, title = { Text(artist.name) }, text = {
            AsyncImage("file:///android_asset/artist-lab/previews/${preview.asset}", "${artist.name} 代表作",
                Modifier.fillMaxWidth().aspectRatio(1f), contentScale = ContentScale.Fit)
        }, confirmButton = { TextButton(onClick = {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(preview.sourceUrl)))
        }) { Text("查看原作") } }, dismissButton = { TextButton(onClick = { focused = null }) { Text("关闭") } })
    }
}
