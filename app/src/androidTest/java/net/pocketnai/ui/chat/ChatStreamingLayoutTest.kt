package net.pocketnai.ui.chat

import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.*
import net.pocketnai.domain.chat.ChatEntry
import net.pocketnai.domain.chat.wireMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections

/** 测的是生产气泡的本地增量排版，不模拟或声称任何服务端调用成功。 */
@RunWith(AndroidJUnit4::class)
class ChatStreamingLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun longStreamingTailHasBoundedLayoutCost() {
        val value = mutableStateOf("开始回复。")
        compose.setContent {
            MaterialTheme {
                LazyColumn(Modifier.fillMaxSize()) {
                    item { ChatMessageBubble(ChatEntry("partial", wireMessage("assistant", value.value)), { File("") }, {}, streaming = true) }
                }
            }
        }
        compose.waitForIdle()
        val thread = HandlerThread("stream-frame-metrics").apply { start() }
        val frames = Collections.synchronizedList(mutableListOf<Double>())
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ -> frames += metrics.getMetric(FrameMetrics.TOTAL_DURATION) / 1_000_000.0 }
        var listening = false
        try {
            // 先预热生产气泡文字测量，再测 5 万至 10 万字的更新成本。
            val full = "这是持续增长的本地长文排版数据。".repeat(10_000)
            compose.runOnIdle { value.value = full.take(50_000) }
            compose.waitForIdle()
            compose.runOnIdle { compose.activity.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper)); listening = true }
            repeat(40) { index ->
                compose.runOnIdle { value.value = full.take(50_000 + (index + 1) * 1250) }
                Thread.sleep(120)
            }
            compose.waitForIdle()
            compose.runOnIdle { compose.activity.window.removeOnFrameMetricsAvailableListener(listener); listening = false }
            compose.onNodeWithText(value.value).assertDoesNotExist()
            compose.onAllNodesWithTag("chat-read-full").assertCountEquals(0)
            val sorted = synchronized(frames) { frames.toList().sorted() }
            assertThat(sorted.size).isGreaterThan(20)
            File(compose.activity.cacheDir, "chat-stream-performance.json").writeText(buildJsonObject {
                put("updates", 40); put("final_chars", value.value.length); put("frames", sorted.size)
                put("p50_ms", sorted[sorted.size / 2]); put("p95_ms", sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.lastIndex)])
                put("over_32ms_percent", 100.0 * sorted.count { it > 32 } / sorted.size)
            }.toString())
        } finally {
            if (listening) compose.runOnIdle { compose.activity.window.removeOnFrameMetricsAvailableListener(listener) }
            thread.quitSafely()
        }
    }
}
