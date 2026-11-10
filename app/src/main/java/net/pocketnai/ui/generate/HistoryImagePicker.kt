package net.pocketnai.ui.generate

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import net.pocketnai.domain.model.GalleryItem
import net.pocketnai.ui.LocalAppContainer
import net.pocketnai.ui.gallery.GalleryModelFilterChip
import net.pocketnai.ui.gallery.GalleryViewModel
import net.pocketnai.ui.safety.SfwAsyncImage
import net.pocketnai.ui.settings.SettingsEditorSheet

/** One selection surface shared by NovelAI references, OpenAI editing and chat attachments. */
@Composable
internal fun HistoryImagePickerDialog(onPick: (String) -> Unit, onDismiss: () -> Unit, onPickItem: ((GalleryItem) -> Unit)? = null) {
    val container = LocalAppContainer.current
    val vm: GalleryViewModel = viewModel(key = "history-image-picker", factory = viewModelFactory {
        initializer { GalleryViewModel(container.generationRepository, container.favoriteImageRepository) }
    })
    val all by vm.items.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val hasMore by vm.hasMore.collectAsStateWithLifecycle()
    val items = remember(all) { all.filterNot { it.relativePath.endsWith(".svg", true) } }
    val grid = rememberLazyGridState()
    var query by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(filter.query)) }
    var selected by remember { mutableStateOf<GalleryItem?>(null) }
    var preview by remember { mutableStateOf<GalleryItem?>(null) }
    var confirmed by remember { mutableStateOf(false) }
    LaunchedEffect(grid) {
        snapshotFlow { Triple(grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1, items.size, hasMore) }
            .collect { (last, count, more) -> if (more && (count == 0 || last >= count - 12)) vm.loadMore() }
    }
    SettingsEditorSheet("从历史选择图片", Icons.Default.PhotoLibrary, onDismiss = {
        val choice = selected
        if (confirmed && choice != null) onPickItem?.invoke(choice) ?: onPick(choice.relativePath) else onDismiss()
    }, scrollContent = false, saveLabel = "使用图片", saveEnabled = selected != null,
        onSave = { confirmed = true; true }) {
        OutlinedTextField(query, { query = it; vm.onQueryChange(it.text) }, label = { Text("搜索提示词") },
            singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) }, modifier = Modifier.fillMaxWidth().testTag("history-image-search"))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { GalleryModelFilterChip(filter.modelApiIds, vm::onModelsChange) }
            FilterChip(filter.favoritesOnly, { vm.onFavoritesOnlyChange(!filter.favoritesOnly) }, label = { Text("仅收藏") },
                leadingIcon = { Icon(Icons.Default.Favorite, null, Modifier.size(18.dp)) })
        }
        if (items.isEmpty() && !hasMore) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(if (filter.isActive) "没有符合条件的图片" else "还没有可选图片", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else LazyVerticalGrid(columns = GridCells.Adaptive(104.dp), state = grid,
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("history-image-picker"),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.imageId }) { item ->
                Box(Modifier.aspectRatio(item.width.toFloat() / item.height.coerceAtLeast(1)).clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer).testTag("history-image-${item.imageId}")
                    .clickable { selected = item }) {
                    SfwAsyncImage(container.fileStore.resolve(item.relativePath), "选择图片", sensitive = item.sensitivePrompt,
                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    if (selected?.imageId == item.imageId) Icon(Icons.Default.CheckCircle, "已选中", tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(50)))
                    FilledTonalIconButton(onClick = { preview = item }, modifier = Modifier.align(Alignment.BottomEnd).padding(2.dp).size(36.dp)) {
                        Icon(Icons.Default.OpenInFull, "预览图片", Modifier.size(18.dp))
                    }
                }
            }
        }
        selected?.let { Text("已选 · ${net.pocketnai.domain.model.GalleryModels.labelOf(it.modelApiId)}", style = MaterialTheme.typography.labelMedium,
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
    }
    preview?.let { item -> Dialog(onDismissRequest = { preview = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { IconButton(onClick = { preview = null }) { Icon(Icons.Default.Close, "关闭预览") } }
                SfwAsyncImage(container.fileStore.resolve(item.relativePath), "图片预览", sensitive = item.sensitivePrompt,
                    contentScale = ContentScale.Fit, modifier = Modifier.weight(1f).fillMaxWidth())
                Text(item.prompt, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Button(onClick = { selected = item; preview = null }, modifier = Modifier.fillMaxWidth()) { Text("选择这张") }
            }
        }
    } }
}
