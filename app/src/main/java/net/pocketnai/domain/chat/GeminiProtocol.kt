package net.pocketnai.domain.chat

import kotlinx.serialization.json.*
import java.net.URI

/**
 * Google Gemini 原生 REST 协议适配（`v1beta/models/{model}:streamGenerateContent?alt=sse`）。
 *
 * 内部仍统一映射成应用现有的 OpenAI 形态 `ChatEntry.wire`（正文、`reasoning_content`、
 * `tool_calls` 与 `extra_content.google.thought_signature`），因此不改变 Room 存储结构，
 * 也允许在同一对话中无缝衔接历史记录。
 */
object GeminiProtocol {
    private val allowedModelChars = Regex("^[A-Za-z0-9._:-]{1,128}$")

    fun baseRoot(baseUrl: String): String {
        val trimmed = baseUrl.trim()
        val url = runCatching { URI(trimmed) }.getOrNull()
            ?: throw ChatFailure("Base URL 格式不正确")
        if (url.scheme != "https" || url.host.isNullOrBlank() || url.rawUserInfo != null ||
            url.rawQuery != null || url.rawFragment != null
        ) throw ChatFailure("请填写不含密码、查询参数的 HTTPS API Base URL")
        val path = url.path.trimEnd('/')
        if (":generateContent" in path || ":streamGenerateContent" in path || path.endsWith("/chat/completions")) {
            throw ChatFailure("请填写 Gemini API 根路径，例如 https://generativelanguage.googleapis.com/v1beta")
        }
        var root = trimmed.trimEnd('/')
        if (root.lowercase().endsWith("/openai")) root = root.dropLast("/openai".length)
        if (path.isEmpty() && url.host.equals("generativelanguage.googleapis.com", ignoreCase = true)) {
            root += "/v1beta"
        }
        return root
    }

    fun modelsEndpoint(baseUrl: String): String = baseRoot(baseUrl) + "/models?pageSize=200"

    fun streamEndpoint(baseUrl: String, model: String): String {
        val clean = model.trim().removePrefix("models/")
        if (clean.isBlank()) throw ChatFailure("请先选择或填写模型")
        if (!allowedModelChars.matches(clean) || ".." in clean) throw ChatFailure("Gemini 模型 ID 格式不正确")
        return baseRoot(baseUrl) + "/models/$clean:streamGenerateContent?alt=sse"
    }

