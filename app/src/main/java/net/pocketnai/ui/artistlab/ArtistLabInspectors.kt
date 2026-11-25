package net.pocketnai.ui.artistlab

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import net.pocketnai.domain.artistlab.ArtistCatalogEntry
import net.pocketnai.domain.artistlab.ArtistLabConfig
import net.pocketnai.domain.artistlab.ArtistTag
import net.pocketnai.ui.common.onMouseWheel
import java.io.File

internal fun formatTicks(ticks: Int): String =
    "${ticks / 20}.${((ticks % 20) * 5).toString().padStart(2, '0')}"

@Composable
internal fun ArtistTagQuickActionChip(
    artistTag: ArtistTag,
    isLocked: Boolean,
    isExcluded: Boolean,
    onPin: () -> Unit,
    onUnpin: () -> Unit,
    onExclude: () -> Unit,
    onReenable: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val name = artistTag.tag.removePrefix("artist: ")
    val label = "${formatTicks(artistTag.ticks)} · $name"
    Box(modifier = modifier) {
        AssistChip(
            onClick = { menuOpen = true },
            label = {
                Text(
                    text = when {
                        isLocked -> "$label · 锁定"
                        isExcluded -> "$label · 已排除"
                        else -> label
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            leadingIcon = {
                when {
                    isLocked -> Icon(Icons.Default.PushPin, null, Modifier.size(16.dp))
                    isExcluded -> Icon(Icons.Default.Block, null, Modifier.size(16.dp))
                    else -> null
                }
            },
            colors = when {
                isLocked -> AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    leadingIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                isExcluded -> AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    labelColor = MaterialTheme.colorScheme.onErrorContainer,
                    leadingIconContentColor = MaterialTheme.colorScheme.onErrorContainer,
                )
                else -> AssistChipDefaults.assistChipColors()
            },
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (isLocked) {
                DropdownMenuItem(
                    text = { Text("取消锁定基底") },
                    leadingIcon = { Icon(Icons.Default.Close, null, Modifier.size(18.dp)) },
                    onClick = { menuOpen = false; onUnpin() },
                )
            } else {
                DropdownMenuItem(
                    text = { Text("设为锁定基底") },
                    leadingIcon = { Icon(Icons.Default.PushPin, null, Modifier.size(18.dp)) },
                    onClick = { menuOpen = false; onPin() },
                )
            }
            if (isExcluded) {
                DropdownMenuItem(
                    text = { Text("重新启用（恢复抽卡）") },
                    leadingIcon = { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) },
                    onClick = { menuOpen = false; onReenable() },
                )
            } else {
                DropdownMenuItem(
                    text = { Text("一键排除（不再抽到）") },
                    leadingIcon = { Icon(Icons.Default.Block, null, Modifier.size(18.dp)) },
                    onClick = { menuOpen = false; onExclude() },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ArtistLabCompareDialog(
    cards: List<LabCard>,
    resolve: (String) -> File,
    onFavorite: (LabCard) -> Unit,
    onOpen: (String) -> Unit,
    onApplyArtistChain: ((String) -> Unit)?,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val haptics = net.pocketnai.ui.motion.rememberPocketHaptics()
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    // 找出所有对比卡片共同包含的画师，以及权重也完全一致的画师，其余差异画师高亮显示。
    val commonArtistTags = remember(cards) {
        if (cards.isEmpty()) emptySet()
        else cards.map { card -> card.mix.artists.map { it.tag }.toSet() }
            .reduce { acc, set -> acc intersect set }
    }
    val identicalTagTicks = remember(cards, commonArtistTags) {
        commonArtistTags.filter { tag ->
            cards.mapNotNull { card -> card.mix.artists.firstOrNull { it.tag == tag }?.ticks }
                .distinct().size == 1
        }.toSet()
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.94f)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        onDismiss()
                        true
                    } else false
                },
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.fillMaxSize().padding(14.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("AB 对比（${cards.size} 张）", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "彩色标签表示该组独有或不同的画师，次级高亮表示权重不同",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "关闭 AB 对比") }
                }
                Spacer(Modifier.height(10.dp))
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val columns = if (maxWidth >= 720.dp) cards.size.coerceIn(2, 4) else 2
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(columns),
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(cards, key = { it.draw.id }) { card ->
                            val image = card.image
                            Card(
                                border = if (card.favorite) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                            ) {
                                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            "#${card.draw.ordinal + 1}",
                                            modifier = Modifier.weight(1f),
                                            style = MaterialTheme.typography.titleMedium,
                                        )
                                        IconButton(
                                            onClick = {
                                                if (!card.favorite) haptics.confirm() else haptics.tick()
                                                onFavorite(card)
                                            },
                                            enabled = image != null,
                                            modifier = Modifier.size(34.dp),
                                        ) {
                                            Icon(
                                                imageVector = if (card.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                                contentDescription = if (card.favorite) "取消图片收藏" else "收藏图片和画师串",
                                                tint = if (card.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(20.dp),
                                            )
                                        }
                                        if (image != null) {
                                            IconButton(
                                                onClick = { onDismiss(); onOpen(image.imageId) },
                                                modifier = Modifier.size(34.dp),
                                            ) {
                                                Icon(Icons.Default.OpenInFull, "查看详情", Modifier.size(18.dp))
                                            }
                                        }
                                    }
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(if (columns >= 3) 260.dp else 210.dp)
                                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), MaterialTheme.shapes.medium),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (image != null) {
                                            net.pocketnai.ui.safety.SfwAsyncImage(
                                                file = resolve(image.relativePath),
                                                sensitive = image.sensitivePrompt,
                                                contentDescription = "对比图 #${card.draw.ordinal + 1}",
                                                modifier = Modifier.fillMaxSize(),
                                                contentScale = ContentScale.Fit,
                                            )
                                        } else {
                                            Text("图片不可用", style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        card.mix.artists.forEach { artist ->
                                            val isDifferentArtist = artist.tag !in commonArtistTags
                                            val isDifferentWeight = !isDifferentArtist && artist.tag !in identicalTagTicks
                                            val bg = when {
                                                isDifferentArtist -> MaterialTheme.colorScheme.tertiaryContainer
                                                isDifferentWeight -> MaterialTheme.colorScheme.secondaryContainer
                                                else -> MaterialTheme.colorScheme.surfaceVariant
                                            }
                                            val fg = when {
                                                isDifferentArtist -> MaterialTheme.colorScheme.onTertiaryContainer
                                                isDifferentWeight -> MaterialTheme.colorScheme.onSecondaryContainer
                                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                                            }
                                            Surface(shape = MaterialTheme.shapes.small, color = bg) {
                                                Text(
                                                    text = "${formatTicks(artist.ticks)} ${artist.tag.removePrefix("artist: ")}",
                                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = if (isDifferentArtist) FontWeight.SemiBold else FontWeight.Normal,
                                                    color = fg,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        }
                                    }
                                    SelectionContainer {
                                        Text(
                                            card.mix.prompt,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 3,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        if (onApplyArtistChain != null) {
                                            Button(
                                                onClick = {
                                                    onApplyArtistChain(card.mix.prompt)
                                                    onDismiss()
                                                },
                                                modifier = Modifier.fillMaxWidth(),
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                            ) {
                                                Icon(Icons.Default.AutoFixHigh, null, Modifier.size(16.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text("应用到生成面板", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                        }
                                        OutlinedButton(
                                            onClick = { clipboard.setText(AnnotatedString(card.mix.prompt)) },
                                            modifier = Modifier.fillMaxWidth(),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                        ) {
                                            Icon(Icons.Default.ContentCopy, null, Modifier.size(15.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("复制画师串", maxLines = 1)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ArtistLabStageInspector(
    cards: List<LabCard>,
    orderIds: List<String>,
    appendNewDraws: Boolean,
    currentCardId: String,
    lockedArtists: List<String>,
    excludedArtists: Set<String>,
    resolve: (String) -> File,
    onSelectCardId: (String) -> Unit,
    onFavorite: (LabCard) -> Unit,
    onPinArtist: (String) -> Unit,
    onUnpinArtist: (String) -> Unit,
    onExcludeArtist: (String) -> Unit,
    onReenableArtist: (String) -> Unit,
    onOpenDetail: (String) -> Unit,
    onApplyArtistChain: ((String) -> Unit)?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 沿用打开大图检视时冻结的顺序快照，同时读取最新的 LabCard（实时刷新收藏心形等状态）；
    // 收藏/取消收藏不会把当前页拉回置顶区，关闭检视回到网格后仍按收藏置顶展示。
    val inspectable = remember(cards, orderIds, appendNewDraws) {
        val withImage = cards.filter { it.image != null }
        if (orderIds.isEmpty()) {
            withImage
        } else {
            val byId = withImage.associateBy { it.draw.id }
            val pinned = orderIds.mapNotNull { byId[it] }
            if (pinned.isEmpty()) {
                withImage
            } else if (!appendNewDraws) {
                pinned
            } else {
                val seen = pinned.mapTo(HashSet(pinned.size)) { it.draw.id }
                pinned + withImage.filter { it.draw.id !in seen }
            }
        }
    }
    val currentIndex = inspectable.indexOfFirst { it.draw.id == currentCardId }
    val card = inspectable.getOrNull(currentIndex) ?: inspectable.firstOrNull()
    if (card == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val resolvedIndex = if (currentIndex >= 0) currentIndex else 0
    val clipboard = LocalClipboardManager.current
    val haptics = net.pocketnai.ui.motion.rememberPocketHaptics()
    val focusRequester = remember { FocusRequester() }
    var lastWheelAt by remember { mutableLongStateOf(0L) }

    val stepPrev: () -> Unit = {
        if (resolvedIndex > 0) onSelectCardId(inspectable[resolvedIndex - 1].draw.id)
    }
    val stepNext: () -> Unit = {
        if (resolvedIndex < inspectable.lastIndex) onSelectCardId(inspectable[resolvedIndex + 1].draw.id)
    }

    LaunchedEffect(card.draw.id) { runCatching { focusRequester.requestFocus() } }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.A, Key.DirectionLeft -> { stepPrev(); true }
                    Key.D, Key.DirectionRight -> { stepNext(); true }
                    Key.F -> {
                        if (!card.favorite) haptics.confirm() else haptics.tick()
                        onFavorite(card)
                        true
                    }
                    Key.Escape -> { onClose(); true }
                    else -> false
                }
            },
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "大图检视 · #${card.draw.ordinal + 1}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "${resolvedIndex + 1} / ${inspectable.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "A/D 切图 · F 收藏 · Esc 返回网格",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = {
                    if (!card.favorite) haptics.confirm() else haptics.tick()
                    onFavorite(card)
                }) {
                    Icon(
                        imageVector = if (card.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = if (card.favorite) "取消图片收藏" else "收藏图片和画师串",
                        tint = if (card.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                card.image?.let { image ->
                    IconButton(onClick = { onOpenDetail(image.imageId) }) {
                        Icon(Icons.Default.OpenInFull, "打开完整图片详情")
                    }
                }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "关闭大图检视") }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f), MaterialTheme.shapes.large)
                    .onMouseWheel { deltaY, _ ->
                        val now = System.currentTimeMillis()
                        if (now - lastWheelAt >= 180L) {
                            lastWheelAt = now
                            if (deltaY > 0f) stepNext() else if (deltaY < 0f) stepPrev()
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                card.image?.let { image ->
                    net.pocketnai.ui.safety.SfwAsyncImage(
                        file = resolve(image.relativePath),
                        sensitive = image.sensitivePrompt,
                        contentDescription = "第 ${card.draw.ordinal + 1} 张大图检视",
                        modifier = Modifier.fillMaxSize().padding(8.dp),
                        contentScale = ContentScale.Fit,
                    )
                }
                if (resolvedIndex > 0) {
                    FilledTonalIconButton(
                        onClick = stepPrev,
                        modifier = Modifier.align(Alignment.CenterStart).padding(12.dp),
                    ) {
                        Icon(Icons.Default.ChevronLeft, "上一张 (A)")
                    }
                }
                if (resolvedIndex < inspectable.lastIndex) {
                    FilledTonalIconButton(
                        onClick = stepNext,
                        modifier = Modifier.align(Alignment.CenterEnd).padding(12.dp),
                    ) {
                        Icon(Icons.Default.ChevronRight, "下一张 (D)")
                    }
                }
            }

            Surface(
                shape = MaterialTheme.shapes.large,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "点击画师标签可设为锁定基底或一键排除",
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
                                onPin = { onPinArtist(artistTag.tag) },
                                onUnpin = { onUnpinArtist(artistTag.tag) },
                                onExclude = { onExcludeArtist(artistTag.tag) },
                                onReenable = { onReenableArtist(artistTag.tag) },
                            )
                        }
                    }
                    SelectionContainer {
                        Text(
                            card.mix.prompt,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (onApplyArtistChain != null) {
                            Button(onClick = { onApplyArtistChain(card.mix.prompt) }) {
                                Icon(Icons.Default.AutoFixHigh, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("应用到生成面板")
                            }
                        }
                        OutlinedButton(onClick = { clipboard.setText(AnnotatedString(card.mix.prompt)) }) {
                            Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("复制画师串")
                        }
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(onClick = stepPrev, enabled = resolvedIndex > 0) { Text("上一张 (A)") }
                        OutlinedButton(onClick = stepNext, enabled = resolvedIndex < inspectable.lastIndex) { Text("下一张 (D)") }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ArtistLockPickerDialog(
    entries: List<ArtistCatalogEntry>,
    excluded: Set<String>,
    lockedArtists: List<String>,
    onToggleLock: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var queryField by remember { mutableStateOf(TextFieldValue(query)) }
    LaunchedEffect(query) {
        if (query != queryField.text) queryField = TextFieldValue(query)
    }
    val enabledEntries = remember(entries, excluded, query) {
        entries.filter { entry ->
            entry.tag !in excluded &&
                (query.isBlank() || entry.name.contains(query, ignoreCase = true) || entry.tag.contains(query, ignoreCase = true))
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f).fillMaxHeight(0.82f),
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("选择锁定基底画师", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "已锁定 ${lockedArtists.size} / ${ArtistLabConfig.MAX_LOCKED_ARTISTS} 位（其余名额随机抽选）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "关闭") }
                }
                net.pocketnai.ui.common.PillSearchField(
                    value = queryField,
                    onValueChange = { queryField = it; query = it.text },
                    placeholder = "搜索已启用画师",
                    clearContentDescription = "清除搜索",
                    onClear = { queryField = TextFieldValue(""); query = "" },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (enabledEntries.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("没有匹配的已启用画师", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(enabledEntries, key = { it.tag }) { entry ->
                            val isLocked = entry.tag in lockedArtists
                            val canSelect = isLocked || lockedArtists.size < ArtistLabConfig.MAX_LOCKED_ARTISTS
                            Surface(
                                shape = MaterialTheme.shapes.medium,
                                color = if (isLocked) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                                else MaterialTheme.colorScheme.surface,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = canSelect) { onToggleLock(entry.tag) },
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(entry.name.ifBlank { entry.tag.removePrefix("artist: ") }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(entry.tag.removePrefix("artist: "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    if (isLocked) {
                                        Icon(Icons.Default.Check, "已锁定", tint = MaterialTheme.colorScheme.primary)
                                    } else {
                                        Text(
                                            if (canSelect) "锁定" else "已满",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (canSelect) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(onClick = onDismiss) { Text("完成") }
                }
            }
        }
    }
}
