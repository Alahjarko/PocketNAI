package net.pocketnai.ui.chat

import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import net.pocketnai.data.chat.*
import net.pocketnai.data.local.PocketNaiDatabase
import net.pocketnai.domain.chat.*
import net.pocketnai.domain.model.*
import java.io.File
import java.util.UUID
import com.google.common.truth.Truth.assertThat

/** Real ViewModel and Room, isolated preferences/files, fake APIs only. */
internal class ChatAgentAcceptance(base: Context, val image: File) {
    private val id = UUID.randomUUID().toString()
    private val directory = File(base.cacheDir, "agent-$id").apply { mkdirs() }
    private val context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir() = directory
        override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("agent-$id-$name", mode)
    }
    private val database = Room.inMemoryDatabaseBuilder(context, PocketNaiDatabase::class.java).build()
    private val settings = LlmSettingsStore(context).apply {
        save(LlmConfig(model = "offline-agent", autoGenerate = true), "test-only-llm-placeholder")
        saveSearch(ChatSearchConfig(enabled = true), "test-only-search-placeholder")
    }
    private val store = ChatStore(database.chatConversationDao())
    var generated = 0
    var searches = 0
    var plannedCount = 3
    var failOn = -1
    var proposeOnly = false
    var editedPrompt: String? = null
    var gate: CompletableDeferred<Unit>? = null
    val viewModel = ChatViewModel(settings, object : ChatClient {
        override suspend fun models(config: LlmConfig, apiKey: String) = emptyList<String>()
        override suspend fun complete(config: LlmConfig, apiKey: String, messages: List<JsonObject>, tools: JsonArray, onPartial: (JsonObject) -> Unit): JsonObject {
            assertThat(messages.toString()).doesNotContain("test-only-llm-placeholder")
            assertThat(messages.toString()).doesNotContain("test-only-search-placeholder")
            assertThat(tools.toString()).contains("web_search")
            val current = messages.drop(messages.indexOfLast { it.string("role") == "user" } + 1)
            val count = current.count { it.string("role") == "tool" }
            if (proposeOnly) return if (count > 0) wireMessage("assistant", "改词建议已准备好。") else buildJsonObject {
                put("role", "assistant"); put("content", "保持构图，仅调整光线。")
                put("tool_calls", buildJsonArray { add(buildJsonObject {
                    put("id", "prompt-edit-$id"); put("type", "function")
                    put("function", buildJsonObject { put("name", "propose_prompt_edit"); put("arguments", """{"title":"柔化光线","prompt":"soft evening light"}""") })
                }) })
            }
            val name = if (count == 0) "web_search" else "generate_images"
            val arguments = if (count == 0) """{"query":"forest traveler official costume"}""" else buildJsonObject {
                put("images", buildJsonArray { repeat(plannedCount) { index -> add(buildJsonObject {
                    put("prompt", listOf("sfw, quiet forest", "sfw, sunset coast", "sfw, mountain village").getOrElse(index) { "sfw, landscape panel ${index + 1}" })
                    put("negative_prompt", "lowres")
                }) } })
            }.toString()
            return if (count >= 2) wireMessage("assistant", "已整理本次创作结果，请查看图片与来源。")
            else buildJsonObject {
                put("role", "assistant"); put("content", if (count == 0) "先核对角色资料。" else "准备三张独立分镜。")
                put("tool_calls", buildJsonArray { add(buildJsonObject {
                    put("id", UUID.randomUUID().toString()); put("type", "function")
                    put("function", buildJsonObject { put("name", name); put("arguments", arguments) })
                }) })
            }
        }
    }, store, AgentFiles(context), object : ChatImageGenerator {
        override fun currentParams() = GenerationParams.defaultsFor(ModelCatalog.profileOf(ImageModel.V4_5_CURATED))
        override fun applyPromptEdit(edit: PromptEdit): Int { editedPrompt = edit.prompt; return 1 }
        override fun undoPromptEdit(provider: String, revision: Int): Boolean { editedPrompt = null; return true }
        override fun authorizationKey() = "offline-account"
        override suspend fun quote(params: GenerationParams) = ChatImageQuote("预计 2 Anlas", 2)
        override suspend fun generate(params: GenerationParams, account: String?, beforeSend: suspend () -> Unit, onImage: (ChatImage) -> Unit): List<ChatImage> {
            beforeSend()
            generated++
            gate?.await()
            if (generated == failOn) throw ChatFailure("模拟断流 TIMEOUT_UNCERTAIN，未自动重试")
            return listOf(ChatImage("offline-$generated", image.path, 400, 1000)).onEach(onImage)
        }
    }, MutableStateFlow(true), ChatAttachmentStore(context), object : ChatSearchClient {
        override suspend fun search(config: ChatSearchConfig, key: String, query: String): List<ChatSearchSource> {
            searches++
            return listOf(ChatSearchSource("角色官方设定（离线样本）", "https://example.org/character", "Travel outfit, blue coat"))
        }
    })
    fun verifySaved(expectedImages: Int) = runBlocking {
        val current = viewModel.state.value.current!!
        val saved = store.load(current.id)!!
        assertThat(saved.entries.sumOf { it.images.size }).isEqualTo(expectedImages)
        assertThat(saved.entries.flatMap { it.images }.map { it.imageId }.distinct()).hasSize(expectedImages)
        assertThat(saved.entries.flatMap { it.sources }).hasSize(1)
        assertThat(saved.entries.toString()).doesNotContain("test-only-search-placeholder")
        assertThat(saved.recoverInterruptedTools()).isEqualTo(saved)
    }
    fun savedPermission() = LlmSettingsStore(context).config.value.effectiveToolPermission
    val draftId = "held-draft-$id"
    private val heldAttachment = File(directory, "chat-attachments/held.jpg")
    fun seedHeldDraft() = runBlocking {
        heldAttachment.parentFile!!.mkdirs(); image.copyTo(heldAttachment)
        val chat = ChatConversation(draftId, "持续创作", System.currentTimeMillis())
        store.saveDraft(chat, ChatStore.Draft("保持构图", 2, 2,
            listOf(ChatAttachment("held", "chat-attachments/held.jpg", "image/jpeg", 400, 1000))))
    }
    fun verifyHeldDraft(expected: String) = runBlocking {
        val draft = store.draft(draftId)
        assertThat(draft.text).isEqualTo(expected)
        assertThat(draft.attachments.single().id).isEqualTo("held")
        assertThat(heldAttachment.isFile).isTrue()
        store.rename(draftId, "保留的草稿"); store.pin(draftId, true)
        val summary = store.observeSummaries().first { rows -> rows.any { it.id == draftId && it.pinnedAt != null } }.first { it.id == draftId }
        assertThat(summary.title).isEqualTo("保留的草稿"); assertThat(summary.hasDraft).isTrue()
    }
    fun close() {
        viewModel.viewModelScope.cancel()
        database.close()
        context.getSharedPreferences("pocketnai_llm", Context.MODE_PRIVATE).edit().clear().commit()
        directory.deleteRecursively()
    }
}
