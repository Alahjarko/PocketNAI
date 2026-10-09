package net.pocketnai.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import net.pocketnai.data.chat.*
import net.pocketnai.domain.chat.*
import net.pocketnai.domain.model.GenerationParams
import net.pocketnai.domain.model.CharacterPrompt
import java.util.UUID

class ChatViewModel(
    private val settings: LlmSettingsStore,
    private val api: ChatClient,
    private val store: ChatStore,
    private val files: AgentFiles,
    private val images: ChatImageGenerator,
    private val enabled: StateFlow<Boolean>,
    private val attachments: ChatAttachmentStore,
) : ViewModel() {
    data class PendingImage(val callId: String, val params: GenerationParams, val quote: String)
    data class UiState(
        val current: ChatConversation? = null,
        val conversations: List<ChatConversation> = emptyList(),
        val busy: Boolean = false,
        val generating: Boolean = false,
        val phase: String = "",
        val partial: JsonObject? = null,
        val pending: PendingImage? = null,
        val models: List<String> = emptyList(),
        val loadingModels: Boolean = false,
        val error: String? = null,
        val draftAttachments: List<ChatAttachment> = emptyList(),
        val importingAttachment: Boolean = false,
    )
    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()
    val config = settings.config
    private var job: Job? = null
    private var confirmation: CompletableDeferred<Boolean>? = null
    private var initialized = false

    init {
        viewModelScope.launch {
            enabled.collect { if (!it && _state.value.busy && !_state.value.generating) stop() }
        }
        viewModelScope.launch {
            store.observeAll().collect { chats ->
                _state.update { it.copy(conversations = chats) }
                if (!initialized) {
                    initialized = true
                    val chat = chats.firstOrNull()?.recoverInterruptedTools() ?: fresh()
                    _state.update { it.copy(current = chat) }
                    if (chat.entries.isNotEmpty()) store.save(chat)
                }
            }
        }
    }
    private fun fresh() = ChatConversation(UUID.randomUUID().toString(), updatedAt = System.currentTimeMillis())
    fun newConversation() { if (!_state.value.busy && !_state.value.importingAttachment) { clearDraftAttachments(); _state.update { it.copy(current = fresh(), partial = null, error = null) } } }
    fun selectConversation(id: String) {
        if (_state.value.busy || _state.value.importingAttachment) return
        viewModelScope.launch {
            runCatching { store.load(id)?.recoverInterruptedTools() }.onSuccess { chat ->
                if (chat != null) { clearDraftAttachments(); _state.update { it.copy(current = chat, error = null) }; store.save(chat) }
            }.onFailure { _state.update { it.copy(error = "无法读取该对话") } }
        }
    }
    fun deleteConversation(id: String) {
        if (_state.value.busy || _state.value.importingAttachment) return
        viewModelScope.launch {
            val old = store.load(id)
            store.delete(id)
            val alive = (store.observeAll().first().flatMap { it.entries }.flatMap { it.attachments } + _state.value.draftAttachments).map { it.id }.toSet()
            old?.entries?.flatMap { it.attachments }?.filter { it.id !in alive }?.distinctBy { it.id }?.forEach { attachments.discard(it) }
            if (_state.value.current?.id == id) newConversation()
        }
    }
    fun hasKey() = settings.hasKey()
    fun saveConfig(next: LlmConfig, key: String): Boolean = try {
        ChatProtocol.endpoint(next.baseUrl, "models")
        if (settings.hasKey() && next.baseUrl.trim().trimEnd('/') != config.value.baseUrl.trim().trimEnd('/') && key.isBlank())
            throw ChatFailure("更换 Base URL 时请填写该服务的 API Key")
        settings.save(next.copy(baseUrl = next.baseUrl.trim(), model = next.model.trim()), key)
        _state.update { it.copy(error = null) }; true
    } catch (e: ChatFailure) { _state.update { it.copy(error = e.userMessage) }; false }
    catch (_: Exception) { _state.update { it.copy(error = "无法加密或保存 API 配置") }; false }
    fun clearKey() { settings.clearKey(); stop(); _state.update { it.copy(models = emptyList()) } }
    fun fetchModels(next: LlmConfig, key: String) {
        if (_state.value.loadingModels) return
        viewModelScope.launch {
            _state.update { it.copy(loadingModels = true, error = null) }
            try {
                val token = key.trim().ifEmpty { settings.apiKey().orEmpty() }
                if (key.isBlank() && next.baseUrl.trim().trimEnd('/') != config.value.baseUrl.trim().trimEnd('/'))
                    throw ChatFailure("请填写该 Base URL 对应的 API Key")
                if (token.isBlank()) throw ChatFailure("请先填写 API Key")
                val found = api.models(next, token)
                if (found.isEmpty()) throw ChatFailure("供应商没有返回模型，可手动填写模型 ID")
                _state.update { it.copy(models = found) }
            } catch (e: ChatFailure) { _state.update { it.copy(error = e.userMessage) } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { _state.update { it.copy(error = "获取模型列表失败") } }
            finally { _state.update { it.copy(loadingModels = false) } }
        }
    }
    fun agentText(name: String) = files.read(name)
    fun saveAgent(name: String, text: String): Boolean = runCatching { files.save(name, text) }.isSuccess
    fun resetAgent(name: String) = files.reset(name)
    fun defaultAgentText(name: String) = files.defaultText(name)
    fun dismissError() { _state.update { it.copy(error = null) } }
    fun confirmImage(accept: Boolean) { confirmation?.complete(accept) }
    fun stop() { if (!_state.value.generating) job?.cancel() }

    fun addPhoto(uri: String) = importAttachment { attachments.importUri(uri) }
    fun addGalleryImage(path: String) = importAttachment { attachments.importGallery(path) }
    private fun importAttachment(import: suspend () -> ChatAttachment) {
        if (_state.value.busy || _state.value.importingAttachment) return
        if (_state.value.draftAttachments.size >= ChatAttachment.MAX_COUNT) { _state.update { it.copy(error = "每条消息最多添加 4 张图片") }; return }
        _state.update { it.copy(importingAttachment = true, error = null) }
        viewModelScope.launch {
            try {
                val attachment = import()
                _state.update { it.copy(draftAttachments = it.draftAttachments + attachment) }
            } catch (failure: ChatFailure) { _state.update { it.copy(error = failure.userMessage) } }
            catch (_: Exception) { _state.update { it.copy(error = "无法添加图片，请重新选择") } }
            finally { _state.update { it.copy(importingAttachment = false) } }
        }
    }
    fun removeAttachment(id: String) {
        if (_state.value.busy || _state.value.importingAttachment) return
        val removed = _state.value.draftAttachments.firstOrNull { it.id == id } ?: return
        _state.update { it.copy(draftAttachments = it.draftAttachments.filterNot { image -> image.id == id }) }
        viewModelScope.launch { attachments.discard(removed) }
    }
    private fun clearDraftAttachments() {
        val unused = _state.value.draftAttachments
        _state.update { it.copy(draftAttachments = emptyList()) }
        viewModelScope.launch { unused.forEach { attachments.discard(it) } }
    }

    fun send(text: String) {
        if ((text.isBlank() && _state.value.draftAttachments.isEmpty()) || _state.value.busy || _state.value.importingAttachment || !enabled.value) return
        val attached = _state.value.draftAttachments.toList()
        val cfg = config.value
        job = viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, phase = "正在思考") }
            var chat = _state.value.current ?: fresh()
            suspend fun persist() {
                chat = chat.copy(updatedAt = System.currentTimeMillis())
                store.save(chat)
                _state.update { it.copy(current = chat) }
            }
            try {
                val token = settings.apiKey()?.takeIf { it.isNotBlank() } ?: throw ChatFailure("请先配置 LLM API Key")
                val userText = text.trim().ifEmpty { "请看看这些图片。" }
                if (userText.length > 64 * 1024) throw ChatFailure("单条输入过长，请缩短内容或分条发送")
                if (userText.contains(token)) throw ChatFailure("请勿在聊天消息中粘贴 API Key")
                val systemPrompt = files.systemPrompt()
                if (systemPrompt.contains(token)) throw ChatFailure("请勿在人格文件中填写 API Key")
                suspend fun withinContext(messages: List<JsonObject>) = withContext(Dispatchers.Default) {
                    messages.sumOf { it.toString().toByteArray(Charsets.UTF_8).size } <= 512 * 1024
                }
                if (!withinContext(listOf(wireMessage("system", systemPrompt)) + chat.entries.map { it.wire } + wireMessage("user", userText)))
                    throw ChatFailure("对话上下文过长，请新建对话；历史消息仍可完整阅读")
                chat = chat.copy(title = if (chat.entries.isEmpty()) userText.take(28) else chat.title,
                    entries = chat.entries + ChatEntry(UUID.randomUUID().toString(), wireMessage("user", userText), attachments = attached))
                persist()
                _state.update { it.copy(draftAttachments = emptyList()) }
                val imageCache = mutableMapOf<String, String>()
                var generated = false
                val seen = mutableSetOf<String>()
                for (round in 0 until 6) {
                    if (!enabled.value) throw ChatFailure("实验性对话已关闭")
                    if (settings.apiKey() != token) throw ChatFailure("API Key 已变更，本条消息已停止")
                    if (!withinContext(listOf(wireMessage("system", systemPrompt)) + chat.entries.map { it.wire })) throw ChatFailure("对话上下文过长，请新建对话；历史消息仍可完整阅读")
                    val messages = listOf(wireMessage("system", systemPrompt)) + attachments.messages(chat.entries, imageCache)
                    val reply = api.complete(cfg, token, messages, ImageChatTools.schema) { partial ->
                        _state.update { it.copy(partial = partial.withoutSecret(token)) }
                    }
                    val assistant = ChatEntry(UUID.randomUUID().toString(), ChatProtocol.assistant(reply).withoutSecret(token))
                    chat = chat.copy(entries = chat.entries + assistant)
                    _state.update { it.copy(partial = null) }; persist()
                    if (assistant.calls.isEmpty()) {
                        if (assistant.content.isBlank()) throw ChatFailure("模型只返回了思考内容，没有最终回复；请检查输出 Tokens")
                        break
                    }
                    for (call in assistant.calls) {
                        var result = ""
                        var resultImages = emptyList<ChatImage>()
                        var notice: String? = null
                        try {
                            if (!seen.add(call.id)) throw ChatFailure("重复的工具调用已跳过")
                            when (call.name) {
                                "get_generation_settings" -> {
                                    val p = images.currentParams()
                                    result = buildJsonObject { put("model", p.model.apiModelId); put("width", p.size.width); put("height", p.size.height)
                                        put("steps", p.steps); put("guidance", p.guidance); put("max_images_per_message", 1)
                                        put("max_characters", CharacterPrompt.limitFor(p.model)); put("use_coords", p.useCharacterCoordinates) }.toString()
                                }
                                "generate_image" -> {
                                    if (generated) throw ChatFailure("本条消息的图片生成次数已用完，请发送新消息")
                                    val params = ImageChatTools.parse(call, images.currentParams())
                                    val quote = images.quote(params)
                                    if (!cfg.autoGenerate) {
                                        confirmation = CompletableDeferred()
                                        _state.update { it.copy(pending = PendingImage(call.id, params, quote), phase = "等待确认图片") }
                                        val accepted = confirmation!!.await()
                                        confirmation = null
                                        _state.update { it.copy(pending = null) }
                                        if (!accepted) throw ChatFailure("用户取消图片生成")
                                    }
                                    if (!enabled.value) throw ChatFailure("实验性对话已关闭")
                                    generated = true // 即使超时也消耗本条消息的调用预算，不重试。
                                    _state.update { it.copy(generating = true, phase = "正在生成图片 · $quote") }
                                    resultImages = images.generate(params) { image ->
                                        resultImages = resultImages + image
                                        _state.update { it.copy(current = chat.copy(entries = chat.entries.map { e ->
                                            if (e.id == assistant.id) e.copy(images = e.images + image) else e
                                        })) }
                                    }
                                    notice = "图片已生成 · $quote"
                                    result = buildJsonObject { put("status", "succeeded"); put("image_ids", JsonArray(resultImages.map { JsonPrimitive(it.imageId) })) }.toString()
                                }
                                else -> throw ChatFailure("不支持该工具")
                            }
                        } catch (e: ChatFailure) {
                            notice = e.userMessage
                            result = buildJsonObject { put("status", "failed"); put("message", e.userMessage) }.toString()
                        } finally { _state.update { it.copy(generating = false, phase = "正在整理回复") } }
                        chat = chat.copy(entries = chat.entries + ChatEntry(UUID.randomUUID().toString(), wireMessage("tool", result, call.id), resultImages, notice))
                        persist()
                    }
                    if (round == 5) throw ChatFailure("工具轮次已达到上限，请发送新消息")
                }
            } catch (e: CancellationException) {
                withContext(NonCancellable) { chat = chat.recoverInterruptedTools(); runCatching { persist() } }
            } catch (e: ChatFailure) {
                withContext(NonCancellable) {
                    chat = chat.recoverInterruptedTools()
                    runCatching { persist() }
                }
                _state.update { it.copy(error = e.userMessage) }
            }
            catch (_: Exception) {
                withContext(NonCancellable) { chat = chat.recoverInterruptedTools(); runCatching { persist() } }
                _state.update { it.copy(error = "对话任务失败，未自动重试") }
            }
            finally {
                confirmation = null
                _state.update { it.copy(busy = false, generating = false, pending = null, partial = null, phase = "") }
            }
        }
    }
}
