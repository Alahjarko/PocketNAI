package net.pocketnai.data.network

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

class GenerationStreamParserTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun frame(event: String?, data: String) = SseFrameReader.Frame(event, data)

    @Test
    fun `intermediate 帧解析出图片与步号`() {
        // 真实结构（真机探针，技术决策记录 §24）：服务端发 step_ix，不发总步数。
        val event = GenerationStreamParser.parse(
            frame(
                "intermediate",
                """{"event_type":"intermediate","gen_id":"g","image":"AAAA","samp_ix":0,"sigma":1.0,"step_ix":5}""",
            ),
            json,
        )
        assertThat(event).isInstanceOf(GenerationStreamParser.Event.Intermediate::class.java)
        val intermediate = event as GenerationStreamParser.Event.Intermediate
        assertThat(intermediate.imageBase64).isEqualTo("AAAA")
        assertThat(intermediate.step).isEqualTo(5)
    }

    @Test
    fun `final 帧解析出图片`() {
        val event = GenerationStreamParser.parse(frame("final", """{"image":"BBBB"}"""), json)
        assertThat(event).isInstanceOf(GenerationStreamParser.Event.Final::class.java)
        assertThat((event as GenerationStreamParser.Event.Final).imageBase64).isEqualTo("BBBB")
    }

    @Test
    fun `error 帧解析出错误消息`() {
        val event = GenerationStreamParser.parse(frame("error", """{"message":"boom"}"""), json)
        assertThat(event).isInstanceOf(GenerationStreamParser.Event.StreamError::class.java)
        assertThat((event as GenerationStreamParser.Event.StreamError).message).isEqualTo("boom")
    }

    @Test
    fun `事件类型也可以来自数据里的 type 字段`() {
        val event = GenerationStreamParser.parse(frame(null, """{"type":"FINAL","image":"CCCC"}"""), json)
        assertThat(event).isInstanceOf(GenerationStreamParser.Event.Final::class.java)
    }

    @Test
    fun `缺少步号时不编造进度`() {
        val event = GenerationStreamParser.parse(
            frame("intermediate", """{"image":"AAAA"}"""),
            json,
        ) as GenerationStreamParser.Event.Intermediate
        assertThat(event.step).isNull()
    }

    @Test
    fun `未识别的事件类型归为 Unknown 并带结构摘要`() {
        val event = GenerationStreamParser.parse(frame("something-new", """{"x":1}"""), json)
        assertThat(event).isInstanceOf(GenerationStreamParser.Event.Unknown::class.java)
        // 摘要里必须带结构信息，但绝不能带字段值。
        val label = (event as GenerationStreamParser.Event.Unknown).label
        assertThat(label).contains("something-new")
        assertThat(label).contains("keys=")
        assertThat(label).doesNotContain("\"x\"")
    }

    @Test
    fun `intermediate 缺少图片字段时归为 Unknown`() {
        val event = GenerationStreamParser.parse(frame("intermediate", """{"step":1}"""), json)
        assertThat(event).isInstanceOf(GenerationStreamParser.Event.Unknown::class.java)
    }

    @Test
    fun `非 JSON 数据不崩溃并归为 Unknown`() {
        val event = GenerationStreamParser.parse(frame("intermediate", "not-json-at-all"), json)
        assertThat(event).isInstanceOf(GenerationStreamParser.Event.Unknown::class.java)
    }

    @Test
    fun `诊断摘要对 base64 载荷只报告长度与形态`() {
        val base64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAAB".repeat(8)
        val label = GenerationStreamParser.label(frame("intermediate", base64), json)
        assertThat(label).contains("base64")
        assertThat(label).contains("len=")
        assertThat(label).doesNotContain("iVBOR")
    }

    @Test
    fun `诊断摘要在数据是 JSON 时列出顶层键名`() {
        val label = GenerationStreamParser.label(frame("final", """{"image":"x","seed":42}"""), json)
        assertThat(label).contains("keys=")
        assertThat(label).contains("image")
        assertThat(label).contains("seed")
    }
}
