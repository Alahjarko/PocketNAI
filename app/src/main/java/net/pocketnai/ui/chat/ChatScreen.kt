package net.pocketnai.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.window.Dialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import net.pocketnai.ui.generate.HistoryImagePickerDialog
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import net.pocketnai.ui.motion.sharedImage
import net.pocketnai.ui.motion.sharedImageRequest
import net.pocketnai.ui.motion.LocalImageMotion
import net.pocketnai.ui.motion.ImageOriginPreview
import net.pocketnai.domain.chat.*
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel, resolveImage: (String) -> File, onOpenImage: (String) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val config by viewModel.config.collectAsStateWithLifecycle()
    var settingsOpen by remember { mutableStateOf(false) }
    var sessionsOpen by remember { mutableStateOf(false) }
    var imagesOpen by remember { mutableStateOf(false) }
    var input by rememberSaveable(state.current?.id, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    var attachmentMenu by remember { mutableStateOf(false) }
    var galleryPicker by remember { mutableStateOf(false) }
    var viewingAttachment by remember { mutableStateOf<ChatAttachment?>(null) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { viewModel.addPhoto(it.toString()) } }
    var menuOpen by remember { mutableStateOf(false) }
    var deleteId by remember { mutableStateOf<String?>(null) }
    var reading by remember { mutableStateOf<Pair<String, String>?>(null) }
    var following by rememberSaveable { mutableStateOf(true) }
    var shownId by rememberSaveable { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()
    val entries = state.current?.entries.orEmpty()
    val busy by rememberUpdatedState(state.busy)
    LaunchedEffect(state.current?.id) {
        if (shownId != state.current?.id) { following = true; shownId = state.current?.id }
        withFrameNanos { }
        if (following && list.layoutInfo.totalItemsCount > 0) list.scrollToItem(list.layoutInfo.totalItemsCount - 1)
    }
    LaunchedEffect(list) {
        list.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> following = false
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    val info = list.layoutInfo
                    val last = info.visibleItemsInfo.lastOrNull()
                    following = last != null && last.index == info.totalItemsCount - 1 &&
                        last.offset + last.size <= info.viewportEndOffset + 48
                }
            }
        }
    }
    // 同一个协程顺序跟随，流式 token 不会取消并重启滚动动画。
    LaunchedEffect(list) {
        while (true) {
            delay(160)
            if (!following || list.isScrollInProgress || reading != null) continue
            val info = list.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: continue
            if (last.index < info.totalItemsCount - 1) list.animateScrollToItem(info.totalItemsCount - 1)
            else {
                val distance = (last.offset + last.size - info.viewportEndOffset).coerceAtLeast(0).toFloat()
                if (distance > 0) {
                    if (busy) list.scrollBy(distance)
                    else list.animateScrollBy(distance, tween(160))
                }
            }
        }
    }
    fun submit() {
        if (!viewModel.hasKey() || config.model.isBlank()) { settingsOpen = true; return }
        if (state.busy || state.importingAttachment || (input.text.isBlank() && state.draftAttachments.isEmpty())) return
        following = true
        viewModel.send(input.text)
        input = TextFieldValue("")
        focus.clearFocus(); keyboard?.hide()
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)) {
                    Icon(Icons.Default.Menu, "对话菜单")
                }
                DropdownMenu(menuOpen, { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("新对话") }, onClick = { viewModel.newConversation(); menuOpen = false }, enabled = !state.busy)
                    DropdownMenuItem(text = { Text("历史对话") }, onClick = { sessionsOpen = true; menuOpen = false })
                    DropdownMenuItem(text = { Text("连接与人格") }, onClick = { settingsOpen = true; menuOpen = false })
                }
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(34.dp).background(Color(0xFFFF8866), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.AutoAwesome, null, tint = Color(0xFF28150C), modifier = Modifier.size(22.dp))
                }
                Text(config.assistantName.ifBlank { "绘伴" }, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                if (config.model.isNotBlank()) Text(config.model, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { imagesOpen = true }, modifier = Modifier.testTag("chat-image-history").background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)) {
                Icon(Icons.Default.History, "打开对话图片记录")
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
        LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth().testTag("chat-messages"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (entries.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("聊聊你想画的场景", style = MaterialTheme.typography.titleLarge)
                    Text("人物、旅行、风景，或一个突然想到的故事。", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!viewModel.hasKey()) OutlinedButton(onClick = { settingsOpen = true }) { Text("连接 LLM") }
                }
            }
            items(entries, key = { it.id }, contentType = { "message" }) { entry ->
                ChatMessageBubble(entry, resolveImage, onOpenImage, onRead = { title, text -> reading = title to text }, onOpenAttachment = { viewingAttachment = it })
            }
            state.partial?.let { partial -> item(key = "partial", contentType = "message") {
                ChatMessageBubble(ChatEntry("partial", partial), resolveImage, onOpenImage, streaming = true)
            } }
            state.pending?.let { pending -> item {
                Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("准备生成一张图片", style = MaterialTheme.typography.titleSmall)
                        Text("${pending.params.model.displayName} · ${pending.params.size.label} · ${pending.quote}", style = MaterialTheme.typography.bodySmall)
                        SelectionContainer { Text(pending.params.prompt, maxLines = 4, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { viewModel.confirmImage(true) }, modifier = Modifier.testTag("chat-confirm-image")) { Text("生成图片") }
                            TextButton(onClick = { viewModel.confirmImage(false) }) { Text("取消") }
                        }
                    }
                }
            } }
            if (state.busy) item { Text(state.phase, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            state.error?.let { error -> item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    IconButton(onClick = viewModel::dismissError) { Icon(Icons.Default.Close, "关闭错误提示") }
                }
            } }
        }
        if (!following && entries.isNotEmpty()) TextButton(onClick = { following = true }, modifier = Modifier.align(Alignment.End).testTag("chat-follow-latest")) {
            Icon(Icons.Default.KeyboardArrowDown, null); Text("回到最新")
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp).testTag("chat-composer")) {
          Column {
            if (state.draftAttachments.isNotEmpty()) LazyRow(Modifier.fillMaxWidth().testTag("chat-attachment-strip"),
                contentPadding = PaddingValues(start = 14.dp, top = 12.dp, end = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(state.draftAttachments, key = { it.id }) { attachment ->
                    Box(Modifier.size(80.dp)) {
                        AsyncImage(resolveImage(attachment.relativePath), "待发送图片", contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).clickable { viewingAttachment = attachment })
                        IconButton(onClick = { viewModel.removeAttachment(attachment.id) }, enabled = !state.busy && !state.importingAttachment,
                            modifier = Modifier.align(Alignment.TopEnd).size(32.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), CircleShape).testTag("chat-remove-attachment")) {
                            Icon(Icons.Default.Close, "移除图片", modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
            if (state.importingAttachment) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp))
            Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    IconButton(onClick = { focus.clearFocus(); keyboard?.hide(); attachmentMenu = true }, enabled = !state.busy && !state.importingAttachment,
                        modifier = Modifier.testTag("chat-attach-button")) { Icon(Icons.Default.Add, "添加图片") }
                    DropdownMenu(attachmentMenu, { attachmentMenu = false }) {
                        DropdownMenuItem(text = { Text("从相册选择") }, onClick = { attachmentMenu = false; photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
                        DropdownMenuItem(text = { Text("从软件画廊选择") }, onClick = { attachmentMenu = false; galleryPicker = true })
                    }
                }
                TextField(value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f).testTag("chat-input"),
                    placeholder = { Text("给 ${config.assistantName.ifBlank { "绘伴" }} 发消息", maxLines = 1) }, maxLines = 5,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { submit() }),
                    colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
                if (state.busy) IconButton(onClick = viewModel::stop, enabled = !state.generating) { Icon(Icons.Default.Stop, "停止回复") }
                else IconButton(onClick = { submit() }, enabled = !state.importingAttachment && (input.text.isNotBlank() || state.draftAttachments.isNotEmpty()), modifier = Modifier.testTag("chat-send")) {
                    Icon(Icons.AutoMirrored.Filled.Send, "发送", tint = MaterialTheme.colorScheme.primary)
                }
            }
          }
        }
    }
    if (settingsOpen) ChatSettingsDialog(viewModel) { settingsOpen = false }
    if (sessionsOpen) ModalBottomSheet(onDismissRequest = { sessionsOpen = false }) {
        Text("历史对话", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(20.dp))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 500.dp)) {
            items(state.conversations, key = { it.id }) { chat ->
                ListItem(headlineContent = { Text(chat.title, maxLines = 2) },
                    supportingContent = { Text("${chat.entries.count { it.role == "user" }} 条消息") },
                    modifier = Modifier.clickable(enabled = !state.busy) { viewModel.selectConversation(chat.id); sessionsOpen = false },
                    trailingContent = { IconButton(onClick = { deleteId = chat.id }, enabled = !state.busy) { Icon(Icons.Default.DeleteOutline, "删除对话") } })
            }
        }
        Spacer(Modifier.height(32.dp))
    }
    if (imagesOpen) ModalBottomSheet(onDismissRequest = { imagesOpen = false }) {
        val images = (listOfNotNull(state.current) + state.conversations).flatMap { chat -> chat.entries.asReversed().flatMap { it.images } }.distinctBy { it.imageId }
        Text("对话生成的图片", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(20.dp))
        if (images.isEmpty()) Text("这里会显示聊天中生成的图片", modifier = Modifier.padding(20.dp))
        LazyVerticalGrid(columns = GridCells.Fixed(2), modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
            contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(images, key = { it.imageId }) { image ->
                AsyncImage(resolveImage(image.relativePath), "聊天生成的图片", contentScale = ContentScale.Crop,
                    modifier = Modifier.aspectRatio(0.85f).clip(RoundedCornerShape(18.dp)).clickable { imagesOpen = false; onOpenImage(image.imageId) })
            }
        }
        Spacer(Modifier.height(32.dp))
    }
    deleteId?.let { id -> AlertDialog(onDismissRequest = { deleteId = null }, title = { Text("删除此对话？") },
        text = { Text("该对话的消息会被删除，图库中的图片仍会保留。") },
        confirmButton = { TextButton(onClick = { viewModel.deleteConversation(id); deleteId = null }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deleteId = null }) { Text("取消") } }) }
    reading?.let { (title, text) -> ChatTextReader(title, text) { reading = null } }
    if (galleryPicker) HistoryImagePickerDialog(onPick = { path -> viewModel.addGalleryImage(path); galleryPicker = false }, onDismiss = { galleryPicker = false })
    viewingAttachment?.let { attachment -> Dialog(onDismissRequest = { viewingAttachment = null }) {
        Surface(shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxWidth()) {
                AsyncImage(resolveImage(attachment.relativePath), "图片附件", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp))
                TextButton(onClick = { viewingAttachment = null }, modifier = Modifier.align(Alignment.End)) { Text("关闭图片") }
            }
        }
    } }
}

