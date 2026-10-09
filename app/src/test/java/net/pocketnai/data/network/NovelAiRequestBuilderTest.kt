package net.pocketnai.data.network

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
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
import net.pocketnai.domain.model.ModelProfile
import net.pocketnai.domain.model.QualityTagsOption
import org.junit.Test

/**
 * 请求体构造的结构化断言。
 *
 * 规划书 3.3 要求 V4.5 / V5 必须带正确的 `v4_prompt` 结构，这是最容易出错、
 * 又只有在真实请求时才会暴露的部分，因此在这里逐字段钉死。
 */
class NovelAiRequestBuilderTest {

    private val v45 = ModelCatalog.profileOf(ImageModel.V4_5_CURATED)
    private val v5 = ModelCatalog.profileOf(ImageModel.V5_CURATED)

    private fun build(
        profile: ModelProfile,
        prompt: String = "1girl, silver hair",
        negative: String = "lowres, bad anatomy",
    ): JsonObject = NovelAiRequestBuilder.build(
        profile,
        GenerationParams.defaultsFor(profile).copy(
            prompt = prompt,
            negativePrompt = negative,
            // 结构断言与质量标签解耦：追加行为由专门的用例覆盖，
            // 否则每改一次质量标签文本都会连带打破这些字段级断言。
            qualityTags = QualityTagsOption.NONE,
        ),
    )

    @Test
    fun `带独立角色时正确构造 char_captions 与 use_coords 且负向包含对应独立负向`() {
        val char1 = CharacterPrompt(
            prompt = "1girl, blonde hair",
            negativePrompt = "bad eyes",
            position = CharacterPosition.FAR_LEFT,
        )
        val char2 = CharacterPrompt(
            prompt = "1boy, black hair",
            negativePrompt = "extra arms",
            position = CharacterPosition.FAR_RIGHT,
        )
        val params = GenerationParams.defaultsFor(v45).copy(
            prompt = "masterpiece",
            negativePrompt = "lowres",
            characters = listOf(char1, char2),
            qualityTags = QualityTagsOption.NONE,
        )

        val parameters = NovelAiRequestBuilder.build(v45, params)["parameters"]!!.jsonObject
        val v4Prompt = parameters["v4_prompt"]!!.jsonObject
        val v4Negative = parameters["v4_negative_prompt"]!!.jsonObject

        assertThat(v4Prompt["use_coords"]!!.jsonPrimitive.boolean).isTrue()
        assertThat(v4Prompt["use_order"]!!.jsonPrimitive.boolean).isTrue()

        val charCaptions = v4Prompt["caption"]!!.jsonObject["char_captions"]!!.jsonArray
        assertThat(charCaptions).hasSize(2)

        val firstChar = charCaptions[0].jsonObject
        assertThat(firstChar["char_caption"]!!.jsonPrimitive.content).isEqualTo("1girl, blonde hair")
        val firstCenter = firstChar["centers"]!!.jsonArray[0].jsonObject
        assertThat(firstCenter["x"]!!.jsonPrimitive.double).isEqualTo(0.15)
        assertThat(firstCenter["y"]!!.jsonPrimitive.double).isEqualTo(0.5)

        val secondChar = charCaptions[1].jsonObject
        assertThat(secondChar["char_caption"]!!.jsonPrimitive.content).isEqualTo("1boy, black hair")
        val secondCenter = secondChar["centers"]!!.jsonArray[0].jsonObject
        assertThat(secondCenter["x"]!!.jsonPrimitive.double).isEqualTo(0.85)
        assertThat(secondCenter["y"]!!.jsonPrimitive.double).isEqualTo(0.5)

        // 负向提示词
        val negCharCaptions = v4Negative["caption"]!!.jsonObject["char_captions"]!!.jsonArray
        assertThat(negCharCaptions).hasSize(2)
        assertThat(negCharCaptions[0].jsonObject["char_caption"]!!.jsonPrimitive.content).isEqualTo("bad eyes")
        assertThat(negCharCaptions[1].jsonObject["char_caption"]!!.jsonPrimitive.content).isEqualTo("extra arms")
        assertThat(v4Negative["legacy_uc"]!!.jsonPrimitive.boolean).isFalse()
    }
}
