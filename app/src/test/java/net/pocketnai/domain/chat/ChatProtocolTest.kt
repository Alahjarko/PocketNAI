package net.pocketnai.domain.chat

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.*
import net.pocketnai.domain.model.*
import org.junit.Test

class ChatProtocolTest {
    @Test fun `误回显的认证值在正文思考和嵌套参数中全部移除`() {
        val placeholder = "test-auth-placeholder"
        val wire = buildJsonObject { put("content", placeholder); put("reasoning_content", "prefix $placeholder")
            put("tool_calls", buildJsonArray { add(buildJsonObject { put("arguments", "{\"prompt\":\"$placeholder\"}"); put("n", 42) }) }) }
        val cleaned = wire.withoutSecret(placeholder)
        assertThat(cleaned.toString()).doesNotContain(placeholder)
        assertThat((cleaned["tool_calls"] as JsonArray).first().jsonObject["n"]!!.jsonPrimitive.int).isEqualTo(42)
        assertThat(wire.toString()).contains(placeholder)
    }
    @Test fun `DeepSeek与Moonshot思考默认启用且图片工具不预设内容立场`() {
        listOf(LlmConfig(model = "deepseek-flash"), LlmConfig(model = "kimi-k2.6")).forEach {
            val body = ChatProtocol.request(it, listOf(wireMessage("user", "hello")), ImageChatTools.schema)
            assertThat((body["thinking"] as JsonObject).string("type")).isEqualTo("enabled")
            assertThat(body.containsKey("temperature")).isFalse()
            assertThat(body.string("tool_choice")).isEqualTo("auto")
        }
        // 2026-10-10 起图片工具不拦截题材（NovelAI 本身支持 NSFW，由服务端与账号策略决定），
        // 唯一底线是涉及未成年人的性内容。
        val base = GenerationParams.defaultsFor(ModelCatalog.profileOf(ImageModel.V4_5_CURATED))
        fun call(prompt: String, negative: String = "lowres") = ToolCall("c1", "generate_image",
            buildJsonObject { put("prompt", prompt); put("negative_prompt", negative) }.toString())
        assertThat(ImageChatTools.parse(call("1girl, penis, ejaculation"), base).prompt)
            .isEqualTo("1girl, penis, ejaculation")
        runCatching { ImageChatTools.parse(call("child sexual content"), base) }.exceptionOrNull().also {
            assertThat(it).isInstanceOf(ChatFailure::class.java)
            assertThat(it).hasMessageThat().contains("未成年")
        }
        runCatching { ImageChatTools.parse(call("1girl", "x".repeat(16001)), base) }.exceptionOrNull().also {
            assertThat(it).isInstanceOf(ChatFailure::class.java)
            assertThat(it).hasMessageThat().contains("逆向提示词过长")
        }
    }
    @Test fun `回传assistant完整思考与工具id而不丢签名字段`() {
        val reply = Json.parseToJsonElement("""{"role":"assistant","content":null,"reasoning_content":"provider reasoning","reasoning_details":[{"signature":"opaque"}],"tool_calls":[{"id":"call1","type":"function","function":{"name":"generate_image","arguments":"{}"}}],"extra":"ignore"}""") as JsonObject
        val wire = ChatProtocol.assistant(reply)
        assertThat(wire.string("reasoning_content")).isEqualTo("provider reasoning")
        assertThat(wire["reasoning_details"]).isEqualTo(reply["reasoning_details"])
        assertThat(wire["tool_calls"]).isEqualTo(reply["tool_calls"])
        assertThat(wire.containsKey("extra")).isFalse()
    }
    @Test fun `未结束与截断流不会被当成可执行工具`() {
        val stream = ChatStreamAccumulator()
        assertThat(runCatching { stream.complete() }.isFailure).isTrue()
        stream.append(Json.parseToJsonElement("""{"choices":[{"delta":{},"finish_reason":"length"}]}""") as JsonObject)
        assertThat(runCatching { stream.complete() }.isFailure).isTrue()
    }
    @Test fun `中断工具只补齐结果不会重新执行且思考保留`() {
        val wire = Json.parseToJsonElement("""{"role":"assistant","content":null,"reasoning_content":"original","tool_calls":[{"id":"one","function":{"name":"generate_image","arguments":"{}"}}]}""") as JsonObject
        val old = ChatConversation("chat", updatedAt = 1, entries = listOf(ChatEntry("a", wire)))
        val fixed = old.recoverInterruptedTools()
        assertThat(fixed.entries).hasSize(2)
        assertThat(fixed.entries.first().wire).isEqualTo(wire)
        assertThat(fixed.entries.last().wire.string("tool_call_id")).isEqualTo("one")
        assertThat(fixed.recoverInterruptedTools()).isEqualTo(fixed)
        val stopped = interruptedChatEntry("stopped", wire, "回复已停止")!!
        assertThat(stopped.reasoning).isEqualTo("original")
        assertThat(stopped.calls).isEmpty()
        assertThat(stopped.notice).isEqualTo("回复已停止")
        assertThat(interruptedChatEntry("empty", wireMessage("assistant", ""), "回复已停止")).isNull()
    }
}
