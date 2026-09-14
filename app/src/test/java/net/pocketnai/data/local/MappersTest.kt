package net.pocketnai.data.local

import com.google.common.truth.Truth.assertThat
import net.pocketnai.domain.model.DirectorReferenceKind
import net.pocketnai.domain.model.Generation
import net.pocketnai.domain.model.GenerationMode
import net.pocketnai.domain.model.GenerationParams
import net.pocketnai.domain.model.GenerationStatus
import net.pocketnai.domain.model.ImageModel
import net.pocketnai.domain.model.ImageSizePreset
import net.pocketnai.domain.model.ModelCatalog
import net.pocketnai.domain.model.ReferenceImage
import net.pocketnai.domain.model.ReferenceRole
import net.pocketnai.domain.model.Sampler
import net.pocketnai.domain.model.NoiseSchedule
import net.pocketnai.domain.model.QualityTagsOption
import net.pocketnai.domain.model.SeedMode
import org.junit.Test

/**
 * 实体与领域模型之间的映射（《参考图功能规划书》5.4）。
 *
 * 重点是**两个方向的完整性**：写进去的字段必须能原样读出来（否则详情页会静默丢信息），
 * 以及无法识别的枚举值必须被丢弃而不是猜一个（猜错会把参考图塞进错误的请求字段）。
 */
class MappersTest {

    private val profile = ModelCatalog.profileOf(ImageModel.V4_5_CURATED)

    private fun generation(
        mode: GenerationMode = GenerationMode.TXT2IMG,
        references: List<ReferenceImage> = emptyList(),
    ) = Generation(
        id = "gen-1",
        createdAt = 1_000L,
        updatedAt = 2_000L,
        status = GenerationStatus.SUCCEEDED,
        title = "示例",
        promptTemplate = "1girl, {red|blue} hair",
        params = GenerationParams.defaultsFor(profile).copy(
            prompt = "1girl, red hair",
            negativePrompt = "lowres",
            size = ImageSizePreset(832, 1216),
            baseSeed = 42L,
            seedMode = SeedMode.FIXED,
        ),
        requestSnapshotVersion = profile.paramsVersion,
        mode = mode,
        references = references,
    )

    private fun entity(
        mode: String? = "TXT2IMG",
    ) = GenerationEntity(
        id = "gen-1",
        createdAt = 1_000L,
        updatedAt = 2_000L,
        status = GenerationStatus.SUCCEEDED.name,
        title = "示例",
        promptTemplate = "1girl, {red|blue} hair",
        mode = mode,
        prompt = "1girl, red hair",
        negativePrompt = "lowres",
        modelApiId = ImageModel.V4_5_CURATED.apiModelId,
        width = 832,
        height = 1216,
        sampleCount = 1,
        steps = 23,
        guidance = 7.0,
        cfgRescale = 0.0,
        sampler = Sampler.EULER_ANCESTRAL.apiValue,
        noiseSchedule = NoiseSchedule.KARRAS.apiValue,
        seedMode = SeedMode.FIXED.name,
        baseSeed = 42L,
        qualityTags = QualityTagsOption.STANDARD.name,
        qualityTagsEnabled = true,
        ucPresetIndex = 0,
        modelConfigVersion = profile.configVersion,
        requestSnapshotVersion = profile.paramsVersion,
        errorCode = null,
        errorMessage = null,
        correlationId = null,
    )

    // ---- 生成记录 ----

    @Test
    fun `生成记录的参数可以原样往返`() {
        val original = generation()

        val restored = Mappers.toDomain(Mappers.toEntity(original))

        assertThat(restored).isNotNull()
        requireNotNull(restored)
        assertThat(restored.id).isEqualTo(original.id)
        assertThat(restored.title).isEqualTo(original.title)
        assertThat(restored.promptTemplate).isEqualTo(original.promptTemplate)
        assertThat(restored.params.prompt).isEqualTo("1girl, red hair")
        assertThat(restored.params.negativePrompt).isEqualTo("lowres")
        assertThat(restored.params.size).isEqualTo(ImageSizePreset(832, 1216))
        assertThat(restored.params.seedMode).isEqualTo(SeedMode.FIXED)
        assertThat(restored.params.baseSeed).isEqualTo(42L)
        assertThat(restored.params.qualityTags).isEqualTo(QualityTagsOption.STANDARD)
        assertThat(restored.params.sampler).isEqualTo(Sampler.EULER_ANCESTRAL)
        assertThat(restored.mode).isEqualTo(GenerationMode.TXT2IMG)
    }

    @Test
    fun `图生图模式可以往返`() {
        val restored = Mappers.toDomain(Mappers.toEntity(generation(mode = GenerationMode.IMG2IMG)))

        assertThat(restored?.mode).isEqualTo(GenerationMode.IMG2IMG)
    }

