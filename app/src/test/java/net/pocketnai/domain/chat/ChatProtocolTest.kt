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
        val panels = ToolCall("panels", "generate_images", """{"images":[{"prompt":"quiet forest","negative_prompt":"lowres","seed":42},{"prompt":"sunset coast","negative_prompt":"lowres","seed":43}]}""")
        val plan = ImageChatTools.parsePlan(panels, base)
        assertThat(plan.map { it.prompt }).containsExactly("quiet forest", "sunset coast").inOrder()
        assertThat(plan.map { it.baseSeed }).containsExactly(42L, 43L).inOrder()
        assertThat(plan.all { it.sampleCount == 1 }).isTrue()
        assertThat(runCatching { ImageChatTools.parsePlan(panels.copy(arguments = """{"images":[{"prompt":"valid","negative_prompt":""},{"prompt":"bad","negative_prompt":"","width":999}]}"""), base) }.isFailure).isTrue()
        val tooMany = buildJsonObject { put("images", JsonArray(List(13) { Json.parseToJsonElement(call("forest").arguments) })) }
        assertThat(runCatching { ImageChatTools.parsePlan(panels.copy(arguments = tooMany.toString()), base) }.isFailure).isTrue()
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
        checkConfirmedBatch()
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

    private fun checkConfirmedBatch() = kotlinx.coroutines.test.runTest {
        val base = GenerationParams.defaultsFor(ModelCatalog.profileOf(ImageModel.V4_5_CURATED))
        val calls = mutableListOf<String>()
        var account = "fake-account"
        var failAt = 2
        var active = true
        var priceChange = 0L
        var beforeRequest: () -> Unit = {}
        val fake = object : ChatImageGenerator {
            override fun currentParams() = base
            override fun authorizationKey() = account
            override suspend fun quote(params: GenerationParams) = ChatImageQuote("预计费用", (if (calls.isEmpty()) 1L else 2L) + priceChange)
            override suspend fun generate(params: GenerationParams, account: String?, beforeSend: suspend () -> Unit, onImage: (ChatImage) -> Unit): List<ChatImage> {
                beforeRequest(); beforeSend()
                calls += params.prompt
                if (calls.size == failAt) throw ChatFailure("TIMEOUT_UNCERTAIN")
                return listOf(ChatImage("image-${calls.size}", "local.png", 64, 64)).onEach(onImage)
            }
        }
        val confirmations = mutableListOf<ChatImagePlan>()
        val saved = mutableListOf<List<ChatImage>>()
        val batch = ChatImageBatch(fake)
        val result = batch.execute((1..3).map { base.copy(prompt = "panel-$it") }, autoSingle = true,
            confirm = { confirmations += it; assertThat(calls.size).isEqualTo(if (it.changed) 1 else 0); true }, active = { active },
            onProgress = { _, _, _ -> }, onImage = {}, checkpoint = { saved += it })
        assertThat(confirmations).hasSize(2) // Auto-single never authorizes a multi-image plan.
        assertThat(confirmations[0].summary).contains("3 Anlas")
        assertThat(confirmations[1].items).hasSize(2)
        assertThat(confirmations[1].summary).contains("4 Anlas")
        assertThat(calls).containsExactly("panel-1", "panel-2").inOrder()
        assertThat(result.images).hasSize(1)
        assertThat(result.status).isEqualTo("failed")
        assertThat(result.message).contains("TIMEOUT_UNCERTAIN")
        assertThat(saved.first()).hasSize(1)
        assertThat(batch.remaining).isEqualTo(0)
        assertThat(runCatching { batch.execute(listOf(base), true, { true }, { true }, { _, _, _ -> }, {}, {}) }.isFailure).isTrue()
        assertThat(calls).hasSize(2)

        calls.clear(); failAt = -1
        val cancelled = ChatImageBatch(fake).execute(listOf(base, base), false, { false }, { true }, { _, _, _ -> }, {}, {})
        assertThat(cancelled.status).isEqualTo("cancelled"); assertThat(calls).isEmpty()
        val changed = ChatImageBatch(fake).execute(listOf(base, base), false, { account = "different"; true }, { true }, { _, _, _ -> }, {}, {})
        assertThat(changed.status).isEqualTo("cancelled"); assertThat(calls).isEmpty()
        val stopped = ChatImageBatch(fake).execute(listOf(base, base), false, { true }, { active }, { _, _, _ -> }, { active = false }, {})
        assertThat(stopped.status).isEqualTo("cancelled"); assertThat(stopped.images).hasSize(1); assertThat(calls).hasSize(1)
        calls.clear(); active = true
        val successful = ChatImageBatch(fake)
        val all = successful.execute(List(12) { base }, false, { true }, { true }, { _, _, _ -> }, {}, {})
        assertThat(all.images).hasSize(12); assertThat(successful.remaining).isEqualTo(0)
        calls.clear(); beforeRequest = { priceChange = 5 }
        val queued = ChatImageBatch(fake).execute(listOf(base), true, { true }, { true }, { _, _, _ -> }, {}, {})
        assertThat(queued.message).contains("等待发送期间费用变化")
        assertThat(calls).isEmpty() // A changed quote during gate waiting never reaches the API.
    }
}
