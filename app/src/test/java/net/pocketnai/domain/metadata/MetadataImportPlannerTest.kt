package net.pocketnai.domain.metadata

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import net.pocketnai.domain.model.GenerationParams
import net.pocketnai.domain.model.ImageModel
import net.pocketnai.domain.model.ImageSizePreset
import net.pocketnai.domain.model.ModelCatalog
import net.pocketnai.domain.model.NoiseSchedule
import net.pocketnai.domain.model.QualityTagsOption
import net.pocketnai.domain.model.Sampler
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

    // ---- 提示词 ----

    @Test
    fun `默认导入提示词与设置`() {
        val plan = MetadataImportPlanner.plan(
            metadata(),
            currentParams(),
            MetadataImportSelection(),
        )

        assertThat(plan.prompt).isEqualTo("1girl, silver hair")
        assertThat(plan.steps).isEqualTo(23)
        assertThat(plan.guidance).isEqualTo(7.0)
        // Seed 默认不勾：同 Seed 同参数会迅速产出几乎一样的图，而那是要花钱的。
        assertThat(plan.seed).isNull()
    }

    @Test
    fun `勾了 Seed 才导入`() {
        val plan = MetadataImportPlanner.plan(
            metadata(),
            currentParams(),
            MetadataImportSelection(seed = true),
        )

        assertThat(plan.seed).isEqualTo(495204733L)
    }

    @Test
    fun `Seed 超范围时只跳 Seed`() {
        val plan = MetadataImportPlanner.plan(
            metadata(PngFixture.commentJson(seed = 5_000_000_000L)),
            currentParams(),
            MetadataImportSelection(seed = true),
        )

        assertThat(plan.seed).isNull()
        assertThat(plan.notes).contains(MetadataImportNote.SeedOutOfRange(5_000_000_000L))
        // 其它字段照常导入。
        assertThat(plan.steps).isEqualTo(23)
    }

    @Test
    fun `勾了实际提示词但没有 Randomizer 时给出说明`() {
        val plan = MetadataImportPlanner.plan(
            metadata(),
            currentParams(),
            MetadataImportSelection(useActualPrompt = true),
        )

        assertThat(plan.prompt).isEqualTo("1girl, silver hair")
        assertThat(plan.notes).contains(MetadataImportNote.ActualPromptUnavailable)
    }

    @Test
    fun `勾了实际提示词时用展开值`() {
        // 与官方网页一致：勾了就把展开值写进输入框（模板原文不再保留），
        // 否则在我们这里那个勾选项没有作用 —— 提交时会从模板重新抽选一遍。
        val plan = MetadataImportPlanner.plan(
            metadataWithPrompt(
                prompt = "{red|blue} hair, 1girl",
                actualPrompt = "blue hair, 1girl",
            ),
            currentParams(),
            MetadataImportSelection(useActualPrompt = true),
        )

        assertThat(plan.prompt).isEqualTo("blue hair, 1girl")
    }

    @Test
    fun `不勾提示词时完全不动它`() {
        val plan = MetadataImportPlanner.plan(
            metadata(),
            currentParams(),
            MetadataImportSelection(prompt = false),
        )

        assertThat(plan.prompt).isNull()
        assertThat(plan.qualityTags).isNull()
    }

    // ---- 质量标签去重 ----

    @Test
    fun `提示词末尾的质量标签被剥离并改成对应预设`() {
        val metadata = metadataWithPrompt("1girl, very aesthetic, masterpiece, no text")

        val plan = MetadataImportPlanner.plan(metadata, currentParams(), MetadataImportSelection())

        assertThat(plan.prompt).isEqualTo("1girl")
        assertThat(plan.qualityTags).isEqualTo(QualityTagsOption.STANDARD)
        assertThat(plan.notes).contains(MetadataImportNote.QualityTagsDetected(QualityTagsOption.STANDARD))
    }

    @Test
    fun `Light 预设同样能认出来`() {
        val metadata = metadataWithPrompt("1girl, very aesthetic, amazing quality, no text")

        val plan = MetadataImportPlanner.plan(metadata, currentParams(), MetadataImportSelection())

        assertThat(plan.prompt).isEqualTo("1girl")
        assertThat(plan.qualityTags).isEqualTo(QualityTagsOption.LIGHT)
    }

    @Test
    fun `认不出质量标签时原样保留并把预设设为 None`() {
        // 这句里只有 masterpiece，没有完整的官方后缀序列 —— 不能删任何东西。
        val metadata = metadataWithPrompt("1girl, masterpiece pose")

        val plan = MetadataImportPlanner.plan(metadata, currentParams(), MetadataImportSelection())

        assertThat(plan.prompt).isEqualTo("1girl, masterpiece pose")
        assertThat(plan.qualityTags).isEqualTo(QualityTagsOption.NONE)
        assertThat(plan.notes).contains(MetadataImportNote.QualityTagsKeptVerbatim)
    }

    @Test
    fun `只剥完整后缀不碰用户自己写的词`() {
        val result = MetadataImportPlanner.stripKnownQualityTags(
            "masterpiece, 1girl, very aesthetic, masterpiece, no text",
        )

        assertThat(result.text).isEqualTo("masterpiece, 1girl")
        assertThat(result.option).isEqualTo(QualityTagsOption.STANDARD)
    }

    @Test
    fun `按官方规则逐块判断质量标签`() {
        // 官方给每一块都追加质量标签，所以两块都有才算匹配。
        val both = MetadataImportPlanner.stripKnownQualityTags(
            "1girl, very aesthetic, masterpiece, no text|2girl, very aesthetic, masterpiece, no text",
        )
        assertThat(both.text).isEqualTo("1girl|2girl")
        assertThat(both.option).isEqualTo(QualityTagsOption.STANDARD)

        // 只有一块有：整串不动（与官方前端一致，宁可不删）。
        val partial = MetadataImportPlanner.stripKnownQualityTags(
            "1girl, very aesthetic, masterpiece, no text|2girl",
        )
        assertThat(partial.option).isNull()
        assertThat(partial.text).contains("very aesthetic")
    }

    // ---- Clean Imports ----

    @Test
    fun `Clean Imports 按官方正则处理括号与逗号空格`() {
        assertThat(CleanImports.clean("{{1girl}},[[blue eyes]],silver hair"))
            .isEqualTo("1girl, blue eyes, silver hair")
    }

    @Test
    fun `默认不做 Clean Imports`() {
        val metadata = metadataWithPrompt("{{1girl}}, silver hair")

        val plan = MetadataImportPlanner.plan(metadata, currentParams(), MetadataImportSelection())

        assertThat(plan.prompt).isEqualTo("{{1girl}}, silver hair")
    }

    @Test
    fun `勾了 Clean Imports 才动提示词`() {
        val metadata = metadataWithPrompt("{{1girl}}, silver hair")

        val plan = MetadataImportPlanner.plan(
            metadata,
            currentParams(),
            MetadataImportSelection(cleanImports = true),
        )

        assertThat(plan.prompt).isEqualTo("1girl, silver hair")
    }

    // ---- 设置逐项降级 ----

    @Test
    fun `模型在四个之内时切换模型`() {
        val plan = MetadataImportPlanner.plan(
            metadata(source = "NovelAI Diffusion V5 0ADF9AB7"),
            currentParams(ImageModel.V5_CURATED),
            MetadataImportSelection(),
        )

        assertThat(plan.model).isEqualTo(ImageModel.V5_FULL)
    }

    @Test
    fun `模型不认识时保持不变并说明`() {
        val plan = MetadataImportPlanner.plan(
            metadata(source = "Stable Diffusion XL 8BA2AF87"),
            currentParams(),
            MetadataImportSelection(),
        )

        assertThat(plan.model).isNull()
        assertThat(plan.notes).contains(MetadataImportNote.ModelUnsupported("Stable Diffusion XL 8BA2AF87"))
    }

    @Test
    fun `尺寸是内置预设才采用`() {
        val plan = MetadataImportPlanner.plan(
            metadata(PngFixture.commentJson(width = 1024, height = 1024)),
            currentParams(),
            MetadataImportSelection(),
        )

        assertThat(plan.size).isEqualTo(ImageSizePreset(1024, 1024))
    }

    @Test
    fun `尺寸不是预设时不改成最接近的`() {
        // 1472×1472 是官方的 LargePlus 方形，不在我们的预设里。
        val plan = MetadataImportPlanner.plan(
            metadata(PngFixture.commentJson(width = 1472, height = 1472)),
            currentParams(),
            MetadataImportSelection(),
        )

        assertThat(plan.size).isNull()
        assertThat(plan.notes).contains(MetadataImportNote.SizeNotPreset(1472, 1472))
    }

    @Test
    fun `Steps 超范围时夹取并说明`() {
        val plan = MetadataImportPlanner.plan(
            metadata(PngFixture.commentJson(steps = 60)),
            currentParams(),
            MetadataImportSelection(),
        )

        assertThat(plan.steps).isEqualTo(50)
        assertThat(plan.notes).contains(MetadataImportNote.StepsClamped(60, 50))
    }

    @Test
    fun `采样器当前模型不支持时保持不变并说明`() {
        val plan = MetadataImportPlanner.plan(
            metadata(PngFixture.commentJson(sampler = "k_heun")),
            currentParams(),
            MetadataImportSelection(),
        )

        assertThat(plan.sampler).isNull()
        assertThat(plan.notes).contains(MetadataImportNote.SamplerUnsupported("k_heun"))
    }

    @Test
    fun `采样器支持但调度不匹配时改用默认调度`() {
        // DPM++ 2S Ancestral 不支持 Karras（catalog 里是 NO_KARRAS）。
        val plan = MetadataImportPlanner.plan(
            metadata(
                PngFixture.commentJson(
                    sampler = Sampler.DPM_PLUS_PLUS_2S_ANCESTRAL.apiValue,
                    noiseSchedule = NoiseSchedule.KARRAS.apiValue,
                ),
            ),
            currentParams(),
            MetadataImportSelection(),
        )

        assertThat(plan.sampler).isEqualTo(Sampler.DPM_PLUS_PLUS_2S_ANCESTRAL)
        assertThat(plan.noiseSchedule).isNull()
        assertThat(plan.notes).contains(
            MetadataImportNote.NoiseScheduleUnsupported(NoiseSchedule.KARRAS.apiValue),
        )
    }

    @Test
    fun `反向提示词导入时说明 UC 预设无法恢复`() {
        val plan = MetadataImportPlanner.plan(
            metadata(PngFixture.commentJson(negativeCaption = "bad hands")),
            currentParams(),
            MetadataImportSelection(),
        )

        assertThat(plan.negativePrompt).isEqualTo("bad hands")
        assertThat(plan.notes).contains(MetadataImportNote.UndesiredContentPresetNotRestorable)
    }

    // ---- 无法恢复的东西 ----

    @Test
    fun `用过 Vibe 或参考图时只提示不建空引用`() {
        val plan = MetadataImportPlanner.plan(
            metadata(
                PngFixture.commentJson(
                    referenceStrengths = "[0.6]",
                    directorStrengths = "[0.5]",
                    hasImage = true,
                ),
            ),
            currentParams(),
            MetadataImportSelection(),
        )

        assertThat(plan.notes).contains(MetadataImportNote.VibeReferencesNotRestorable)
        assertThat(plan.notes).contains(MetadataImportNote.DirectorReferencesNotRestorable)
        assertThat(plan.notes).contains(MetadataImportNote.BaseImageNotRestorable)
    }

    @Test
    fun `检测到角色提示词只报告数量`() {
        val plan = MetadataImportPlanner.plan(
            metadata(
                PngFixture.commentJson(
                    charCaptions = "[${PngFixture.charCaption("1girl", 0.25, 0.5)}," +
                        "${PngFixture.charCaption("1boy", 0.75, 0.5)}]",
                ),
            ),
            currentParams(),
            MetadataImportSelection(),
        )

        assertThat(plan.notes).contains(MetadataImportNote.CharactersNotImportable(2))
        // 绝不能把角色词拼进基础提示词冒充"角色已导入"。
        assertThat(plan.prompt).doesNotContain("1boy")
    }

    @Test
    fun `全部不勾时什么也不改`() {
        val plan = MetadataImportPlanner.plan(
            metadata(),
            currentParams(),
            MetadataImportSelection(
                prompt = false,
                negativePrompt = false,
                settings = false,
                seed = false,
            ),
        )

        assertThat(plan.changesAnything).isFalse()
        assertThat(plan.notes).contains(MetadataImportNote.NothingToImport)
    }
}
