package net.pocketnai.domain.chat

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
enum class LlmDialect { AUTO, KIMI, DEEPSEEK, GEMINI, OPENAI_COMPATIBLE }

@Serializable
enum class ChatToolPermission { ASK, SINGLE_IMAGE, FULL }

/** 密钥单独通过 Keystore 保存，不属于可序列化配置。 */
@Serializable
data class LlmConfig(
    val baseUrl: String = "https://api.openai.com/v1",
    val model: String = "",
    val dialect: LlmDialect = LlmDialect.AUTO,
    val thinking: Boolean = true,
    val maxTokens: Int = 16384,
    val autoGenerate: Boolean = false,
    val assistantName: String = "绘伴",
    val toolPermission: ChatToolPermission? = null,
) {
    // Existing installations retain their old behavior; FULL is never inferred during migration.
    val effectiveToolPermission: ChatToolPermission get() = toolPermission
        ?: if (autoGenerate) ChatToolPermission.SINGLE_IMAGE else ChatToolPermission.ASK
}

@Serializable
data class ChatImage(val imageId: String, val relativePath: String, val width: Int, val height: Int)

@Serializable
data class ChatAttachment(val id: String, val relativePath: String, val mimeType: String, val width: Int, val height: Int,
    val promptContext: ChatImageContext? = null,
    val sensitivePrompt: Boolean = false,
) {
    val hasSensitivePrompt: Boolean get() = sensitivePrompt || promptContext?.let(net.pocketnai.domain.safety.SfwPolicy::isSensitive) == true
    companion object { const val MAX_COUNT = 4 }
}

@Serializable
data class ChatEntry(
    val id: String,
    val wire: JsonObject,
    val images: List<ChatImage> = emptyList(),
    val notice: String? = null,
    val attachments: List<ChatAttachment> = emptyList(),
    val sources: List<ChatSearchSource> = emptyList(),
    val promptEdit: PromptEdit? = null,
) {
    val role: String get() = wire.string("role").orEmpty()
    val content: String get() = wire.string("content").orEmpty()
    val reasoning: String get() = wire.string("reasoning_content") ?: wire.string("reasoning").orEmpty()
    val calls: List<ToolCall> get() = (wire["tool_calls"] as? JsonArray).orEmpty().mapNotNull {
        val obj = it as? JsonObject ?: return@mapNotNull null
        val fn = obj["function"] as? JsonObject ?: return@mapNotNull null
        val id = obj.string("id") ?: return@mapNotNull null
        ToolCall(id, fn.string("name").orEmpty(), fn.string("arguments").orEmpty())
    }
}

/** An interrupted display draft may be kept, but its incomplete calls are never actionable. */
fun interruptedChatEntry(id: String, partial: JsonObject?, notice: String): ChatEntry? {
    if (partial == null) return null
    val wire = JsonObject(ChatProtocol.assistant(partial).filterKeys { it != "tool_calls" })
    val entry = ChatEntry(id, wire, notice = notice)
    return entry.takeIf { it.content.isNotBlank() || it.reasoning.isNotBlank() }
}

@Serializable
data class ToolCall(val id: String, val name: String, val arguments: String)

@Serializable
data class ChatConversation(
    val id: String,
    val title: String = "新对话",
    val updatedAt: Long,
    val entries: List<ChatEntry> = emptyList(),
)

data class ChatReferenceCandidate(
    val refId: String,
    val relativePath: String,
    val source: String,
)

data class ChatReferenceSpec(
    val refId: String,
    val relativePath: String,
    val role: net.pocketnai.domain.model.ReferenceRole,
    val strength: Double? = null,
    val directorKind: net.pocketnai.domain.model.DirectorReferenceKind? = null,
)

