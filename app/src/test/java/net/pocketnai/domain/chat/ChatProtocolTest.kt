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
        checkOptionalSfwMode()
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

    private fun checkOptionalSfwMode() {
        val policy = net.pocketnai.domain.safety.SfwPolicy
        val base = GenerationParams.defaultsFor(ModelCatalog.defaultProfile()).copy(prompt = "a landscape", negativePrompt = "nsfw, nude")
        assertThat(policy.blocks(true, base)).isFalse()
        assertThat(policy.blocks(true, base.copy(characters = listOf(CharacterPrompt(negativePrompt = "nsfw"))))).isFalse()
        val marked = base.copy(characters = listOf(CharacterPrompt(prompt = "nsfw")))
        assertThat(policy.blocks(true, marked)).isTrue()
        assertThat(policy.blocks(false, marked)).isFalse()
        listOf("ＮＳＦＷ", "1.4::NSFW::", "exposed_breasts", "rating:explicit", "裸体").forEach {
            assertThat(policy.containsSensitivePrompt(it)).isTrue()
        }
        listOf("cocktail, classic, assassin, sexy dress, breastplate", "explicit instructions for a logo", "no nudity, without nsfw", "不要裸体").forEach {
            assertThat(policy.containsSensitivePrompt(it)).isFalse()
        }
        assertThat(policy.containsSensitivePrompt("nsfw, no nudity")).isTrue()
        val file = java.io.File("/app/files/generations/sample/image.png")
        val display = net.pocketnai.ui.safety.SfwDisplayState(enabled = true)
        assertThat(display.masks(file)).isTrue()
        assertThat(display.copy(ready = true, knownPaths = setOf(file.absolutePath)).masks(file)).isFalse()
        val protected = display.copy(ready = true, paths = setOf(file.absolutePath), knownPaths = setOf(file.absolutePath))
        assertThat(protected.masks(file, knownSensitive = false)).isTrue()
        assertThat(protected.masks(file, revealed = true)).isFalse()
        assertThat(protected.copy(enabled = false).masks(file)).isFalse()
        val source = IntArray(64 * 64) { index -> if ((index / 64 + index % 64) % 2 == 0) -1 else 0xff000000.toInt() }
        val original = source.copyOf()
        val blurred = net.pocketnai.domain.safety.GaussianBlur.apply(source, 64, 64)
        val center = (16..47).flatMap { y -> (16..47).map { x -> blurred[y * 64 + x] and 255 } }
        assertThat(center.max() - center.min()).isLessThan(5)
        assertThat(source.toList()).isEqualTo(original.toList())
        val color = 0xff5483ab.toInt()
        assertThat(net.pocketnai.domain.safety.GaussianBlur.apply(IntArray(25) { color }, 5, 5).toList()).containsExactlyElementsIn(List(25) { color })
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
        val display = "柔和光线 e\u0301 👩‍🎨 和完整画面"
        var shown = 0
        while (shown < display.length) {
            val end = ChatTextLayout.nextVisibleEnd(display, shown)
            assertThat(end).isGreaterThan(shown)
            assertThat(display[end - 1].isHighSurrogate()).isFalse()
            assertThat(display.substring(0, end).endsWith("‍")).isFalse()
            shown = end
        }
        assertThat(display.substring(0, shown)).isEqualTo(display)
        assertThat(ChatTextLayout.nextVisibleEnd("a\uD83D", 1)).isEqualTo(1)
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
