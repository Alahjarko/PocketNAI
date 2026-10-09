package net.pocketnai.data.settings

import com.google.common.truth.Truth.assertThat
import net.pocketnai.domain.model.DirectorReferenceKind
import net.pocketnai.domain.model.GenerationDraft
import net.pocketnai.domain.model.GenerationParams
import net.pocketnai.domain.model.ImageModel
import net.pocketnai.domain.model.ReferenceImage
import net.pocketnai.domain.model.ReferenceRole
import net.pocketnai.domain.model.ImageOrientation
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

    @Test
    fun `角色的二维坐标和长提示词在重启草稿后保留`() {
        val character = net.pocketnai.domain.model.CharacterPrompt(
            prompt = "long character tag, ".repeat(200), negativePrompt = "hat",
            centerX = 0.12345, centerY = 0.98765,
        )
        val original = draftWith { it.copy(characters = listOf(character)) }
        assertThat(GenerationDraftCodec.decode(GenerationDraftCodec.encode(original))?.params?.characters)
            .containsExactly(character)
    }

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
}
