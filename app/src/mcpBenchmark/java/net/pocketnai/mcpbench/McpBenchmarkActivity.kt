package net.pocketnai.mcpbench

import android.app.Application
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import net.pocketnai.data.mcp.*
import net.pocketnai.domain.task.BackgroundTasks
import net.pocketnai.ui.settings.LocalMcpScreen
import net.pocketnai.ui.theme.PocketNaiTheme
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** No AppContainer or provider credentials: all image/LLM requests are local fixtures. */
class McpBenchmarkApplication : Application(), LocalMcpOwner {
    val generated = AtomicInteger()
    override val localMcpManager by lazy { LocalMcpManager(this, object : McpImageBackend {
        override suspend fun capabilities() = buildJsonObject { put("offline_fixture", true); put("providers", buildJsonArray { add("novelai"); add("openai") }) }
        override suspend fun prepare(arguments: JsonObject) = object : McpImagePlan {
            override val provider = arguments["provider"]!!.jsonPrimitive.content
            override val prompt = arguments["prompt"]!!.jsonPrimitive.content
            override val negative = ""
            override val description = "$provider · 离线测试图片 · 160 × 224"
            override val quote = "离线验证 · 不产生费用"
        }
        override suspend fun quote(plan: McpImagePlan) = plan.quote
        override suspend fun generate(plan: McpImagePlan, protection: BackgroundTasks.Task, onImage: suspend (String) -> Unit) {
            protection.ensureRunning()
            val n = generated.incrementAndGet()
            File(filesDir, "fake-generation-count.txt").writeText(n.toString())
            delay(8000)
            val bitmap = Bitmap.createBitmap(160, 224, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(0xff9cbbd4.toInt())
            val canvas = android.graphics.Canvas(bitmap)
            val paint = android.graphics.Paint().apply { color = 0xff3e5c82.toInt(); isAntiAlias = true }
            canvas.drawCircle(80f, 95f, 48f, paint)
            paint.color = android.graphics.Color.WHITE; paint.textSize = 17f
            canvas.drawText("MCP OK", 44f, 186f, paint)
            val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            bitmap.recycle()
            File(filesDir, "offline-image.png").writeBytes(bytes)
            onImage("offline-image")
        }
        override suspend fun image(imageId: String) = buildJsonObject { put("content", buildJsonArray {
            add(buildJsonObject { put("type", "image"); put("mimeType", "image/png"); put("data",
                Base64.encodeToString(File(filesDir, "offline-image.png").readBytes(), Base64.NO_WRAP)) })
        }) }
    }) }
    private var llm: OfflineChatServer? = null
    fun startOfflineLlm() { if (llm == null) llm = OfflineChatServer(filesDir).also { it.start(5000, true) } }
}

class McpBenchmarkActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as McpBenchmarkApplication
        app.startOfflineLlm()
        setContent { PocketNaiTheme { LocalMcpScreen(app.localMcpManager) { finish() } } }
    }
}
