package net.pocketnai.data.local

import net.pocketnai.core.ErrorCode
import net.pocketnai.domain.model.DirectorReferenceKind
import net.pocketnai.domain.model.GalleryItem
import net.pocketnai.domain.model.GeneratedImage
import net.pocketnai.domain.model.Generation
import net.pocketnai.domain.model.GenerationMode
import net.pocketnai.domain.model.GenerationParams
import net.pocketnai.domain.model.GenerationStatus
import net.pocketnai.domain.model.ImageModel
import net.pocketnai.domain.model.ImageSizePreset
import net.pocketnai.domain.model.ModelCatalog
import net.pocketnai.domain.model.NoiseSchedule
import net.pocketnai.domain.model.QualityTagsOption
import net.pocketnai.domain.model.ReferenceImage
import net.pocketnai.domain.model.ReferenceRole
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
        mode = generation.mode.name,
        prompt = generation.params.prompt,
        negativePrompt = generation.params.negativePrompt,
        modelApiId = generation.params.model.apiModelId,
        width = generation.params.size.width,
        height = generation.params.size.height,
        outputWidth = generation.params.outputSize?.width,
        outputHeight = generation.params.outputSize?.height,
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
                // 两列都为 NULL（v4 之前的记录，或没裁切过）时按"不裁切"降级。
                outputSize = if (entity.outputWidth != null && entity.outputHeight != null) {
                    ImageSizePreset(width = entity.outputWidth, height = entity.outputHeight)
                } else {
                    null
                },
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
            // v4 之前落库的记录没有这一列，按纯文生图降级是唯一正确的解读。
            mode = GenerationMode.fromNameOrDefault(entity.mode),
            errorCode = entity.errorCode?.let { runCatching { ErrorCode.valueOf(it) }.getOrNull() },
            errorMessage = entity.errorMessage,
            correlationId = entity.correlationId,
        )
    }

    // ---- 参考图 ----
    //
    // 参考图行与生成记录分开读写：`Generation.references` 由仓库层在写入生成记录后
    // 单独插入，读取时也要单独查一次再拼装。这样 `generations` 表的结构与既有查询
    // 完全不受影响，也不会因为一次 join 把画廊的查询变复杂。

    /**
     * 参考图行的主键：按"哪条生成记录 + 什么角色 + 第几张"唯一。
     *
     * **不能用 `ReferenceImage.id` 当主键。** 那是"素材"的身份，同一张图被两次生成
     * 使用（草稿恢复、复用参数都会这样）时它是一样的；而 `insertReferences` 用的是
     * `OnConflictStrategy.IGNORE`，于是第二条记录的参考图会被静默丢弃 ——
     * 表现为详情页看不到参考图、"复用参数"带不回起点图。这是真机验证时抓到的缺陷。
     *
     * 这个组合在一条生成记录内天然唯一（`GenerationRequest.validate` 不允许重复角色/序号），
     * 因此既是确定的，也不会碰撞。
     */
    private fun referenceRowId(
        generationId: String,
        role: ReferenceRole,
        ordinal: Int,
    ): String = "$generationId:${role.name}:$ordinal"

    fun toEntity(reference: ReferenceImage, generationId: String): ReferenceImageEntity =
        ReferenceImageEntity(
            id = referenceRowId(generationId, reference.role, reference.ordinal),
            generationId = generationId,
            role = reference.role.name,
            ordinal = reference.ordinal,
            relativePath = reference.relativePath,
            width = reference.width,
            height = reference.height,
            byteSize = reference.byteSize,
            sha256 = reference.sha256,
            strength = reference.strength,
            informationExtracted = reference.informationExtracted,
            secondaryStrength = reference.secondaryStrength,
            directorKind = reference.directorKind?.apiValue,
            vibeRelativePath = reference.vibeRelativePath,
            createdAt = reference.createdAt,
        )

    /** 无法识别的角色名直接丢弃该行，而不是猜一个角色 —— 猜错会把它塞进错误的请求字段。 */
    fun toDomain(entity: ReferenceImageEntity): ReferenceImage? {
        val role = runCatching { ReferenceRole.valueOf(entity.role) }.getOrNull() ?: return null
        return ReferenceImage(
            id = entity.id,
            role = role,
            ordinal = entity.ordinal,
            relativePath = entity.relativePath,
            width = entity.width,
            height = entity.height,
            byteSize = entity.byteSize,
            sha256 = entity.sha256,
            createdAt = entity.createdAt,
            strength = entity.strength,
            informationExtracted = entity.informationExtracted,
            secondaryStrength = entity.secondaryStrength,
            directorKind = entity.directorKind?.let { DirectorReferenceKind.fromApiValueOrDefault(it) },
            vibeRelativePath = entity.vibeRelativePath,
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
