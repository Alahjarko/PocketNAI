package net.pocketnai.ui.artistlab

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.*
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun ArtistMixFavoritesDialog(
    vm: ArtistLabViewModel,
    resolve: (String) -> File,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val mixes by vm.favoriteMixGallery.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var focusedPrompt by rememberSaveable { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<FavoriteArtistMix?>(null) }
    val visible = remember(mixes, query) {
        mixes.filter { ArtistMixFavoriteGallery.matches(it, query) }
    }
    val clipboard = LocalClipboardManager.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val copy: (FavoriteArtistMix) -> Unit = {
        clipboard.setText(AnnotatedString(it.prompt))
        snackbar.currentSnackbarData?.dismiss()
        scope.launch { snackbar.showSnackbar("已复制画师串") }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(0.92f).padding(horizontal = 12.dp),
            shape = MaterialTheme.shapes.extraLarge) {
            Box {
                Column(Modifier.padding(horizontal = 12.dp)) {
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("画师串收藏", style = MaterialTheme.typography.titleLarge)
                            Text("${mixes.size} 组", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "关闭画师串收藏") }
                    }
                    OutlinedTextField(query, { query = it }, label = { Text("搜索画师串") }, singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp))
                    if (visible.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(if (mixes.isEmpty()) "还没有收藏画师串" else "没有匹配的画师串")
                    } else LazyVerticalGrid(columns = GridCells.Adaptive(150.dp), modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(bottom = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(visible, key = { it.prompt }, contentType = { "favorite-mix" }) { mix ->
                            FavoriteMixCard(mix, resolve, { focusedPrompt = mix.prompt }, { copy(mix) }, { removing = mix })
                        }
                    }
                }
                if (focusedPrompt == null) SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(12.dp))
            }
        }
    }
    mixes.firstOrNull { it.prompt == focusedPrompt }?.let { mix ->
        FavoriteMixDetail(mix, resolve, { focusedPrompt = null }, { copy(mix) }, onOpen, snackbar)
    }
    removing?.let { mix ->
        AlertDialog(onDismissRequest = { removing = null }, title = { Text("移除画师串收藏？") },
            text = { Text("原图和图片星标会保留。") },
            confirmButton = { TextButton(onClick = { vm.removeMix(mix); removing = null }) { Text("移除") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("取消") } })
    }
}

@Composable
private fun FavoriteMixCard(mix: FavoriteArtistMix, resolve: (String) -> File,
                            onFocus: () -> Unit, onCopy: () -> Unit, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card {
        Box(Modifier.fillMaxWidth().aspectRatio(0.8f).clickable(onClick = onFocus), contentAlignment = Alignment.Center) {
            val cover = mix.images.firstOrNull()
            if (cover != null) AsyncImage(resolve(cover.relativePath), "画师串生成图",
                Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.ImageNotSupported, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("暂无关联图片", style = MaterialTheme.typography.bodySmall)
            }
            if (mix.images.size > 1) Surface(Modifier.align(Alignment.BottomEnd).padding(6.dp),
                shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)) {
                Text("${mix.images.size} 张", Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium)
            }
        }
        Text(mix.artists.joinToString(" · "), Modifier.fillMaxWidth().clickable(onClick = onFocus)
            .padding(start = 10.dp, end = 10.dp, top = 10.dp),
            style = MaterialTheme.typography.bodyMedium, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCopy, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp)); Text("复制")
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "画师串操作") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("查看图片与权重") }, onClick = { menu = false; onFocus() })
                    DropdownMenuItem(text = { Text("移除画师串收藏") }, onClick = { menu = false; onRemove() })
                }
            }
        }
    }
}

@Composable
private fun FavoriteMixDetail(mix: FavoriteArtistMix, resolve: (String) -> File,
                              onDismiss: () -> Unit, onCopy: () -> Unit, onOpen: (String) -> Unit,
                              snackbar: SnackbarHostState) {
    var selectedId by rememberSaveable(mix.prompt) { mutableStateOf(mix.images.firstOrNull()?.imageId) }
    val image = mix.images.firstOrNull { it.imageId == selectedId } ?: mix.images.firstOrNull()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(0.92f).padding(horizontal = 12.dp), shape = MaterialTheme.shapes.extraLarge) {
            Box {
              Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("图片与画师串", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    if (image != null) Text("${mix.images.indexOf(image) + 1} / ${mix.images.size}", style = MaterialTheme.typography.labelLarge)
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "关闭图片与画师串") }
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (image != null) AsyncImage(resolve(image.relativePath), "画师串生成图预览",
                        Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    else Text("暂无关联图片")
                }
                if (mix.images.size > 1) LazyRow(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(mix.images, key = { _, image -> image.imageId }, contentType = { _, _ -> "mix-image" }) { index, candidate ->
                        Surface(onClick = { selectedId = candidate.imageId }, shape = MaterialTheme.shapes.small,
                            border = if (candidate.imageId == image?.imageId) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null) {
                            AsyncImage(resolve(candidate.relativePath), "切换第 ${index + 1} 张",
                                Modifier.size(64.dp, 80.dp), contentScale = ContentScale.Fit)
                        }
                    }
                }
                SelectionContainer(Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
                    Text(mix.prompt, style = MaterialTheme.typography.bodyMedium)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onCopy, modifier = Modifier.weight(1f)) { Text("复制画师串") }
                    OutlinedButton(onClick = { image?.let { onOpen(it.imageId) } }, enabled = image != null,
                        modifier = Modifier.weight(1f)) { Text("图片详情") }
                }
              }
              SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(12.dp))
            }
        }
    }
}
