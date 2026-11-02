package net.pocketnai.domain.chat

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import net.pocketnai.data.chat.OpenAiCompatibleChatClient
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
        }
    }
}
