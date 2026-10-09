package net.pocketnai.data.network

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.pocketnai.domain.model.CharacterPosition
import net.pocketnai.domain.model.CharacterPrompt
import net.pocketnai.domain.model.GenerationParams
import net.pocketnai.domain.model.ImageModel
import net.pocketnai.domain.model.ModelCatalog
import net.pocketnai.domain.model.QualityTagsOption
import org.junit.Test

/**
 * 请求体构造的结构化断言。
 *
 * 规划书 3.3 要求 V4.5 / V5 必须带正确的 `v4_prompt` 结构，这是最容易出错、
 * 又只有在真实请求时才会暴露的部分，因此在这里逐字段钉死。
 */
class NovelAiRequestBuilderTest {

    private val v5 = ModelCatalog.profileOf(ImageModel.V5_FULL)

    @Test
    fun `V5漫画文字提取保留角色坐标与独立负向`() {
        val char1 = CharacterPrompt(
            prompt = "1girl, blonde hair, says 「左边」",
            negativePrompt = "bad eyes",
            position = CharacterPosition.FAR_LEFT,
        )
        val char2 = CharacterPrompt(
            prompt = "1boy, black hair, says ‘右边’",
            negativePrompt = "extra arms",
            position = CharacterPosition.FAR_RIGHT,
        )
        val original = "The bottom of the screen reads \"这是一段测试文本\", speech bubble in the comic says “这是一个对话框”"
        val params = GenerationParams.defaultsFor(v5).copy(
            prompt = original,
            negativePrompt = "lowres",
            // Opposite input order verifies that text follows coordinates, not array order.
            characters = listOf(char2, char1),
            qualityTags = QualityTagsOption.STANDARD,
            useCharacterCoordinates = true,
        )

        val payload = NovelAiRequestBuilder.build(v5, params)
        val parameters = payload["parameters"]!!.jsonObject
        val v4Prompt = parameters["v4_prompt"]!!.jsonObject
        val v4Negative = parameters["v4_negative_prompt"]!!.jsonObject
        val expected = original + ", very aesthetic, masterpiece, no text, teXt: 这是一个对话框\n\n这是一段测试文本\n\n左边\n\n右边"
        assertThat(payload["input"]!!.jsonPrimitive.content).isEqualTo(expected)
        assertThat(v4Prompt["caption"]!!.jsonObject["base_caption"]!!.jsonPrimitive.content).isEqualTo(expected)
        assertThat(params.prompt).isEqualTo(original)

        assertThat(v4Prompt["use_coords"]!!.jsonPrimitive.boolean).isTrue()
        assertThat(v4Prompt["use_order"]!!.jsonPrimitive.boolean).isTrue()

        val charCaptions = v4Prompt["caption"]!!.jsonObject["char_captions"]!!.jsonArray
        assertThat(charCaptions).hasSize(2)

        val firstChar = charCaptions[0].jsonObject
        assertThat(firstChar["char_caption"]!!.jsonPrimitive.content).isEqualTo(char2.prompt)
        val firstCenter = firstChar["centers"]!!.jsonArray[0].jsonObject
        assertThat(firstCenter["x"]!!.jsonPrimitive.double).isEqualTo(0.85)
        assertThat(firstCenter["y"]!!.jsonPrimitive.double).isEqualTo(0.5)

        val secondChar = charCaptions[1].jsonObject
        assertThat(secondChar["char_caption"]!!.jsonPrimitive.content).isEqualTo(char1.prompt)
        val secondCenter = secondChar["centers"]!!.jsonArray[0].jsonObject
        assertThat(secondCenter["x"]!!.jsonPrimitive.double).isEqualTo(0.15)
        assertThat(secondCenter["y"]!!.jsonPrimitive.double).isEqualTo(0.5)

        // 负向提示词
        val negCharCaptions = v4Negative["caption"]!!.jsonObject["char_captions"]!!.jsonArray
        assertThat(negCharCaptions).hasSize(2)
        assertThat(negCharCaptions[0].jsonObject["char_caption"]!!.jsonPrimitive.content).isEqualTo("extra arms")
        assertThat(negCharCaptions[1].jsonObject["char_caption"]!!.jsonPrimitive.content).isEqualTo("bad eyes")
        assertThat(v4Negative["legacy_uc"]!!.jsonPrimitive.boolean).isFalse()
        val negative = "nsfw, lowres, artistic error, film grain, scan artifacts, worst quality, bad quality, jpeg artifacts, very displeasing, chromatic aberration, dithering, halftone, screentone, multiple views, logo, too many watermarks, negative space, blank page, lowres"
        assertThat(parameters["negative_prompt"]!!.jsonPrimitive.content).isEqualTo(negative)
        assertThat(v4Negative["caption"]!!.jsonObject["base_caption"]!!.jsonPrimitive.content).isEqualTo(negative)
        assertThat(parameters.containsKey("ucPreset")).isFalse()
        assertThat(parameters["tag_hint_uc_preset"]!!.jsonPrimitive.content).isEqualTo("2")
        val automatic = NovelAiRequestBuilder.build(v5, params.copy(useCharacterCoordinates = false,
            characters = params.characters + CharacterPrompt(negativePrompt = "unused")))["parameters"]!!.jsonObject["v4_prompt"]!!.jsonObject
        assertThat(automatic["use_coords"]!!.jsonPrimitive.boolean).isFalse()
        assertThat(automatic["caption"]!!.jsonObject["char_captions"]!!.jsonArray).hasSize(2)
    }
}
