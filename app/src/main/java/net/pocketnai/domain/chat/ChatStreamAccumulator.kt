package net.pocketnai.domain.chat

import kotlinx.serialization.json.*

/** 聚合增量 tool_calls；没有完整终止事件时，不能执行半截参数。 */
class ChatStreamAccumulator {
    private val content = StringBuilder()
    private val reasoning = StringBuilder()
    private var reasoningSeen = false
    private val details = mutableListOf<JsonElement>()
    private data class CallParts(val id: StringBuilder = StringBuilder(), val name: StringBuilder = StringBuilder(), val args: StringBuilder = StringBuilder())
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
        (delta["tool_calls"] as? JsonArray)?.forEach {
            val call = it as? JsonObject ?: return@forEach
            val index = (call["index"] as? JsonPrimitive)?.intOrNull ?: 0
            if (index !in 0..15) throw ChatFailure("工具调用数量过多")
            val parts = calls.getOrPut(index) { CallParts() }
            call.string("id")?.let(parts.id::append)
            val fn = call["function"] as? JsonObject ?: return@forEach
            fn.string("name")?.let(parts.name::append)
            fn.string("arguments")?.let(parts.args::append)
            if (parts.args.length > 64 * 1024) throw ChatFailure("图片参数过长")
        }
    }

    fun message(): JsonObject = buildJsonObject {
        put("role", "assistant"); put("content", content.toString())
        if (reasoningSeen) put("reasoning_content", reasoning.toString())
        if (details.isNotEmpty()) put("reasoning_details", JsonArray(details))
        if (calls.isNotEmpty()) put("tool_calls", buildJsonArray {
            calls.values.forEach { parts -> add(buildJsonObject {
                put("id", parts.id.toString()); put("type", "function")
                put("function", buildJsonObject { put("name", parts.name.toString()); put("arguments", parts.args.toString()) })
            }) }
        })
    }

    fun complete(): JsonObject {
        if (finishReason == "length") throw ChatFailure("模型达到输出上限，回复未完成；请增加最大输出 Tokens")
        if (finishReason !in setOf("stop", "tool_calls")) throw ChatFailure("回复中断，未执行图片工具；可以重新发送消息")
        if (calls.values.any { it.id.isEmpty() || it.name.isEmpty() }) throw ChatFailure("工具响应不完整")
        return message()
    }
}
