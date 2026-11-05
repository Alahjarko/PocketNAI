package net.pocketnai.domain.chat

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import net.pocketnai.data.chat.OpenAiCompatibleChatClient
import net.pocketnai.domain.model.*
import net.pocketnai.domain.openai.OpenAiImageParams
import net.pocketnai.domain.openai.OpenAiImageSnapshot
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test

class ChatVisionHttpTest {
    @Test fun `供应商拒绝与断流都保留明确状态且不重试`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400).setBody("do not expose server error content"))
            val client = OpenAiCompatibleChatClient(endpoint = { _, resource -> server.url("/$resource").toString() })
            val original = wireMessage("user", "图片")
            val context = ChatImageContext("NovelAI", "blue sky local-test-placeholder", "lowres", "nai-diffusion-4-5-full", 832, 1216, seed = 42)
            val enriched = ChatProtocol.withImageContext(original, listOf(context), "local-test-placeholder")
            val image = ChatProtocol.withImages(enriched, listOf("data:image/jpeg;base64,dGVzdA=="))
            assertThat(original.string("content")).isEqualTo("图片")
            assertThat(enriched.string("content")).contains("blue sky")
            assertThat(enriched.string("content")).doesNotContain("local-test-placeholder")
            val serialized = Json.encodeToString(ChatAttachment.serializer(), ChatAttachment("old", "chat-attachments/old.jpg", "image/jpeg", 64, 64,
                context.redacted("local-test-placeholder").bounded()))
            assertThat(Json.decodeFromString(ChatAttachment.serializer(), serialized).promptContext!!.seed).isEqualTo(42L)
            assertThat(Json.decodeFromString(ChatAttachment.serializer(), """{"id":"old","relativePath":"chat-attachments/old.jpg","mimeType":"image/jpeg","width":64,"height":64}""").promptContext).isNull()
            assertThat(ChatImageContext("NovelAI", "字".repeat(7000)).bounded().truncated).isTrue()
            // OpenAI's legacy NovelAI storage fields must never masquerade as image parameters.
            val legacy = GenerationParams.defaultsFor(ModelCatalog.profileOf(ImageModel.V4_5_CURATED)).copy(prompt = "legacy placeholder", negativePrompt = "legacy negative")
            val generation = Generation("image", 0, 0, GenerationStatus.SUCCEEDED, "sample", "", legacy, 1,
                openAi = OpenAiImageSnapshot(OpenAiImageParams(prompt = "a quiet lake", quality = "auto"), responseQuality = "high"))
            val openAi = ChatImageContext.fromGeneration(generation, 99L, 1536, 1024)
            assertThat(openAi.prompt).isEqualTo("a quiet lake")
            assertThat(openAi.quality).isEqualTo("high")
            assertThat(openAi.width).isEqualTo(1536)
            assertThat(openAi.negativePrompt).isEmpty()
            assertThat(openAi.seed).isNull()
            assertThat(ChatProtocol.withImageContext(original, listOf(null))).isEqualTo(original)
            assertThat(ChatProtocol.withImageContext(original, listOf(null, context)).string("content")).contains("\"image_index\":2")
            val failure = runCatching { client.complete(LlmConfig(model = "local-test"), "local-test-placeholder", listOf(image), ImageChatTools.schema) }.exceptionOrNull() as ChatFailure
            assertThat(failure.userMessage).contains("识图")
            assertThat(failure.userMessage).doesNotContain("server error content")
            assertThat(server.requestCount).isEqualTo(1)
            val sent = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertThat(sent["messages"].toString()).doesNotContain("local-test-placeholder")
            assertThat(sent["messages"].toString()).contains("generation_parameters")
            assertThat(sent["messages"].toString()).contains("image_url")
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"choices\":[{\"delta\":{\"content\":\"first-\"}}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{\"content\":\"tail\",\"tool_calls\":[{\"index\":0,\"id\":\"one\",\"function\":{\"name\":\"generate_image\",\"arguments\":\"{\"}}]}}]}\n\n"))
            var last: JsonObject? = null
            val disconnected = runCatching { client.complete(LlmConfig(model = "local-test"), "local-test-placeholder",
                listOf(wireMessage("user", "ordinary local sample")), ImageChatTools.schema) { last = it } }.exceptionOrNull() as ChatFailure
            assertThat(disconnected.userMessage).contains("中断")
            assertThat(last!!.string("content")).isEqualTo("first-tail")
            assertThat(interruptedChatEntry("kept", last, "回复未完成")!!.calls).isEmpty()
            assertThat(server.requestCount).isEqualTo(2)
            server.takeRequest()
            checkSearchHttp(server)
        }
    }

    private suspend fun checkSearchHttp(server: MockWebServer) {
        val secret = "local-search-placeholder"
        val client = net.pocketnai.data.chat.WebSearchClient(endpoint = { config -> server.url(
            if (config.provider == ChatSearchProvider.DEEPSEEK) "/v1/tools/search" else "/search").toString() })
        val config = ChatSearchConfig(enabled = true)
        val query = ChatSearchTool.query(ToolCall("s", "web_search", """{"query":"character official costume"}"""))
        server.enqueue(MockResponse().setBody("""{"results":[{"title":"Official character","url":"https://example.org/character","content":"blue coat $secret"},{"title":"bad","url":"file:///private","content":"ignored"}]}"""))
        val sources = client.search(config, secret, query)
        assertThat(sources).hasSize(1)
        assertThat(ChatSearchTool.result(sources)).doesNotContain(secret)
        assertThat(sources.single().snippet).contains("blue coat")
        val tavily = server.takeRequest()
        assertThat(tavily.path).isEqualTo("/search")
        assertThat(tavily.getHeader("Authorization")).isEqualTo("Bearer $secret")
        val body = Json.parseToJsonElement(tavily.body.readUtf8()).jsonObject
        assertThat(body.string("query")).isEqualTo(query)
        assertThat(body["auto_parameters"]!!.jsonPrimitive.boolean).isFalse()
        assertThat(body["include_images"]!!.jsonPrimitive.boolean).isFalse()
        assertThat(body.toString()).doesNotContain(secret)
        val deepseek = config.copy(provider = ChatSearchProvider.DEEPSEEK, baseUrl = "https://api.deepseek.com/v1")
        assertThat(net.pocketnai.data.chat.WebSearchClient.searchEndpoint(deepseek)).isEqualTo("https://api.deepseek.com/v1/tools/search")
        assertThat(net.pocketnai.data.chat.WebSearchClient.searchEndpoint(deepseek.copy(baseUrl = "https://api.deepseek.com"))).isEqualTo("https://api.deepseek.com/v1/tools/search")
        server.enqueue(MockResponse().setBody("""{"results":[{"title":"Character source","url":"https://example.org/source","summary":"silver hair","snippet":"short","content":""}]}"""))
        assertThat(client.search(deepseek, secret, query).single().snippet).isEqualTo("silver hair")
        val ds = server.takeRequest()
        assertThat(ds.path).isEqualTo("/v1/tools/search")
        val dsBody = Json.parseToJsonElement(ds.body.readUtf8()).jsonObject
        assertThat(dsBody["count"]!!.jsonPrimitive.int).isEqualTo(5)
        assertThat(dsBody["need_content"]!!.jsonPrimitive.boolean).isFalse()
        assertThat(dsBody).doesNotContainKey("ori_question")
        assertThat(runCatching { ChatSearchTool.query(ToolCall("s", "web_search", """{"query":"x","url":"https://example.org"}""")) }.isFailure).isTrue()
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/unexpected")).setBody(secret))
        val before = server.requestCount
        val failure = runCatching { client.search(config, secret, query) }.exceptionOrNull() as ChatFailure
        assertThat(failure.userMessage).contains("重定向")
        assertThat(failure.userMessage).doesNotContain(secret)
        assertThat(server.requestCount).isEqualTo(before + 1)
        server.takeRequest()
        server.enqueue(MockResponse().setResponseCode(401).setBody(secret))
        val rejected = runCatching { client.search(config, secret, query) }.exceptionOrNull() as ChatFailure
        assertThat(rejected.userMessage).contains("无效")
        assertThat(rejected.userMessage).doesNotContain(secret)
        assertThat(server.requestCount).isEqualTo(before + 2)
        server.takeRequest()
    }
}
