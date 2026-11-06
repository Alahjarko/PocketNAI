package net.pocketnai.ui.billing

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.pocketnai.domain.billing.*
import net.pocketnai.domain.openai.OpenAiImageParams
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun spendingUsd(amount: Double): String = NumberFormat.getCurrencyInstance(Locale.US).apply {
    minimumFractionDigits = 2
    maximumFractionDigits = 6
}.format(amount) + " USD"

@Composable
internal fun SpendingOverviewCard(provider: String, amount: String, detail: String, onClick: () -> Unit) {
    Card(onClick = onClick) {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(provider, style = MaterialTheme.typography.titleMedium)
                Text(amount, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "查看 $provider 流水")
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
internal fun LazyListScope.openAiSpendingItems(stats: OpenAiSpendingStatistics) {
    item(key = "openai-summary") {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("用量折算", style = MaterialTheme.typography.labelLarge)
                Text(if (stats.knownCount > 0) spendingUsd(stats.totalUsageUsd) else "—",
                    style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("${stats.recordCount} 次请求 · ${stats.knownCount} 次可按用量折算", style = MaterialTheme.typography.bodySmall)
                if (stats.unknownCount > 0) Text("${stats.unknownCount} 次费用待确认，未计入合计", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (stats.knownCount > 0) item(key = "openai-trend") { OpenAiTrend(stats) }
    item(key = "openai-ledger-title") { Text("消耗流水", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleLarge) }
    if (stats.recordCount == 0) item(key = "openai-empty") {
        Text("这段时间暂无记录", Modifier.fillMaxWidth().padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    stats.groups.forEach { group ->
        stickyHeader(key = "openai-day-${group.date}") {
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(group.date.format(DateTimeFormatter.ofPattern("yyyy年M月d日")), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Text(if (group.knownCount > 0) spendingUsd(group.usageUsd) else "费用待确认",
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
        items(group.entries, key = { "openai-${it.id}" }, contentType = { "openai-spending" }) { OpenAiTransaction(it) }
    }
}

@Composable
private fun OpenAiTransaction(entry: OpenAiSpendingEntry) {
    val time = Instant.ofEpochMilli(entry.createdAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm:ss"))
    val snapshot = entry.snapshot
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(OpenAiImageParams.labelOf(entry.model), style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(entry.usageUsd?.let(::spendingUsd) ?: "费用待确认", style = MaterialTheme.typography.titleMedium,
                    color = if (entry.usageUsd == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
            }
            Text(buildList {
                add(when (entry.status) {
                    "QUEUED" -> "排队中"; "GENERATING" -> "生成中"; "SUCCEEDED" -> "已完成"
                    "PARTIAL" -> "部分完成"; "FAILED" -> "未完成"; else -> "状态待确认"
                })
                snapshot?.let { add("${it.params.count} 张 · ${it.params.outputFormat.uppercase(Locale.ROOT)}") }
            }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            snapshot?.usage?.let { usage ->
                Text("文本输入 ${usage.textInput} · 图片输入 ${usage.imageInput} · 图片输出 ${usage.imageOutput} tokens",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (entry.usageUsd == null && entry.estimateMinUsd != null && entry.estimateMaxUsd != null) {
                val estimate = if (entry.estimateMinUsd == entry.estimateMaxUsd) spendingUsd(entry.estimateMinUsd!!)
                    else spendingUsd(entry.estimateMinUsd!!) + "～" + spendingUsd(entry.estimateMaxUsd!!)
                Text("生成前粗估 $estimate", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun OpenAiTrend(stats: OpenAiSpendingStatistics) {
    val scroll = rememberScrollState()
    var selected by rememberSaveable(stats.range) { mutableStateOf<String?>(null) }
    val chosen = stats.trend.firstOrNull { it.date.toString() == selected }
    val maximum = stats.trend.maxOfOrNull { it.usageUsd }?.coerceAtLeast(0.000001) ?: 0.000001
    val formatter = remember(stats.range) { DateTimeFormatter.ofPattern(if (stats.range == AnlasStatsRange.ALL) "yyyy/M" else "M/d") }
    LaunchedEffect(stats.range, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("用量折算趋势", style = MaterialTheme.typography.titleMedium)
            chosen?.let { Text("${it.date.format(formatter)} · ${spendingUsd(it.usageUsd)}", style = MaterialTheme.typography.bodySmall) }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val width = (maxWidth / stats.trend.size.coerceAtLeast(1)).coerceAtLeast(8.dp)
                Row(Modifier.horizontalScroll(scroll), verticalAlignment = Alignment.Bottom) {
                    stats.trend.forEach { point ->
                        Column(Modifier.width(width).height(124.dp).clip(RoundedCornerShape(6.dp))
                            .clickable { selected = point.date.toString() }
                            .semantics { contentDescription = "${point.date.format(formatter)}，用量折算 ${spendingUsd(point.usageUsd)}" }
                            .padding(horizontal = if (width < 16.dp) 1.dp else 3.dp), verticalArrangement = Arrangement.Bottom) {
                            Box(Modifier.fillMaxWidth().height(if (point.usageUsd == 0.0) 2.dp else (120.dp * (point.usageUsd / maximum).toFloat()).coerceAtLeast(3.dp))
                                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                .background(if (point.usageUsd == 0.0) MaterialTheme.colorScheme.outlineVariant else if (chosen == point)
                                    MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary))
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stats.trend.firstOrNull()?.date?.format(formatter).orEmpty(), style = MaterialTheme.typography.labelSmall)
                Text(stats.trend.lastOrNull()?.date?.format(formatter).orEmpty(), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
