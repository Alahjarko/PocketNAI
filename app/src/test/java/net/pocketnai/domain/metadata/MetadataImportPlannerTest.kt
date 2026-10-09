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
    fun `角色二维位置原样导入不再吸附五档`() {
        // 官方是 5×5 网格（x=0.1/0.3/…），我们只有五档横排：吸附必须进 notes。
        val plan = MetadataImportPlanner.plan(
            metadata(
                PngFixture.commentJson(
                    charCaptions = "[${PngFixture.charCaption("left girl", 0.1, 0.2)}," +
                        "${PngFixture.charCaption("right boy", 0.9, 0.8)}]",
                ),
            ),
            currentParams(),
            MetadataImportSelection(),
        )

        assertThat(plan.characters!!.map { it.centerX }).containsExactly(0.1, 0.9).inOrder()
        assertThat(plan.characters!!.map { it.centerY }).containsExactly(0.2, 0.8).inOrder()
        assertThat(plan.notes.filterIsInstance<MetadataImportNote.CharactersPositionSnapped>()).isEmpty()
    }
}
