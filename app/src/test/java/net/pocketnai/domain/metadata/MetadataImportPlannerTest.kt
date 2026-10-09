package net.pocketnai.domain.metadata

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import net.pocketnai.domain.model.GenerationParams
import net.pocketnai.domain.model.ImageModel
import net.pocketnai.domain.model.ModelCatalog
import org.junit.Test

/**
 * 导入规划器。
 *
 * 这里断言的是**"导入什么、跳过什么、为什么"**：
 * 逐项降级是本功能最容易出错的地方（少跳一项就会静默改写用户参数并让复现失败）。
 */
class MetadataImportPlannerTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun metadata(
        commentJson: String = PngFixture.commentJson(),
        source: String = "NovelAI Diffusion V5 DB276663",
        /**
         * Description 里的提示词。
         *
         * 默认空串：这样解析器会回退到 `Comment.prompt`，测试就只需要把提示词写一遍。
         * 真实文件里两者是同一句话（已核对），"Description 优先"这条顺序由解析器测试单独覆盖。
         */
        prompt: String = "",
    ): NovelAiImageMetadata {
        val png = PngFixture.novelAi(commentJson = commentJson, source = source, prompt = prompt)
        val chunks = (PngTextChunks.read(png.inputStream()) as PngTextChunks.Result.Read).chunks
        return NovelAiMetadataParser.parse(chunks, json)!!
    }

    /** 构造一份提示词写在 `Comment` 里的元数据，避免测试里把同一句话写两遍。 */
    private fun metadataWithPrompt(
        prompt: String,
        source: String = "NovelAI Diffusion V5 DB276663",
        negativeCaption: String = "",
        actualPrompt: String? = null,
    ): NovelAiImageMetadata = metadata(
        commentJson = PngFixture.commentJson(
            prompt = prompt,
            baseCaption = prompt,
            negativeCaption = negativeCaption,
            actualPrompt = actualPrompt,
        ),
        source = source,
    )

    private fun currentParams(model: ImageModel = ImageModel.V5_CURATED): GenerationParams =
        GenerationParams.defaultsFor(ModelCatalog.profileOf(model))

    @Test
    fun `网页漫画导入后修改台词保留角色而不复用旧文字段或叠加预设`() {
        val prompt = "comic, says \"你好\""
        val quality = "very aesthetic, masterpiece, no text"
        val negative = "nsfw, lowres, artistic error, film grain, scan artifacts, worst quality, bad quality, jpeg artifacts, very displeasing, chromatic aberration, dithering, halftone, screentone, multiple views, logo, too many watermarks, negative space, blank page, bad hands"
        val comment = """{
            "prompt": "comic, says \"你好\", $quality, teXt: 你好",
            "v4_prompt": {"use_coords":false,"caption":{"base_caption":"comic, says \"你好\", $quality, teXt: 你好",
                "char_captions":[${PngFixture.charCaption("left girl", 0.1, 0.2)},${PngFixture.charCaption("right boy", 0.9, 0.8)}]}},
            "v4_negative_prompt":{"caption":{"base_caption":"$negative","char_captions":[]}},
            "tag_hint_qt":1,"tag_hint_uc_preset":2
        }"""
        val plan = MetadataImportPlanner.plan(
            metadata(comment, source = "NovelAI Diffusion V5 0ADF9AB7"),
            currentParams(ImageModel.V5_FULL),
            MetadataImportSelection(),
        )

        assertThat(plan.characters!!.map { it.centerX }).containsExactly(0.1, 0.9).inOrder()
        assertThat(plan.characters!!.map { it.centerY }).containsExactly(0.2, 0.8).inOrder()
        assertThat(plan.notes.filterIsInstance<MetadataImportNote.CharactersPositionSnapped>()).isEmpty()
        assertThat(plan.prompt).isEqualTo(prompt)
        assertThat(plan.qualityTags).isEqualTo(net.pocketnai.domain.model.QualityTagsOption.STANDARD)
        assertThat(plan.negativePrompt).isEqualTo("bad hands")
        assertThat(plan.undesiredContentPresetIndex).isEqualTo(0)
        assertThat(plan.useCharacterCoordinates).isFalse()
        val edited = currentParams(ImageModel.V5_FULL).copy(prompt = plan.prompt!!.replace("你好", "再见"),
            negativePrompt = plan.negativePrompt!!, qualityTags = plan.qualityTags!!,
            undesiredContentPresetIndex = plan.undesiredContentPresetIndex!!,
            characters = plan.characters!!, useCharacterCoordinates = plan.useCharacterCoordinates!!)
        val payload = net.pocketnai.data.network.NovelAiRequestBuilder.build(ModelCatalog.profileOf(edited.model), edited)
        val parameters = payload["parameters"] as kotlinx.serialization.json.JsonObject
        assertThat((payload["input"] as kotlinx.serialization.json.JsonPrimitive).content)
            .isEqualTo("comic, says \"再见\", $quality, teXt: 再见")
        assertThat((parameters["negative_prompt"] as kotlinx.serialization.json.JsonPrimitive).content).isEqualTo(negative)
        // The deliberately capitalized manual marker is not the web's automatic marker.
        val manual = "comic, says \"你好\", Text: 手写文字"
        assertThat(net.pocketnai.domain.prompt.NovelAiTextPrompt.removeAutomatic(manual, emptyList(), false)).isEqualTo(manual)
    }
}
