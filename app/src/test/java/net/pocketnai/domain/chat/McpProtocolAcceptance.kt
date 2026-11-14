package net.pocketnai.domain.chat

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import net.pocketnai.domain.mcp.McpProtocol
import net.pocketnai.data.mcp.McpImageLinks
import net.pocketnai.domain.mcp.McpNovelAiParams
import net.pocketnai.domain.model.*
import net.pocketnai.data.network.NovelAiRequestBuilder

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

    val properties = listed.single { it.jsonObject["name"]!!.jsonPrimitive.content == "submit_image_task" }
        .jsonObject["inputSchema"]!!.jsonObject["properties"]!!.jsonObject
    assertThat(properties.keys).containsAtLeast("characters", "use_coords")
    val base = GenerationParams.defaultsFor(ModelCatalog.profileOf(ImageModel.V5_FULL))
        .copy(characters = listOf(CharacterPrompt(prompt = "never inherit draft role")))
    val input = Json.parseToJsonElement("""{"prompt":"two geometric characters","negative_prompt":"lowres","characters":[{"prompt":"1.2::blue hair::","negative_prompt":"hat","x":0.2,"y":0.3},{"prompt":"red hair","negative_prompt":"glasses","x":0.8,"y":0.7}]}""").jsonObject
    val parsed = McpNovelAiParams.parse(input, base)
    assertThat(parsed.useCharacterCoordinates).isTrue()
    assertThat(parsed.characters.map { it.prompt }).containsExactly("1.2::blue hair::", "red hair").inOrder()
    val payload = NovelAiRequestBuilder.build(ModelCatalog.profileOf(parsed.model), parsed)["parameters"]!!.jsonObject
    for ((key, expected) in listOf("v4_prompt" to listOf("1.2::blue hair::", "red hair"), "v4_negative_prompt" to listOf("hat", "glasses"))) {
        val roles = payload[key]!!.jsonObject["caption"]!!.jsonObject["char_captions"]!!.jsonArray
        assertThat(roles.map { it.jsonObject["char_caption"]!!.jsonPrimitive.content }).containsExactlyElementsIn(expected).inOrder()
        assertThat(roles[1].jsonObject["centers"]!!.jsonArray[0].jsonObject["x"]!!.jsonPrimitive.double).isEqualTo(0.8)
    }
    assertThat(McpNovelAiParams.characterDetails(parsed)).contains("角色 2 · x=0.8, y=0.7")
    val automatic = McpNovelAiParams.parse(JsonObject(input + ("use_coords" to JsonPrimitive(false))), base)
    assertThat(automatic.useCharacterCoordinates).isFalse()
    assertThat(McpNovelAiParams.characterDetails(automatic)).contains("AI 自动布局")
    assertThat(McpNovelAiParams.parse(buildJsonObject { put("prompt", "scene | literal caption") }, base).characters).isEmpty()
    assertThat(McpNovelAiParams.parse(buildJsonObject { put("prompt", "scene"); put("characters", buildJsonArray {}) }, base).characters).isEmpty()
    for (invalid in listOf(
        """[{"prompt":"role","x":1.1}]""", """[{"prompt":"role","y":"0.5"}]""",
        """[{"prompt":"role","weight":1.2}]""", """[{"prompt":"role","negative_prompt":false}]""",
        """[{"prompt":""}]""", """null""")) {
        assertThat(runCatching { McpNovelAiParams.parse(JsonObject(input + ("characters" to Json.parseToJsonElement(invalid))), base) }.isFailure).isTrue()
    }
    fun many(count: Int) = JsonObject(input + ("characters" to JsonArray(List(count) { input["characters"]!!.jsonArray[0] })))
    assertThat(runCatching { McpNovelAiParams.parse(many(7), base.copy(model = ImageModel.V4_5_FULL)) }.isFailure).isTrue()
    assertThat(McpNovelAiParams.parse(many(32), base).characters).hasSize(32)
    assertThat(runCatching { McpNovelAiParams.parse(many(33), base) }.isFailure).isTrue()
    val grid = ModelCatalog.profileOf(ImageModel.V4_5_FULL).normalize(parsed.copy(model = ImageModel.V4_5_FULL))
    assertThat(grid.characters.first().centerX).isEqualTo(0.3)
    assertThat(parsed.characters.first().centerX).isEqualTo(0.2)

    var now = 0L
    val links = McpImageLinks { now }
    val path = links.issue("private-image-id")
    assertThat(path).doesNotContain("private-image-id")
    assertThat(links.resolve(path)).isEqualTo("private-image-id")
    assertThat(links.issue("private-image-id")).isEqualTo(path)
    assertThat(links.resolve(path + "/../another")).isNull()
    now += McpImageLinks.TTL_MILLIS
    assertThat(links.resolve(path)).isNull()
    val renewed = links.issue("private-image-id")
    assertThat(renewed).isNotEqualTo(path)
    repeat(McpImageLinks.MAX_LINKS) { links.issue("other-$it") }
    assertThat(links.resolve(renewed)).isNull()
    val last = links.issue("last")
    links.clear()
    assertThat(links.resolve(last)).isNull()
}
