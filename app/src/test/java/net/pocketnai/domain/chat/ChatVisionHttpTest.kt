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
            val image = ChatProtocol.withImages(wireMessage("user", "图片"), listOf("data:image/jpeg;base64,dGVzdA=="))
            val failure = runCatching { client.complete(LlmConfig(model = "local-test"), "local-test-placeholder", listOf(image), ImageChatTools.schema) }.exceptionOrNull() as ChatFailure
            assertThat(failure.userMessage).contains("识图")
            assertThat(failure.userMessage).doesNotContain("server error content")
            assertThat(server.requestCount).isEqualTo(1)
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
