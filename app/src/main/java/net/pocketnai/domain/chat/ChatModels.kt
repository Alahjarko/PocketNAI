package net.pocketnai.domain.chat

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
enum class LlmDialect { AUTO, KIMI, DEEPSEEK, OPENAI_COMPATIBLE }

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
    suspend fun prepare(params: List<net.pocketnai.domain.model.GenerationParams>): List<net.pocketnai.domain.model.GenerationParams> = params
    fun authorizationKey(): String?
    suspend fun quote(params: net.pocketnai.domain.model.GenerationParams): ChatImageQuote
    suspend fun generate(params: net.pocketnai.domain.model.GenerationParams, account: String?, beforeSend: suspend () -> Unit, onImage: (ChatImage) -> Unit): List<ChatImage>
}
