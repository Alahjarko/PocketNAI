package net.pocketnai.data.settings

import com.google.common.truth.Truth.assertThat
import net.pocketnai.domain.model.GenerationDraft
import net.pocketnai.domain.model.GenerationParams
import net.pocketnai.domain.model.ImageModel
import net.pocketnai.domain.model.ImageOrientation
import net.pocketnai.domain.model.ImageSizePreset
import net.pocketnai.domain.model.ModelCatalog
import net.pocketnai.domain.model.NoiseSchedule
import net.pocketnai.domain.model.QualityTagsOption
import net.pocketnai.domain.model.ResolutionTier
import net.pocketnai.domain.model.Sampler
import net.pocketnai.domain.model.SeedMode
import org.junit.Test

/**
 * 生成草稿的序列化。
 *
 * 重点不在"能存能读"，而在**降级行为**：草稿是跨版本、跨机型的持久数据，
 * 上次保存的模型 id 可能已经不存在、数值可能因为默认值调整而越界。
 * 这些情况下必须退化成可用状态，而不是抛异常或者把整个草稿丢掉。
 */
class GenerationDraftCodecTest {

    private val profile = ModelCatalog.profileOf(ImageModel.V4_5_CURATED)

    private fun draftWith(
        prompt: String = "1girl, silver hair",
        negative: String = "lowres",
        configure: (GenerationParams) -> GenerationParams = { it },
    ): GenerationDraft {
        val base = GenerationParams.defaultsFor(profile).copy(
            prompt = prompt,
            negativePrompt = negative,
        )
        return GenerationDraft(
            params = configure(base),
            promptTemplate = prompt,
            negativeTemplate = negative,
        )
    }

    @Test
    fun `往返编码解码保留全部字段`() {
        val original = draftWith { params ->
            params.copy(
                model = ImageModel.V5_CURATED,
                size = profile.sizeFor(ResolutionTier.LARGE, ImageOrientation.LANDSCAPE)!!,
                sampleCount = 3,
                steps = 31,
                guidance = 6.5,
                cfgRescale = 0.25,
                sampler = Sampler.DPM_PLUS_PLUS_2M,
                noiseSchedule = NoiseSchedule.EXPONENTIAL,
                seedMode = SeedMode.FIXED,
                baseSeed = 123456789L,
                qualityTags = QualityTagsOption.LIGHT,
                undesiredContentPresetIndex = 2,
            )
        }

        val restored = GenerationDraftCodec.decode(GenerationDraftCodec.encode(original))

        assertThat(restored).isNotNull()
        val params = restored!!.params
        assertThat(params.model).isEqualTo(ImageModel.V5_CURATED)
        assertThat(params.size).isEqualTo(original.params.size)
        assertThat(params.sampleCount).isEqualTo(3)
        assertThat(params.steps).isEqualTo(31)
        assertThat(params.guidance).isWithin(1e-9).of(6.5)
        assertThat(params.cfgRescale).isWithin(1e-9).of(0.25)
        assertThat(params.sampler).isEqualTo(Sampler.DPM_PLUS_PLUS_2M)
        assertThat(params.noiseSchedule).isEqualTo(NoiseSchedule.EXPONENTIAL)
        assertThat(params.seedMode).isEqualTo(SeedMode.FIXED)
        assertThat(params.baseSeed).isEqualTo(123456789L)
        assertThat(params.qualityTags).isEqualTo(QualityTagsOption.LIGHT)
        assertThat(params.undesiredContentPresetIndex).isEqualTo(2)
        assertThat(restored.promptTemplate).isEqualTo("1girl, silver hair")
        assertThat(restored.negativeTemplate).isEqualTo("lowres")
    }

    @Test
    fun `提示词一起被记住`() {
        val draft = draftWith(prompt = "{{masterpiece}}, 1girl", negative = "bad hands")
        val restored = GenerationDraftCodec.decode(GenerationDraftCodec.encode(draft))!!
        assertThat(restored.promptTemplate).isEqualTo("{{masterpiece}}, 1girl")
        assertThat(restored.negativeTemplate).isEqualTo("bad hands")
    }

    @Test
    fun `没有草稿或数据损坏时返回 null 让调用方用出厂默认`() {
        assertThat(GenerationDraftCodec.decode(null)).isNull()
        assertThat(GenerationDraftCodec.decode("")).isNull()
        assertThat(GenerationDraftCodec.decode("   ")).isNull()
        assertThat(GenerationDraftCodec.decode("not json at all")).isNull()
        assertThat(GenerationDraftCodec.decode("{\"unexpected\":")).isNull()
    }

