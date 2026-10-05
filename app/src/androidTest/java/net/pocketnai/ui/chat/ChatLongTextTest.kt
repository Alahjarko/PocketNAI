package net.pocketnai.ui.chat

import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import net.pocketnai.PocketNaiApplication
import net.pocketnai.domain.chat.*
import net.pocketnai.ui.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import java.util.UUID

/** 只测本地真实界面排版和手势；不调用 LLM 或图片服务，不改用户已有记录。 */
@RunWith(AndroidJUnit4::class)
class ChatLongTextTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun longHistoryReaderAndGestures() = runBlocking {
        val app = compose.activity.application as PocketNaiApplication
        val store = app.container.chatStore
        val id = "render-check-${UUID.randomUUID()}"
        val body = (1..1700).joinToString("\n") { "段落 $it：这是用于测量本地长文滚动的文本，完整消息保留，气泡只显示预览。" }
        val entries = (0..19).map { index -> ChatEntry("$id-$index", wireMessage(if (index % 2 == 0) "user" else "assistant",
            if (index == 19) body else "历史消息 $index\n" + body.take(12_000))) }
        val thread = HandlerThread("chat-frame-metrics").apply { start() }
        val frames = Collections.synchronizedList(mutableListOf<Double>())
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            frames += metrics.getMetric(FrameMetrics.TOTAL_DURATION) / 1_000_000.0
        }
        var vm: ChatViewModel? = null
        var previous: String? = null
        var listening = false
        var measuredWindow = compose.activity.window
        val measurements = mutableListOf<JsonObject>()
        fun record(phase: String) {
            val sorted = synchronized(frames) { frames.toList().sorted().also { frames.clear() } }
            assertThat(sorted.size).isGreaterThan(50)
            measurements += buildJsonObject {
                put("phase", phase); put("frames", sorted.size); put("p50_ms", sorted[sorted.size / 2])
                put("p95_ms", sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.lastIndex)])
                put("over_32ms_percent", 100.0 * sorted.count { it > 32 } / sorted.size)
            }
        }
        try {
            compose.waitForIdle()
            compose.runOnIdle {
                app.container.settingsStore.setChatEnabled(true)
                vm = ViewModelProvider(compose.activity)[ChatViewModel::class.java]
                previous = vm!!.state.value.current?.id
            }
            store.save(ChatConversation(id, "长文滑动验收", System.currentTimeMillis(), entries))
            compose.runOnIdle { vm!!.selectConversation(id) }
            compose.waitUntil(10_000) { vm!!.state.value.current?.id == id }
            compose.onNodeWithText("对话").performClick()
            compose.onNodeWithTag("chat-messages").performScrollToIndex(19)
            compose.onNodeWithText("查看全文（${body.length} 字）").assertExists()
            compose.onNodeWithText(body).assertDoesNotExist()
            screenshot(app.cacheDir, "chat-long-history.png")
            // 初次 Compose/JIT 预热后再统计滑动。
            repeat(2) {
                compose.onNodeWithTag("chat-messages").performTouchInput { swipeDown(durationMillis = 300) }
                compose.onNodeWithTag("chat-messages").performTouchInput { swipeUp(durationMillis = 300) }
            }
            compose.runOnIdle { measuredWindow.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper)); listening = true }
            repeat(5) { realSwipe(down = true); realSwipe(down = false) }
            compose.waitForIdle()
            compose.runOnIdle { measuredWindow.removeOnFrameMetricsAvailableListener(listener); listening = false }
            record("history_scroll")
            compose.onNodeWithTag("chat-messages").performScrollToIndex(19)
            compose.onNodeWithText("查看全文（${body.length} 字）").performScrollTo().performClick()
            compose.onNodeWithTag("chat-full-reader").assertExists()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("段落 1：", substring = true).fetchSemanticsNodes().isNotEmpty() }
            realSwipe(down = false); realSwipe(down = true)
            compose.runOnIdle {
                measuredWindow = WindowInspector.getGlobalWindowViews().mapNotNull(::dialogWindow).first()
                measuredWindow.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper)); listening = true
            }
            repeat(5) { realSwipe(down = false); realSwipe(down = true) }
            compose.waitForIdle()
            compose.runOnIdle { measuredWindow.removeOnFrameMetricsAvailableListener(listener); listening = false }
            record("reader_scroll")
            screenshot(app.cacheDir, "chat-long-reader.png")
            compose.onNodeWithTag("chat-full-reader").performScrollToIndex(ChatTextLayout.pages(body).lastIndex)
            compose.onNodeWithText("段落 1700：", substring = true).assertExists()
            compose.onNodeWithText("关闭全文").performClick()
            compose.onNodeWithTag("chat-messages").performTouchInput { swipeDown(durationMillis = 500) }
            compose.onNodeWithTag("chat-follow-latest").assertExists()
            compose.onNodeWithTag("chat-follow-latest").performClick()
            File(app.cacheDir, "chat-long-performance.json").writeText(buildJsonObject {
                put("messages", entries.size); put("total_chars", entries.sumOf { it.content.length }); put("reader_chars", body.length)
                put("measurements", JsonArray(measurements))
            }.toString())
        } finally {
            if (listening) compose.runOnIdle { measuredWindow.removeOnFrameMetricsAvailableListener(listener) }
            store.delete(id)
            compose.runOnIdle { previous?.let { vm?.selectConversation(it) } }
            thread.quitSafely()
        }
    }

    private fun dialogWindow(view: View): Window? {
        if (view is DialogWindowProvider) return view.window
        if (view is ViewGroup) for (index in 0 until view.childCount) dialogWindow(view.getChildAt(index))?.let { return it }
        return null
    }

    private fun realSwipe(down: Boolean) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val start = if (down) 750 else 1500
        val end = if (down) 1500 else 750
        automation.executeShellCommand("input swipe 540 $start 540 $end 450").use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
        Thread.sleep(100)
    }

    private fun screenshot(root: File, name: String) {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(root, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
