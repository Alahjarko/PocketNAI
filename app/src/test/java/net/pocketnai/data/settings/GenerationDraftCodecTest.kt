package net.pocketnai.data.settings

import com.google.common.truth.Truth.assertThat
import net.pocketnai.domain.model.DirectorReferenceKind
import net.pocketnai.domain.model.CustomResolution
import net.pocketnai.domain.model.GenerationDraft
import net.pocketnai.domain.model.GenerationParams
import net.pocketnai.domain.model.ImageModel
import net.pocketnai.domain.model.ReferenceImage
import net.pocketnai.domain.model.ReferenceRole
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
    fun `多张参考图可以往返`() {
        val draft = draftWith().copy(
            references = listOf(
                reference("img", ReferenceRole.IMG2IMG, ordinal = 0),
                reference("cr-0", ReferenceRole.DIRECTOR, ordinal = 0),
                reference("cr-1", ReferenceRole.DIRECTOR, ordinal = 1),
            ),
        )

        val restored = GenerationDraftCodec.decode(GenerationDraftCodec.encode(draft))

        assertThat(restored?.references?.map { it.id })
            .containsExactly("img", "cr-0", "cr-1")
            .inOrder()
        assertThat(restored?.references?.first { it.id == "cr-1" }?.directorKind)
            .isEqualTo(DirectorReferenceKind.CHARACTER_AND_STYLE)
    }

    @Test
    fun `只存了单张起点图的旧草稿仍能读回`() {
        // 升级前保存的草稿只有 reference 字段（那时只支持一张图生图起点图），
        // 读不回来等于让用户重新选一次图。这里直接用一份旧结构的 JSON。
        val legacy = """
            {"version":1,"prompt":"1girl","negative":"","modelApiId":"nai-diffusion-4-5-curated",
             "width":832,"height":1216,"sampleCount":1,"steps":23,"guidance":7.0,"cfgRescale":0.0,
             "sampler":"k_euler_ancestral","noiseSchedule":"karras","seedMode":"RANDOM","baseSeed":0,
             "qualityTags":"STANDARD","ucPresetIndex":0,
             "reference":{"id":"old","role":"IMG2IMG","relativePath":"references/old.png",
                          "width":2560,"height":1440,"byteSize":100,"sha256":"old",
                          "createdAt":0,"strength":0.7}}
        """.trimIndent()

        val restored = GenerationDraftCodec.decode(legacy)

        assertThat(restored?.references?.map { it.id }).containsExactly("old")
        assertThat(restored?.references?.first()?.strength).isEqualTo(0.7)
        assertThat(restored?.references?.first()?.role).isEqualTo(ReferenceRole.IMG2IMG)
    }

    @Test
    fun `参考图数量不影响参数本身的往返`() {
        val restored = GenerationDraftCodec.decode(
            GenerationDraftCodec.encode(draftWith().copy(references = emptyList())),
        )

        assertThat(restored?.references).isEmpty()
        assertThat(restored?.params?.model).isEqualTo(ImageModel.V4_5_CURATED)
    }

    private fun reference(
        id: String,
        role: ReferenceRole,
        ordinal: Int,
    ) = ReferenceImage(
        id = id,
        role = role,
        ordinal = ordinal,
        relativePath = "references/$id.png",
        width = 1024,
        height = 1536,
        byteSize = 100,
        sha256 = id,
        createdAt = 0L,
        strength = 0.5,
        informationExtracted = 0.9,
        secondaryStrength = 0.4,
        directorKind = if (role == ReferenceRole.DIRECTOR) {
            DirectorReferenceKind.CHARACTER_AND_STYLE
        } else {
            null
        },
    )

    @Test
    fun `自定义分辨率与最终尺寸都能往返`() {
        val draft = GenerationDraft(
            params = GenerationParams.defaultsFor(ModelCatalog.profileOf(ImageModel.V5_FULL)).copy(
                // 1920×1080 的目标以 1920×1088 提交：两个尺寸都必须活过重启。
                size = ImageSizePreset(1920, 1088),
                outputSize = ImageSizePreset(1920, 1080),
            ),
            promptTemplate = "1girl",
            negativeTemplate = "",
            customResolution = CustomResolution(width = 1920, height = 1080, exactOutput = true),
        )

        val restored = GenerationDraftCodec.decode(GenerationDraftCodec.encode(draft))

        assertThat(restored).isNotNull()
        requireNotNull(restored)
        assertThat(restored.params.size).isEqualTo(ImageSizePreset(1920, 1088))
        assertThat(restored.params.outputSize).isEqualTo(ImageSizePreset(1920, 1080))
        assertThat(restored.params.needsCrop).isTrue()
        assertThat(restored.customResolution)
            .isEqualTo(CustomResolution(width = 1920, height = 1080, exactOutput = true))
    }

    @Test
    fun `预设尺寸的草稿不会被误判成自定义`() {
        val draft = GenerationDraft(
            params = GenerationParams.defaultsFor(ModelCatalog.profileOf(ImageModel.V4_5_CURATED)),
            promptTemplate = "",
            negativeTemplate = "",
        )

        val restored = GenerationDraftCodec.decode(GenerationDraftCodec.encode(draft))!!

        assertThat(restored.customResolution).isNull()
        assertThat(restored.params.outputSize).isNull()
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
