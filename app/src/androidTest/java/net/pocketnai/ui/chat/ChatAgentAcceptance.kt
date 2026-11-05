package net.pocketnai.ui.chat

import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
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
    var failOn = -1
    var gate: CompletableDeferred<Unit>? = null
    val viewModel = ChatViewModel(settings, object : ChatClient {
        override suspend fun models(config: LlmConfig, apiKey: String) = emptyList<String>()
        override suspend fun complete(config: LlmConfig, apiKey: String, messages: List<JsonObject>, tools: JsonArray, onPartial: (JsonObject) -> Unit): JsonObject {
            assertThat(messages.toString()).doesNotContain("test-only-llm-placeholder")
            assertThat(messages.toString()).doesNotContain("test-only-search-placeholder")
            assertThat(tools.toString()).contains("web_search")
            val current = messages.drop(messages.indexOfLast { it.string("role") == "user" } + 1)
            val count = current.count { it.string("role") == "tool" }
            val name = if (count == 0) "web_search" else "generate_images"
            val arguments = if (count == 0) """{"query":"forest traveler official costume"}""" else
                """{"images":[{"prompt":"sfw, quiet forest","negative_prompt":"lowres"},{"prompt":"sfw, sunset coast","negative_prompt":"lowres"},{"prompt":"sfw, mountain village","negative_prompt":"lowres"}]}"""
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
    fun close() {
        viewModel.viewModelScope.cancel()
        database.close()
        context.getSharedPreferences("pocketnai_llm", Context.MODE_PRIVATE).edit().clear().commit()
        directory.deleteRecursively()
    }
}