/** 按对话出现顺序列出可作为参考图的图片（用户附件与已生成图片），跳过矢量图并按路径去重。 */
fun ChatConversation.availableReferenceImages(): List<ChatReferenceCandidate> {
    val seen = mutableSetOf<String>()
    val result = mutableListOf<ChatReferenceCandidate>()
    for (entry in entries) {
        for (att in entry.attachments) {
            if (att.relativePath.isNotBlank() && !att.relativePath.endsWith(".svg", ignoreCase = true) && seen.add(att.relativePath)) {
                result += ChatReferenceCandidate("ref_${result.size + 1}", att.relativePath, "attachment")
            }
        }
        for (img in entry.images) {
            if (img.relativePath.isNotBlank() && !img.relativePath.endsWith(".svg", ignoreCase = true) && seen.add(img.relativePath)) {
                result += ChatReferenceCandidate("ref_${result.size + 1}", img.relativePath, "generated")
            }
        }
    }
    return result
}

fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/** 服务端误回显认证值时也不进入界面、工具参数或普通历史。 */
fun JsonObject.withoutSecret(secret: String): JsonObject {
    if (secret.isEmpty()) return this
    fun clean(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.mapValues { clean(it.value) })
        is JsonArray -> JsonArray(value.map(::clean))
        is JsonPrimitive -> if (value.isString && secret in value.content) JsonPrimitive(value.content.replace(secret, "[API Key 已隐藏]")) else value
    }
    return clean(this) as JsonObject
}

fun wireMessage(role: String, content: String, toolCallId: String? = null): JsonObject = buildJsonObject {
    put("role", role); put("content", content)
    toolCallId?.let { put("tool_call_id", it) }
}

/** 重新打开对话时只补齐未完成的工具结果，绝不自动重发图片请求。 */
fun ChatConversation.recoverInterruptedTools(): ChatConversation {
    val replied = entries.filter { it.role == "tool" }.mapNotNull { it.wire.string("tool_call_id") }.toSet()
    val missing = entries.flatMap { it.calls }.filter { it.id !in replied }
    return copy(entries = entries + missing.map {
        ChatEntry("interrupted-${it.id}", wireMessage("tool", "{\"status\":\"interrupted\",\"message\":\"上次任务已中断，未自动重试。\"}", it.id), notice = "上次工具任务已中断")
    })
}

/** 修改某条用户或助手消息的正文，保留原有附件、思考与工具字段。 */
fun ChatConversation.editEntry(entryId: String, newText: String): ChatConversation? {
    val index = entries.indexOfFirst { it.id == entryId }
    if (index < 0) return null
    val target = entries[index]
    if (target.role != "user" && target.role != "assistant") return null
    val updatedWire = JsonObject(target.wire + ("content" to JsonPrimitive(newText)))
    return copy(entries = entries.mapIndexed { i, entry -> if (i == index) entry.copy(wire = updatedWire) else entry })
}

/**
 * 为「重试」截断对话：
 * - 若选中用户消息，截断该消息之后的所有内容（保留该用户消息及附件）；
 * - 若选中助手消息，回溯到它所属的那条用户消息，一并清掉该轮旧回复与中间工具调用。
 * 返回截断后的对话与被截掉的条目（供清理孤立附件）。
 */
fun ChatConversation.truncateForRetry(entryId: String): Pair<ChatConversation, List<ChatEntry>>? {
    val index = entries.indexOfFirst { it.id == entryId }
    if (index < 0) return null
    val userIndex = when (entries[index].role) {
        "user" -> index
        "assistant" -> (index - 1 downTo 0).firstOrNull { entries[it].role == "user" } ?: return null
        else -> return null
    }
    val kept = entries.take(userIndex + 1)
    val dropped = entries.drop(userIndex + 1)
    return copy(entries = kept) to dropped
}

/**
 * 从指定消息处分叉出一条独立新对话：
 * - 若该消息是带 `tool_calls` 的助手消息，自动把紧随其后的配对 `tool` 结果一起带入分支，
 *   再经 [recoverInterruptedTools] 兜底，避免悬空 `tool_call_id` 引发 400。
 * - 所有条目分配全新 id，避免与原对话共享界面状态键；附件文件按引用计数安全共享。
 */