@Composable
fun ChatMessageBubble(entry: ChatEntry, resolveImage: (String) -> File, onOpenImage: (String) -> Unit,
    streaming: Boolean = false, onRead: (String, String) -> Unit = { _, _ -> }, onOpenAttachment: (ChatAttachment) -> Unit = {}) {
    if (entry.content.isBlank() && entry.reasoning.isBlank() && entry.images.isEmpty() && entry.attachments.isEmpty() && entry.notice == null) return
    val imageMotion = LocalImageMotion.current
    val user = entry.role == "user"
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (user) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        entry.attachments.forEach { attachment ->
            val file = resolveImage(attachment.relativePath)
            if (file.isFile) AsyncImage(file, "发送的图片附件", contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth(0.72f).aspectRatio(attachment.width.toFloat() / attachment.height.coerceAtLeast(1))
                    .clip(RoundedCornerShape(22.dp)).clickable { onOpenAttachment(attachment) })
            else Text("图片附件已丢失", style = MaterialTheme.typography.bodySmall)
        }
        entry.images.forEach { image ->
            val file = resolveImage(image.relativePath)
            if (file.isFile) AsyncImage(sharedImageRequest(file), "对话生成的图片", contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth(0.78f).aspectRatio(image.width.toFloat() / image.height.coerceAtLeast(1))
                    .sharedImage(image.imageId).clip(RoundedCornerShape(22.dp)).clickable { imageMotion?.preview = ImageOriginPreview(image.imageId, file, image.width.toFloat() / image.height.coerceAtLeast(1), "对话生成的图片"); onOpenImage(image.imageId) })
            else Text("图片已从图库移除", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (entry.role == "tool") {
            entry.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            if (entry.reasoning.isNotBlank()) {
                var expanded by remember(entry.id) { mutableStateOf(false) }
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起思考过程" else "思考过程") }
                if (expanded) Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(0.92f)) {
                    BoundedChatText(entry.reasoning, "思考过程", streaming, onRead, small = true)
                }
            }
            if (entry.content.isNotBlank()) Surface(shape = RoundedCornerShape(24.dp),
                color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(if (user) 0.86f else 0.92f)) {
                BoundedChatText(entry.content, if (user) "我的消息" else "回复全文", streaming, onRead)
            }
        }
    }
}

@Composable
private fun BoundedChatText(text: String, title: String, streaming: Boolean,
    onRead: (String, String) -> Unit, small: Boolean = false) {
    val long = remember(text) { ChatTextLayout.isLong(text) }
    val visible = remember(text, streaming) { if (streaming) ChatTextLayout.liveTail(text) else if (long) ChatTextLayout.preview(text) else text }
    Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
        if (streaming && long) Text("正在回复 · ${text.length} 字 · 显示最新片段", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer { Text(visible, style = if (small) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyLarge,
            overflow = TextOverflow.Ellipsis) }
        if (!streaming && long) TextButton(onClick = { onRead(title, text) }, modifier = Modifier.testTag("chat-read-full")) {
            Text("查看全文（${text.length} 字）")
        }
    }
}
