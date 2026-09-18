package net.pocketnai.data.network

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.pocketnai.domain.model.CharacterPosition
import net.pocketnai.domain.model.CharacterPrompt
import net.pocketnai.domain.model.GenerationParams
import net.pocketnai.domain.model.ImageModel
import net.pocketnai.domain.model.ModelCatalog
import net.pocketnai.domain.model.ModelProfile
import net.pocketnai.domain.model.NoiseSchedule
import net.pocketnai.domain.model.QualityTagsOption
import net.pocketnai.domain.model.Sampler
import net.pocketnai.domain.model.SeedMode
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

    // ---- 质量标签：官方把文本追加到提示词末尾，而不是交给服务端处理 ----

    @Test
    fun `Standard 档位把质量标签追加到提交的提示词末尾`() {
        val params = GenerationParams.defaultsFor(v45).copy(
            prompt = "1girl, silver hair",
            qualityTags = QualityTagsOption.STANDARD,
        )

        val json = NovelAiRequestBuilder.build(v45, params)
        val expected = "1girl, silver hair, very aesthetic, masterpiece, no text"

        assertThat(json["input"]!!.jsonPrimitive.content).isEqualTo(expected)
        assertThat(json["parameters"]!!.jsonObject["v4_prompt"]!!.jsonObject["caption"]!!.jsonObject["base_caption"]!!.jsonPrimitive.content)
            .isEqualTo(expected)
    }

    @Test
    fun `Light 档位使用另一组质量标签文本`() {
        val params = GenerationParams.defaultsFor(v5).copy(
            prompt = "cat",
            qualityTags = QualityTagsOption.LIGHT,
        )

        val parameters = NovelAiRequestBuilder.build(v5, params)["parameters"]!!.jsonObject
        val caption = parameters["v4_prompt"]!!.jsonObject["caption"]!!.jsonObject

        assertThat(caption["base_caption"]!!.jsonPrimitive.content)
            .isEqualTo("cat, very aesthetic, amazing quality, no text")
    }

    @Test
    fun `None 档位提交原始提示词`() {
        val params = GenerationParams.defaultsFor(v45).copy(
            prompt = "1girl",
            qualityTags = QualityTagsOption.NONE,
        )

        val json = NovelAiRequestBuilder.build(v45, params)

        assertThat(json["input"]!!.jsonPrimitive.content).isEqualTo("1girl")
    }

    @Test
    fun `质量标签由应用自己追加，因此关闭服务端的 qualityToggle`() {
        // 如果两边都生效，同一段文本会被叠加两次。
        listOf(QualityTagsOption.STANDARD, QualityTagsOption.LIGHT, QualityTagsOption.NONE)
            .forEach { option ->
                val params = GenerationParams.defaultsFor(v45).copy(prompt = "cat", qualityTags = option)
                val parameters = NovelAiRequestBuilder.build(v45, params)["parameters"]!!.jsonObject
                assertThat(parameters["qualityToggle"]!!.jsonPrimitive.boolean).isFalse()
            }
    }

    @Test
    fun `负向提示词不会带上质量标签`() {
        val params = GenerationParams.defaultsFor(v45).copy(
            prompt = "cat",
            negativePrompt = "lowres",
            qualityTags = QualityTagsOption.STANDARD,
        )

        val parameters = NovelAiRequestBuilder.build(v45, params)["parameters"]!!.jsonObject
        val negativeCaption = parameters["v4_negative_prompt"]!!.jsonObject["caption"]!!.jsonObject

        assertThat(parameters["negative_prompt"]!!.jsonPrimitive.content).isEqualTo("lowres")
        assertThat(negativeCaption["base_caption"]!!.jsonPrimitive.content).isEqualTo("lowres")
    }

    @Test
    fun `顶层字段使用模型 id 与 generate 动作`() {
        val json = build(v45)
        assertThat(json["model"]!!.jsonPrimitive.content).isEqualTo("nai-diffusion-4-5-curated")
        assertThat(json["action"]!!.jsonPrimitive.content).isEqualTo("generate")
        assertThat(json["input"]!!.jsonPrimitive.content).isEqualTo("1girl, silver hair")
    }

    @Test
    fun `v4_5 与 v5 都带上正确的结构化正向提示词`() {
        listOf(v45, v5).forEach { profile ->
            val parameters = build(profile)["parameters"]!!.jsonObject
            val caption = parameters["v4_prompt"]!!.jsonObject["caption"]!!.jsonObject

            assertThat(caption["base_caption"]!!.jsonPrimitive.content)
                .isEqualTo("1girl, silver hair")
            // 首版不做多角色，char_captions 必须是空数组而不是缺失字段。
            assertThat(caption["char_captions"]!!.jsonArray).isEmpty()
        }
    }

    @Test
    fun `结构化负向提示词与 legacy_uc 正确`() {
        listOf(v45, v5).forEach { profile ->
            val parameters = build(profile)["parameters"]!!.jsonObject
            val negativeBlock = parameters["v4_negative_prompt"]!!.jsonObject
            val caption = negativeBlock["caption"]!!.jsonObject

            assertThat(caption["base_caption"]!!.jsonPrimitive.content)
                .isEqualTo("lowres, bad anatomy")
            assertThat(caption["char_captions"]!!.jsonArray).isEmpty()
            assertThat(negativeBlock["legacy_uc"]!!.jsonPrimitive.boolean).isFalse()
        }
    }

    @Test
    fun `不启用自定义角色坐标`() {
        val v4Prompt = build(v5)["parameters"]!!.jsonObject["v4_prompt"]!!.jsonObject
        assertThat(v4Prompt["use_coords"]!!.jsonPrimitive.boolean).isFalse()
        assertThat(v4Prompt["use_order"]!!.jsonPrimitive.boolean).isTrue()
    }

    @Test
    fun `参数值使用 API 约定的原始取值`() {
        val profile = v45
        val params = GenerationParams.defaultsFor(profile).copy(
            prompt = "cat",
            negativePrompt = "",
            steps = 33,
            guidance = 6.5,
            cfgRescale = 0.25,
            sampler = Sampler.DPM_PLUS_PLUS_2M,
            noiseSchedule = NoiseSchedule.EXPONENTIAL,
            sampleCount = 3,
            seedMode = SeedMode.FIXED,
            baseSeed = 123456789L,
            qualityTags = QualityTagsOption.NONE,
            undesiredContentPresetIndex = 2,
        )

        val parameters = NovelAiRequestBuilder.build(profile, params)["parameters"]!!.jsonObject

        assertThat(parameters["steps"]!!.jsonPrimitive.int).isEqualTo(33)
        assertThat(parameters["scale"]!!.jsonPrimitive.content).isEqualTo("6.5")
        assertThat(parameters["cfg_rescale"]!!.jsonPrimitive.content).isEqualTo("0.25")
        assertThat(parameters["sampler"]!!.jsonPrimitive.content).isEqualTo("k_dpmpp_2m")
        assertThat(parameters["noise_schedule"]!!.jsonPrimitive.content).isEqualTo("exponential")
        assertThat(parameters["n_samples"]!!.jsonPrimitive.int).isEqualTo(3)
        assertThat(parameters["seed"]!!.jsonPrimitive.content).isEqualTo("123456789")
        assertThat(parameters["qualityToggle"]!!.jsonPrimitive.boolean).isFalse()
        assertThat(parameters["ucPreset"]!!.jsonPrimitive.int).isEqualTo(2)
        assertThat(parameters["params_version"]!!.jsonPrimitive.int).isEqualTo(3)
    }

    @Test
    fun `不支持的采样器组合会被归一化后再提交`() {
        // DDIM 只支持 Native；这里故意传入 Karras，构造器必须自行修正，
        // 保证不会把已知无效组合发到服务端。
        val invalid = GenerationParams.defaultsFor(v45).copy(
            prompt = "cat",
            sampler = Sampler.DDIM,
            noiseSchedule = NoiseSchedule.KARRAS,
        )
        val parameters = NovelAiRequestBuilder.build(v45, invalid)["parameters"]!!.jsonObject

        assertThat(parameters["sampler"]!!.jsonPrimitive.content).isEqualTo("ddim")
        assertThat(parameters["noise_schedule"]!!.jsonPrimitive.content).isEqualTo("native")
    }

    @Test
    fun `尺寸按档案原样写入宽高`() {
        val params = GenerationParams.defaultsFor(v45).copy(
            prompt = "cat",
            size = net.pocketnai.domain.model.ImageSizePreset(width = 1216, height = 832),
        )
        val parameters = NovelAiRequestBuilder.build(v45, params)["parameters"]!!.jsonObject

        assertThat(parameters["width"]!!.jsonPrimitive.int).isEqualTo(1216)
        assertThat(parameters["height"]!!.jsonPrimitive.int).isEqualTo(832)
    }

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

    @Test
    fun `空白角色提示词会被过滤不进入 char_captions`() {
        val blankChar = CharacterPrompt(prompt = "   ", negativePrompt = "")
        val activeChar = CharacterPrompt(prompt = "1girl", position = CharacterPosition.CENTER)
        val params = GenerationParams.defaultsFor(v45).copy(
            prompt = "masterpiece",
            characters = listOf(blankChar, activeChar),
            qualityTags = QualityTagsOption.NONE,
        )

        val parameters = NovelAiRequestBuilder.build(v45, params)["parameters"]!!.jsonObject
        val v4Prompt = parameters["v4_prompt"]!!.jsonObject
        val charCaptions = v4Prompt["caption"]!!.jsonObject["char_captions"]!!.jsonArray

        assertThat(charCaptions).hasSize(1)
        assertThat(charCaptions[0].jsonObject["char_caption"]!!.jsonPrimitive.content).isEqualTo("1girl")
    }

    @Test
    fun `无独立角色时 use_coords 为 false 且 char_captions 为空`() {
        val params = GenerationParams.defaultsFor(v45).copy(
            prompt = "cat",
            characters = emptyList(),
            qualityTags = QualityTagsOption.NONE,
        )

        val parameters = NovelAiRequestBuilder.build(v45, params)["parameters"]!!.jsonObject
        val v4Prompt = parameters["v4_prompt"]!!.jsonObject

        assertThat(v4Prompt["use_coords"]!!.jsonPrimitive.boolean).isFalse()
        val charCaptions = v4Prompt["caption"]!!.jsonObject["char_captions"]!!.jsonArray
        assertThat(charCaptions).isEmpty()
    }
}
