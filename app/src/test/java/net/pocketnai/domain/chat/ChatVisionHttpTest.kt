package net.pocketnai.domain.chat

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import net.pocketnai.data.chat.OpenAiCompatibleChatClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test

class ChatVisionHttpTest {
    @Test fun `图片数组实际进入请求而且不被转成普通字符串`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"ok","reasoning_content":"reasoning"}}]}"""))
            val client = OpenAiCompatibleChatClient(endpoint = { _, resource -> server.url("/$resource").toString() })
            val image = ChatProtocol.withImages(wireMessage("user", "描述图片"), listOf("data:image/jpeg;base64,dGVzdA=="))
            val reply = client.complete(LlmConfig(model = "local-test"), "local-test-placeholder", listOf(image), ImageChatTools.schema)
            val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertThat(body["messages"]!!.jsonArray.single().jsonObject["content"]).isEqualTo(image["content"])
            assertThat(reply.string("reasoning_content")).isEqualTo("reasoning")
        }
    }
    @Test fun `供应商拒绝图片时给出识图指引并且只请求一次`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400).setBody("do not expose server error content"))
            val client = OpenAiCompatibleChatClient(endpoint = { _, resource -> server.url("/$resource").toString() })
            val image = ChatProtocol.withImages(wireMessage("user", "图片"), listOf("data:image/jpeg;base64,dGVzdA=="))
            val failure = runCatching { client.complete(LlmConfig(model = "local-test"), "local-test-placeholder", listOf(image), ImageChatTools.schema) }.exceptionOrNull() as ChatFailure
            assertThat(failure.userMessage).contains("识图")
            assertThat(failure.userMessage).doesNotContain("server error content")
            assertThat(server.requestCount).isEqualTo(1)
        }
    }
}
