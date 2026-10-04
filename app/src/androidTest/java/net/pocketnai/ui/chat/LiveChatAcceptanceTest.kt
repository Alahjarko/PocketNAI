package net.pocketnai.ui.chat

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import net.pocketnai.PocketNaiApplication
import net.pocketnai.domain.chat.*
import net.pocketnai.domain.model.*
import net.pocketnai.ui.MainActivity
import net.pocketnai.ui.generate.GenerateViewModel
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 仅由本次用户显式授权的命令运行；默认测试套件跳过，凭据由 stdin 临时注入并立即删除。 */
@RunWith(AndroidJUnit4::class)
class LiveChatAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun realThinkingProvidersAndOneSfwImage() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowLive") == "true")
        val app = compose.activity.application as PocketNaiApplication
        val container = app.container
        val fixtureFile = File(app.cacheDir, "chat-live-credentials.json")
        val fixture = try { Json.parseToJsonElement(fixtureFile.readText()) as JsonObject } finally { fixtureFile.delete() }
        val providers = fixture["providers"] as JsonArray
        val prefs = app.getSharedPreferences("pocketnai_llm", 0)
        val oldPrefs = prefs.all.toMap()
        val oldConfig = container.llmSettingsStore.config.value
        val results = mutableListOf<JsonObject>()
        var imageId: String? = null
        var selected: JsonObject? = null
        try {
            compose.waitForIdle()
            var base: GenerationParams? = null
            compose.runOnIdle { base = ViewModelProvider(compose.activity)[GenerateViewModel::class.java].state.value.params }
            for (element in providers) {
                val provider = element as JsonObject
                val cfg = LlmConfig(baseUrl = provider.string("baseUrl")!!, model = provider.string("model")!!, thinking = true)
                try {
                    val key = provider.string("apiKey")!!
                    val models = container.chatClient.models(cfg, key)
                    assertThat(models).contains(cfg.model)
                    val normal = container.chatClient.complete(cfg, key, listOf(wireMessage("user", "计算 7×19 加上 11×23 等于多少，只用一句中文给出结果。")), JsonArray(emptyList()))
                    val normalReasoning = normal.string("reasoning_content").orEmpty()
                    val tool = JsonArray(ImageChatTools.schema.filter { (it as JsonObject)["function"]!!.jsonObject.string("name") == "get_generation_settings" })
                    val messages = mutableListOf(wireMessage("system", "使用工具获取应用设置，用简短中文回答。"),
                        wireMessage("user", "请先调用 get_generation_settings 读取当前画布，然后只用一句话告诉我宽高。不要生成图片。"))
                    val first = ChatProtocol.assistant(container.chatClient.complete(cfg, key, messages, tool))
                    val entry = ChatEntry("probe", first)
                    assertThat(entry.calls).isNotEmpty()
                    assertThat(entry.calls.all { it.name == "get_generation_settings" }).isTrue()
                    messages += first
                    for (call in entry.calls) messages += wireMessage("tool", buildJsonObject {
                        put("model", base!!.model.apiModelId); put("width", base!!.size.width); put("height", base!!.size.height)
                    }.toString(), call.id)
                    val second = container.chatClient.complete(cfg, key, messages, tool)
                    assertThat(second.string("content").orEmpty()).isNotEmpty()
                    val reasoningChars = normalReasoning.length + entry.reasoning.length + second.string("reasoning_content").orEmpty().length
                    results += buildJsonObject { put("provider", provider.string("name")); put("model", cfg.model); put("status", if (reasoningChars > 0) "passed" else "no_reasoning_returned")
                        put("ordinary_reasoning_chars", normalReasoning.length); put("first_fields", JsonArray(first.keys.map(::JsonPrimitive)))
                        put("reasoning_chars", reasoningChars); put("tool_calls", entry.calls.size); put("reasoning_roundtrip", true) }
                    if (selected == null) selected = provider
                } catch (e: Throwable) {
                    results += buildJsonObject { put("provider", provider.string("name")); put("model", cfg.model); put("status", "failed")
                        put("reason", (e as? ChatFailure)?.userMessage ?: "协议断言未通过") }
                }
            }
            require(selected != null) { "没有可用于普通聊天的服务" }
            val chosen = selected!!
            container.llmSettingsStore.save(LlmConfig(baseUrl = chosen.string("baseUrl")!!, model = chosen.string("model")!!,
                thinking = true, autoGenerate = false), chosen.string("apiKey")!!)
            compose.runOnIdle { container.settingsStore.setChatEnabled(true) }
            compose.onNodeWithText("对话", useUnmergedTree = false).performClick()
            var vm: ChatViewModel? = null
            compose.runOnIdle { vm = ViewModelProvider(compose.activity)[ChatViewModel::class.java]; vm!!.newConversation() }
            val marker = File(app.cacheDir, "chat-live-image-once.marker")
            if (!marker.exists()) {
                compose.onNodeWithTag("chat-input").performTextInput("请用 generate_image 生成一张普通 SFW 动漫图：成年女性穿完整的宽松针织衫与长裙，在雨后的咖啡店门口捧着热咖啡，暖色店灯，湿润街道反光。请使用 nai-diffusion-4-5-curated，832×1216，正向包含 sfw。只生成一张，成功后用一句简短中文回应。")
                compose.onNodeWithTag("chat-send").performClick()
                compose.waitUntil(240_000) { vm!!.state.value.pending != null || (!vm!!.state.value.busy && vm!!.state.value.error != null) }
                val pending = vm!!.state.value.pending ?: throw IllegalStateException("没有收到可执行图片卡片")
                assertThat(pending.params.model).isEqualTo(ImageModel.V4_5_CURATED)
                assertThat(pending.params.steps).isEqualTo(23)
                assertThat(pending.params.guidance).isEqualTo(7.0)
                assertThat(pending.params.size).isEqualTo(ImageSizePreset(832, 1216))
                assertThat(pending.params.prompt.lowercase()).contains("sfw")
                marker.writeText("started:${System.currentTimeMillis()}")
                compose.onNodeWithTag("chat-messages").performScrollToNode(hasTestTag("chat-confirm-image"))
                compose.onNodeWithTag("chat-confirm-image").performClick()
                compose.waitUntil(360_000) { !vm!!.state.value.busy }
                val chat = vm!!.state.value.current!!
                val images = chat.entries.flatMap { it.images }
                assertThat(images).hasSize(1)
                imageId = images.single().imageId
                assertThat(container.fileStore.resolve(images.single().relativePath).isFile).isTrue()
                val detail = container.generationRepository.loadDetail(imageId!!)
                assertThat(detail).isNotNull()
                assertThat(detail!!.generation.params.model).isEqualTo(ImageModel.V4_5_CURATED)
                assertThat(container.chatStore.load(chat.id)!!.entries.flatMap { it.images }).hasSize(1)
                assertThat(container.generationRepository.observeGallery().first().any { it.imageId == imageId }).isTrue()
                screenshot(app.cacheDir, "chat-live-conversation.png")
                compose.onNodeWithTag("chat-image-history").performClick()
                compose.onNodeWithText("对话生成的图片").assertIsDisplayed()
                screenshot(app.cacheDir, "chat-live-image-history.png")
                marker.writeText("completed:$imageId")
            } else {
                results += buildJsonObject { put("image", "skipped_existing_attempt"); put("reason", "避免重发真实图片请求") }
            }
        } finally {
            container.llmSettingsStore.save(oldConfig)
            val edit = prefs.edit().clear()
            oldPrefs.forEach { (key, value) -> when (value) {
                is String -> edit.putString(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
            } }
            edit.commit()
            fixtureFile.delete()
            File(app.cacheDir, "chat-live-result.json").writeText(buildJsonObject {
                put("providers", JsonArray(results)); imageId?.let { put("image_id", it) }; put("test_credentials_removed", true)
            }.toString())
        }
        assertThat(results.filter { it.string("provider") != null }.all { it.string("status") == "passed" }).isTrue()
    }

    private fun screenshot(root: File, name: String) {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(root, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
