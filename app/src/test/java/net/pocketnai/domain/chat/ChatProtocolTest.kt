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
    @Test fun `流式空思考字段仍然保留以便工具回传`() {
        val stream = ChatStreamAccumulator()
        stream.append(Json.parseToJsonElement("""{"choices":[{"delta":{"content":"ok","reasoning_content":""},"finish_reason":"stop"}]}""") as JsonObject)
        assertThat(stream.complete().containsKey("reasoning_content")).isTrue()
        assertThat(stream.complete().string("reasoning_content")).isEmpty()
    }
    @Test fun `网关前缀原样保留且不重复追加v1`() {
        assertThat(ChatProtocol.endpoint("https://example.com/api/v1/", "models")).isEqualTo("https://example.com/api/v1/models")
        assertThat(ChatProtocol.endpoint("https://api.deepseek.com", "chat/completions")).isEqualTo("https://api.deepseek.com/chat/completions")
    }
    @Test fun `拒绝会泄露密钥或路径错误的BaseUrl`() {
        listOf("http://example.com/v1", "https://user:secret@example.com", "https://example.com/?key=x", "https://example.com/v1/chat/completions").forEach {
            assertThat(runCatching { ChatProtocol.endpoint(it, "models") }.isFailure).isTrue()
        }
    }
    @Test fun `DeepSeek与Moonshot思考默认启用且不传温度`() {
        listOf(LlmConfig(model = "deepseek-flash"), LlmConfig(model = "kimi-k2.6")).forEach {
            val body = ChatProtocol.request(it, listOf(wireMessage("user", "hello")), ImageChatTools.schema)
            assertThat((body["thinking"] as JsonObject).string("type")).isEqualTo("enabled")
            assertThat(body.containsKey("temperature")).isFalse()
            assertThat(body.string("tool_choice")).isEqualTo("auto")
        }
    }
    @Test fun `KimiCode模型通过原路径和reasoningEffort设置思考`() {
        val body = ChatProtocol.request(LlmConfig(baseUrl = "https://api.kimi.com/coding/v1", model = "kimi-for-coding"), emptyList(), ImageChatTools.schema)
        assertThat(body.string("reasoning_effort")).isEqualTo("high")
        assertThat(body.containsKey("thinking")).isFalse()
    }
    @Test fun `固定思考Kimi模型不发送不兼容开关`() {
        listOf("kimi-k2-thinking", "kimi-k3", "kimi-k2.7-code").forEach {
            assertThat(ChatProtocol.request(LlmConfig(model = it), emptyList(), ImageChatTools.schema).containsKey("thinking")).isFalse()
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
    @Test fun `流式分片重组工具参数和思考`() {
        val stream = ChatStreamAccumulator()
        stream.append(Json.parseToJsonElement("""{"choices":[{"delta":{"reasoning_content":"think","tool_calls":[{"index":0,"id":"call1","function":{"name":"generate_image","arguments":"{\"prompt\":"}}]}}]}""") as JsonObject)
        stream.append(Json.parseToJsonElement("""{"choices":[{"delta":{"reasoning_content":" more","tool_calls":[{"index":0,"function":{"arguments":"\"sfw\"}"}}]},"finish_reason":"tool_calls"}]}""") as JsonObject)
        val entry = ChatEntry("reply", stream.complete())
        assertThat(entry.reasoning).isEqualTo("think more")
        assertThat(entry.calls.single().arguments).isEqualTo("""{"prompt":"sfw"}""")
        assertThat(entry.calls.single().id).isEqualTo("call1")
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
    }
    @Test fun `图片工具参数保留独立角色坐标并使用单张请求`() {
        val base = GenerationParams.defaultsFor(ModelCatalog.defaultProfile())
        val call = ToolCall("one", "generate_image", """{"prompt":"sfw, adult woman, fully clothed","negative_prompt":"low quality","width":832,"height":1216,"seed":42,"characters":[{"prompt":"adult woman, silver hair","x":0.8,"y":0.2}]}""")
        val params = ImageChatTools.parse(call, base)
        assertThat(params.sampleCount).isEqualTo(1)
        assertThat(params.baseSeed).isEqualTo(42)
        assertThat(params.characters.single().centerY).isEqualTo(0.2)
        assertThat(params.qualityTags).isEqualTo(QualityTagsOption.NONE)
        assertThat(base.prompt).isEmpty()
    }
    @Test fun `模型错误尺寸错误或虚构功能不会静默归一化后执行`() {
        val base = GenerationParams.defaultsFor(ModelCatalog.defaultProfile())
        listOf("\"width\":833", "\"model\":\"unknown\"", "\"action\":\"upscale\"", "\"seed\":-1").forEach {
            val call = ToolCall("x", "generate_image", "{\"prompt\":\"sfw\",\"negative_prompt\":\"\",$it}")
            assertThat(runCatching { ImageChatTools.parse(call, base) }.isFailure).isTrue()
        }
    }
}
