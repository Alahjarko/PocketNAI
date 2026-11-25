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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.pocketnai.data.local.ArtistLabRunEntity
import net.pocketnai.data.settings.GenerationDraftCodec
import net.pocketnai.domain.artistlab.ArtistCatalogEntry
import net.pocketnai.domain.artistlab.ArtistLabConfig
import net.pocketnai.domain.artistlab.ArtistLabForm
import net.pocketnai.domain.artistlab.ArtistLabPlanner
import net.pocketnai.domain.model.GenerationParams
import net.pocketnai.domain.model.shortDisplayName
import net.pocketnai.domain.prompt.ArtistPromptSplitter
import net.pocketnai.ui.common.onSecondaryClick
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.random.Random

private val labConfigJson = Json { ignoreUnknownKeys = true }

private val StringListSaver = Saver<List<String>, ArrayList<String>>(
    save = { ArrayList(it) },
    restore = { it.toList() },
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ArtistLabScreen(
    vm: ArtistLabViewModel,
    currentParams: GenerationParams,
    resolve: (String) -> File,
    onApplyArtistChain: ((String) -> Unit)? = null,
    onOpen: (String) -> Unit,
) {
    val runs by vm.runs.collectAsStateWithLifecycle()
    val selected by vm.selectedRun.collectAsStateWithLifecycle()
    val cards by vm.cards.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val pausing by vm.pausing.collectAsStateWithLifecycle()
    val ready by vm.ready.collectAsStateWithLifecycle()
    val catalogCount by vm.catalogCount.collectAsStateWithLifecycle()
    val catalogEntries by vm.catalogEntries.collectAsStateWithLifecycle()
    val excludedArtists by vm.excludedArtists.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val approval by vm.approval.collectAsStateWithLifecycle()
    val form by vm.form.collectAsStateWithLifecycle()
    var configOpen by rememberSaveable { mutableStateOf(false) }
    var catalogOpen by rememberSaveable { mutableStateOf(false) }
    var historyOpen by remember { mutableStateOf(false) }
    var favoritesOpen by remember { mutableStateOf(false) }
    var cleanupOpen by remember { mutableStateOf(false) }
    var onlyFavorites by rememberSaveable { mutableStateOf(false) }
    var focusedId by rememberSaveable { mutableStateOf<String?>(null) }
    var stageCardId by rememberSaveable { mutableStateOf<String?>(null) }
    var stageOrderIds by rememberSaveable(stateSaver = StringListSaver) { mutableStateOf<List<String>>(emptyList()) }
    var stageOnlyFavorites by rememberSaveable { mutableStateOf(false) }
    var compareMode by rememberSaveable { mutableStateOf(false) }
    var compareIds by rememberSaveable(stateSaver = StringListSaver) { mutableStateOf<List<String>>(emptyList()) }
    var compareDialogOpen by remember { mutableStateOf(false) }
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
    val runConfig = remember(run?.configJson) {
        run?.configJson?.let { runCatching { labConfigJson.decodeFromString<ArtistLabConfig>(it) }.getOrNull() }
    }
    val snapshot = remember(runConfig?.paramsJson) {
        runConfig?.paramsJson?.let { runCatching { GenerationDraftCodec.decode(it)?.params }.getOrNull() }
    }
    val lockedArtists = form?.lockedArtists.orEmpty()
    val focused = remember(cards, focusedId) { focusedId?.let { id -> cards.firstOrNull { it.draw.id == id } } }
    val compareCards = remember(cards, compareIds) {
        val byId = cards.associateBy { it.draw.id }
        compareIds.mapNotNull { id -> byId[id]?.takeIf { it.image != null } }
    }

    LaunchedEffect(approval) { if (approval != null) configOpen = false }
    LaunchedEffect(selected) {
        stageCardId = null
        stageOrderIds = emptyList()
        stageOnlyFavorites = false
        focusedId = null
        compareMode = false
        compareIds = emptyList()
        compareDialogOpen = false
    }

    val openStageInspector: (LabCard) -> Unit = { card ->
        if (card.image != null) {
            // 打开大图检视时冻结当前浏览顺序：检视过程中收藏/取消收藏只更新状态，不再触发置顶重排把进度拉回第 1 张。
            stageOrderIds = shown.asSequence().filter { it.image != null }.map { it.draw.id }.toList()
            stageOnlyFavorites = onlyFavorites
            stageCardId = card.draw.id
        }
    }
    val closeStageInspector: () -> Unit = {
        stageCardId = null
        stageOrderIds = emptyList()
        stageOnlyFavorites = false
    }
    val setOnlyFavorites: (Boolean) -> Unit = { next ->
        if (onlyFavorites != next) {
            onlyFavorites = next
            if (stageCardId != null) {
                val visible = cards.filter { it.statusVisible() && (!next || it.favorite) }
                val nextShown = if (next) visible else visible.filter { it.favorite } + visible.filterNot { it.favorite }
                val nextOrder = nextShown.asSequence().filter { it.image != null }.map { it.draw.id }.toList()
                stageOnlyFavorites = next
                stageOrderIds = nextOrder
                if (stageCardId !in nextOrder) {
                    stageCardId = nextOrder.firstOrNull()
                }
            }
        }
    }

    val toggleCompareSelection: (LabCard) -> Unit = { card ->
        if (card.image != null) {
            if (card.draw.id in compareIds) {
                compareIds = compareIds - card.draw.id
            } else if (compareIds.size < 4) {
                compareIds = compareIds + card.draw.id
            } else {
                vm.showMessage("AB 对比最多选择 4 张已完成图片")
            }
        }
    }

    val toggleCompareMode: () -> Unit = {
        compareMode = !compareMode
        if (!compareMode) {
            compareIds = emptyList()
            compareDialogOpen = false
        } else {
            closeStageInspector()
        }
    }

    val notice = message.ifBlank { run?.message.orEmpty() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val isWide = maxWidth >= 720.dp
        if (isWide) {
            Row(Modifier.fillMaxSize()) {
                // 左栏（300.dp）：标题、快捷操作、当前实验进度、筛选/AB/清理与嵌入式实验记录列表
                Column(Modifier.width(300.dp).fillMaxHeight()) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("抽卡实验室", style = MaterialTheme.typography.headlineSmall)
                            Text(
                                if (ready) "$catalogCount 位画师" else "词库加载中",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { catalogOpen = true }, enabled = ready) { Icon(Icons.Default.Palette, "画师库") }
                        IconButton(onClick = { favoritesOpen = true }) { Icon(Icons.Default.Bookmarks, "画师串收藏") }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = { vm.clearMessage(); configOpen = true },
                            enabled = ready && !busy && catalogCount > 0,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("新建实验")
                        }
                        if (busy) {
                            OutlinedButton(onClick = vm::pause, enabled = !pausing) { Text(if (pausing) "等待暂停" else "暂停") }
                        } else if (pending > 0) {
                            OutlinedButton(onClick = vm::prepareResume) { Text("继续 $pending 张") }
                        }
                    }
                    if (cards.isNotEmpty()) {
                        RunSummarySection(
                            snapshot = snapshot,
                            runConfig = runConfig,
                            processed = processed,
                            total = cards.size,
                            done = done,
                            favoritesCount = favorites.size,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                        FlowRow(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            FilterChip(!onlyFavorites, { setOnlyFavorites(false) }, { Text("全部") })
                            FilterChip(onlyFavorites, { setOnlyFavorites(true) }, { Text("仅收藏") })
                            FilterChip(
                                selected = compareMode,
                                onClick = toggleCompareMode,
                                label = { Text(if (compareMode) "AB (${compareCards.size}/4)" else "AB 对比") },
                                leadingIcon = { Icon(Icons.Default.Compare, null, Modifier.size(16.dp)) },
                            )
                            TextButton(
                                onClick = { cleanupOpen = true },
                                enabled = !busy && cleanable > 0,
                                contentPadding = PaddingValues(horizontal = 8.dp),
                            ) {
                                Icon(Icons.Default.DeleteSweep, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("清理未收藏")
                            }
                        }
                        if (compareMode) {
                            CompareSelectionBar(
                                selectedCount = compareCards.size,
                                onStartCompare = { compareDialogOpen = true },
                                onClear = { compareIds = emptyList() },
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            )
                        }
                    }
                    if (notice.isNotBlank()) {
                        Text(
                            notice,
                            Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider(Modifier.padding(top = 6.dp))
                    Text(
                        "实验记录（${runs.size}）",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    EmbeddedRunHistoryList(
                        runs = runs,
                        selectedId = selected,
                        enabled = !busy,
                        onSelect = vm::select,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
                VerticalDivider()
                // 右栏：结果网格 或 大图检视模式（A/D 切图、F 收藏、Esc 返回）
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    val activeStageId = stageCardId
                    if (activeStageId != null && !compareMode) {
                        ArtistLabStageInspector(
                            cards = cards,
                            orderIds = stageOrderIds,
                            appendNewDraws = !stageOnlyFavorites,
                            currentCardId = activeStageId,
                            lockedArtists = lockedArtists,
                            excludedArtists = excludedArtists,
                            resolve = resolve,
                            onSelectCardId = { stageCardId = it },
                            onFavorite = vm::favorite,
                            onPinArtist = vm::pinArtistToForm,
                            onUnpinArtist = vm::unpinArtistFromForm,
                            onExcludeArtist = { tag ->
                                vm.setArtistEnabled(tag, false)
                                vm.showMessage("已排除 ${tag.removePrefix("artist: ")}，后续新实验不再抽到")
                            },
                            onReenableArtist = { tag ->
                                vm.setArtistEnabled(tag, true)
                                vm.showMessage("已重新启用 ${tag.removePrefix("artist: ")}")
                            },
                            onOpenDetail = onOpen,
                            onApplyArtistChain = onApplyArtistChain,
                            onClose = closeStageInspector,
                        )
                    } else {
                        LabResultsGridPane(
                            shown = shown,
                            onlyFavorites = onlyFavorites,
                            busy = busy,
                            cleanupOpen = cleanupOpen,
                            isWide = true,
                            compareMode = compareMode,
                            compareIds = compareIds,
                            plannedAspectRatio = (snapshot ?: currentParams).targetSize.let { it.width.toFloat() / it.height },
                            resolve = resolve,
                            onOpen = onOpen,
                            onInspectStage = openStageInspector,
                            onFocusMix = { focusedId = it.draw.id },
                            onFavorite = vm::favorite,
                            onToggleCompare = toggleCompareSelection,
                            onApplyArtistChain = onApplyArtistChain,
                            onCopyPrompt = { prompt ->
                                clipboard.setText(AnnotatedString(prompt))
                                vm.showMessage("已复制画师串")
                            },
                        )
                    }
                }
            }
        } else {
            // 手机单栏布局（保持原有紧凑结构，新增 AB 对比切换与选卡栏）
            Box(Modifier.fillMaxSize()) {
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
                        RunSummarySection(
                            snapshot = snapshot,
                            runConfig = runConfig,
                            processed = processed,
                            total = cards.size,
                            done = done,
                            favoritesCount = favorites.size,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                        FlowRow(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            FilterChip(!onlyFavorites, { setOnlyFavorites(false) }, { Text("全部结果") })
                            FilterChip(onlyFavorites, { setOnlyFavorites(true) }, { Text("只看收藏") })
                            FilterChip(
                                selected = compareMode,
                                onClick = toggleCompareMode,
                                label = { Text(if (compareMode) "AB (${compareCards.size}/4)" else "AB 对比") },
                                leadingIcon = { Icon(Icons.Default.Compare, null, Modifier.size(16.dp)) },
                            )
                            TextButton(onClick = { cleanupOpen = true }, enabled = !busy && cleanable > 0) {
                                Icon(Icons.Default.DeleteSweep, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("清理未收藏")
                            }
                        }
                        if (compareMode) {
                            CompareSelectionBar(
                                selectedCount = compareCards.size,
                                onStartCompare = { compareDialogOpen = true },
                                onClear = { compareIds = emptyList() },
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            )
                        }
                    }
                    if (notice.isNotBlank()) Text(notice, Modifier.padding(16.dp, 4.dp), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LabResultsGridPane(
                        shown = shown,
                        onlyFavorites = onlyFavorites,
                        busy = busy,
                        cleanupOpen = cleanupOpen,
                        isWide = false,
                        compareMode = compareMode,
                        compareIds = compareIds,
                        plannedAspectRatio = (snapshot ?: currentParams).targetSize.let { it.width.toFloat() / it.height },
                        resolve = resolve,
                        onOpen = onOpen,
                        onInspectStage = openStageInspector,
                        onFocusMix = { focusedId = it.draw.id },
                        onFavorite = vm::favorite,
                        onToggleCompare = toggleCompareSelection,
                        onApplyArtistChain = onApplyArtistChain,
                        onCopyPrompt = { prompt ->
                            clipboard.setText(AnnotatedString(prompt))
                            vm.showMessage("已复制画师串")
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                val activeStageId = stageCardId
                if (activeStageId != null && !compareMode) {
                    ArtistLabStageInspector(
                        cards = cards,
                        orderIds = stageOrderIds,
                        appendNewDraws = !stageOnlyFavorites,
                        currentCardId = activeStageId,
                        lockedArtists = lockedArtists,
                        excludedArtists = excludedArtists,
                        resolve = resolve,
                        onSelectCardId = { stageCardId = it },
                        onFavorite = vm::favorite,
                        onPinArtist = vm::pinArtistToForm,
                        onUnpinArtist = vm::unpinArtistFromForm,
                        onExcludeArtist = { tag ->
                            vm.setArtistEnabled(tag, false)
                            vm.showMessage("已排除 ${tag.removePrefix("artist: ")}，后续新实验不再抽到")
                        },
                        onReenableArtist = { tag ->
                            vm.setArtistEnabled(tag, true)
                            vm.showMessage("已重新启用 ${tag.removePrefix("artist: ")}")
                        },
                        onOpenDetail = onOpen,
                        onApplyArtistChain = onApplyArtistChain,
                        onClose = closeStageInspector,
                    )
                }
            }
        }
    }

    if (catalogOpen) ArtistCatalogDialog(vm, onDismiss = { catalogOpen = false })
    if (configOpen) LabConfigDialog(
        params = currentParams,
        form = form,
        catalogEntries = catalogEntries,
        excludedArtists = excludedArtists,
        error = message,
        onChange = vm::updateForm,
        onClose = { configOpen = false },
    ) { prompt, negative, seed, count, artists, min, max, locked, tiered ->
        vm.prepare(prompt, negative, seed, count, artists, min, max, locked, tiered)
    }
    approval?.let { a -> AlertDialog(onDismissRequest = vm::dismissApproval, title = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("确认${if (a.runId != null) "继续" else "开始"}抽卡", Modifier.weight(1f))
            net.pocketnai.ui.common.InfoButton("每次只请求 1 张，完成后等待 1～2 秒。可切换应用或锁屏继续，通知栏可暂停。失败或费用变化会暂停，已发送的请求不会重试。")
        }
    },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${a.remaining} 张 · 每张 ${a.config.artistCount} 位画师\n权重 ${a.config.minTicks / 20.0}～${a.config.maxTicks / 20.0}，步长 0.05" +
                if (a.config.tieredWeights) "\n阶梯权重：主画师高权 + 辅画师低权" else "")
            if (a.config.lockedArtists.isNotEmpty()) {
                Text(
                    "锁定基底画师：${a.config.lockedArtists.joinToString("、") { it.removePrefix("artist: ") }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("${a.params.model.displayName} · ${a.params.size.label}\n${a.params.steps} Steps · Guidance ${"%.2f".format(Locale.ROOT, a.params.guidance)}\nSeed ${a.params.baseSeed}")
            Text(a.quote.labDescription(a.remaining), color = MaterialTheme.colorScheme.primary)
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
    focused?.let { card ->
        AlertDialog(
            onDismissRequest = { focusedId = null },
            title = { Text("第 ${card.draw.ordinal + 1} 张的画师串") },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "点击画师标签可快速设为锁定基底或一键排除",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        card.mix.artists.forEach { artistTag ->
                            ArtistTagQuickActionChip(
                                artistTag = artistTag,
                                isLocked = artistTag.tag in lockedArtists,
                                isExcluded = artistTag.tag in excludedArtists,
                                onPin = { vm.pinArtistToForm(artistTag.tag) },
                                onUnpin = { vm.unpinArtistFromForm(artistTag.tag) },
                                onExclude = {
                                    vm.setArtistEnabled(artistTag.tag, false)
                                    vm.showMessage("已排除 ${artistTag.tag.removePrefix("artist: ")}，后续新实验不再抽到")
                                },
                                onReenable = {
                                    vm.setArtistEnabled(artistTag.tag, true)
                                    vm.showMessage("已重新启用 ${artistTag.tag.removePrefix("artist: ")}")
                                },
                            )
                        }
                    }
                    HorizontalDivider()
                    SelectionContainer {
                        Column {
                            Text(card.mix.prompt, style = MaterialTheme.typography.bodyMedium)
                            if (card.draw.message.isNotBlank()) Text("\n${card.draw.message}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (onApplyArtistChain != null) {
                        Button(
                            onClick = {
                                onApplyArtistChain(card.mix.prompt)
                                focusedId = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.AutoFixHigh, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("应用到生成面板")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(card.mix.prompt))
                    vm.showMessage("已复制画师串")
                    focusedId = null
                }) { Text("复制画师串") }
            },
            dismissButton = { TextButton(onClick = { focusedId = null }) { Text("关闭") } },
        )
    }
    if (historyOpen) AlertDialog(onDismissRequest = { historyOpen = false }, title = { Text("实验记录") },
        text = { LazyColumn(Modifier.heightIn(max = 420.dp)) {
            if (runs.isEmpty()) item { Text("还没有实验记录") }
            items(runs, key = { it.id }) { item ->
                TextButton(onClick = { vm.select(item.id); historyOpen = false }) {
                    Text(formatRunTitle(item))
                }
            }
        } }, confirmButton = { TextButton(onClick = { historyOpen = false }) { Text("关闭") } })
    if (favoritesOpen) ArtistMixFavoritesDialog(
        vm = vm,
        resolve = resolve,
        onDismiss = { favoritesOpen = false },
        onOpen = {
            favoritesOpen = false
            onOpen(it)
        },
        onApplyArtistChain = onApplyArtistChain,
    )
    if (compareDialogOpen && compareCards.size >= 2) {
        ArtistLabCompareDialog(
            cards = compareCards,
            resolve = resolve,
            onFavorite = vm::favorite,
            onOpen = onOpen,
            onApplyArtistChain = onApplyArtistChain,
            onDismiss = { compareDialogOpen = false },
        )
    }
}

@Composable
private fun RunSummarySection(
    snapshot: GenerationParams?,
    runConfig: ArtistLabConfig?,
    processed: Int,
    total: Int,
    done: Int,
    favoritesCount: Int,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        snapshot?.let {
            Text(
                "${it.model.displayName} · ${it.size.label} · Seed ${it.baseSeed}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val extraInfo = buildList {
            if (!runConfig?.lockedArtists.isNullOrEmpty()) {
                add("锁定 ${runConfig!!.lockedArtists.joinToString("/") { it.removePrefix("artist: ") }}")
            }
            if (runConfig?.tieredWeights == true) add("阶梯权重")
        }.joinToString(" · ")
        if (extraInfo.isNotEmpty()) {
            Text(
                extraInfo,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("完成 $processed / $total · 成功 $done", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            Text("收藏 $favoritesCount", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }
        LinearProgressIndicator(
            progress = { if (total > 0) processed.toFloat() / total else 0f },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
    }
}

@Composable
private fun CompareSelectionBar(
    selectedCount: Int,
    onStartCompare: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "已选 $selectedCount / 4",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            if (selectedCount > 0) {
                TextButton(onClick = onClear, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("清空")
                }
            }
            Button(
                onClick = onStartCompare,
                enabled = selectedCount in 2..4,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text("开始对比 ($selectedCount)")
            }
        }
    }
}

@Composable
private fun EmbeddedRunHistoryList(
    runs: List<ArtistLabRunEntity>,
    selectedId: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (runs.isEmpty()) {
        Box(modifier.padding(16.dp), contentAlignment = Alignment.Center) {
            Text("还没有实验记录", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(runs, key = { it.id }) { item ->
            val isSelected = item.id == selectedId
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                border = if (isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled) { onSelect(item.id) },
            ) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                    Text(
                        formatRunTitle(item),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (item.message.isNotBlank()) {
                        Text(
                            item.message,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LabResultsGridPane(
    shown: List<LabCard>,
    onlyFavorites: Boolean,
    busy: Boolean,
    cleanupOpen: Boolean,
    isWide: Boolean,
    compareMode: Boolean,
    compareIds: List<String>,
    plannedAspectRatio: Float,
    resolve: (String) -> File,
    onOpen: (String) -> Unit,
    onInspectStage: (LabCard) -> Unit,
    onFocusMix: (LabCard) -> Unit,
    onFavorite: (LabCard) -> Unit,
    onToggleCompare: (LabCard) -> Unit,
    onApplyArtistChain: ((String) -> Unit)?,
    onCopyPrompt: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (shown.isEmpty()) {
        Box(modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.Casino, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                Text(
                    if (onlyFavorites) "还没有收藏图片" else if (busy) "第一张正在准备中" else "还没有实验结果",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(if (isWide) 176.dp else 156.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(shown, key = { it.draw.id }, contentType = { "draw" }) { card ->
            val haptics = net.pocketnai.ui.motion.rememberPocketHaptics()
            val compareIndex = compareIds.indexOf(card.draw.id)
            val isCompareSelected = compareIndex >= 0
            var contextMenuOpen by remember { mutableStateOf(false) }

            val border = when {
                compareMode && isCompareSelected -> BorderStroke(2.5.dp, MaterialTheme.colorScheme.tertiary)
                card.favorite -> BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                else -> null
            }

            Box {
                Card(
                    border = border,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onSecondaryClick { contextMenuOpen = true }
                        .then(
                            if (compareMode && card.image != null) {
                                Modifier.clickable { onToggleCompare(card) }
                            } else Modifier
                        ),
                ) {
                    Row(Modifier.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (compareMode && card.image != null) {
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = if (isCompareSelected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.padding(end = 6.dp),
                            ) {
                                Text(
                                    text = if (isCompareSelected) "${'A' + compareIndex}" else "+",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (isCompareSelected) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Text("#${card.draw.ordinal + 1}", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                        IconButton(onClick = { onFocusMix(card) }) { Icon(Icons.Default.Info, "查看画师串") }
                        IconButton(
                            onClick = {
                                if (!card.favorite) haptics.confirm() else haptics.tick()
                                onFavorite(card)
                            },
                            enabled = card.image != null && !cleanupOpen,
                        ) {
                            net.pocketnai.ui.common.FeedbackIcon(
                                icon = if (card.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                description = if (card.favorite) "取消图片收藏" else "收藏图片和画师串",
                                tint = if (card.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    ArtistLabResultImage(
                        drawId = card.draw.id,
                        ordinal = card.draw.ordinal,
                        status = card.draw.status,
                        image = card.image,
                        plannedAspectRatio = plannedAspectRatio,
                        resolve = resolve,
                        onOpen = onOpen,
                        onClick = when {
                            compareMode && card.image != null -> ({ onToggleCompare(card) })
                            isWide && card.image != null -> ({ onInspectStage(card) })
                            else -> null
                        },
                    )
                    Text(
                        card.mix.artists.joinToString(", ") { it.tag.removePrefix("artist: ") },
                        Modifier.padding(10.dp, 0.dp, 10.dp, 10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                DropdownMenu(expanded = contextMenuOpen, onDismissRequest = { contextMenuOpen = false }) {
                    if (card.image != null) {
                        DropdownMenuItem(
                            text = { Text("大图检视 (A/D · F)") },
                            leadingIcon = { Icon(Icons.Default.OpenInFull, null, Modifier.size(18.dp)) },
                            onClick = { contextMenuOpen = false; onInspectStage(card) },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("画师串与快捷操作") },
                        leadingIcon = { Icon(Icons.Default.Info, null, Modifier.size(18.dp)) },
                        onClick = { contextMenuOpen = false; onFocusMix(card) },
                    )
                    if (card.image != null) {
                        DropdownMenuItem(
                            text = { Text(if (card.favorite) "取消图片收藏" else "收藏图片和画师串") },
                            leadingIcon = { Icon(if (card.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, null, Modifier.size(18.dp)) },
                            onClick = { contextMenuOpen = false; onFavorite(card) },
                        )
                    }
                    if (onApplyArtistChain != null) {
                        DropdownMenuItem(
                            text = { Text("应用到生成面板") },
                            leadingIcon = { Icon(Icons.Default.AutoFixHigh, null, Modifier.size(18.dp)) },
                            onClick = { contextMenuOpen = false; onApplyArtistChain(card.mix.prompt) },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("复制画师串") },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp)) },
                        onClick = { contextMenuOpen = false; onCopyPrompt(card.mix.prompt) },
                    )
                    if (card.image != null) {
                        DropdownMenuItem(
                            text = { Text(if (isCompareSelected) "移出 AB 对比" else "加入 AB 对比") },
                            leadingIcon = { Icon(Icons.Default.Compare, null, Modifier.size(18.dp)) },
                            onClick = { contextMenuOpen = false; onToggleCompare(card) },
                        )
                    }
                }
            }
        }
    }
}

private fun formatRunTitle(item: ArtistLabRunEntity): String {
    return SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(item.createdAt)) + " · " +
        when (item.status) {
            "COMPLETED" -> "已完成"
            "RUNNING" -> "进行中"
            else -> "已暂停"
        }
}

private fun LabCard.statusVisible() = image != null || draw.status in listOf("RUNNING", "UNCERTAIN", "FAILED")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun LabConfigDialog(
    params: GenerationParams,
    form: ArtistLabForm?,
    catalogEntries: List<ArtistCatalogEntry>,
    excludedArtists: Set<String>,
    error: String,
    onChange: (ArtistLabForm) -> Unit,
    onClose: () -> Unit,
    onPrepare: (String, String, String, String, Int, Int, Int, List<String>, Boolean) -> Unit,
) {
    var prompt by rememberSaveable {
        mutableStateOf(
            form?.prompt ?: ArtistPromptSplitter.split(params.prompt, extractOnlyCompleted = false).mainPrompt
        )
    }
    var negative by rememberSaveable { mutableStateOf(form?.negative ?: params.negativePrompt) }
    var seed by rememberSaveable { mutableStateOf(form?.seed ?: if (params.baseSeed > 0) params.baseSeed.toString() else Random.nextLong(0, GenerationParams.MAX_SEED + 1).toString()) }
    var count by rememberSaveable { mutableStateOf(form?.count ?: "100") }
    var artists by rememberSaveable { mutableIntStateOf(form?.artists ?: 3) }
    var min by rememberSaveable { mutableIntStateOf(form?.minTicks ?: 10) }
    var max by rememberSaveable { mutableIntStateOf(form?.maxTicks ?: 30) }
    var lockedArtists by rememberSaveable(stateSaver = StringListSaver) {
        mutableStateOf(form?.lockedArtists.orEmpty().take(ArtistLabConfig.MAX_LOCKED_ARTISTS))
    }
    var tieredWeights by rememberSaveable { mutableStateOf(form?.tieredWeights ?: false) }
    var lockPickerOpen by remember { mutableStateOf(false) }

    LaunchedEffect(prompt, negative, seed, count, artists, min, max, lockedArtists, tieredWeights) {
        onChange(ArtistLabForm(prompt, negative, seed, count, artists, min, max, lockedArtists, tieredWeights))
    }
    val (lowerMax, upperMin) = remember(min, max) { ArtistLabPlanner.tieredBounds(min, max) }

    val fieldShape = RoundedCornerShape(16.dp)
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
    )

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            val isWide = maxWidth >= 600.dp
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                shadowElevation = 8.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 620.dp)
                    .heightIn(max = if (isWide) 780.dp else maxHeight),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // 顶部标题与当前首页参数胶囊
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 22.dp, end = 14.dp, top = 18.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "新建抽卡实验",
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.weight(1f),
                            )
                            net.pocketnai.ui.common.InfoButton("使用首页的模型与参数进行单张文生图，不携带首页参考图与独立角色。")
                            FilledTonalIconButton(
                                onClick = onClose,
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                ),
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(Icons.Default.Close, "关闭", modifier = Modifier.size(18.dp))
                            }
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            listOf(
                                params.model.shortDisplayName,
                                params.size.label,
                                "${params.steps} Steps",
                                "CFG ${"%.2f".format(Locale.ROOT, params.guidance)}",
                            ).forEach { badge ->
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                ) {
                                    Text(
                                        text = badge,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))

                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (error.isNotBlank()) {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.errorContainer,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = error,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                )
                            }
                        }

                        // 卡片 1：固定基础提示词
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text("固定提示词", style = MaterialTheme.typography.titleSmall)
                                OutlinedTextField(
                                    value = prompt,
                                    onValueChange = { prompt = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = fieldShape,
                                    colors = fieldColors,
                                    label = { Text("基础正向提示词（不含 artist:）") },
                                    minLines = 2,
                                    maxLines = 5,
                                    trailingIcon = {
                                        net.pocketnai.ui.common.ClearPromptButton(prompt, "固定基础提示词") { prompt = it; 0 }
                                    },
                                )
                                OutlinedTextField(
                                    value = negative,
                                    onValueChange = { negative = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = fieldShape,
                                    colors = fieldColors,
                                    label = { Text("基础负面提示词") },
                                    minLines = 1,
                                    maxLines = 3,
                                    trailingIcon = {
                                        net.pocketnai.ui.common.ClearPromptButton(negative, "固定负面提示词") { negative = it; 0 }
                                    },
                                )
                            }
                        }

                        // 卡片 2：Seed 与抽卡次数
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text("批次与种子", style = MaterialTheme.typography.titleSmall)
                                if (isWide) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        OutlinedTextField(
                                            value = seed,
                                            onValueChange = { seed = it },
                                            modifier = Modifier.weight(1.35f),
                                            shape = fieldShape,
                                            colors = fieldColors,
                                            label = { Text("固定 Seed") },
                                            singleLine = true,
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            trailingIcon = {
                                                IconButton(onClick = { seed = Random.nextLong(0, GenerationParams.MAX_SEED + 1).toString() }) {
                                                    Icon(Icons.Default.Shuffle, "随机一个固定种子", modifier = Modifier.size(18.dp))
                                                }
                                            },
                                        )
                                        OutlinedTextField(
                                            value = count,
                                            onValueChange = { count = it },
                                            modifier = Modifier.weight(1f),
                                            shape = fieldShape,
                                            colors = fieldColors,
                                            label = { Text("抽卡次数（1～10000）") },
                                            singleLine = true,
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        )
                                    }
                                } else {
                                    OutlinedTextField(
                                        value = seed,
                                        onValueChange = { seed = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = fieldShape,
                                        colors = fieldColors,
                                        label = { Text("固定 Seed") },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        trailingIcon = {
                                            IconButton(onClick = { seed = Random.nextLong(0, GenerationParams.MAX_SEED + 1).toString() }) {
                                                Icon(Icons.Default.Shuffle, "随机一个固定种子", modifier = Modifier.size(18.dp))
                                            }
                                        },
                                    )
                                    OutlinedTextField(
                                        value = count,
                                        onValueChange = { count = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = fieldShape,
                                        colors = fieldColors,
                                        label = { Text("抽卡次数（1～10000）") },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    )
                                }
                            }
                        }

                        // 卡片 3：画师数量、锁定基底与权重策略
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text("每张抽取画师", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                                        Text(
                                            text = "$artists 位",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                                        )
                                    }
                                    net.pocketnai.ui.common.InfoButton("每张使用相同 Seed 和基础提示词，只随机画师组合与权重。")
                                }
                                Slider(
                                    value = artists.toFloat(),
                                    onValueChange = { artists = it.roundToInt() },
                                    valueRange = 1f..10f,
                                    steps = 8,
                                )

                                // 锁定基底画师（最多 3 位，其余随机混搭）
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Column(Modifier.weight(1f)) {
                                                Text(
                                                    "锁定基底画师（${lockedArtists.size}/${ArtistLabConfig.MAX_LOCKED_ARTISTS}）",
                                                    style = MaterialTheme.typography.titleSmall,
                                                )
                                                Text(
                                                    "固定保留主画风，其余名额从启用画师库随机混搭",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                            FilledTonalButton(
                                                onClick = { lockPickerOpen = true },
                                                shape = CircleShape,
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                            ) {
                                                Icon(Icons.Default.PushPin, null, Modifier.size(16.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text("选择画师", style = MaterialTheme.typography.labelMedium)
                                            }
                                        }
                                        if (lockedArtists.isNotEmpty()) {
                                            FlowRow(
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                            ) {
                                                lockedArtists.forEach { tag ->
                                                    InputChip(
                                                        selected = true,
                                                        onClick = { lockedArtists = lockedArtists - tag },
                                                        shape = CircleShape,
                                                        label = { Text(tag.removePrefix("artist: "), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                                        trailingIcon = { Icon(Icons.Default.Close, "移除锁定", Modifier.size(16.dp)) },
                                                    )
                                                }
                                            }
                                        }
                                        if (lockedArtists.size > artists) {
                                            Text(
                                                "当前每张仅抽取 $artists 位，本次将取前 $artists 位锁定画师",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text("单画师权重（步长 0.05）", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                                        Text(
                                            text = "${formatTicks(min)}～${formatTicks(max)}",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                                        )
                                    }
                                }
                                RangeSlider(
                                    value = min.toFloat()..max.toFloat(),
                                    onValueChange = { min = it.start.roundToInt(); max = it.endInclusive.roundToInt() },
                                    valueRange = 10f..30f,
                                    steps = 19,
                                )

                                // 阶梯权重（主高辅低）开关
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                                    modifier = Modifier.fillMaxWidth().clickable { tieredWeights = !tieredWeights },
                                ) {
                                    Row(
                                        Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text("阶梯权重（主高辅低）", style = MaterialTheme.typography.titleSmall)
                                            Text(
                                                "主画师 ${formatTicks(upperMin)}～${formatTicks(max)} · 辅画师 ${formatTicks(min)}～${formatTicks(lowerMax)}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        Switch(checked = tieredWeights, onCheckedChange = { tieredWeights = it })
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onClose, shape = CircleShape) {
                            Text("取消")
                        }
                        Button(
                            onClick = { onPrepare(prompt, negative, seed, count, artists, min, max, lockedArtists, tieredWeights) },
                            shape = CircleShape,
                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                        ) {
                            Text("核对参数与费用")
                        }
                    }
                }
            }
        }
    }
    if (lockPickerOpen) {
        ArtistLockPickerDialog(
            entries = catalogEntries,
            excluded = excludedArtists,
            lockedArtists = lockedArtists,
            onToggleLock = { tag ->
                lockedArtists = if (tag in lockedArtists) {
                    lockedArtists - tag
                } else if (lockedArtists.size < ArtistLabConfig.MAX_LOCKED_ARTISTS) {
                    (lockedArtists + tag).distinct()
                } else {
                    lockedArtists
                }
            },
            onDismiss = { lockPickerOpen = false },
        )
    }
}
