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
        var agent by mutableStateOf<ChatAgentAcceptance?>(null)
        var agentSettings by mutableStateOf(false)
        var disconnectOpen by mutableStateOf(false)
        var disconnectAllowed by mutableStateOf(true)
        var disconnectCount = 0
        var agentFontScale by mutableFloatStateOf(1f)
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
            compose.runOnUiThread {
                // The generic test Activity must match MainActivity's manifest IME policy.
                compose.activity.window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
            compose.setContent {
                MaterialTheme {
                    if (disconnectOpen) net.pocketnai.ui.connect.AccountDisconnectConfirmation(
                        "断开连接？", "仅离线测试账号，不访问用户凭据", "确认断开",
                        onConfirm = { if (disconnectAllowed) { disconnectCount++; true } else false },
                        onDismiss = { disconnectOpen = false },
                    )
                    ImageMotionRoot {
                        if (agent != null) {
                            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(
                                androidx.compose.ui.platform.LocalDensity.current.density, agentFontScale)) {
                                if (agentSettings) ChatSettingsScreen(agent!!.viewModel) { agentSettings = false }
                                else ChatScreen(agent!!.viewModel, { agent!!.image }, {})
                            }
                            return@ImageMotionRoot
                        }
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
            compose.mainClock.autoAdvance = true
            compose.runOnUiThread { disconnectOpen = true }
            compose.onNodeWithText("取消").performClick()
            compose.waitForIdle()
            compose.onNodeWithText("确认断开").assertDoesNotExist()
            assertThat(disconnectCount).isEqualTo(0)
            compose.runOnUiThread { disconnectOpen = true; disconnectAllowed = false }
            compose.onNodeWithText("确认断开").performClick()
            compose.onNodeWithText("确认断开").assertIsNotEnabled()
            assertThat(disconnectCount).isEqualTo(0)
            compose.onNodeWithText("取消").performClick()
            compose.waitForIdle()
            compose.onNodeWithText("确认断开").assertDoesNotExist()
            compose.runOnUiThread { disconnectOpen = true; disconnectAllowed = true }
            compose.onNodeWithText("确认断开").assertIsEnabled().performClick()
            compose.waitForIdle()
            assertThat(disconnectCount).isEqualTo(1)
            compose.mainClock.autoAdvance = false
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
                val ime = compose.activity.window.decorView.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.ime())?.bottom ?: 0
                // Insets can arrive before Compose's corresponding measure pass on Redroid.
                ime > 200 && compose.onNodeWithTag("composer").fetchSemanticsNode().boundsInWindow.bottom <=
                    compose.activity.window.decorView.height - ime
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
            net.pocketnai.data.chat.verifyLlmProviderPersistence(compose.activity)
            compose.runOnUiThread { agent = ChatAgentAcceptance(compose.activity, file) }
            val fixture = agent!!
            compose.waitUntil(5000) { fixture.viewModel.state.value.current != null }
            compose.onNodeWithContentDescription("对话菜单").performClick()
            compose.onNodeWithText("连接与人格").assertDoesNotExist()
            shell("input keyevent 4")
            compose.runOnUiThread { agentSettings = true }
            val originalProviderId = fixture.viewModel.providers.value.activeProviderId!!
            compose.onNodeWithText("添加提供商").performClick()
            compose.onNodeWithText("提供商名称").performTextInput("Offline second")
            compose.onNodeWithText("API Key").performTextInput("test-only-second-provider")
            compose.onNodeWithText("手动添加").performScrollTo().performClick()
            compose.onNodeWithText("模型 ID").performTextInput("offline-agent")
            compose.onNodeWithText("确定").assertIsDisplayed()
            compose.waitForIdle(); instrumentation.waitForIdleSync()
            shell("screencap -p /data/local/tmp/pocketnai-model-editor-keyboard.png")
            compose.onNodeWithText("确定").performClick()
            compose.onNodeWithText("保存").assertIsDisplayed()
            compose.onNodeWithText("保存").performClick()
            assertThat(fixture.viewModel.providers.value.providers).hasSize(2)
            assertThat(fixture.viewModel.providers.value.activeProviderId).isEqualTo(originalProviderId)
            compose.waitForIdle(); instrumentation.waitForIdleSync(); android.os.SystemClock.sleep(350)
            shell("screencap -p /data/local/tmp/pocketnai-provider-settings.png")
            compose.onNodeWithContentDescription("返回设置").performClick()
            compose.onNodeWithTag("chat-model-picker").performClick()
            compose.onAllNodesWithText("offline-agent").onLast().performClick()
            assertThat(fixture.viewModel.providers.value.activeProviderId).isNotEqualTo(originalProviderId)
            assertThat(fixture.savedPermission()).isEqualTo(net.pocketnai.domain.chat.ChatToolPermission.SINGLE_IMAGE)
            compose.onNodeWithTag("chat-model-picker").performClick()
            compose.waitForIdle(); instrumentation.waitForIdleSync(); android.os.SystemClock.sleep(350)
            shell("screencap -p /data/local/tmp/pocketnai-model-picker.png")
            compose.onAllNodesWithText("offline-agent").onFirst().performClick()
            assertThat(fixture.viewModel.providers.value.activeProviderId).isEqualTo(originalProviderId)
            compose.runOnUiThread { agentSettings = true }
            compose.onNodeWithText("联网搜索").performScrollTo().performClick()
            compose.onAllNodesWithText("DeepSeek", useUnmergedTree = true).onLast().performClick()
            compose.onNodeWithText("https://api.deepseek.com/v1").assertIsDisplayed()
            shell("screencap -p /data/local/tmp/pocketnai-agent-search.png")
            shell("input keyevent 4")
            compose.waitForIdle()
            compose.onNodeWithContentDescription("返回设置").performClick()
            compose.waitForIdle()
            compose.runOnUiThread { fixture.viewModel.send("请画三张风景分镜，先查资料") }
            compose.waitUntil(5000) { fixture.viewModel.state.value.pending != null }
            assertThat(fixture.generated).isEqualTo(0)
            assertThat(fixture.searches).isEqualTo(1)
            compose.onNodeWithTag("chat-confirm-image").performScrollTo().assertIsDisplayed()
            // Every row remains complete; its whole surface opens the correct parameters.
            for (index in 1..3) {
                compose.onNodeWithTag("chat-plan-image-$index").performScrollTo().assertIsDisplayed().performClick()
                compose.onNodeWithText("第 $index 张参数").assertIsDisplayed()
                compose.onNodeWithContentDescription("关闭全文").performClick()
            }
            compose.onNodeWithTag("chat-confirm-image").performScrollTo()
            val lastPlanRow = compose.onNodeWithTag("chat-plan-image-3").fetchSemanticsNode().boundsInRoot
            val confirm = compose.onNodeWithTag("chat-confirm-image").fetchSemanticsNode().boundsInRoot
            assertThat(lastPlanRow.bottom).isAtMost(confirm.top)
            assertThat(lastPlanRow.height).isGreaterThan(100f)
            compose.onNodeWithText("本次 3 张 · 合计预计 6 Anlas").assertIsDisplayed()
            compose.mainClock.advanceTimeBy(400); compose.waitForIdle(); android.os.SystemClock.sleep(150)
            shell("screencap -p /data/local/tmp/pocketnai-agent-plan-fixed.png")
            compose.onNodeWithTag("chat-confirm-image").performClick()
            compose.waitUntil(15_000) { !fixture.viewModel.state.value.busy }
            assertThat(fixture.generated).isEqualTo(3)
            fixture.verifySaved(3)
            // A timeout on image two preserves image one and never sends image three.
            fixture.failOn = 5
            compose.runOnUiThread { fixture.viewModel.newConversation(); fixture.viewModel.send("再规划三张分镜") }
            compose.waitUntil(5000) { fixture.viewModel.state.value.pending != null }
            compose.onNodeWithTag("chat-confirm-image").performScrollTo().performClick()
            compose.waitUntil(15_000) { !fixture.viewModel.state.value.busy }
            assertThat(fixture.generated).isEqualTo(5)
            fixture.verifySaved(1)
            // Stopping while a request is in flight finishes that image, then stops the rest.
            fixture.failOn = -1; fixture.gate = kotlinx.coroutines.CompletableDeferred()
            compose.runOnUiThread { fixture.viewModel.newConversation(); fixture.viewModel.send("准备下一组三张") }
            compose.waitUntil(5000) { fixture.viewModel.state.value.pending != null }
            compose.onNodeWithTag("chat-confirm-image").performScrollTo().performClick()
            compose.waitUntil(5000) { fixture.viewModel.state.value.generating }
            compose.onNodeWithTag("chat-stop").assertIsEnabled().performClick()
            fixture.gate!!.complete(Unit)
            compose.waitUntil(10_000) { !fixture.viewModel.state.value.busy }
            assertThat(fixture.generated).isEqualTo(6)
            fixture.verifySaved(1)
            // The saved FULL mode is exercised through the real settings UI and ViewModel.
            compose.runOnUiThread { agentSettings = true }
            compose.onNodeWithText("人格与工具权限").performScrollTo().performClick()
            compose.onNodeWithText("完全允许").performScrollTo().performClick()
            compose.mainClock.advanceTimeBy(400); compose.waitForIdle(); android.os.SystemClock.sleep(150)
            shell("screencap -p /data/local/tmp/pocketnai-agent-full-permission.png")
            compose.onNodeWithText("保存").performClick()
            assertThat(fixture.savedPermission()).isEqualTo(net.pocketnai.domain.chat.ChatToolPermission.FULL)
            compose.onNodeWithContentDescription("返回设置").performClick()
            fixture.gate = null
            compose.runOnUiThread { fixture.viewModel.newConversation(); fixture.viewModel.send("直接完成三张分镜") }
            compose.waitUntil(15_000) {
                assertThat(fixture.viewModel.state.value.pending).isNull()
                !fixture.viewModel.state.value.busy
            }
            assertThat(fixture.generated).isEqualTo(9)
            fixture.verifySaved(3)
            compose.runOnUiThread {
                agentFontScale = 1.35f
                fixture.plannedCount = 12
                fixture.viewModel.saveConfig(fixture.viewModel.config.value.copy(toolPermission = net.pocketnai.domain.chat.ChatToolPermission.ASK), "")
                fixture.viewModel.newConversation(); fixture.viewModel.send("规划十二张风景，先让我核对")
            }
            compose.waitUntil(5000) { fixture.viewModel.state.value.pending != null }
            compose.onNodeWithTag("chat-plan-image-12").performScrollTo().assertIsDisplayed().performClick()
            compose.onNodeWithText("第 12 张参数").assertIsDisplayed()
            compose.onNodeWithContentDescription("关闭全文").performClick()
            compose.onNodeWithTag("chat-confirm-image").performScrollTo().assertIsDisplayed()
            assertThat(compose.onNodeWithTag("chat-plan-image-12").fetchSemanticsNode().boundsInRoot.bottom)
                .isAtMost(compose.onNodeWithTag("chat-confirm-image").fetchSemanticsNode().boundsInRoot.top)
            compose.mainClock.advanceTimeBy(400); compose.waitForIdle(); android.os.SystemClock.sleep(150)
            shell("screencap -p /data/local/tmp/pocketnai-agent-plan-twelve-large.png")
            compose.runOnUiThread { fixture.viewModel.stop() }
            compose.waitUntil(5000) { !fixture.viewModel.state.value.busy }
            assertThat(fixture.generated).isEqualTo(9)
        } finally {
            compose.runOnUiThread { agent?.close(); agent = null }
            shell("ime set $previousIme"); shell("ime disable $helperIme")
            file.delete()
        }
    }
}
