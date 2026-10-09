package net.pocketnai.ui.artistlab

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import net.pocketnai.ui.motion.sharedImage
import net.pocketnai.ui.motion.sharedImageRequest
import net.pocketnai.ui.motion.LocalImageMotion
import net.pocketnai.ui.motion.ImageOriginPreview
import net.pocketnai.domain.model.GenerationParams
import net.pocketnai.domain.artistlab.ArtistLabConfig
import net.pocketnai.domain.artistlab.ArtistLabForm
import net.pocketnai.data.settings.GenerationDraftCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.random.Random

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistLabScreen(vm: ArtistLabViewModel, currentParams: GenerationParams, resolve: (String) -> File, onOpen: (String) -> Unit) {
    val imageMotion = LocalImageMotion.current
    val runs by vm.runs.collectAsStateWithLifecycle()
    val selected by vm.selectedRun.collectAsStateWithLifecycle()
    val cards by vm.cards.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val pausing by vm.pausing.collectAsStateWithLifecycle()
    val ready by vm.ready.collectAsStateWithLifecycle()
    val catalogCount by vm.catalogCount.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val approval by vm.approval.collectAsStateWithLifecycle()
    val form by vm.form.collectAsStateWithLifecycle()
    var configOpen by rememberSaveable { mutableStateOf(false) }
    var catalogOpen by rememberSaveable { mutableStateOf(false) }
    var historyOpen by remember { mutableStateOf(false) }
    var favoritesOpen by remember { mutableStateOf(false) }
    var cleanupOpen by remember { mutableStateOf(false) }
    var onlyFavorites by rememberSaveable { mutableStateOf(false) }
    var focused by remember { mutableStateOf<LabCard?>(null) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.confirm() }
    val favorites = remember(cards) { cards.filter { it.favorite && it.image != null } }
    val shown = remember(cards, onlyFavorites) {
        val visible = cards.filter { it.statusVisible() && (!onlyFavorites || it.favorite) }
        if (onlyFavorites) visible else visible.filter { it.favorite } + visible.filterNot { it.favorite }
    }
    val done = remember(cards) { cards.count { it.draw.status == "SUCCEEDED" } }
    val processed = remember(cards) { cards.count { it.draw.status in listOf("SUCCEEDED", "FAILED", "UNCERTAIN") } }
    val pending = remember(cards) { cards.count { it.draw.status == "PLANNED" } }
    val cleanable = remember(cards) { cards.count { it.image != null && !it.favorite } }
    val run = runs.firstOrNull { it.id == selected }
    val snapshot = remember(run?.configJson) { runCatching {
        GenerationDraftCodec.decode(Json.decodeFromString<ArtistLabConfig>(run!!.configJson).paramsJson)?.params
    }.getOrNull() }
    LaunchedEffect(approval) { if (approval != null) configOpen = false }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("抽卡实验室", style = MaterialTheme.typography.headlineSmall)
                Text(if (ready) "$catalogCount 位画师" else "词库加载中",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { catalogOpen = true }, enabled = ready) { Icon(Icons.Default.Palette, "画师库") }
            IconButton(onClick = { historyOpen = true }, enabled = !busy) { Icon(Icons.Default.History, "实验记录") }
            IconButton(onClick = { favoritesOpen = true }) { Icon(Icons.Default.Bookmarks, "画师串收藏") }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.clearMessage(); configOpen = true }, enabled = ready && !busy && catalogCount > 0, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("新建实验")
            }
            if (busy) OutlinedButton(onClick = vm::pause, enabled = !pausing) { Text(if (pausing) "等待暂停" else "暂停") }
            else if (pending > 0) OutlinedButton(onClick = vm::prepareResume) { Text("继续 $pending 张") }
        }
        if (cards.isNotEmpty()) {
            snapshot?.let { Text("${it.model.displayName} · ${it.size.label} · Seed ${it.baseSeed}",
                Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("完成 $processed / ${cards.size} · 成功 $done", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                Text("收藏 ${favorites.size}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            }
            LinearProgressIndicator(progress = { processed.toFloat() / cards.size }, modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp))
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(!onlyFavorites, { onlyFavorites = false }, { Text("全部结果") })
                FilterChip(onlyFavorites, { onlyFavorites = true }, { Text("只看收藏") })
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { cleanupOpen = true }, enabled = !busy && cleanable > 0) {
                    Icon(Icons.Default.DeleteSweep, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("清理未收藏")
                }
            }
        }
        val notice = message.ifBlank { run?.message.orEmpty() }
        if (notice.isNotBlank()) Text(notice, Modifier.padding(16.dp, 4.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (favorites.isNotEmpty() && !onlyFavorites) Text("收藏图片置顶", Modifier.padding(16.dp, 4.dp),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        if (shown.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.Casino, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                Text(if (onlyFavorites) "还没有收藏图片" else if (busy) "第一张正在准备中" else "还没有实验结果", style = MaterialTheme.typography.titleMedium)
            }
        } else LazyVerticalGrid(columns = GridCells.Adaptive(156.dp), modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(shown, key = { it.draw.id }, contentType = { "draw" }) { card ->
                Card(border = if (card.favorite) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null) {
                    Row(Modifier.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("#${card.draw.ordinal + 1}", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                        IconButton(onClick = { focused = card }) { Icon(Icons.Default.Info, "查看画师串") }
                        IconButton(onClick = { vm.favorite(card) }, enabled = card.image != null && !cleanupOpen) {
                            Icon(if (card.favorite) Icons.Default.Star else Icons.Default.StarBorder, if (card.favorite) "取消图片收藏" else "收藏图片和画师串",
                                tint = if (card.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (card.image != null) AsyncImage(sharedImageRequest(resolve(card.image.relativePath)), "第 ${card.draw.ordinal + 1} 张",
                        Modifier.fillMaxWidth().aspectRatio(card.image.aspectRatio.coerceIn(0.65f, 1.5f)).sharedImage(card.image.imageId).clickable { imageMotion?.preview = ImageOriginPreview(card.image.imageId, resolve(card.image.relativePath), card.image.aspectRatio, "抽卡结果"); onOpen(card.image.imageId) }, contentScale = ContentScale.Crop)
                    else Box(Modifier.fillMaxWidth().height(90.dp), contentAlignment = Alignment.Center) {
                        Text(if (card.draw.status == "RUNNING") "生成中…" else if (card.draw.status == "FAILED") "生成失败" else "结果待确认")
                    }
                    Text(card.mix.artists.joinToString(", ") { it.tag.removePrefix("artist: ") }, Modifier.padding(10.dp, 0.dp, 10.dp, 10.dp),
                        style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    if (catalogOpen) ArtistCatalogDialog(vm, onDismiss = { catalogOpen = false })
    if (configOpen) LabConfigDialog(currentParams, form, message, vm::updateForm, { configOpen = false }) { prompt, negative, seed, count, artists, min, max ->
        vm.prepare(prompt, negative, seed, count, artists, min, max)
    }
    approval?.let { a -> AlertDialog(onDismissRequest = vm::dismissApproval, title = { Text("确认${if (a.runId != null) "继续" else "开始"}抽卡") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${a.remaining} 张 · 每张 ${a.config.artistCount} 位画师\n权重 ${a.config.minTicks / 20.0}～${a.config.maxTicks / 20.0}，步长 0.05")
            Text("${a.params.model.displayName} · ${a.params.size.label}\n${a.params.steps} Steps · Guidance ${a.params.guidance}\nSeed ${a.params.baseSeed}")
            Text(a.quote.labDescription(a.remaining), color = MaterialTheme.colorScheme.primary)
            Text("每次只请求 1 张，完成后等待 1～2 秒。可切换应用或锁屏继续，通知栏可暂停。失败或费用变化会暂停，已发送的请求不会重试。", style = MaterialTheme.typography.bodySmall)
        } }, confirmButton = { TextButton(onClick = {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else vm.confirm()
        }) { Text("确认 ${a.remaining} 次请求") } },
        dismissButton = { TextButton(onClick = vm::dismissApproval) { Text("取消") } }) }
    if (cleanupOpen) AlertDialog(onDismissRequest = { cleanupOpen = false }, title = { Text("清理本批未收藏图片？") },
        text = { Text("删除本批 $cleanable 张未收藏图片，保留 ${favorites.size} 张收藏图片和所有画师串。其它批次与普通画廊图片不受影响。图片文件删除后无法撤销。") },
        confirmButton = { TextButton(onClick = { cleanupOpen = false; vm.cleanup() }) { Text("清理未收藏") } },
        dismissButton = { TextButton(onClick = { cleanupOpen = false }) { Text("取消") } })
    focused?.let { card -> AlertDialog(onDismissRequest = { focused = null }, title = { Text("第 ${card.draw.ordinal + 1} 张的画师串") },
        text = { SelectionContainer { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(card.mix.prompt); if (card.draw.message.isNotBlank()) Text("\n${card.draw.message}")
        } } }, confirmButton = { TextButton(onClick = { clipboard.setText(AnnotatedString(card.mix.prompt)); focused = null }) { Text("复制画师串") } },
        dismissButton = { TextButton(onClick = { focused = null }) { Text("关闭") } }) }
    if (historyOpen) AlertDialog(onDismissRequest = { historyOpen = false }, title = { Text("实验记录") },
        text = { LazyColumn(Modifier.heightIn(max = 420.dp)) {
            if (runs.isEmpty()) item { Text("还没有实验记录") }
            items(runs, key = { it.id }) { item ->
                TextButton(onClick = { vm.select(item.id); historyOpen = false }) {
                    Text(SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(item.createdAt)) + " · " +
                        when(item.status) { "COMPLETED" -> "已完成"; "RUNNING" -> "进行中"; else -> "已暂停" })
                }
            }
        } }, confirmButton = { TextButton(onClick = { historyOpen = false }) { Text("关闭") } })
    if (favoritesOpen) ArtistMixFavoritesDialog(vm, resolve, onDismiss = { favoritesOpen = false }, onOpen = {
        favoritesOpen = false
        onOpen(it)
    })
}

private fun LabCard.statusVisible() = image != null || draw.status in listOf("RUNNING", "UNCERTAIN", "FAILED")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LabConfigDialog(params: GenerationParams, form: ArtistLabForm?, error: String, onChange: (ArtistLabForm) -> Unit, onClose: () -> Unit,
                            onPrepare: (String, String, String, String, Int, Int, Int) -> Unit) {
    var prompt by rememberSaveable { mutableStateOf(form?.prompt ?: params.prompt) }
    var negative by rememberSaveable { mutableStateOf(form?.negative ?: params.negativePrompt) }
    var seed by rememberSaveable { mutableStateOf(form?.seed ?: if (params.baseSeed > 0) params.baseSeed.toString() else Random.nextLong(0, GenerationParams.MAX_SEED + 1).toString()) }
    var count by rememberSaveable { mutableStateOf(form?.count ?: "100") }
    var artists by rememberSaveable { mutableIntStateOf(form?.artists ?: 3) }
    var min by rememberSaveable { mutableIntStateOf(form?.minTicks ?: 10) }
    var max by rememberSaveable { mutableIntStateOf(form?.maxTicks ?: 30) }
    LaunchedEffect(prompt, negative, seed, count, artists, min, max) { onChange(ArtistLabForm(prompt, negative, seed, count, artists, min, max)) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.safeDrawingPadding().imePadding().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("新建抽卡实验", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, "关闭") }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Text("从首页复制参数 · 单张文生图\n${params.model.displayName} · ${params.size.label} · ${params.steps} Steps · Guidance ${params.guidance}\n不携带首页参考图与独立角色。", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(prompt, { prompt = it }, Modifier.fillMaxWidth(), label = { Text("固定基础提示词（不含 artist:）") }, minLines = 2, maxLines = 6)
                    OutlinedTextField(negative, { negative = it }, Modifier.fillMaxWidth(), label = { Text("固定负面提示词") }, minLines = 1, maxLines = 4)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(seed, { seed = it }, Modifier.weight(1f), label = { Text("固定 Seed") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        IconButton(onClick = { seed = Random.nextLong(0, GenerationParams.MAX_SEED + 1).toString() }) { Icon(Icons.Default.Shuffle, "随机一个固定种子") }
                    }
                    Text("每张抽取 $artists 位画师", style = MaterialTheme.typography.titleSmall)
                    Slider(artists.toFloat(), { artists = it.roundToInt() }, valueRange = 1f..10f, steps = 8)
                    Text("单画师权重 ${min / 20.0}～${max / 20.0} · 步长 0.05", style = MaterialTheme.typography.titleSmall)
                    RangeSlider(min.toFloat()..max.toFloat(), { min = it.start.roundToInt(); max = it.endInclusive.roundToInt() }, valueRange = 10f..30f, steps = 19)
                    OutlinedTextField(count, { count = it }, Modifier.fillMaxWidth(), label = { Text("抽卡次数（1～10000）") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    Text("每张使用相同 Seed 和基础提示词，只随机画师组合与权重。画师标签来自公开数据，NovelAI 对不同画师的识别与画风表现可能不同。", style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = { onPrepare(prompt, negative, seed, count, artists, min, max) }, Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                    Text("核对参数与费用")
                }
            }
        }
    }
}
