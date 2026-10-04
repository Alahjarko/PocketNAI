package net.pocketnai.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.pocketnai.domain.chat.ChatTextLayout

/** 阅读器逐段排版，与气泡高度和滚动动画独立；完整文本不丢失。 */
@Composable
fun ChatTextReader(title: String, text: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var copyNotice by remember { mutableStateOf<String?>(null) }
    val pages by produceState<List<String>?>(null, text) {
        value = withContext(Dispatchers.Default) { ChatTextLayout.pages(text) }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
      Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
       Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(top = 8.dp))
            TextButton(onClick = { copyNotice = if (runCatching { clipboard.setText(AnnotatedString(text)) }.isSuccess) "已复制全文" else "文本过长，系统无法复制全文" }) { Text("复制全文") }
            TextButton(onClick = onDismiss) { Text("关闭全文") }
        }
        Text("${text.length} 字 · 分段阅读", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 20.dp))
        copyNotice?.let { Text(it, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 20.dp)) }
        if (pages == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(20.dp))
        LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("chat-full-reader"),
            contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(pages.orEmpty(), key = { index, _ -> index }, contentType = { _, _ -> "paragraph" }) { _, page ->
                SelectionContainer { Text(page, style = MaterialTheme.typography.bodyLarge) }
            }
        }
       }
      }
    }
}
