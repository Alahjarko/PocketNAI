package net.pocketnai.domain.mcp

import kotlinx.serialization.json.*

/** JSON response mode of Streamable HTTP; no request waits for image generation. */
class McpProtocol(private val tools: McpTools) {
    interface McpTools { suspend fun call(name: String, arguments: JsonObject): JsonObject }
    data class Reply(val status: Int, val body: String? = null)

    suspend fun handle(body: String, version: String?): Reply {
        val request = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: return error(null, -32700, "Invalid JSON", 400)
        val id = request["id"]
        val method = request["method"] as? JsonPrimitive
        if ((request["jsonrpc"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull != "2.0" || method?.isString != true ||
            (id != null && (id !is JsonPrimitive || id is JsonNull || (!id.isString && id.longOrNull == null))))
            return error(null, -32600, "Invalid request", 400)
        if (version != null && version !in VERSIONS) return error(id, -32600, "Unsupported protocol version", 400)
        val params = request["params"]?.let { it as? JsonObject ?: return error(id, -32602, "Invalid params") } ?: buildJsonObject {}
        if (id == null) return if (method.content.startsWith("notifications/")) Reply(202)
            else error(null, -32600, "Request id required", 400)
        val result = when (method.content) {
            "initialize" -> buildJsonObject {
                val preferred = (params["protocolVersion"] as? JsonPrimitive)?.contentOrNull
                put("protocolVersion", preferred?.takeIf { it in VERSIONS } ?: VERSIONS.last())
                put("capabilities", buildJsonObject { put("tools", buildJsonObject { put("listChanged", false) }) })
                put("serverInfo", buildJsonObject { put("name", "PocketNAI"); put("version", "1.0") })
                put("instructions", "Local image tasks. Submit once with a unique request_id, then poll get_image_task. Never resubmit on a timeout. Confirmation may be required in PocketNAI. Retrieve finished images with get_image_result.")
            }
            "ping" -> buildJsonObject {}
            "tools/list" -> buildJsonObject { put("tools", schema) }
            "resources/list" -> buildJsonObject { put("resources", buildJsonArray {}) }
            "prompts/list" -> buildJsonObject { put("prompts", buildJsonArray {}) }
            "tools/call" -> {
                val name = (params["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: return error(id, -32602, "Tool name required")
                val args = params["arguments"]?.let { it as? JsonObject ?: return error(id, -32602, "Invalid arguments") } ?: buildJsonObject {}
                if (schema.none { it.jsonObject["name"]?.jsonPrimitive?.content == name }) return error(id, -32602, "Unknown tool")
                tools.call(name, args)
            }
            else -> return error(id, -32601, "Method not found")
        }
        return Reply(200, buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", result) }.toString())
    }

    private fun error(id: JsonElement?, code: Int, message: String, status: Int = 200) = Reply(status, buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id ?: JsonNull)
        put("error", buildJsonObject { put("code", code); put("message", message) })
    }.toString())

    companion object {
        val VERSIONS = listOf("2025-03-26", "2025-06-18", "2025-11-25")
        fun text(value: JsonElement, failed: Boolean = false) = buildJsonObject {
            put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", value.toString()) }) })
            put("isError", failed)
        }
        fun failure(message: String) = text(buildJsonObject { put("error", message) }, true)
        private fun field(type: String, description: String) = buildJsonObject { put("type", type); put("description", description) }
        private fun tool(name: String, description: String, required: List<String>, properties: JsonObject, readOnly: Boolean = true) = buildJsonObject {
            put("name", name); put("description", description)
            put("inputSchema", buildJsonObject {
                put("type", "object"); put("properties", properties); put("additionalProperties", false)
                put("required", JsonArray(required.map(::JsonPrimitive)))
            })
            put("annotations", buildJsonObject { put("readOnlyHint", readOnly); put("idempotentHint", true); put("openWorldHint", !readOnly) })
        }
        val schema = buildJsonArray {
            add(tool("get_image_capabilities", "Read supported image providers, models and task workflow. No keys or draft prompts.", emptyList(), buildJsonObject {}))
            add(tool("submit_image_task", "Submit ONE text-to-image task. May incur fees. Use a unique request_id for each deliberate user request; reuse the SAME request_id on retries. Returns immediately; the user may need to confirm in PocketNAI. Does not attach existing reference images.", listOf("request_id", "provider", "prompt"), buildJsonObject {
                put("request_id", field("string", "Unique stable request identifier, 8–128 ASCII letters/digits/underscore/hyphen. Reuse only for retries of the same task."))
                put("provider", buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf("novelai", "openai").map(::JsonPrimitive))) })
                put("prompt", field("string", "Complete image description / positive prompt."))
                put("negative_prompt", field("string", "NovelAI negative prompt; omit for OpenAI."))
                put("model", field("string", "A model ID returned by get_image_capabilities."))
                put("width", field("integer", "NovelAI canvas width; multiples of 64."))
                put("height", field("integer", "NovelAI canvas height; multiples of 64."))
                put("seed", field("integer", "Optional NovelAI fixed seed."))
                put("size", field("string", "OpenAI size: 1024x1024, 1536x1024 or 1024x1536."))
                put("quality", field("string", "OpenAI quality: low, medium, high or auto."))
            }, false))
            add(tool("get_image_task", "Query an existing task. Never submit again while awaiting confirmation or generation. Optional wait_seconds waits at most 20 seconds for a change.", listOf("job_id"), buildJsonObject {
                put("job_id", field("string", "Task ID from submit_image_task.")); put("wait_seconds", field("integer", "Optional 0–20 seconds."))
            }))
            add(tool("get_image_result", "Return the completed task image as MCP ImageContent for display. The original stays in PocketNAI gallery. No arbitrary gallery/file access.", listOf("job_id"), buildJsonObject { put("job_id", field("string", "Completed task ID.")) }))
            add(tool("cancel_image_task", "Cancel a task that has not been sent. An already sent image request cannot be cancelled or retried.", listOf("job_id"), buildJsonObject { put("job_id", field("string", "Task ID.")) }, false))
        }
    }
}
