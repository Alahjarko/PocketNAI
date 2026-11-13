package net.pocketnai.mcpbench

import fi.iki.elonen.NanoHTTPD
import kotlinx.serialization.json.*
import net.pocketnai.data.mcp.McpJobStore
import java.io.File

/** Deterministic local OpenAI-compatible fixture for testing RikkaHub's real MCP client. */
class OfflineChatServer(private val files: File) : NanoHTTPD("127.0.0.1", 47762) {
    override fun serve(session: IHTTPSession): Response { return try {
        if (session.uri.endsWith("/models")) return newFixedLengthResponse(Response.Status.OK, "application/json",
            """{"object":"list","data":[{"id":"gpt-4o-mini","object":"model","owned_by":"offline-fixture"}]}""")
        if (!session.uri.endsWith("/chat/completions")) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "offline only")
        val length = session.headers["content-length"]?.toIntOrNull() ?: 0
        if (length !in 1..2_000_000) return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "invalid request")
        val bytes = ByteArray(length)
        var at = 0
        while (at < length) { val n = session.inputStream.read(bytes, at, length - at); if (n <= 0) break; at += n }
        val body = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        val all = body["messages"]!!.jsonArray.map { it.jsonObject }
        val lastUser = all.indexOfLast { it["role"]?.jsonPrimitive?.content == "user" }
        val messages = all.drop((lastUser + 1).coerceAtLeast(0))
        val calls = messages.flatMap { (it["tool_calls"] as? JsonArray).orEmpty() }.map { it.jsonObject }
        val last = messages.lastOrNull { it["role"]?.jsonPrimitive?.content == "tool" }
        val lastCall = calls.lastOrNull { it["id"] == last?.get("tool_call_id") }?.get("function")?.jsonObject?.get("name")?.jsonPrimitive?.content
        fun text(value: JsonElement?): String = when (value) {
            is JsonPrimitive -> value.content
            is JsonArray -> value.joinToString("\n") { it.jsonObject["text"]?.jsonPrimitive?.content.orEmpty() }
            else -> ""
        }
        val content = last?.let { text(it["content"]) }.orEmpty()
        val result = runCatching { Json.parseToJsonElement(content.substring(content.indexOf('{'), content.lastIndexOf('}') + 1)).jsonObject }.getOrNull()
        val state = result?.get("state")?.jsonPrimitive?.content
        val id = result?.get("job_id")?.jsonPrimitive?.content
        val tools = body["tools"]?.jsonArray.orEmpty().mapNotNull { it.jsonObject["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content }
        var suffix: String? = when {
            lastCall == null -> "submit_image_task"
            lastCall.endsWith("get_image_result") -> null
            state == "succeeded" -> "get_image_result"
            id != null -> "get_image_task"
            else -> null
        }
        val name = suffix?.let { end -> tools.firstOrNull { it.endsWith(end) } }
        if (name == null) suffix = null
        val args = buildJsonObject {
            when (suffix) {
                "submit_image_task" -> {
                    put("request_id", "rk_" + McpJobStore.digest(text(all.getOrNull(lastUser)?.get("content")) + lastUser).take(24))
                    put("provider", "novelai"); put("prompt", "sfw, blue geometric shape")
                }
                "get_image_task" -> { put("job_id", id); put("wait_seconds", 12) }
                "get_image_result" -> put("job_id", id)
            }
        }
        File(files, "offline-chat-stages.txt").appendText((suffix ?: "done") + "\n")
        val delta = buildJsonObject {
            put("role", "assistant")
            if (name != null) put("tool_calls", buildJsonArray { add(buildJsonObject {
                put("index", 0); put("id", "offline-${System.nanoTime()}"); put("type", "function")
                put("function", buildJsonObject { put("name", name); put("arguments", args.toString()) })
            }) }) else put("content", if (lastCall?.endsWith("get_image_result") == true) "离线联调完成，图片已取回。" else "请先启用本机 MCP 工具。")
        }
        fun chunk(value: JsonObject, finish: String? = null) = buildJsonObject {
            put("id", "chatcmpl-offline"); put("object", "chat.completion.chunk"); put("created", System.currentTimeMillis() / 1000)
            put("model", "gpt-4o-mini"); put("choices", buildJsonArray { add(buildJsonObject {
                put("index", 0); put("delta", value); put("finish_reason", finish?.let(::JsonPrimitive) ?: JsonNull)
            }) })
        }.toString()
        newFixedLengthResponse(Response.Status.OK, "text/event-stream",
            "data: ${chunk(delta)}\n\ndata: ${chunk(buildJsonObject {}, if (name != null) "tool_calls" else "stop")}\n\ndata: [DONE]\n\n")
    } catch (_: Exception) { newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"Offline fixture request failed\"}") }
    }
}
