package net.pocketnai.data.settings

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.pocketnai.domain.model.GenerationDraft
import net.pocketnai.domain.model.GenerationParams
import net.pocketnai.domain.model.ImageModel
import net.pocketnai.domain.model.ImageSizePreset
import net.pocketnai.domain.model.ModelCatalog
import net.pocketnai.domain.model.ModelProfile
import net.pocketnai.domain.model.NoiseSchedule
import net.pocketnai.domain.model.QualityTagsOption
import net.pocketnai.domain.model.ReferenceImage
import net.pocketnai.domain.model.ReferenceRole
import net.pocketnai.domain.model.Sampler
import net.pocketnai.domain.model.SeedMode

/**
 * 生成草稿的序列化。
 *
 * 单独成为纯 Kotlin 类（不碰 Android），因此可以直接在 JVM 单元测试里
 * 断言往返一致与各种损坏输入的降级行为 —— 这块逻辑最容易在版本升级后出问题。
 *
 * ## 降级原则
 * 解码**不抛异常、也不整体丢弃**。字段级别的损坏只影响那个字段：
 * - 不认识的模型 id → 退回默认模型，其余参数照旧；
 * - 不认识的采样器 / 调度 → 用该模型的默认值；
 * - 越界数值、不支持的组合、非法尺寸 → 由 [net.pocketnai.domain.model.ModelProfile.normalize] 修正。
 *
 * 只有整串 JSON 无法解析时才返回 null，让调用方回到出厂默认。
 */
object GenerationDraftCodec {

    /** 结构版本。将来字段语义变化时递增，并在 [decode] 里做兼容处理。 */
    const val CURRENT_VERSION: Int = 1

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Serializable
    private data class DraftDto(
        val version: Int = CURRENT_VERSION,
        val prompt: String = "",
        val negative: String = "",
        val modelApiId: String = "",
        val width: Int = 0,
        val height: Int = 0,
        val sampleCount: Int = 0,
        val steps: Int = 0,
        val guidance: Double = 0.0,
        val cfgRescale: Double = 0.0,
        val sampler: String = "",
        val noiseSchedule: String = "",
        val seedMode: String = "",
        val baseSeed: Long = 0L,
        val qualityTags: String = "",
        val ucPresetIndex: Int = -1,
        /**
         * Image2Img 的起点图。
         *
         * 只存本地文件索引与逐条参数，**不存图片数据**：草稿是一个很小的首选项文件，
         * 图片本身已经在 `files/references/` 里内容寻址存着。
         * 多张参考图（Vibe / Precise Reference）等到那两个功能落地时再改成列表。
         */
        val reference: ReferenceDto? = null,
    )

    @Serializable
    private data class ReferenceDto(
        val id: String = "",
        val role: String = "",
        val relativePath: String = "",
        val width: Int = 0,
        val height: Int = 0,
        val byteSize: Long = 0L,
        val sha256: String = "",
        val createdAt: Long = 0L,
        val strength: Double? = null,
    )

    fun encode(draft: GenerationDraft): String {
        val params = draft.params
        return json.encodeToString(
            DraftDto.serializer(),
            DraftDto(
                version = CURRENT_VERSION,
                prompt = draft.promptTemplate,
                negative = draft.negativeTemplate,
                modelApiId = params.model.apiModelId,
                width = params.size.width,
                height = params.size.height,
                sampleCount = params.sampleCount,
                steps = params.steps,
                guidance = params.guidance,
                cfgRescale = params.cfgRescale,
                sampler = params.sampler.apiValue,
                noiseSchedule = params.noiseSchedule.apiValue,
                seedMode = params.seedMode.name,
                baseSeed = params.baseSeed,
                qualityTags = params.qualityTags.name,
                ucPresetIndex = params.undesiredContentPresetIndex,
                reference = draft.referenceSource?.let { reference ->
                    ReferenceDto(
                        id = reference.id,
                        role = reference.role.name,
                        relativePath = reference.relativePath,
                        width = reference.width,
                        height = reference.height,
                        byteSize = reference.byteSize,
                        sha256 = reference.sha256,
                        createdAt = reference.createdAt,
                        strength = reference.strength,
                    )
                },
            ),
        )
    }

    /** 解码失败或输入为空时返回 null，由调用方使用 [GenerationDraft.defaults]。 */
    fun decode(raw: String?): GenerationDraft? {
        if (raw.isNullOrBlank()) return null
        val dto = runCatching { json.decodeFromString(DraftDto.serializer(), raw) }
            .getOrNull() ?: return null

        val model = ImageModel.fromApiModelId(dto.modelApiId) ?: ImageModel.DEFAULT
        val profile = ModelCatalog.profileOf(model)

        val rawParams = GenerationParams(
            model = model,
            prompt = dto.prompt,
            negativePrompt = dto.negative,
            // 宽高为 0 时交给 normalize 判定为非法并换回默认尺寸。
            size = ImageSizePreset(width = dto.width, height = dto.height),
            sampleCount = dto.sampleCount,
            steps = dto.steps,
            guidance = dto.guidance,
            cfgRescale = dto.cfgRescale,
            sampler = Sampler.fromApiValue(dto.sampler) ?: profile.defaultSampler,
            noiseSchedule = NoiseSchedule.fromApiValue(dto.noiseSchedule)
                ?: profile.defaultNoiseSchedule,
            seedMode = runCatching { SeedMode.valueOf(dto.seedMode) }.getOrNull()
                ?: SeedMode.RANDOM,
            baseSeed = dto.baseSeed,
            qualityTags = QualityTagsOption.fromNameOrDefault(dto.qualityTags),
            undesiredContentPresetIndex = dto.ucPresetIndex.takeIf { index ->
                profile.undesiredContentPresets.any { it.index == index }
            } ?: profile.defaultUndesiredContentPresetIndex,
        )

        return GenerationDraft(
            // normalize 负责夹取越界数值、修正无效的采样器/调度组合、换掉非法尺寸。
            params = profile.normalize(rawParams),
            promptTemplate = dto.prompt,
            negativeTemplate = dto.negative,
            referenceSource = dto.reference?.toDomain(profile),
        )
    }

    /**
     * 参考图的降级：路径或角色读不出来就当作没有参考图。
     *
     * 这里刻意不检查文件是否存在 —— 编解码器是纯 Kotlin 的，不碰文件系统。
     * 文件真的丢了的话，提交时会被 `GenerationRequest.validate` 拦下并明确提示；
     * 界面上的缩略图会显示为空白，用户重新选一张即可。
     */
    private fun ReferenceDto.toDomain(profile: ModelProfile): ReferenceImage? {
        if (relativePath.isBlank()) return null
        val parsedRole = runCatching { ReferenceRole.valueOf(role) }.getOrNull() ?: return null
        return ReferenceImage(
            id = id.ifBlank { relativePath },
            role = parsedRole,
            ordinal = 0,
            relativePath = relativePath,
            width = width,
            height = height,
            byteSize = byteSize,
            sha256 = sha256,
            createdAt = createdAt,
            strength = strength?.let { profile.img2imgStrengthRange.clamp(it) },
        )
    }
}
