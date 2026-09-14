package net.pocketnai.data.local

import net.pocketnai.core.ErrorCode
import net.pocketnai.domain.model.GalleryItem
import net.pocketnai.domain.model.GeneratedImage
import net.pocketnai.domain.model.Generation
import net.pocketnai.domain.model.GenerationParams
import net.pocketnai.domain.model.GenerationStatus
import net.pocketnai.domain.model.ImageModel
import net.pocketnai.domain.model.ImageSizePreset
import net.pocketnai.domain.model.ModelCatalog
import net.pocketnai.domain.model.NoiseSchedule
import net.pocketnai.domain.model.QualityTagsOption
import net.pocketnai.domain.model.Sampler
import net.pocketnai.domain.model.SeedMode

/**
 * 存储实体与领域模型之间的显式映射。
 *
 * 刻意不使用 `@TypeConverters` + 领域模型直接入库：领域模型演进（例如新增字段）
 * 不应该悄悄改变数据库结构，映射写在一处更容易审查。
 */
object Mappers {

    fun toEntity(generation: Generation): GenerationEntity = GenerationEntity(
        id = generation.id,
        createdAt = generation.createdAt,
        updatedAt = generation.updatedAt,
        status = generation.status.name,
        title = generation.title,
        promptTemplate = generation.promptTemplate,
        prompt = generation.params.prompt,
        negativePrompt = generation.params.negativePrompt,
        modelApiId = generation.params.model.apiModelId,
        width = generation.params.size.width,
        height = generation.params.size.height,
        sampleCount = generation.params.sampleCount,
        steps = generation.params.steps,
        guidance = generation.params.guidance,
        cfgRescale = generation.params.cfgRescale,
        sampler = generation.params.sampler.apiValue,
        noiseSchedule = generation.params.noiseSchedule.apiValue,
        seedMode = generation.params.seedMode.name,
        baseSeed = generation.params.baseSeed,
        qualityTags = generation.params.qualityTags.name,
        // 遗留列继续同步写入，保证旧版本应用回退安装时仍能读出合理语义。
        qualityTagsEnabled = generation.params.qualityTags.enabled,
        ucPresetIndex = generation.params.undesiredContentPresetIndex,
        modelConfigVersion = ModelCatalog.profileOf(generation.params.model).configVersion,
        requestSnapshotVersion = generation.requestSnapshotVersion,
        errorCode = generation.errorCode?.name,
        errorMessage = generation.errorMessage,
        correlationId = generation.correlationId,
        deletedAt = null,
    )

    fun toDomain(entity: GenerationEntity): Generation? {
        val model = ImageModel.fromApiModelId(entity.modelApiId) ?: return null
        val sampler = Sampler.fromApiValue(entity.sampler) ?: return null
        val schedule = NoiseSchedule.fromApiValue(entity.noiseSchedule) ?: return null
        val status = runCatching { GenerationStatus.valueOf(entity.status) }.getOrNull() ?: return null
        val seedMode = runCatching { SeedMode.valueOf(entity.seedMode) }.getOrNull() ?: return null

        return Generation(
            id = entity.id,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
            status = status,
            title = entity.title,
            promptTemplate = entity.promptTemplate,
            params = GenerationParams(
                model = model,
                prompt = entity.prompt,
                negativePrompt = entity.negativePrompt,
                size = ImageSizePreset(width = entity.width, height = entity.height),
                sampleCount = entity.sampleCount,
                steps = entity.steps,
                guidance = entity.guidance,
                cfgRescale = entity.cfgRescale,
                sampler = sampler,
                noiseSchedule = schedule,
                seedMode = seedMode,
                baseSeed = entity.baseSeed,
                qualityTags = QualityTagsOption.fromNameOrDefault(entity.qualityTags),
                undesiredContentPresetIndex = entity.ucPresetIndex,
            ),
            requestSnapshotVersion = entity.requestSnapshotVersion,
            errorCode = entity.errorCode?.let { runCatching { ErrorCode.valueOf(it) }.getOrNull() },
            errorMessage = entity.errorMessage,
            correlationId = entity.correlationId,
        )
    }

    fun toDomain(row: GalleryImageRow): GalleryItem = GalleryItem(
        imageId = row.imageId,
        generationId = row.generationId,
        ordinal = row.ordinal,
        relativePath = row.relativePath,
        width = row.width,
        height = row.height,
        byteSize = row.byteSize,
        seed = row.seed,
        exported = row.exportedUri != null,
        createdAt = row.imageCreatedAt,
        status = runCatching { GenerationStatus.valueOf(row.status) }.getOrDefault(GenerationStatus.SUCCEEDED),
        title = row.title,
        model = ImageModel.fromApiModelId(row.modelApiId),
        prompt = row.prompt,
        negativePrompt = row.negativePrompt,
        sampleCount = row.sampleCount,
    )

    fun toImageEntity(image: GeneratedImage): GeneratedImageEntity = GeneratedImageEntity(
        id = image.id,
        generationId = image.generationId,
        ordinal = image.ordinal,
        seed = image.seed,
        relativePath = image.privateFilePath,
        width = image.width,
        height = image.height,
        byteSize = image.byteSize,
        sha256 = image.sha256,
        metadataJson = image.metadataJson,
        createdAt = image.createdAt,
        exportedUri = image.exportedUri,
    )

    fun toDomain(entity: GeneratedImageEntity): GeneratedImage = GeneratedImage(
        id = entity.id,
        generationId = entity.generationId,
        ordinal = entity.ordinal,
        seed = entity.seed,
        privateFilePath = entity.relativePath,
        width = entity.width,
        height = entity.height,
        byteSize = entity.byteSize,
        sha256 = entity.sha256,
        metadataJson = entity.metadataJson,
        createdAt = entity.createdAt,
        exportedUri = entity.exportedUri,
    )
}
