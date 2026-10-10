package net.pocketnai.ui.chat

import androidx.activity.ComponentActivity
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import net.pocketnai.domain.chat.ChatEntry
import net.pocketnai.domain.chat.ChatImage
import net.pocketnai.domain.chat.wireMessage
import net.pocketnai.ui.PocketNaiPageFrame
import net.pocketnai.ui.motion.*
import coil.compose.AsyncImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ChatShortReplyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun 聊天图文及键盘往返保持输入区稳定() {
        val text = (1..12).joinToString("\n\n") { "$it. 角色、服装、场景与光线的要点" }
        var replyText by mutableStateOf(text)
        var streaming by mutableStateOf(false)
        var jumpVisible by mutableStateOf(false)
        val file = File(compose.activity.cacheDir, "chat-motion-${UUID.randomUUID()}.png")
        Bitmap.createBitmap(400, 1000, Bitmap.Config.ARGB_8888).let { bitmap ->
            bitmap.eraseColor(android.graphics.Color.BLUE)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
        var imeBottom by mutableIntStateOf(0)
        var systemIme by mutableStateOf(false)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes().toString(Charsets.UTF_8).trim() }
        }
        val previousIme = shell("settings get secure default_input_method")
        val helperIme = "net.pocketnai.test/net.pocketnai.ui.chat.ChatInsetTestIme"
        lateinit var motion: ImageMotionState
        lateinit var list: androidx.compose.foundation.lazy.LazyListState
        compose.mainClock.autoAdvance = false
        try {
            compose.setContent {
                MaterialTheme {
                    ImageMotionRoot {
                        motion = LocalImageMotion.current!!
                        val nav = rememberNavController()
                        NavHost(nav, "chat", enterTransition = { imagePageEnter() }, exitTransition = { imagePageExit() }) {
                            composable("chat") {
                                ImageMotionRoute(this, "chat") {
                                    PocketNaiPageFrame(nav, "chat", true, false,
                                        keyboardInsets = if (systemIme) WindowInsets.ime else WindowInsets(bottom = imeBottom)) {
                                        ChatPageLayout(header = { Text("对话", Modifier.height(64.dp).padding(16.dp)) }, messages = {
                                            list = rememberLazyListState()
                                            LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
                                                item { ChatMessageBubble(ChatEntry("short", wireMessage("assistant", replyText)), { file }, {}, streaming = streaming) }
                                                item { ChatMessageBubble(ChatEntry("image", wireMessage("tool", ""), images = listOf(
                                                    ChatImage("sample", file.path, 400, 1000))), { file }, {
                                                    motion.open("chat", it); nav.navigate("detail")
                                                }) }
                                            }
                                        }, floating = {
                                            ChatJumpToLatest(jumpVisible) { jumpVisible = false }
                                        }, composer = {
                                            var input by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue("")) }
                                            ChatComposer(input, { input = it }, "绘伴", false, false, false, false, {}, {}, modifier = Modifier.testTag("composer"))
                                        })
                                    }
                                }
                            }
                            composable("detail") {
                                ImageMotionRoute(this, "chat") {
                                    Column {
                                        Text("返回", Modifier.clickable { motion.returnToSource(); nav.popBackStack() }.padding(24.dp))
                                        AsyncImage(sharedImageRequest(file), "大图", modifier = Modifier.fillMaxWidth().aspectRatio(0.4f).sharedImage("sample"))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            compose.mainClock.advanceTimeBy(500)
            compose.waitUntil(5000) { compose.onAllNodesWithText("角色、服装、场景与光线的要点").fetchSemanticsNodes().size == 12 }
            compose.onAllNodesWithTag("chat-read-full").assertCountEquals(0)
            val baseline = compose.onNodeWithTag("composer").fetchSemanticsNode().boundsInRoot.bottom
            val viewport = compose.onNodeWithTag("chat-message-viewport").fetchSemanticsNode().boundsInRoot
            compose.runOnUiThread { jumpVisible = true }
            compose.mainClock.advanceTimeBy(200)
            compose.onNodeWithContentDescription("回到最新").assertIsDisplayed()
            assertThat(compose.onNodeWithTag("chat-message-viewport").fetchSemanticsNode().boundsInRoot).isEqualTo(viewport)
            assertThat(compose.onNodeWithTag("composer").fetchSemanticsNode().boundsInRoot.bottom).isEqualTo(baseline)
            compose.onNodeWithContentDescription("回到最新").performClick()
            compose.mainClock.advanceTimeBy(200)
            compose.onNodeWithTag("chat-follow-latest").assertDoesNotExist()
            val barHeight = compose.onNodeWithTag("bottom-navigation").fetchSemanticsNode().boundsInRoot.height
            val peak = barHeight.toInt() + 240
            // Insets advance frame by frame, including the final keyboard/bar handoff.
            val heights = (0..peak step 16).toList() + peak + (peak downTo 0 step 16).toList() + 0
            heights.forEach { height ->
                compose.runOnUiThread { imeBottom = height }
                compose.mainClock.advanceTimeBy(32)
                val bottom = compose.onNodeWithTag("composer").fetchSemanticsNode().boundsInRoot.bottom
                assertThat(bottom).isWithin(2f).of(baseline - (height - barHeight).coerceAtLeast(0f))
            }
            compose.runOnUiThread { streaming = true; replyText = "## 雨后计划\n\n这是**加粗" }
            compose.waitUntil(5000) {
                compose.mainClock.advanceTimeBy(32)
                compose.onAllNodesWithText("这是加粗").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onAllNodesWithText("**", substring = true).assertCountEquals(0)
            compose.runOnUiThread { replyText += "文本**。\n\n```kotlin\nval n = 2\n```" }
            compose.waitUntil(5000) {
                compose.mainClock.advanceTimeBy(32)
                compose.onAllNodesWithText("val n = 2", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.runOnUiThread { streaming = false }
            compose.mainClock.advanceTimeBy(100)
            compose.onNodeWithText("这是加粗文本。").assertExists()
            val boldLayouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithText("这是加粗文本。").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(boldLayouts) }
            assertThat(boldLayouts.single().layoutInput.text.spanStyles.any { it.item.fontWeight == androidx.compose.ui.text.font.FontWeight.Bold }).isTrue()
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
                File(compose.activity.cacheDir, "chat-native-markdown.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            // Replay real Compose layout/draw work locally; no provider or user history is used.
            val durations = java.util.Collections.synchronizedList(mutableListOf<Double>())
            val metricsThread = android.os.HandlerThread("markdown-frame-metrics").also { it.start() }
            val listener = android.view.Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
                durations.add(metrics.getMetric(android.view.FrameMetrics.TOTAL_DURATION) / 1_000_000.0)
            }
            compose.runOnUiThread { compose.activity.window.addOnFrameMetricsAvailableListener(listener, android.os.Handler(metricsThread.looper)); streaming = true }
            repeat(40) { index ->
                compose.runOnUiThread { replyText += "\n\n### 段落 $index\n\n这是**画面要点**：服装、场景和光线保持一致。".repeat(3) }
                compose.mainClock.advanceTimeBy(48)
                compose.waitForIdle()
                android.os.SystemClock.sleep(35)
            }
            compose.runOnUiThread { compose.activity.window.removeOnFrameMetricsAvailableListener(listener); streaming = false }
            metricsThread.quitSafely(); metricsThread.join()
            compose.mainClock.advanceTimeBy(100)
            val frames = synchronized(durations) { durations.sorted() }
            assertThat(frames.size).isGreaterThan(10)
            File(compose.activity.cacheDir, "chat-markdown-replay.json").writeText(
                "{\"updates\":40,\"sourceChars\":${replyText.length},\"frames\":${frames.size},\"p95Ms\":${frames[(frames.size * 0.95).toInt().coerceAtMost(frames.lastIndex)]},\"over32Ms\":${frames.count { it > 32 }}}")
            compose.onNodeWithTag("chat-read-full").assertExists()
            // Return to the image case in this same local conversation, without changing user data.
            compose.runOnUiThread { replyText = text }
            compose.runOnIdle { kotlinx.coroutines.runBlocking { list.scrollToItem(1) } }
            compose.mainClock.advanceTimeBy(500)
            compose.waitUntil(5000) {
                compose.mainClock.advanceTimeBy(32)
                val imageBounds = compose.onNodeWithContentDescription("对话生成的图片").fetchSemanticsNode().boundsInRoot
                val visible = compose.onRoot().captureToImage().asAndroidBitmap()
                val pixel = visible.getPixel(imageBounds.center.x.toInt(), imageBounds.center.y.toInt())
                android.graphics.Color.blue(pixel) - android.graphics.Color.red(pixel) > 200
            }
            compose.onNodeWithContentDescription("对话生成的图片").performClick()
            compose.mainClock.advanceTimeBy(500)
            compose.onNodeWithText("返回").performClick()
            compose.mainClock.advanceTimeBy(280)
            val region = compose.onNodeWithTag("chat-composer-region").fetchSemanticsNode().boundsInRoot
            val frame = compose.onRoot().captureToImage().asAndroidBitmap()
            val imageAboveComposer = (region.top.toInt() + 2 until region.bottom.toInt() - 2 step 4).any { y ->
                (region.left.toInt() + 2 until region.right.toInt() - 2 step 4).any { x ->
                    val pixel = frame.getPixel(x.coerceIn(0, frame.width - 1), y.coerceIn(0, frame.height - 1))
                    android.graphics.Color.blue(pixel) - android.graphics.Color.red(pixel) > 60
                }
            }
            assertThat(imageAboveComposer).isFalse()
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
                File(compose.activity.cacheDir, "chat-return-composer.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            compose.mainClock.advanceTimeBy(400)
            assertThat(motion.returning).isNull()
            compose.onNodeWithTag("composer").assertIsDisplayed()
            compose.onNodeWithTag("bottom-navigation").assertIsDisplayed()
            // A real Android IME window complements the deterministic animation-frame check.
            shell("ime enable $helperIme"); shell("ime set $helperIme")
            compose.runOnUiThread { systemIme = true }
            compose.mainClock.autoAdvance = true
            compose.onNodeWithTag("chat-input").performClick()
            compose.waitUntil(10_000) {
                (compose.activity.window.decorView.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.ime())?.bottom ?: 0) > 200
            }
            val keyboardTop = compose.activity.window.decorView.height -
                compose.activity.window.decorView.rootWindowInsets.getInsets(android.view.WindowInsets.Type.ime()).bottom
            assertThat(compose.onNodeWithTag("composer").fetchSemanticsNode().boundsInWindow.bottom).isAtMost(keyboardTop.toFloat())
            compose.runOnUiThread {
                fun editor(view: android.view.View): android.view.View? {
                    if (view.javaClass.simpleName == "AndroidComposeView") return view
                    if (view is android.view.ViewGroup) for (index in 0 until view.childCount) editor(view.getChildAt(index))?.let { return it }
                    return null
                }
                val connection = editor(compose.activity.window.decorView)!!.onCreateInputConnection(android.view.inputmethod.EditorInfo())!!
                connection.setComposingText("xiang", 1)
                connection.setComposingText("xiangyao", 1)
                connection.commitText("想要", 1)
                connection.setSelection(0, 0)
                connection.commitText("再", 1)
            }
            compose.onNodeWithTag("chat-input").assertTextEquals("再想要")
            compose.onNodeWithTag("chat-input").performTextClearance()
            shell("input keyevent 4")
            compose.waitUntil(10_000) {
                (compose.activity.window.decorView.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.ime())?.bottom ?: 0) == 0
            }
            assertThat(compose.onNodeWithTag("composer").fetchSemanticsNode().boundsInRoot.bottom).isWithin(2f).of(baseline)
        } finally {
            shell("ime set $previousIme"); shell("ime disable $helperIme")
            file.delete()
        }
    }
}