fun ChatConversation.branchAt(
    entryId: String,
    newConversationId: String,
    now: Long,
    idGenerator: () -> String = { java.util.UUID.randomUUID().toString() },
): ChatConversation? {
    val index = entries.indexOfFirst { it.id == entryId }
    if (index < 0) return null
    var end = index
    val pendingCallIds = entries[index].calls.map { it.id }.toMutableSet()
    while (pendingCallIds.isNotEmpty() && end + 1 < entries.size && entries[end + 1].role == "tool") {
        end++
        entries[end].wire.string("tool_call_id")?.let(pendingCallIds::remove)
    }
    val baseTitle = title.ifBlank { "新对话" }
    val branchTitle = if (baseTitle.endsWith(" · 分支")) baseTitle else "${baseTitle.take(40)} · 分支"
    val cloned = entries.take(end + 1).map { it.copy(id = idGenerator()) }
    return ChatConversation(
        id = newConversationId,
        title = branchTitle,
        updatedAt = now,
        entries = cloned,
    ).recoverInterruptedTools()
}

class ChatFailure(val userMessage: String) : Exception(userMessage)

interface ChatClient {
    suspend fun models(config: LlmConfig, apiKey: String): List<String>
    suspend fun complete(
        config: LlmConfig, apiKey: String, messages: List<JsonObject>, tools: JsonArray,
        onPartial: (JsonObject) -> Unit = {},
    ): JsonObject
}

interface ChatImageGenerator {
    fun currentParams(): net.pocketnai.domain.model.GenerationParams
    fun openAiParams(): net.pocketnai.domain.openai.OpenAiImageParams? = null
    fun promptSnapshot() = PromptEdit(beforePrompt = currentParams().prompt, beforeNegative = currentParams().negativePrompt)
    fun applyPromptEdit(edit: PromptEdit): Int? = null
    fun undoPromptEdit(provider: String, revision: Int): Boolean = false
    suspend fun prepare(params: List<net.pocketnai.domain.model.GenerationParams>): List<net.pocketnai.domain.model.GenerationParams> = params
    suspend fun prepareReferences(
        specs: List<ChatReferenceSpec>,
        model: net.pocketnai.domain.model.ImageModel?,
    ): List<net.pocketnai.domain.model.ReferenceImage> = emptyList()
    fun authorizationKey(): String?
    suspend fun quote(params: net.pocketnai.domain.model.GenerationParams): ChatImageQuote
    suspend fun quote(
        params: net.pocketnai.domain.model.GenerationParams,
        references: List<net.pocketnai.domain.model.ReferenceImage>,
    ): ChatImageQuote = quote(params)
    suspend fun generate(params: net.pocketnai.domain.model.GenerationParams, account: String?, beforeSend: suspend () -> Unit, onImage: (ChatImage) -> Unit): List<ChatImage>
    suspend fun generate(
        params: net.pocketnai.domain.model.GenerationParams,
        references: List<net.pocketnai.domain.model.ReferenceImage>,
        account: String?,
        beforeSend: suspend () -> Unit,
        onImage: (ChatImage) -> Unit,
    ): List<ChatImage> = generate(params, account, beforeSend, onImage)
    suspend fun quoteOpenAi(params: net.pocketnai.domain.openai.OpenAiImageParams): ChatImageQuote =
        throw ChatFailure("OpenAI 图片生成未配置")
    suspend fun quoteOpenAi(
        params: net.pocketnai.domain.openai.OpenAiImageParams,
        references: List<net.pocketnai.domain.model.ReferenceImage>,
    ): ChatImageQuote = quoteOpenAi(params)
    suspend fun generateOpenAi(
        params: net.pocketnai.domain.openai.OpenAiImageParams,
        account: String?,
        beforeSend: suspend () -> Unit,
        onImage: (ChatImage) -> Unit,
    ): List<ChatImage> = throw ChatFailure("OpenAI 图片生成未配置")
    suspend fun generateOpenAi(
        params: net.pocketnai.domain.openai.OpenAiImageParams,
        references: List<net.pocketnai.domain.model.ReferenceImage>,
        account: String?,
        beforeSend: suspend () -> Unit,
        onImage: (ChatImage) -> Unit,
    ): List<ChatImage> = generateOpenAi(params, account, beforeSend, onImage)
}
