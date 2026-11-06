package net.pocketnai.ui.billing

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.launch
import net.pocketnai.data.repo.AnlasLedgerRepository
import net.pocketnai.domain.billing.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.text.NumberFormat

private val TabularNumbers = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum")

internal enum class SpendingTab(val label: String) { OVERVIEW("总览"), ANLAS("Anlas"), OPENAI("OpenAI") }

@Composable
fun AnlasStatisticsScreen(repository: AnlasLedgerRepository, onBack: () -> Unit) {
    val generationDao = net.pocketnai.ui.LocalAppContainer.current.database.generationDao()
    val vm: AnlasStatisticsViewModel = viewModel(factory = viewModelFactory {
        initializer { AnlasStatisticsViewModel(repository, createSavedStateHandle(), generationDao) }
    })
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var confirmingClear by rememberSaveable { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf(SpendingTab.OVERVIEW) }
    AnlasStatisticsContent(state, vm::setRange, onBack, { confirmingClear = true }, snackbar,
        selectedTab = tab, onTabChange = { tab = it })
    if (confirmingClear) {
        AlertDialog(
            onDismissRequest = { if (!clearing) confirmingClear = false },
            title = { Text("清空 ${tab.label} 流水？") },
            text = { Text("将删除本机所有 ${tab.label} 流水，统计也会重置。图片、对话、另一服务的流水与服务端账单不受影响，此操作不可撤销。") },
            confirmButton = {
                TextButton(enabled = !clearing, onClick = {
                    clearing = true
                    vm.clear(tab == SpendingTab.OPENAI) { success ->
                        clearing = false
                        confirmingClear = false
                        scope.launch { snackbar.showSnackbar(if (success) "本机流水已清空" else "清空失败，请稍后重试") }
                    }
                }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text(if (clearing) "正在清空…" else "清空全部")
                }
            },
            dismissButton = { TextButton(enabled = !clearing, onClick = { confirmingClear = false }) { Text("取消") } },
        )
    }
}