    @Test
    fun `模型 id 不认识时退回默认模型并保留其余参数`() {
        // 模拟降级：应用版本变化后旧的模型 id 已不存在。
        val json = GenerationDraftCodec.encode(draftWith { it.copy(steps = 42) })
            .replace(ImageModel.V4_5_CURATED.apiModelId, "nai-diffusion-does-not-exist")

        val restored = GenerationDraftCodec.decode(json)

        assertThat(restored).isNotNull()
        assertThat(restored!!.params.model).isEqualTo(ImageModel.DEFAULT)
        // 关键：不要因为一个字段不认识就把用户其它设置一起丢掉。
        assertThat(restored.params.steps).isEqualTo(42)
    }

    @Test
    fun `越界数值会被夹取回合法区间`() {
        val json = GenerationDraftCodec.encode(
            draftWith { it.copy(steps = 999, guidance = 999.0, cfgRescale = -5.0, sampleCount = 99) },
        )

        val params = GenerationDraftCodec.decode(json)!!.params

        assertThat(params.steps).isEqualTo(profile.stepsRange.max.toInt())
        assertThat(params.guidance).isWithin(1e-9).of(profile.guidanceRange.max)
        assertThat(params.cfgRescale).isWithin(1e-9).of(profile.cfgRescaleRange.min)
        assertThat(params.sampleCount).isEqualTo(profile.maxSampleCount)
    }

    @Test
    fun `非法尺寸会换回模型默认尺寸`() {
        // 宽高为 0 是"字段缺失"的表现，必须能兜住。
        val json = GenerationDraftCodec.encode(
            draftWith { it.copy(size = ImageSizePreset(width = 0, height = 0)) },
        )

        val params = GenerationDraftCodec.decode(json)!!.params

        assertThat(profile.sizeConstraints.isValid(params.size.width, params.size.height)).isTrue()
    }

    @Test
    fun `采样器或调度不认识时用模型的默认值`() {
        val json = GenerationDraftCodec.encode(
            draftWith { it.copy(sampler = Sampler.EULER, noiseSchedule = NoiseSchedule.NATIVE) },
        )
            .replace("\"k_euler\"", "\"k_not_a_sampler\"")
            .replace("\"native\"", "\"not_a_schedule\"")

        val params = GenerationDraftCodec.decode(json)!!.params

        assertThat(params.sampler).isEqualTo(profile.defaultSampler)
        assertThat(params.noiseSchedule).isEqualTo(profile.defaultNoiseSchedule)
    }

    @Test
    fun `不支持的采样器调度组合会被修正`() {
        val json = GenerationDraftCodec.encode(
            draftWith { it.copy(sampler = Sampler.DDIM, noiseSchedule = NoiseSchedule.KARRAS) },
        )

        val params = GenerationDraftCodec.decode(json)!!.params

        assertThat(profile.isCombinationSupported(params.sampler, params.noiseSchedule)).isTrue()
    }

    @Test
    fun `ucPreset 序号无效时用默认值`() {
        val json = GenerationDraftCodec.encode(
            draftWith { it.copy(undesiredContentPresetIndex = 99) },
        )

        val params = GenerationDraftCodec.decode(json)!!.params

        assertThat(profile.undesiredContentPresets.map { it.index })
            .contains(params.undesiredContentPresetIndex)
    }

    @Test
    fun `多出未知字段时仍然能解码`() {
        // 旧版本应用读到新版本写的草稿（或反之）时，不应该直接失败。
        val json = GenerationDraftCodec.encode(draftWith())
            .dropLast(1) + ",\"somethingNew\":123}"

        val restored = GenerationDraftCodec.decode(json)

        assertThat(restored).isNotNull()
        assertThat(restored!!.params.model).isEqualTo(ImageModel.V4_5_CURATED)
    }

    @Test
    fun `出厂默认草稿本身可以往返`() {
        val restored = GenerationDraftCodec.decode(
            GenerationDraftCodec.encode(GenerationDraft.defaults()),
        )
        assertThat(restored).isNotNull()
        assertThat(restored!!.params.model).isEqualTo(ImageModel.DEFAULT)
    }
}
