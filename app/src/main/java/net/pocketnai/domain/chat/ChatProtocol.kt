package net.pocketnai.domain.chat

import kotlinx.serialization.json.*
import java.net.URI

object ChatProtocol {
    /** base URL 可以含网关前缀；不猜测追加 /v1，用户填什么前缀就使用什么。 */
    fun endpoint(baseUrl: String, resource: String): String {
        val url = runCatching { URI(baseUrl.trim()) }.getOrNull()
            ?: throw ChatFailure("Base URL 格式不正确")
        if (url.scheme != "https" || url.host.isNullOrBlank() || url.rawUserInfo != null ||
            url.rawQuery != null || url.rawFragment != null
        ) throw ChatFailure("请填写不含密码、查询参数的 HTTPS API Base URL")
        val path = url.path.trimEnd('/')
        if (path.endsWith("/chat/completions") || path.endsWith("/models"))
            throw ChatFailure("请填写 API 根路径，例如 https://api.deepseek.com/v1")
        return baseUrl.trim().trimEnd('/') + "/" + resource
    }

    fun dialect(config: LlmConfig): LlmDialect {
        if (config.dialect != LlmDialect.AUTO) return config.dialect
        val value = (config.baseUrl + " " + config.model).lowercase()
        return when {
            "kimi" in value || "moonshot" in value -> LlmDialect.KIMI
            "deepseek" in value -> LlmDialect.DEEPSEEK
            else -> LlmDialect.OPENAI_COMPATIBLE
        }
    }

    fun request(config: LlmConfig, messages: List<JsonObject>, tools: JsonArray): JsonObject {
        if (config.model.isBlank()) throw ChatFailure("请先选择或填写模型")
        return buildJsonObject {
            put("model", config.model); put("stream", true)
            put("max_tokens", config.maxTokens.coerceIn(1024, 65536))
            put("messages", JsonArray(messages))
            if (tools.isNotEmpty()) { put("tools", tools); put("tool_choice", "auto") }
            when (dialect(config)) {
                LlmDialect.DEEPSEEK -> put("thinking", buildJsonObject { put("type", if (config.thinking) "enabled" else "disabled") })
                LlmDialect.KIMI -> {
                    val model = config.model.lowercase()
                    // 这些型号内建思考，不能传 disabled；K3 不接受 thinking 字段。
                    if ("/coding/" in config.baseUrl || model == "k3" || model.startsWith("k3-")) {
                        put("reasoning_effort", if (config.thinking) "high" else "none")
                    } else if ("kimi-k3" !in model && "k2-thinking" !in model && "k2.7-code" !in model) {
                        put("thinking", buildJsonObject { put("type", if (config.thinking) "enabled" else "disabled") })
                    }
                }
                else -> if (config.thinking) put("reasoning_effort", "high")
            }
            // 不发送 temperature，避免 Kimi 思考模型拒绝请求。
        }
    }

    /** 保留完整思考字段及工具调用，不把应用内部图片路径送到供应商。 */
    fun assistant(message: JsonObject): JsonObject = JsonObject(
        message.filterKeys { it in setOf("content", "reasoning_content", "reasoning", "reasoning_details", "tool_calls", "refusal") } +
            mapOf("role" to JsonPrimitive("assistant"), "content" to (message["content"] ?: JsonNull)),
    )
}
