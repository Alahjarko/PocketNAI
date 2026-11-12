package net.pocketnai.preview

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import net.pocketnai.domain.chat.*
import net.pocketnai.domain.model.ThemeMode
import net.pocketnai.ui.chat.*
import net.pocketnai.ui.theme.PocketNaiTheme
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.File

/** Offline production-composable previews; these do not simulate a conversation or call an API. */
object AssistantPreviewRenderer {
    fun render() {
        val reply = "可以保留原图的**银发、湖面和逆光**，把镜头拉远一些。\n\n" +
            "### 这次可以这样调整\n\n" +
            "- 人物放在画面左侧，给远山留出空间。\n- 使用柔和的清晨光线，保留发丝的亮边。\n\n" +
            "提示词片段：`wide composition, soft morning light, distant mountains`"
        runBlocking { warmChatMarkdown(reply) }
        val context = ChatImageContext("NovelAI", "1girl, silver hair, lakeside, morning light, wide composition",
            "lowres, blurry", "nai-diffusion-4-5-full", 832, 1216, 23, 7.0, 42, "k_euler_ancestral",
            characters = listOf(ChatContextCharacter("silver hair, blue eyes")))
        val entries = listOf(
            ChatEntry("question", wireMessage("user", "想保留这张图的氛围，构图再开阔一点。")),
            ChatEntry("answer", wireMessage("assistant", reply)),
        )
        listOf("assistant-light", "assistant-dark", "assistant-large-text", "assistant-history", "assistant-image-parameters").forEach { name ->
            val large = name == "assistant-large-text"
            val renderer = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(softButtons = false, fontScale = if (large) 1.5f else 1f))
            renderer.apply(object : Statement() {
                override fun evaluate() = renderer.snapshot(name) {
                    PocketNaiTheme(if (name == "assistant-dark") ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false) {
                        if (name == "assistant-image-parameters") Surface(Modifier.fillMaxSize()) {
                            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                item { Text("图片预览", style = MaterialTheme.typography.headlineSmall) }
                                item { ChatImageContextPanel(context) }
                                item { TextButton(onClick = {}) { Text("仅发送图片") } }
                            }
                        } else Surface(Modifier.fillMaxSize()) { ChatPageLayout(header = {
                            Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("绘伴", style = MaterialTheme.typography.titleLarge)
                            }
                            HorizontalDivider()
                        }, messages = {
                            val history = name == "assistant-history"
                            val shown = if (history) List(100) { index ->
                                ChatEntry("history-$index", wireMessage(if (index % 2 == 0) "user" else "assistant",
                                    if (index % 2 == 0) "第 ${index / 2 + 1} 轮：把画面的光线再柔和一点。" else reply))
                            } else entries
                            LazyColumn(state = rememberLazyListState(if (history) 92 else 0), modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(shown, key = { it.id }, contentType = { it.role }) {
                                    ChatMessageBubble(it, { path -> File(path) }, { _, _ -> })
                                }
                            }
                        }, floating = { ChatJumpToLatest(name == "assistant-history", {}) }, composer = {
                            ChatComposer(TextFieldValue(""), {}, "绘伴", false, false, false, false, {}, {},
                                leadingActions = { IconButton(onClick = {}) { Icon(Icons.Default.Add, "添加图片") } })
                        }) }
                    }
                }
            }, Description.createTestDescription(AssistantPreviewRenderer::class.java, name)).evaluate()
        }
    }
}