    @Test
    fun `模式列缺失的旧记录读成纯文生图`() {
        val restored = Mappers.toDomain(entity(mode = null))

        assertThat(restored?.mode).isEqualTo(GenerationMode.TXT2IMG)
    }

    @Test
    fun `模型 id 无法识别时丢弃整条记录而不是猜一个模型`() {
        val broken = entity().copy(modelApiId = "totally-bogus-model")

        assertThat(Mappers.toDomain(broken)).isNull()
    }

    // ---- 参考图 ----

    private fun reference(
        id: String,
        role: ReferenceRole,
        ordinal: Int,
        directorKind: DirectorReferenceKind? = null,
    ) = ReferenceImage(
        id = id,
        role = role,
        ordinal = ordinal,
        relativePath = "references/$id.png",
        width = 832,
        height = 1216,
        byteSize = 2048,
        sha256 = id,
        createdAt = 3_000L,
        strength = 0.6,
        informationExtracted = 0.8,
        secondaryStrength = 0.5,
        directorKind = directorKind,
        vibeRelativePath = if (role == ReferenceRole.VIBE) "vibes/$id.vibe" else null,
    )

    @Test
    fun `参考图的逐条参数可以原样往返`() {
        val original = reference("cr-1", ReferenceRole.DIRECTOR, ordinal = 0)
            .copy(directorKind = DirectorReferenceKind.CHARACTER_AND_STYLE, vibeRelativePath = null)

        val restored = Mappers.toDomain(Mappers.toEntity(original, generationId = "gen-1"))

        assertThat(restored).isNotNull()
        requireNotNull(restored)
        // id 刻意不参与比对：它在库里是"哪条生成记录的第几个参考图"（行身份），
        // 而 original 里的 id 是"用户选的那个素材"的身份。两者语义不同，
        // 素材可以稳定复用，行身份则必须随生成记录变化（见 referenceRowId 的说明）。
        assertThat(restored.copy(id = original.id)).isEqualTo(original)
        assertThat(restored.id).isEqualTo("gen-1:DIRECTOR:0")
    }

    @Test
    fun `Vibe 的编码缓存路径可以往返`() {
        val original = reference("vibe-1", ReferenceRole.VIBE, ordinal = 1)

        val restored = Mappers.toDomain(Mappers.toEntity(original, generationId = "gen-1"))

        assertThat(restored?.vibeRelativePath).isEqualTo("vibes/vibe-1.vibe")
        assertThat(restored?.role).isEqualTo(ReferenceRole.VIBE)
    }

    @Test
    fun `参考图行会带上它所属的生成记录 id`() {
        val entity = Mappers.toEntity(reference("a", ReferenceRole.VIBE, 0), generationId = "gen-9")

        assertThat(entity.generationId).isEqualTo("gen-9")
    }

    /**
     * 同一张素材被两次生成使用时，必须产生两行互不冲突的记录。
     *
     * 这一条是真机验证时抓到的缺陷的回归测试：起初用素材自己的 id 当主键，
     * 加上 `OnConflictStrategy.IGNORE`，第二次生成插参考图时被静默忽略 ——
     * 表现为那条历史的详情页没有参考图、"复用参数"也带不回起点图。
     */
    @Test
    fun `同一张参考图在两条生成记录里产生不同的主键`() {
        val asset = reference("asset-1", ReferenceRole.IMG2IMG, 0)

        val first = Mappers.toEntity(asset, generationId = "gen-1")
        val second = Mappers.toEntity(asset, generationId = "gen-2")

        assertThat(first.id).isNotEqualTo(second.id)
        assertThat(first.sha256).isEqualTo(second.sha256)
    }

    @Test
    fun `同一条生成记录内的参考图主键不重复`() {
        val ids = listOf(
            Mappers.toEntity(reference("a", ReferenceRole.VIBE, 0), "gen-1").id,
            Mappers.toEntity(reference("a", ReferenceRole.VIBE, 1), "gen-1").id,
            Mappers.toEntity(reference("a", ReferenceRole.DIRECTOR, 0), "gen-1").id,
        )

        assertThat(ids.toSet()).hasSize(3)
    }

    @Test
    fun `无法识别的角色名丢弃该行而不是猜一个角色`() {
        val broken = Mappers.toEntity(reference("a", ReferenceRole.VIBE, 0), "gen-1")
            .copy(role = "SOMETHING_ELSE")

        assertThat(Mappers.toDomain(broken)).isNull()
    }

    @Test
    fun `Director 的取用方式以 API 取值存储`() {
        val entity = Mappers.toEntity(
            reference("cr-1", ReferenceRole.DIRECTOR, 0)
                .copy(directorKind = DirectorReferenceKind.CHARACTER_AND_STYLE),
            generationId = "gen-1",
        )

        assertThat(entity.directorKind).isEqualTo("character&style")
    }
}