    fun parseModels(root: JsonObject, apiKey: String): List<String> {
        val native = (root["models"] as? JsonArray).orEmpty().mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val methods = (obj["supportedGenerationMethods"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            if (methods != null && "generateContent" !in methods) return@mapNotNull null
            obj.string("name")?.removePrefix("models/")?.takeIf(String::isNotBlank)
        }
        val items = if (native.isNotEmpty()) native else {
            (root["data"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.string("id")?.removePrefix("models/") }
        }
        return items.filterNot { apiKey in it }.distinct().sorted().take(500)
    }

    fun request(config: LlmConfig, messages: List<JsonObject>, tools: JsonArray): JsonObject {
        if (config.model.isBlank()) throw ChatFailure("请先选择或填写模型")
        val systemParts = mutableListOf<String>()
        val toolNamesById = mutableMapOf<String, String>()
        messages.forEach { msg ->
            (msg["tool_calls"] as? JsonArray)?.forEach { item ->
                val obj = item as? JsonObject ?: return@forEach
                val id = obj.string("id").orEmpty()
                val name = (obj["function"] as? JsonObject)?.string("name").orEmpty()
                if (id.isNotEmpty() && name.isNotEmpty()) toolNamesById[id] = name
            }
        }

        data class ContentTurn(val role: String, val parts: MutableList<JsonObject>)
        val turns = mutableListOf<ContentTurn>()
        fun appendParts(role: String, newParts: List<JsonObject>) {
            if (newParts.isEmpty()) return
            val last = turns.lastOrNull()
            if (last != null && last.role == role) last.parts.addAll(newParts)
            else turns += ContentTurn(role, newParts.toMutableList())
        }

        messages.forEach { msg ->
            when (msg.string("role")) {
                "system" -> msg.string("content")?.takeIf(String::isNotBlank)?.let(systemParts::add)
                "user" -> appendParts("user", userParts(msg["content"]))
                "assistant" -> appendParts("model", assistantParts(msg))
                "tool" -> {
                    val callId = msg.string("tool_call_id").orEmpty()
                    val name = msg.string("name")?.takeIf(String::isNotBlank) ?: toolNamesById[callId] ?: "tool"
                    val raw = msg.string("content").orEmpty()
                    val responseObj = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
                        ?: buildJsonObject { put("result", raw) }
                    val part = buildJsonObject {
                        put("functionResponse", buildJsonObject {
                            if (callId.isNotEmpty()) put("id", callId)
                            put("name", name)
                            put("response", responseObj)
                        })
                    }
                    // 连续多个并行工具结果合并到同一条 user turn 中，符合 Gemini 原生规范。
                    appendParts("user", listOf(part))
                }
            }
        }

        return buildJsonObject {
            if (systemParts.isNotEmpty()) {
                put("systemInstruction", buildJsonObject {
                    put("parts", buildJsonArray { add(buildJsonObject { put("text", systemParts.joinToString("\n\n")) }) })
                })
            }
            put("contents", buildJsonArray {
                turns.forEach { turn ->
                    add(buildJsonObject {
                        put("role", turn.role)
                        put("parts", JsonArray(turn.parts))
                    })
                }
            })
            put("generationConfig", buildJsonObject {
                put("maxOutputTokens", config.maxTokens.coerceIn(1024, 65536))
                put("thinkingConfig", buildJsonObject {
                    put("includeThoughts", config.thinking)
                })
            })
            val declarations = convertTools(tools)
            if (declarations.isNotEmpty()) {
                put("tools", buildJsonArray {
                    add(buildJsonObject { put("functionDeclarations", JsonArray(declarations)) })
                })
            }
        }
    }

    private fun userParts(content: JsonElement?): List<JsonObject> = when (content) {
        is JsonPrimitive -> content.contentOrNull?.takeIf(String::isNotEmpty)?.let {
            listOf(buildJsonObject { put("text", it) })
        }.orEmpty()
        is JsonArray -> content.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            when (obj.string("type")) {
                "text" -> obj.string("text")?.takeIf(String::isNotEmpty)?.let { buildJsonObject { put("text", it) } }
                "image_url" -> {
                    val url = (obj["image_url"] as? JsonObject)?.string("url").orEmpty()
                    parseDataUrl(url)?.let { (mime, b64) ->
                        buildJsonObject {
                            put("inlineData", buildJsonObject {
                                put("mimeType", mime)
                                put("data", b64)
                            })
                        }
                    }
                }
                else -> null
            }
        }
        else -> emptyList()
    }

    private fun assistantParts(msg: JsonObject): List<JsonObject> {
        val parts = mutableListOf<JsonObject>()
        val msgSig = thoughtSignatureOf(msg["extra_content"])
        val text = msg.string("content").orEmpty()
        val calls = (msg["tool_calls"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        if (text.isNotEmpty()) {
            parts += buildJsonObject {
                put("text", text)
                if (calls.isEmpty() && msgSig != null) put("thoughtSignature", msgSig)
            }
        }
        calls.forEachIndexed { index, call ->
            val fn = call["function"] as? JsonObject ?: return@forEachIndexed
            val name = fn.string("name").orEmpty()
            if (name.isEmpty()) return@forEachIndexed
            val argsRaw = fn.string("arguments").orEmpty()
            val argsObj = runCatching { Json.parseToJsonElement(argsRaw) as? JsonObject }.getOrNull() ?: buildJsonObject {}
            val callId = call.string("id").orEmpty()
            val sig = thoughtSignatureOf(call["extra_content"]) ?: if (index == 0) msgSig else null
            parts += buildJsonObject {
                put("functionCall", buildJsonObject {
                    if (callId.isNotEmpty()) put("id", callId)
                    put("name", name)
                    put("args", argsObj)
                })
                if (sig != null) put("thoughtSignature", sig)
            }
        }
        return parts
    }

    private fun thoughtSignatureOf(extra: JsonElement?): String? {
        val obj = extra as? JsonObject ?: return null
        val google = obj["google"] as? JsonObject ?: return null
        return google.string("thought_signature")?.takeIf(String::isNotBlank)
    }

    internal fun extraContentWithSignature(signature: String): JsonObject = buildJsonObject {
        put("google", buildJsonObject { put("thought_signature", signature) })
    }

    private fun parseDataUrl(url: String): Pair<String, String>? {
        if (!url.startsWith("data:")) return null
        val comma = url.indexOf(',')
        if (comma < 0) return null
        val meta = url.substring(5, comma)
        if (!meta.endsWith(";base64")) return null
        val mime = meta.removeSuffix(";base64").ifBlank { "image/jpeg" }
        val data = url.substring(comma + 1)
        if (data.isEmpty()) return null
        return mime to data
    }

    private fun convertTools(tools: JsonArray): List<JsonObject> = tools.mapNotNull { item ->
        val obj = item as? JsonObject ?: return@mapNotNull null
        val fn = obj["function"] as? JsonObject ?: return@mapNotNull null
        val name = fn.string("name")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        buildJsonObject {
            put("name", name)
            fn.string("description")?.let { put("description", it) }
            val params = fn["parameters"] as? JsonObject
            val props = params?.get("properties") as? JsonObject
            // Gemini 原生 API 要求 OBJECT 类型的 parameters.properties 不能为空；无参工具直接省略 parameters。
            if (params != null && props != null && props.isNotEmpty()) {
                put("parameters", convertSchema(params))
            }
        }
    }

    private fun convertSchema(schema: JsonObject): JsonObject = buildJsonObject {
        schema.string("type")?.let { put("type", it.uppercase()) }
        schema.string("description")?.let { put("description", it) }
        (schema["enum"] as? JsonArray)?.let { put("enum", it) }
        (schema["required"] as? JsonArray)?.takeIf { it.isNotEmpty() }?.let { put("required", it) }
        (schema["items"] as? JsonObject)?.let { put("items", convertSchema(it)) }
        (schema["properties"] as? JsonObject)?.takeIf { it.isNotEmpty() }?.let { props ->
            put("properties", buildJsonObject {
                props.forEach { (k, v) -> (v as? JsonObject)?.let { put(k, convertSchema(it)) } }
            })
        }
    }
}

/** 聚合 Gemini 原生 `streamGenerateContent?alt=sse` 的增量片段，输出统一的 `assistant` 消息结构。 */
class GeminiStreamAccumulator {
    private val content = StringBuilder()
    private val reasoning = StringBuilder()
    private var reasoningSeen = false
    private var messageSignature: String? = null
    private data class NativeCall(val id: String, val name: String, val argsJson: String, val signature: String?)
    private val calls = mutableListOf<NativeCall>()
    var finishReason: String? = null
        private set
    private var totalChars = 0

    fun append(chunk: JsonObject) {
        totalChars += chunk.toString().length
        if (totalChars > 4 * 1024 * 1024) throw ChatFailure("回复过长，已停止读取")
        val candidate = (chunk["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return
        candidate.string("finishReason")?.let { finishReason = it }
        val parts = (candidate["content"] as? JsonObject)?.get("parts") as? JsonArray ?: return
        parts.forEach { item ->
            val part = item as? JsonObject ?: return@forEach
            val sig = part.string("thoughtSignature")?.takeIf(String::isNotBlank)
            val fnCall = part["functionCall"] as? JsonObject
            if (fnCall != null) {
                if (calls.size >= 16) throw ChatFailure("工具调用数量过多")
                val name = fnCall.string("name").orEmpty()
                if (name.isEmpty()) throw ChatFailure("工具响应不完整")
                val argsJson = (fnCall["args"] as? JsonObject)?.toString() ?: "{}"
                if (argsJson.length > 64 * 1024) throw ChatFailure("图片参数过长")
                val id = fnCall.string("id")?.takeIf(String::isNotBlank) ?: "gemini_call_${calls.size}"
                calls += NativeCall(id, name, argsJson, sig)
                return@forEach
            }
            val isThought = (part["thought"] as? JsonPrimitive)?.booleanOrNull == true
            val text = part.string("text")
            if (text != null) {
                if (isThought) {
                    reasoningSeen = true
                    reasoning.append(text)
                } else {
                    content.append(text)
                }
            }
            if (sig != null) messageSignature = sig
        }
    }

    fun message(): JsonObject = buildJsonObject {
        put("role", "assistant")
        if (content.isEmpty() && calls.isNotEmpty()) put("content", JsonNull) else put("content", content.toString())
        if (reasoningSeen) put("reasoning_content", reasoning.toString())
        messageSignature?.let { put("extra_content", GeminiProtocol.extraContentWithSignature(it)) }
        if (calls.isNotEmpty()) put("tool_calls", buildJsonArray {
            calls.forEach { call ->
                add(buildJsonObject {
                    put("id", call.id); put("type", "function")
                    put("function", buildJsonObject {
                        put("name", call.name)
                        put("arguments", call.argsJson)
                    })
                    call.signature?.let { put("extra_content", GeminiProtocol.extraContentWithSignature(it)) }
                })
            }
        })
    }

    fun complete(): JsonObject {
        when (finishReason) {
            "MAX_TOKENS" -> throw ChatFailure("模型达到输出上限，回复未完成；请增加最大输出 Tokens")
            "SAFETY", "RECITATION", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "IMAGE_SAFETY" ->
                throw ChatFailure("Gemini 安全策略拦截了本次回复")
            "MALFORMED_FUNCTION_CALL" ->
                throw ChatFailure("模型生成的工具调用格式异常，请重试或换一种表述")
            "STOP" -> Unit
            null -> if (calls.isEmpty()) throw ChatFailure("回复中断，未执行图片工具；可以重新发送消息")
            else -> if (calls.isEmpty()) throw ChatFailure("回复中断（$finishReason），未执行图片工具；可以重新发送消息")
        }
        return message()
    }
}
