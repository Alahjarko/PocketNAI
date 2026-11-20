package net.pocketnai.domain.chat

import kotlinx.serialization.json.*

/** 聚合增量 tool_calls；没有完整终止事件时，不能执行半截参数。 */
class ChatStreamAccumulator {
    private val content = StringBuilder()
    private val reasoning = StringBuilder()
    private var reasoningSeen = false
    private val details = mutableListOf<JsonElement>()
    private var messageExtraContent: JsonElement? = null
    private data class CallParts(
        val id: StringBuilder = StringBuilder(),
        val name: StringBuilder = StringBuilder(),
        val args: StringBuilder = StringBuilder(),
        var extraContent: JsonElement? = null,
    )
    private val calls = sortedMapOf<Int, CallParts>()
    var finishReason: String? = null
        private set
    private var totalChars = 0

    fun append(chunk: JsonObject) {
        totalChars += chunk.toString().length
        if (totalChars > 4 * 1024 * 1024) throw ChatFailure("回复过长，已停止读取")
        val choice = (chunk["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return
        choice.string("finish_reason")?.let { finishReason = it }
        val delta = choice["delta"] as? JsonObject ?: return
        delta.string("content")?.let(content::append)
        if (delta.containsKey("reasoning_content") || delta.containsKey("reasoning")) reasoningSeen = true
        (delta.string("reasoning_content") ?: delta.string("reasoning"))?.let(reasoning::append)
        (delta["reasoning_details"] as? JsonArray)?.let { details.addAll(it) }
        delta["extra_content"]?.let { if (it !is JsonNull) messageExtraContent = it }
        (delta["tool_calls"] as? JsonArray)?.forEachIndexed { pos, it ->
            val call = it as? JsonObject ?: return@forEachIndexed
            val index = (call["index"] as? JsonPrimitive)?.intOrNull ?: pos
            if (index !in 0..15) throw ChatFailure("工具调用数量过多")
            val parts = calls.getOrPut(index) { CallParts() }
            call.string("id")?.takeIf(String::isNotEmpty)?.let { id ->
                // 部分 OpenAI 兼容网关会在每个增量分片重复带出完整 id，避免拼接成重复字符串。
                if (parts.id.isEmpty()) parts.id.append(id)
            }
            call["extra_content"]?.let { extra -> if (extra !is JsonNull) parts.extraContent = extra }
            val fn = call["function"] as? JsonObject ?: return@forEachIndexed
            fn.string("name")?.takeIf(String::isNotEmpty)?.let { name ->
                if (parts.name.toString() != name) parts.name.append(name)
            }
            fn.string("arguments")?.let(parts.args::append)
            if (parts.args.length > 64 * 1024) throw ChatFailure("图片参数过长")
        }
    }

    fun message(): JsonObject = buildJsonObject {
        put("role", "assistant")
        if (content.isEmpty() && calls.isNotEmpty()) put("content", JsonNull) else put("content", content.toString())
        if (reasoningSeen) put("reasoning_content", reasoning.toString())
        if (details.isNotEmpty()) put("reasoning_details", JsonArray(details))
        messageExtraContent?.let { put("extra_content", it) }
        if (calls.isNotEmpty()) put("tool_calls", buildJsonArray {
            calls.forEach { (index, parts) ->
                val callId = parts.id.toString().ifEmpty { "call_$index" }
                add(buildJsonObject {
                    put("id", callId); put("type", "function")
                    put("function", buildJsonObject { put("name", parts.name.toString()); put("arguments", parts.args.toString()) })
                    parts.extraContent?.let { put("extra_content", it) }
                })
            }
        })
    }

    fun complete(): JsonObject {
        if (finishReason == "length") throw ChatFailure("模型达到输出上限，回复未完成；请增加最大输出 Tokens")
        if (finishReason !in setOf("stop", "tool_calls")) throw ChatFailure("回复中断，未执行图片工具；可以重新发送消息")
        if (calls.values.any { it.name.isEmpty() }) throw ChatFailure("工具响应不完整")
        return message()
    }
}