/** 与实际页面共用的纯展示入口，支持无需模拟器的 Layoutlib 渲染。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun AnlasStatisticsContent(
    state: AnlasStatisticsState,
    onRangeChange: (AnlasStatsRange) -> Unit,
    onBack: () -> Unit,
    onClear: () -> Unit,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    listState: LazyListState = rememberLazyListState(),
    selectedTab: SpendingTab = SpendingTab.OVERVIEW,
    onTabChange: (SpendingTab) -> Unit = {},
) {
    val statistics = state.statistics
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("消耗统计") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = {
                    net.pocketnai.ui.common.InfoButton("本机所有账号汇总。Anlas 是操作时观察到的余额扣减，0 不等于免费。OpenAI 美元金额按整次请求用量和当时保存的费率折算，不等于账单；未返回用量的请求不计入合计，生成前粗估单独显示。两种计费单位不相加。\n\n清理图片不会删除流水；按设备本地日期分组。")
                    if ((selectedTab == SpendingTab.ANLAS && (statistics?.storedRecordCount ?: 0) > 0) ||
                        (selectedTab == SpendingTab.OPENAI && (state.openAi?.storedRecordCount ?: 0) > 0)) {
                        IconButton(onClick = onClear) { Icon(Icons.Outlined.DeleteOutline, "清空 ${selectedTab.label} 流水") }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            when {
                state.loadFailed -> StatisticsPlaceholder("暂时无法读取本机流水", "返回后重试，已有记录不会被清空。")
                statistics == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.semantics { contentDescription = "正在读取本机统计" })
                }
                else -> LazyColumn(
                    modifier = Modifier.widthIn(max = 840.dp).fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "provider") {
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            SpendingTab.entries.forEachIndexed { index, tab ->
                                SegmentedButton(selected = selectedTab == tab, onClick = { onTabChange(tab) },
                                    shape = SegmentedButtonDefaults.itemShape(index, SpendingTab.entries.size)) { Text(tab.label) }
                            }
                        }
                    }
                    item(key = "range") {
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            AnlasStatsRange.entries.forEachIndexed { index, range ->
                                SegmentedButton(
                                    selected = statistics.range == range,
                                    onClick = { onRangeChange(range) },
                                    shape = SegmentedButtonDefaults.itemShape(index, AnlasStatsRange.entries.size),
                                ) { Text(range.label) }
                            }
                        }
                    }
                    if (selectedTab == SpendingTab.OVERVIEW) {
                        item(key = "overview-anlas") {
                            SpendingOverviewCard("NovelAI", "${formatAnlas(statistics.totalSpent)} Anlas", "${statistics.recordCount} 次操作") { onTabChange(SpendingTab.ANLAS) }
                        }
                        item(key = "overview-openai") {
                            val openAi = state.openAi
                            SpendingOverviewCard("OpenAI", if (openAi != null && openAi.knownCount > 0) spendingUsd(openAi.totalUsageUsd) else "—",
                                "用量折算 · ${openAi?.recordCount ?: 0} 次请求" + if ((openAi?.unknownCount ?: 0) > 0) " · ${openAi!!.unknownCount} 次费用待确认" else "") { onTabChange(SpendingTab.OPENAI) }
                        }
                    } else if (selectedTab == SpendingTab.OPENAI) {
                        state.openAi?.let { openAi -> openAiSpendingItems(openAi) }
                    } else {
                    item(key = "summary") { StatisticsSummary(statistics) }
                    if (statistics.recordCount > 0) {
                        item(key = "trend") { SpendingTrend(statistics) }
                        item(key = "actions") { ActionBreakdown(statistics) }
                    }
                    item(key = "ledger-title") {
                        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("消耗流水", style = MaterialTheme.typography.titleLarge)
                            Text("${formatAnlas(statistics.recordCount.toLong())} 条记录", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (statistics.groups.isEmpty()) {
                        item(key = "empty") { StatisticsPlaceholder("这段时间暂无记录", "生图、重绘或超分后，观察到的消耗会记录在这里。") }
                    }
                    statistics.groups.forEach { group ->
                        stickyHeader(key = "day-${group.date}") {
                            Row(
                                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                                    .padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(dayLabel(group.date), style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.weight(1f))
                                Text("消耗 ${formatAnlas(group.spent)} Anlas",
                                    style = MaterialTheme.typography.labelLarge.merge(TabularNumbers),
                                    color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        items(group.entries, key = { it.id }, contentType = { "transaction" }) { entry -> TransactionCard(entry) }
                    }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatisticsSummary(stats: AnlasStatistics) {
    Column {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("记录消耗", style = MaterialTheme.typography.labelLarge)
                AnimatedContent(targetState = stats.totalSpent, label = "统计金额") { amount ->
                    Text("${formatAnlas(amount)} Anlas",
                        style = MaterialTheme.typography.headlineLarge.merge(TabularNumbers), fontWeight = FontWeight.Bold)
                }
                Text("${stats.range.label} · ${stats.activeDays} 个活跃日", style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard("操作次数", formatAnlas(stats.recordCount.toLong()), "含零消耗记录", Modifier.weight(1f))
            MetricCard("平均每次", NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }.format(stats.averageSpent),
                "Anlas / 次", Modifier.weight(1f))
        }
    }
}

@Composable
private fun MetricCard(title: String, value: String, subtitle: String, modifier: Modifier) {
    Card(modifier.fillMaxHeight()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineSmall.merge(TabularNumbers), fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SpendingTrend(stats: AnlasStatistics) {
    val chartScroll = rememberScrollState()
    LaunchedEffect(stats.range, stats.trend.size, chartScroll.maxValue) { chartScroll.scrollTo(chartScroll.maxValue) }
    var selected by rememberSaveable(stats.range) { mutableStateOf<String?>(null) }
    val point = stats.trend.find { it.date.toString() == selected }
    val maximum = stats.trend.maxOfOrNull { it.spent }?.coerceAtLeast(1L) ?: 1L
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("消耗趋势", style = MaterialTheme.typography.titleMedium)
            Text(point?.let { "${trendLabel(it.date, stats.range)} · ${formatAnlas(it.spent)} Anlas" }
                ?: if (stats.range == AnlasStatsRange.ALL) "每月消耗 · 点击柱形查看" else "每日消耗 · 点击柱形查看",
                style = MaterialTheme.typography.bodySmall.merge(TabularNumbers), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("峰值 ${formatAnlas(stats.trend.maxOfOrNull { it.spent } ?: 0)} Anlas",
                style = MaterialTheme.typography.labelSmall.merge(TabularNumbers), color = MaterialTheme.colorScheme.onSurfaceVariant)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val barWidth = (maxWidth / stats.trend.size.coerceAtLeast(1)).coerceAtLeast(8.dp)
                Row(Modifier.fillMaxWidth().horizontalScroll(chartScroll), verticalAlignment = Alignment.Bottom) {
                    stats.trend.forEach { item ->
                        val fraction by animateFloatAsState(item.spent.toFloat() / maximum, tween(300), label = "趋势柱形")
                        val active = point?.date == item.date
                        Column(
                            Modifier.width(barWidth).height(124.dp).clip(RoundedCornerShape(6.dp))
                                .clickable { selected = item.date.toString() }
                                .semantics { contentDescription = "${trendLabel(item.date, stats.range)}，消耗 ${item.spent} Anlas" }
                                .padding(horizontal = if (barWidth < 16.dp) 1.dp else 3.dp),
                            verticalArrangement = Arrangement.Bottom,
                        ) {
                            Box(Modifier.fillMaxWidth().height(if (item.spent == 0L) 2.dp else (120.dp * fraction).coerceAtLeast(3.dp))
                                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                .background(if (item.spent == 0L) MaterialTheme.colorScheme.outlineVariant
                                    else if (active) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary))
                        }
                    }
                }
            }
            if (stats.range == AnlasStatsRange.ALL) {
                Text("记录范围 ${stats.trend.firstOrNull()?.let { trendLabel(it.date, stats.range) }.orEmpty()} — " +
                    stats.trend.lastOrNull()?.let { trendLabel(it.date, stats.range) }.orEmpty(),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (chartScroll.maxValue > 0) Text("左右滑动查看更多月份", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stats.trend.firstOrNull()?.let { trendLabel(it.date, stats.range) } ?: "", style = MaterialTheme.typography.labelSmall)
                Text(stats.trend.lastOrNull()?.let { trendLabel(it.date, stats.range) } ?: "", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun ActionBreakdown(stats: AnlasStatistics) {
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("按操作类型", style = MaterialTheme.typography.titleMedium)
            stats.actions.forEach { action ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${actionLabel(action.actionType)} · ${action.count} 次", style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f))
                        Text("${formatAnlas(action.spent)} Anlas", style = MaterialTheme.typography.labelLarge.merge(TabularNumbers))
                    }
                    LinearProgressIndicator(
                        progress = { if (stats.totalSpent == 0L) 0f else action.spent.toFloat() / stats.totalSpent },
                        modifier = Modifier.fillMaxWidth().height(5.dp),
                    )
                }
            }
            Text("零消耗记录 ${stats.zeroSpendCount} 次", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TransactionCard(entry: AnlasLedgerEntry) {
    val zone = ZoneId.systemDefault()
    val time = remember(entry.createdAt, zone) {
        Instant.ofEpochMilli(entry.createdAt).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm:ss"))
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(actionLabel(entry.actionType), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    Text(time, style = MaterialTheme.typography.labelSmall.merge(TabularNumbers),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(if (entry.anlasSpent > 0) "−${formatAnlas(entry.anlasSpent)} Anlas" else "0 Anlas",
                    modifier = Modifier.widthIn(max = 220.dp),
                    style = MaterialTheme.typography.titleLarge.merge(TabularNumbers), fontWeight = FontWeight.Bold,
                    color = if (entry.anlasSpent > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(entry.description, style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                entry.balanceAfter?.let {
                    Text("变动后余额 ${formatAnlas(it)} Anlas", style = MaterialTheme.typography.labelSmall.merge(TabularNumbers),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f))
                }
                entry.v5AllowanceDelta?.takeIf { it > 0 }?.let {
                    Text("V5 额度 −$it%", style = MaterialTheme.typography.labelSmall.merge(TabularNumbers),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f))
                }
            }
        }
    }
}

@Composable
private fun StatisticsPlaceholder(title: String, detail: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Outlined.BarChart, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(detail, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun actionLabel(type: String): String = when (type) {
    "GENERATE" -> "生图"
    "INPAINT" -> "重绘"
    "UPSCALE" -> "超分"
    else -> "其他操作"
}
private fun dayLabel(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern(
    if (date.year == LocalDate.now().year) "M月d日" else "yyyy年M月d日"))
private fun trendLabel(date: LocalDate, range: AnlasStatsRange): String =
    date.format(DateTimeFormatter.ofPattern(if (range == AnlasStatsRange.ALL) "yyyy/M" else "M/d"))
