package net.pocketnai.ui.chat

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.provider.MediaStore
import androidx.room.Room
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import net.pocketnai.PocketNaiApplication
import net.pocketnai.data.chat.*
import net.pocketnai.data.local.PocketNaiDatabase
import net.pocketnai.domain.chat.*
import net.pocketnai.domain.model.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** 相册 URI → 附件 → 发出多模态消息 → Room → 重新读取；只读已知 SFW 图，不接触真实服务。 */
@RunWith(AndroidJUnit4::class)
class ChatAttachmentPersistenceTest {
    @Test fun photoAndGalleryRemainReadableAfterConversationReload() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PocketNaiApplication
        val safe = app.container.generationRepository.observeGallery().first().first { it.imageId == "e18aff2f-275d-4d7e-9723-f2db61d1784f" }
        val prefix = "attachment-check-${UUID.randomUUID()}"
        val isolated = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("$prefix-$name", mode)
        }
        val database = Room.inMemoryDatabaseBuilder(app, PocketNaiDatabase::class.java).build()
        val store = ChatStore(database.chatConversationDao())
        val attachments = ChatAttachmentStore(app)
        val settings = LlmSettingsStore(isolated)
        val sent = mutableListOf<List<JsonObject>>()
        val backend = object : ChatClient {
            override suspend fun models(config: LlmConfig, apiKey: String) = listOf("local-test")
            override suspend fun complete(config: LlmConfig, apiKey: String, messages: List<JsonObject>, tools: JsonArray, onPartial: (JsonObject) -> Unit): JsonObject {
                sent += messages
                return wireMessage("assistant", "本地请求结构验证完成")
            }
        }
        val noGeneration = object : ChatImageGenerator {
            override fun currentParams() = GenerationParams.defaultsFor(ModelCatalog.defaultProfile())
            override suspend fun quote(params: GenerationParams): String = error("No generation in attachment test")
            override suspend fun generate(params: GenerationParams, onImage: (ChatImage) -> Unit): List<ChatImage> = error("No generation in attachment test")
        }
        var vm: ChatViewModel? = null
        var retained = emptyList<ChatAttachment>()
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$prefix.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PocketNAI-tests")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = app.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        var removed = false
        try {
            app.contentResolver.openOutputStream(uri)!!.use { output -> app.container.fileStore.resolve(safe.relativePath).inputStream().use { it.copyTo(output) } }
            app.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            settings.save(LlmConfig(model = "local-test"), "local-test-placeholder")
            withContext(Dispatchers.Main) { vm = ChatViewModel(settings, backend, store, app.container.agentFiles, noGeneration, MutableStateFlow(true), attachments) }
            withTimeout(10_000) { vm!!.state.first { it.current != null } }
            withContext(Dispatchers.Main) { vm!!.addPhoto(uri.toString()) }
            withTimeout(10_000) { vm!!.state.first { !it.importingAttachment && it.draftAttachments.size == 1 } }
            withContext(Dispatchers.Main) { vm!!.addGalleryImage(safe.relativePath) }
            withTimeout(10_000) { vm!!.state.first { !it.importingAttachment && it.draftAttachments.size == 2 } }
            retained = vm!!.state.value.draftAttachments
            withContext(Dispatchers.Main) { vm!!.send("请描述这两张图片的服装") }
            withTimeout(10_000) { vm!!.state.first { !it.busy && it.current!!.entries.any { entry -> entry.role == "assistant" } } }
            assertThat(sent).hasSize(1)
            val parts = sent.single().last()["content"]!!.jsonArray
            assertThat(parts).hasSize(3)
            assertThat(parts.drop(1).all { it.jsonObject["image_url"]!!.jsonObject.string("url")!!.startsWith("data:image/jpeg;base64,") }).isTrue()
            val loaded = store.load(vm!!.state.value.current!!.id)!!
            assertThat(loaded.entries.first().attachments).isEqualTo(retained)
            assertThat(database.chatConversationDao().find(loaded.id)!!.entriesJson).doesNotContain("base64,")
            app.contentResolver.delete(uri, null, null) // 相册原始 URI 消失，私有附件仍可恢复请求。
            removed = true
            val hydrated = attachments.messages(loaded.entries, mutableMapOf())
            assertThat(hydrated.first()["content"]!!.jsonArray).hasSize(3)
            assertThat(app.container.fileStore.resolve(safe.relativePath).isFile).isTrue()
        } finally {
            withContext(Dispatchers.Main) { vm?.viewModelScope?.cancel() }
            retained.forEach { attachments.discard(it) }
            if (!removed) app.contentResolver.delete(uri, null, null)
            isolated.getSharedPreferences("pocketnai_llm", 0).edit().clear().commit()
            database.close()
        }
    }
}
