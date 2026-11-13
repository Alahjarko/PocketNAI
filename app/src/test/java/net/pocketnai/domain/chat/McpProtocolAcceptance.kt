package net.pocketnai.domain.chat

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import net.pocketnai.domain.mcp.McpProtocol

internal fun verifyLocalMcpProtocol() = runBlocking {
    var calls = 0
    val protocol = McpProtocol(object : McpProtocol.McpTools {
        override suspend fun call(name: String, arguments: JsonObject): JsonObject {
            calls++; return McpProtocol.text(buildJsonObject { put("supported", true) })
        }
    })
    suspend fun request(method: String, params: JsonObject = buildJsonObject {}) = protocol.handle(buildJsonObject {
        put("jsonrpc", "2.0"); put("id", "client-request"); put("method", method); put("params", params)
    }.toString(), "2025-11-25")
    val init = request("initialize", buildJsonObject { put("protocolVersion", "2025-03-26") })
    assertThat(init.status).isEqualTo(200)
    assertThat(Json.parseToJsonElement(init.body!!).jsonObject["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content)
        .isEqualTo("2025-03-26")
    assertThat(protocol.handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}""", null).status).isEqualTo(202)
    val listed = Json.parseToJsonElement(request("tools/list").body!!).jsonObject["result"]!!.jsonObject["tools"]!!.jsonArray
    assertThat(listed.map { it.jsonObject["name"]!!.jsonPrimitive.content }).containsAtLeast("submit_image_task", "get_image_task", "get_image_result")
    request("tools/call", buildJsonObject { put("name", "get_image_capabilities") })
    assertThat(calls).isEqualTo(1)
    val unknown = request("tools/call", buildJsonObject { put("name", "delete_all_files") })
    assertThat(unknown.body).contains("Unknown tool")
    assertThat(calls).isEqualTo(1)
    assertThat(protocol.handle("""{"jsonrpc":{},"id":1,"method":"ping"}""", null).status).isEqualTo(400)
    assertThat(protocol.handle("""{"jsonrpc":"2.0","id":true,"method":"ping"}""", null).status).isEqualTo(400)
    assertThat(protocol.handle("broken JSON", null).body).doesNotContain("broken JSON")
    assertThat(protocol.handle("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", "unsupported").status).isEqualTo(400)
    assertThat(request("not_a_method").body).contains("-32601")
}
