package net.pocketnai.domain.metadata

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import net.pocketnai.domain.model.ImageModel
import net.pocketnai.domain.model.NoiseSchedule
import net.pocketnai.domain.model.Sampler
import org.junit.Test

/**
 * 元数据解析器与 `Source` → 模型的哈希映射。
 *
 * 输入形状按**真实文件**构造（字段名、`Generation_time` 的下划线写法、
 * 元数据块在 IDAT 之后），样本值来自 2026-09-14 本机生成图的核对结果。
 */
class NovelAiMetadataParserTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun parse(
        commentJson: String,
        prompt: String = "1girl, silver hair",
        source: String = "NovelAI Diffusion V5 DB276663",
        software: String = "NovelAI",
    ): NovelAiImageMetadata? {
        val png = PngFixture.novelAi(
            commentJson = commentJson,
            prompt = prompt,
            source = source,
            software = software,
        )
        val chunks = (PngTextChunks.read(png.inputStream()) as PngTextChunks.Result.Read).chunks
        return NovelAiMetadataParser.parse(chunks, json)
    }

    // ---- 判定"是不是 NovelAI 图片" ----

    @Test
    fun `没有 Software 标记时不认为是 NovelAI 图片`() {
        val png = PngFixture.png(
            texts = listOf("Description" to "1girl", "Software" to "Photoshop"),
        )
        val chunks = (PngTextChunks.read(png.inputStream()) as PngTextChunks.Result.Read).chunks

        assertThat(NovelAiMetadataParser.parse(chunks, json)).isNull()
    }

    @Test
    fun `只有 Software 也能给出对象但参数为空`() {
        val png = PngFixture.png(texts = listOf("Software" to "NovelAI"))
        val chunks = (PngTextChunks.read(png.inputStream()) as PngTextChunks.Result.Read).chunks

        val metadata = NovelAiMetadataParser.parse(chunks, json)
        assertThat(metadata).isNotNull()
        assertThat(metadata!!.settings).isEqualTo(ImportedSettings.EMPTY)
        assertThat(metadata.hasImportableContent).isFalse()
    }

    // ---- 正常路径 ----

    @Test
    fun `解析出提示词、参数与模型`() {
        val metadata = parse(PngFixture.commentJson())!!

        assertThat(metadata.software).isEqualTo("NovelAI")
        assertThat(metadata.source).isEqualTo("NovelAI Diffusion V5 DB276663")
        assertThat(metadata.prompt).isEqualTo("1girl, silver hair")
        assertThat(metadata.generationTimeSeconds).isEqualTo(2.18)
        assertThat(metadata.settings.model).isEqualTo(ImageModel.V5_CURATED)
        assertThat(metadata.settings.width).isEqualTo(832)
        assertThat(metadata.settings.height).isEqualTo(1216)
        assertThat(metadata.settings.steps).isEqualTo(23)
        assertThat(metadata.settings.guidance).isEqualTo(7.0)
        assertThat(metadata.settings.cfgRescale).isEqualTo(0.0)
        assertThat(metadata.settings.seed).isEqualTo(495204733L)
        assertThat(metadata.settings.sampler).isEqualTo(Sampler.EULER_ANCESTRAL)
        assertThat(metadata.settings.noiseSchedule).isEqualTo(NoiseSchedule.KARRAS)
        assertThat(metadata.settings.sampleCount).isEqualTo(1)
    }

    @Test
    fun `反向提示词优先取 v4_negative_prompt 再回退 uc`() {
        val withV4 = parse(PngFixture.commentJson(negativeCaption = "bad hands", uc = "lowres"))!!
        assertThat(withV4.negativePrompt).isEqualTo("bad hands")

        val onlyUc = parse(PngFixture.commentJson(negativeCaption = "", uc = "lowres"))!!
        assertThat(onlyUc.negativePrompt).isEqualTo("lowres")
    }

    @Test
    fun `提示词优先用 Description 再回退 Comment`() {
        // 官方前端用的就是 Description（= 用户提交的原文，带 Randomizer 时是模板）。
        val fromDescription = parse(
            PngFixture.commentJson(prompt = "来自 Comment"),
            prompt = "来自 Description",
        )!!
        assertThat(fromDescription.prompt).isEqualTo("来自 Description")

        // Description 缺失（元数据被工具改过）时回退到 Comment.prompt。
        val fromComment = parse(PngFixture.commentJson(prompt = "来自 Comment"), prompt = "")!!
        assertThat(fromComment.prompt).isEqualTo("来自 Comment")
    }

    @Test
    fun `读出 Randomizer 的实际提示词`() {
        val metadata = parse(
            PngFixture.commentJson(actualPrompt = "red hair, 1girl"),
            prompt = "1girl, silver hair",
        )!!

        assertThat(metadata.prompt).isEqualTo("1girl, silver hair")
        assertThat(metadata.actualPrompt).isEqualTo("red hair, 1girl")
    }

    @Test
    fun `读出角色提示词与位置`() {
        val metadata = parse(
            PngFixture.commentJson(
                charCaptions = "[${PngFixture.charCaption("1girl, silver hair", 0.25, 0.5)}," +
                    "${PngFixture.charCaption("1boy, black hair", 0.75, 0.5)}]",
                negativeCharCaptions = "[${PngFixture.charCaption("bad hands", 0.0, 0.0)}]",
            ),
        )!!

        assertThat(metadata.characters).hasSize(2)
        assertThat(metadata.characters[0].prompt).isEqualTo("1girl, silver hair")
        assertThat(metadata.characters[0].centerX).isEqualTo(0.25)
        assertThat(metadata.characters[0].centerY).isEqualTo(0.5)
        // 反向词按下标与正向对应；缺一条时留空。
        assertThat(metadata.characters[0].negativePrompt).isEqualTo("bad hands")
        assertThat(metadata.characters[1].negativePrompt).isNull()
    }

    @Test
    fun `识别出用过 Vibe 与 Precise Reference`() {
        val metadata = parse(
            PngFixture.commentJson(
                referenceStrengths = "[0.6, 0.6]",
                directorStrengths = "[0.5]",
                hasImage = true,
            ),
        )!!

        assertThat(metadata.usedVibeReferences).isTrue()
        assertThat(metadata.usedDirectorReferences).isTrue()
        assertThat(metadata.usedBaseImage).isTrue()
    }

    @Test
    fun `不认识的值只记录不猜测`() {
        val metadata = parse(
            PngFixture.commentJson(sampler = "k_heun", noiseSchedule = "sgm_uniform"),
            source = "NovelAI Diffusion V3 ABCDEF12",
        )!!

        assertThat(metadata.settings.sampler).isNull()
        assertThat(metadata.settings.unsupportedSampler).isEqualTo("k_heun")
        assertThat(metadata.settings.noiseSchedule).isNull()
        assertThat(metadata.settings.unsupportedNoiseSchedule).isEqualTo("sgm_uniform")
        // V3 不在支持范围内：保留 Source 原文供界面说明，绝不映射成 V4.5。
        assertThat(metadata.settings.model).isNull()
        assertThat(metadata.settings.unsupportedModelSource).isEqualTo("NovelAI Diffusion V3 ABCDEF12")
    }

    @Test
    fun `Comment 不是合法 JSON 时不抛异常`() {
        val metadata = parse("这不是 JSON", prompt = "1girl")!!

        assertThat(metadata.prompt).isEqualTo("1girl")
        // 参数读不出来了，但 Source 仍然告诉我们这是哪个模型画的。
        assertThat(metadata.settings.steps).isNull()
        assertThat(metadata.settings.sampler).isNull()
        assertThat(metadata.settings.model).isEqualTo(ImageModel.V5_CURATED)
    }

    @Test
    fun `字段类型不符时当作缺失`() {
        val metadata = parse(
            """{"prompt": "1girl", "steps": "23", "width": 832.5, "height": 1216, "scale": null}""",
        )!!

        // 字符串形式的数字不接受；带小数的宽高不接受（宽高必须是整数）。
        assertThat(metadata.settings.steps).isNull()
        assertThat(metadata.settings.width).isNull()
        assertThat(metadata.settings.height).isEqualTo(1216)
        assertThat(metadata.settings.guidance).isNull()
    }

    // ---- Source → 模型哈希表 ----

    @Test
    fun `模型哈希表与本机实测一致`() {
        // 这四条是 2026-09-14 从数据库与生成图的 Source 逐条对出来的。
        assertThat(NovelAiModelHashes.resolve("NovelAI Diffusion V5 DB276663"))
            .isEqualTo(ImageModel.V5_CURATED)
        assertThat(NovelAiModelHashes.resolve("NovelAI Diffusion V5 0ADF9AB7"))
            .isEqualTo(ImageModel.V5_FULL)
        assertThat(NovelAiModelHashes.resolve("NovelAI Diffusion V4.5 1229B44F"))
            .isEqualTo(ImageModel.V4_5_FULL)
        assertThat(NovelAiModelHashes.resolve("NovelAI Diffusion V4.5 4BDE2A90"))
            .isEqualTo(ImageModel.V4_5_FULL)
    }

    @Test
    fun `未知的 V5 哈希按 Curated 处理而未知的 V4_5 同样`() {
        // 官方前端的 default 分支就是这样：认不出具体哈希时按当前代的 Curated。
        assertThat(NovelAiModelHashes.resolve("NovelAI Diffusion V5 FFFFFFFF"))
            .isEqualTo(ImageModel.V5_CURATED)
        assertThat(NovelAiModelHashes.resolve("NovelAI Diffusion V4.5 FFFFFFFF"))
            .isEqualTo(ImageModel.V4_5_CURATED)
    }

    @Test
    fun `更早的模型不映射到我们支持的四个`() {
        assertThat(NovelAiModelHashes.resolve("Stable Diffusion XL 8BA2AF87")).isNull()
        assertThat(NovelAiModelHashes.resolve("NovelAI Diffusion V4 37442FCA")).isNull()
        assertThat(NovelAiModelHashes.resolve(null)).isNull()
        assertThat(NovelAiModelHashes.resolve("")).isNull()
    }
}
