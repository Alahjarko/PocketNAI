package net.pocketnai.data.repo

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import net.pocketnai.core.AppError
import net.pocketnai.core.ErrorCode
import net.pocketnai.core.Hashing
import net.pocketnai.core.Outcome
import net.pocketnai.BuildConfig
import net.pocketnai.data.files.GenerationFileStore
import net.pocketnai.data.image.OutputImageProcessor
import net.pocketnai.data.local.GenerationDao
import net.pocketnai.data.local.Mappers
import net.pocketnai.data.network.GenerationStreamEvent
import net.pocketnai.data.network.NovelAiApi
import net.pocketnai.data.network.NovelAiRequestBuilder
import net.pocketnai.data.network.PngValidator
import net.pocketnai.data.network.ZipImageExtractor
import net.pocketnai.data.security.CredentialStore
import net.pocketnai.domain.image.ImageGeometry
import net.pocketnai.domain.image.ImageTransform
import net.pocketnai.domain.image.PixelSize
import net.pocketnai.domain.image.LiveReferencePathsProvider
import net.pocketnai.domain.image.ResolutionPlanner
import net.pocketnai.domain.image.ReferenceImageEncoder
import net.pocketnai.domain.image.toPixelSize
import net.pocketnai.domain.model.GeneratedImage
import net.pocketnai.domain.model.GalleryItem
import net.pocketnai.domain.model.Generation
import net.pocketnai.domain.model.GenerationMode
import net.pocketnai.domain.model.GenerationParams
import net.pocketnai.domain.model.GenerationRequest
import net.pocketnai.domain.model.GenerationStatus
import net.pocketnai.domain.model.GenerationSummary
import net.pocketnai.domain.model.ImageModel
import net.pocketnai.domain.model.ModelCatalog
import net.pocketnai.domain.model.ParamViolation
import net.pocketnai.domain.model.ReferenceImage
import net.pocketnai.domain.model.ReferenceRole
import net.pocketnai.domain.model.ReferenceViolation
import net.pocketnai.domain.model.SeedMode
import net.pocketnai.domain.prompt.PromptRandomizer
import net.pocketnai.domain.prompt.PromptTitle
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

/**
 * 生成任务与历史的唯一入口。
 *
 * 完整流程对应规划书 4.2：
 * 1. 校验 Token 与参数，任何不合法都在**创建记录之前**返回；
 * 2. 先落一条 `Generating` 记录，界面立刻能显示占位卡片；
 * 3. ZIP 响应写入任务专用临时文件；
 * 4. 解包 + 校验 PNG（[ZipImageExtractor]）；
 * 5. 原子写入正式目录；
 * 6. 数据库提交成功后才删除 ZIP 与中间文件；
 * 7. 失败时清中间文件，但保留失败记录供用户查看。
 *
 * 存储策略：图片文件先落盘、数据库后提交。若在两者之间进程被杀，启动时的
 * [cleanupOnStartup] 会把没有数据库记录的图片目录当作孤立文件清理掉。
 */
class GenerationRepository(
    private val api: NovelAiApi,
    private val credentialStore: CredentialStore,
    private val dao: GenerationDao,
    private val fileStore: GenerationFileStore,
    private val zipExtractor: ZipImageExtractor = ZipImageExtractor(),
    /** 生成结果的后处理（自定义分辨率的裁切与元数据保全）。 */
    private val outputImageProcessor: OutputImageProcessor = OutputImageProcessor(),
    /**
     * 参考图 → 请求体 base64 的编码器。
     *
     * 默认实现直接失败：宁可让图生图报"图片无法读取"，也不能在忘记接线时
     * 静默地按纯文生图提交 —— 那会让用户以为自己在用参考图，其实没有。
     */
    private val referenceEncoder: ReferenceImageEncoder = ReferenceImageEncoder { _, _ ->
        Outcome.Failure(AppError.of(ErrorCode.REFERENCE_DECODE_FAILED))
    },
    /**
     * 数据库之外还需要保留的参考图（编辑区草稿里挂着的那几张）。
     *
     * 不是可选参数：漏掉它会让"选好图还没生成就重启"的用户丢掉刚选的图，
     * 而那是一个只在真机上才会发现的静默数据丢失。
     */
    private val liveReferencePaths: LiveReferencePathsProvider,
    /**
     * 流式中间预览是否启用（设置页开关；连续失败后由设置层自动关闭）。
     *
     * 默认关闭：这个开关只影响"用哪条传输链路"，关掉时一切走已验证的 ZIP 路径。
     */
    private val streamingEnabled: () -> Boolean = { false },
    /**
     * 报告一次流式失败（设置层据此累计失败次数，达到阈值自动关闭流式）。
     *
     * 返回 true 表示本次调用触发了自动关闭 —— 错误文案里会带上这句，
     * 让用户知道"下次生成会回到普通方式"，而不是以为功能坏了。
     */
    private val onStreamingFailure: () -> Boolean = { false },
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val random: Random = Random.Default,
) {

    fun observeGenerations(): Flow<List<GenerationSummary>> =
        dao.observeGenerations().map { rows ->
            rows.mapNotNull { row ->
                Mappers.toDomain(row.generation)?.let { GenerationSummary(it, row.imageCount) }
            }
        }

    fun observeGallery(): Flow<List<GalleryItem>> =
        dao.observeGalleryImages().map { rows -> rows.map(Mappers::toDomain) }

    /**
     * 执行一次生成（纯文生图）。
     *
     * 保留这个重载是为了让既有调用点读起来不变：它只是"参考图为空"的特例。
     */
    fun generate(
        params: GenerationParams,
        promptTemplate: String,
    ): Flow<GenerationEvent> = generate(GenerationRequest(params = params), promptTemplate)

    /**
     * 执行一次生成（可带参考图）。
     *
     * 返回冷流：只有真正收集时才发请求，且请求一旦发出就不会被取消重试
     * —— 规划书 4.2 明确“生成不可取消”，离开页面不等同于取消服务端任务。
     */
    fun generate(
        request: GenerationRequest,
        promptTemplate: String,
    ): Flow<GenerationEvent> = flow {
        val generationId = idGenerator()
        val startedAt = clock()
        val params = request.params
        val profile = ModelCatalog.profileOf(params.model)

        // 生成链路只关心可用的 Bearer Token，不关心它来自 PST 还是账号会话。
        val token = credentialStore.load()?.token
        if (token.isNullOrEmpty()) {
            emit(GenerationEvent.FatalError(generationId, AppError.of(ErrorCode.TOKEN_INVALID)))
            return@flow
        }

        val normalized = profile.normalize(params)
        val blockingViolations = profile.validate(normalized)
            .filterNot { it is ParamViolation.PromptTooLong }
        if (blockingViolations.isNotEmpty()) {
            emit(
                GenerationEvent.FatalError(
                    generationId,
                    AppError.of(ErrorCode.INVALID_PARAMS, detail = describe(blockingViolations)),
                ),
            )
            return@flow
        }

        // 参考图的校验与 base64 编码都在**创建记录之前**完成：
        // 一张已被系统清掉的文件、或一张读不出来的图，不该留下一条注定失败的历史记录。
        val referenceViolations = request.validate(profile) { fileStore.exists(it.relativePath) }
        if (referenceViolations.isNotEmpty()) {
            emit(GenerationEvent.FatalError(generationId, referenceError(referenceViolations)))
            return@flow
        }
        val encodedImages = mutableMapOf<ReferenceRole, List<String>>()
        // Vibe 编码会产出可复用的 `.vibe` 缓存，因此这里保留一份可写回的参考图列表。
        var referencesWithVibe = request.references
        for (role in ReferenceRole.entries) {
            val group = request.referencesOf(role)
            if (group.isEmpty()) continue
            val encoded = mutableListOf<String>()
            for (reference in group) {
                val outcome = if (role == ReferenceRole.VIBE) {
                    vibeBase64For(reference, token, normalized.model)
                } else {
                    referenceEncoder.encodeBase64(
                        reference.relativePath,
                        transformFor(reference, normalized),
                    )
                }
                when (outcome) {
                    is Outcome.Success -> encoded += outcome.value
                    is Outcome.Failure -> {
                        emit(GenerationEvent.FatalError(generationId, outcome.error))
                        return@flow
                    }
                }
            }
            encodedImages[role] = encoded
        }
        if (request.referencesOf(ReferenceRole.VIBE).isNotEmpty()) {
            // 编码产物已落盘，把缓存路径写回参考图：同一张图下次不必再编码一次。
            referencesWithVibe = referencesWithVibe.map { reference ->
                if (reference.role == ReferenceRole.VIBE) {
                    reference.copy(
                        vibeRelativePath = fileStore.vibeRelativePath(
                            vibeCacheKey(reference, normalized.model),
                        ),
                    )
                } else {
                    reference
                }
            }
        }

        val estimatedBytes = normalized.size.totalPixels.toLong() *
            normalized.sampleCount *
            ESTIMATED_BYTES_PER_PIXEL
        if (!fileStore.hasSpaceFor(estimatedBytes)) {
            emit(GenerationEvent.FatalError(generationId, AppError.of(ErrorCode.STORAGE_FULL)))
            return@flow
        }

        // 这一次生成用的 seed 在这里定下来，下面的**历史记录与请求体用的是同一份参数**。
        // 曾经踩过的坑：seed 只算进了落库的那一份，而请求体发的是原始 request.params，
        // 于是 RANDOM 模式下每次都把默认值 0 发出去 —— 同一提示词反复生成同一张图，
        // 而历史里却显示着一个"看起来随机"的 seed（那个值根本没被用过）。
        val effectiveParams = normalized.withResolvedSeed(random)
        val title = PromptTitle.buildTitle(effectiveParams.prompt, startedAt)

        dao.upsertGeneration(
            Mappers.toEntity(
                Generation(
                    id = generationId,
                    createdAt = startedAt,
                    updatedAt = startedAt,
                    status = GenerationStatus.GENERATING,
                    title = title,
                    promptTemplate = promptTemplate,
                    params = effectiveParams,
                    requestSnapshotVersion = profile.paramsVersion,
                    mode = request.mode,
                ),
            ),
        )
        // 参考图一条一行。写在生成记录之后：外键要求父行先存在。
        if (referencesWithVibe.isNotEmpty()) {
            dao.insertReferences(referencesWithVibe.map { Mappers.toEntity(it, generationId) })
        }
        emit(GenerationEvent.Started(generationId))

        // 两条链路（流式 SSE / 普通 ZIP）在这里分叉，产物统一成
        // ZipImageExtractor.ExtractedImage —— 后面的裁切、落盘、记账完全共用。
        val useStreaming = streamingEnabled()
        val payload = NovelAiRequestBuilder.build(
            profile = profile,
            request = request.copy(params = effectiveParams),
            upstreamImages = encodedImages,
            streaming = useStreaming,
        )

        var archiveToDelete: File? = null
        val extracted: ZipImageExtractor.Result.Success = if (useStreaming) {
            var streamFailure: AppError? = null
            var streamErrorMessage: String? = null
            var completed: GenerationStreamEvent.Completed? = null
            val finals = mutableListOf<ZipImageExtractor.ExtractedImage>()

            api.generateImageStream(token, payload).collect { event ->
                when (event) {
                    is GenerationStreamEvent.Intermediate -> {
                        // 中间图只做界面预览：写 cache、同序号覆盖，不落历史。
                        decodePreviewBase64(event.imageBase64)?.let { bytes ->
                            val preview = fileStore.writePreview(generationId, PREVIEW_ORDINAL, bytes)
                            emit(
                                GenerationEvent.Intermediate(
                                    generationId = generationId,
                                    ordinal = PREVIEW_ORDINAL,
                                    previewPath = preview.absolutePath,
                                    // 服务端只给 step_ix（不告诉总步数），
                                    // 百分比用本次请求的 steps 自己算。
                                    progress = event.step
                                        ?.let { (it + 1).toDouble() / effectiveParams.steps }
                                        ?.coerceIn(0.0, 1.0),
                                ),
                            )
                        }
                    }

                    is GenerationStreamEvent.Final ->
                        decodePngBase64(event.imageBase64)?.let { bytes ->
                            finals += storeIncomingImage(generationId, finals.size + 1, bytes)
                        }

                    is GenerationStreamEvent.StreamError -> streamErrorMessage = event.message
                    is GenerationStreamEvent.Completed -> completed = event
                    is GenerationStreamEvent.Failed -> streamFailure = event.error
                }
            }

            val streamError = streamFailure
                ?: streamErrorMessage?.let { AppError.of(ErrorCode.SERVER_ERROR, detail = it) }
                ?: if (finals.isEmpty()) {
                    AppError.of(ErrorCode.SERVER_ERROR, detail = describeStreamMismatch(completed))
                } else {
                    null
                }

            if (streamError != null) {
                fileStore.clearPreviews(generationId)
                fileStore.clearIncoming(generationId)
                // 记录一次流式失败：连续失败达到阈值时，设置层会关闭流式预览。
                val autoDisabled = onStreamingFailure()
                val error = if (autoDisabled) {
                    streamError.copy(
                        detail = listOfNotNull(
                            streamError.detail,
                            "流式预览已连续失败并自动关闭；下次生成将使用普通方式。",
                        ).joinToString(separator = " "),
                    )
                } else {
                    streamError
                }
                failGeneration(generationId, error)
                emit(GenerationEvent.FatalError(generationId, error))
                return@flow
            }
            ZipImageExtractor.Result.Success(finals)
        } else {
            val archive = fileStore.newArchiveFile(generationId)
            val transport = api.generateImage(
                token = token,
                payload = payload,
                destinationZip = archive,
            )
            if (transport is Outcome.Failure) {
                archive.delete()
                fileStore.clearIncoming(generationId)
                failGeneration(generationId, transport.error)
                emit(GenerationEvent.FatalError(generationId, transport.error))
                return@flow
            }

            val incomingDir = fileStore.newIncomingDir(generationId)
            val extraction = archive.inputStream().use { zipExtractor.extract(it, incomingDir) }
            if (extraction is ZipImageExtractor.Result.Failure) {
                archive.delete()
                fileStore.clearIncoming(generationId)
                failGeneration(generationId, extraction.error)
                emit(GenerationEvent.FatalError(generationId, extraction.error))
                return@flow
            }
            archiveToDelete = archive
            extraction as ZipImageExtractor.Result.Success
        }
        // 自定义分辨率：画布是 64 对齐的，用户要的最终尺寸可能不是。
        // 裁切必须发生在**落盘之前** —— 历史文件、数据库宽高、缩略图与相册导出
        // 都以落盘那一刻为准，之后再改就要重新保证三者一致。
        val outputCrop = effectiveParams.outputSize?.let { output ->
            if (output == effectiveParams.size) {
                null
            } else {
                ResolutionPlanner.centeredCrop(
                    canvas = PixelSize(effectiveParams.size.width, effectiveParams.size.height),
                    target = PixelSize(output.width, output.height),
                )
            }
        }
        val processed = outputImageProcessor.apply(
            images = extracted.images,
            crop = outputCrop,
            canvas = PixelSize(effectiveParams.size.width, effectiveParams.size.height),
        )
        val committed = try {
            fileStore.commitImages(generationId, processed)
        } catch (e: IOException) {
            archiveToDelete?.delete()
            fileStore.clearIncoming(generationId)
            fileStore.clearPreviews(generationId)
            val error = AppError.of(ErrorCode.STORAGE_FULL, detail = "写入历史目录失败: ${e.message.orEmpty()}")
            failGeneration(generationId, error)
            emit(GenerationEvent.FatalError(generationId, error))
            return@flow
        }

        // 单张时我们知道这次用的 seed（随机模式下就是我们自己抽的那个），因此直接落库 ——
        // 用户在详情页看到它，才能按同一个 seed 复现。批量（n_samples > 1）时服务端会从
        // 这个 seed 派生出每张图各自的 seed，那个值我们不知道，留空而不是四张都填同一个数。
        val knownSeed = effectiveParams.baseSeed.takeIf { effectiveParams.sampleCount == 1 }
        val committedAt = clock()
        val images = committed.map { image ->
            GeneratedImage(
                id = idGenerator(),
                generationId = generationId,
                ordinal = image.ordinal,
                seed = knownSeed,
                privateFilePath = fileStore.relativePathOf(generationId, image.ordinal),
                width = image.width,
                height = image.height,
                byteSize = image.byteSize,
                sha256 = image.sha256,
                metadataJson = null,
                createdAt = committedAt,
                exportedUri = null,
            )
        }
        dao.upsertImages(images.map(Mappers::toImageEntity))

        val status = if (images.size < effectiveParams.sampleCount) {
            GenerationStatus.PARTIAL
        } else {
            GenerationStatus.SUCCEEDED
        }
        dao.updateStatus(
            generationId = generationId,
            status = status.name,
            updatedAt = clock(),
            errorCode = null,
            errorMessage = null,
            correlationId = null,
        )

        // 规划书 6.2 第 7 步：数据库提交成功后才删除 ZIP 与中间文件；
        // 流式路径的预览图到此也完成使命（它只服务于生成期间的界面显示）。
        archiveToDelete?.delete()
        fileStore.clearIncoming(generationId)
        fileStore.clearPreviews(generationId)

        images.forEach { emit(GenerationEvent.Final(generationId, it)) }
        emit(GenerationEvent.Completed(generationId, status))
    }.flowOn(Dispatchers.IO)

    /** 详情页需要的完整信息：图片本身 + 它所属的生成记录与参数（含参考图）。 */
    suspend fun loadDetail(imageId: String): ImageDetail? {
        val imageEntity = dao.findImage(imageId) ?: return null
        val generationEntity = dao.findGeneration(imageEntity.generationId) ?: return null
        val generation = Mappers.toDomain(generationEntity) ?: return null
        // 参考图单独查一次再拼装：画廊与历史的查询不 join 它，避免影响瀑布流。
        val references = dao.findReferences(generation.id).mapNotNull(Mappers::toDomain)
        return ImageDetail(
            image = Mappers.toDomain(imageEntity),
            generation = generation.copy(references = references),
        )
    }

    data class ImageDetail(
        val image: GeneratedImage,
        val generation: Generation,
    )

    /**
     * 图像超分放大。
     *
     * 必须且只能由用户在详情页明确确认后触发。
     * 服务端 `/ai/upscale` 可能会消耗 Anlas，成功后作为一张新图片与记录插入画廊。
     *
     * @return 成功时返回新生成的 [GeneratedImage.id]
     */
    suspend fun upscaleImage(
        imageId: String,
        scale: Int,
    ): Outcome<String> = withContext(Dispatchers.IO) {
        val detail = loadDetail(imageId)
            ?: return@withContext Outcome.Failure(
                AppError.of(ErrorCode.UNKNOWN, detail = "图片记录不存在"),
            )
        val token = credentialStore.load()?.token
            ?: return@withContext Outcome.Failure(AppError.of(ErrorCode.TOKEN_INVALID))

        val sourceFile = fileOfRelativePath(detail.image.privateFilePath)
        if (!sourceFile.isFile) {
            return@withContext Outcome.Failure(
                AppError.of(ErrorCode.UNKNOWN, detail = "原图文件不存在: ${sourceFile.path}"),
            )
        }

        val sourceBytes = try {
            sourceFile.readBytes()
        } catch (e: IOException) {
            return@withContext Outcome.Failure(
                AppError.of(ErrorCode.STORAGE_FULL, detail = "读取原图失败: ${e.message}"),
            )
        }
        val imageBase64 = Base64.encodeToString(sourceBytes, Base64.NO_WRAP)

        val generationId = idGenerator()
        val incomingDir = fileStore.newIncomingDir(generationId)
        val archiveFile = fileStore.newArchiveFile(generationId)

        val callOutcome = api.upscaleImage(
            token = token,
            imageBase64 = imageBase64,
            width = detail.image.width,
            height = detail.image.height,
            scale = scale,
            destinationFile = archiveFile,
        )

        if (callOutcome is Outcome.Failure) {
            fileStore.clearIncoming(generationId)
            return@withContext callOutcome
        }

        if (!archiveFile.isFile || archiveFile.length() == 0L) {
            fileStore.clearIncoming(generationId)
            return@withContext Outcome.Failure(
                AppError.of(ErrorCode.SERVER_ERROR, detail = "超分响应为空"),
            )
        }

        val header = ByteArray(4)
        archiveFile.inputStream().use { it.read(header) }
        val isZip = header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()
        val dimensions = PngValidator.readDimensions(archiveFile)

        val extractedImages: List<ZipImageExtractor.ExtractedImage> = if (isZip) {
            val extraction = archiveFile.inputStream().use { stream ->
                zipExtractor.extract(stream, incomingDir)
            }
            if (extraction is ZipImageExtractor.Result.Failure) {
                fileStore.clearIncoming(generationId)
                return@withContext Outcome.Failure(extraction.error)
            }
            (extraction as ZipImageExtractor.Result.Success).images
        } else if (dimensions != null) {
            val sha256 = Hashing.sha256(archiveFile)
            listOf(
                ZipImageExtractor.ExtractedImage(
                    ordinal = 1,
                    file = archiveFile,
                    byteSize = archiveFile.length(),
                    sha256 = sha256,
                    width = dimensions.width,
                    height = dimensions.height,
                ),
            )
        } else {
            fileStore.clearIncoming(generationId)
            return@withContext Outcome.Failure(
                AppError.of(ErrorCode.ZIP_INVALID, detail = "未知的超分返回格式"),
            )
        }

        if (extractedImages.isEmpty()) {
            fileStore.clearIncoming(generationId)
            return@withContext Outcome.Failure(
                AppError.of(ErrorCode.ZIP_INVALID, detail = "未能解出有效图片"),
            )
        }

        val committed = try {
            fileStore.commitImages(generationId, extractedImages)
        } catch (e: IOException) {
            fileStore.clearIncoming(generationId)
            return@withContext Outcome.Failure(
                AppError.of(ErrorCode.STORAGE_FULL, detail = "写入历史目录失败: ${e.message.orEmpty()}"),
            )
        }

        val committedAt = clock()
        val targetWidth = committed.firstOrNull()?.width ?: (detail.image.width * scale)
        val targetHeight = committed.firstOrNull()?.height ?: (detail.image.height * scale)

        val newGeneration = detail.generation.copy(
            id = generationId,
            title = "${detail.generation.title} (${scale}x)",
            mode = GenerationMode.UPSCALE,
            createdAt = committedAt,
            updatedAt = committedAt,
            status = GenerationStatus.SUCCEEDED,
        )
        dao.upsertGeneration(Mappers.toEntity(newGeneration))

        val newImageId = idGenerator()
        val newImage = GeneratedImage(
            id = newImageId,
            generationId = generationId,
            ordinal = 1,
            seed = detail.image.seed,
            privateFilePath = fileStore.relativePathOf(generationId, 1),
            width = targetWidth,
            height = targetHeight,
            byteSize = committed.firstOrNull()?.byteSize ?: 0L,
            sha256 = committed.firstOrNull()?.sha256.orEmpty(),
            metadataJson = detail.image.metadataJson,
            createdAt = committedAt,
            exportedUri = null,
        )
        dao.upsertImages(listOf(Mappers.toImageEntity(newImage)))
        fileStore.clearIncoming(generationId)
        Outcome.Success(newImageId)
    }

    /** 删除第一步：标记删除，界面立即隐藏，可以撤销。 */
    suspend fun markDeleted(generationId: String) {
        dao.markDeleted(generationId, clock())
    }

    /** 撤销删除。 */
    suspend fun undoDelete(generationId: String) {
        dao.restore(generationId, clock())
    }

    /** 删除第二步：清理文件与数据库记录，不可撤销。 */
    suspend fun purgeDeleted() {
        dao.allDeletedIds().forEach { generationId ->
            fileStore.deleteGeneration(generationId)
            dao.purgeGeneration(generationId)
        }
    }

    /** 立即彻底删除（用户确认不撤销时使用）。 */
    suspend fun deleteImmediately(generationId: String) {
        fileStore.deleteGeneration(generationId)
        dao.purgeGeneration(generationId)
    }

    suspend fun markExported(imageId: String, exportedUri: String?) {
        dao.updateExportedUri(imageId, exportedUri)
    }

    /** 解析图片的绝对路径，供图片加载与导出使用。 */
    fun fileOf(item: GalleryItem): File = fileStore.resolve(item.relativePath)

    fun fileOfRelativePath(relativePath: String): File = fileStore.resolve(relativePath)

    fun usedBytes(): Long = fileStore.usedBytes()

    data class ImageCleanupReport(
        val deletedImagesCount: Int,
        val freedBytes: Long,
        val retainedFavoritesCount: Int,
    )

    /**
     * 清理生成的历史图片（针对大量抽卡图片积压的定制清理）。
     *
     * @param deleteFavorites 是否同时清理已收藏的图片。false 时严格保护所有收藏图片。
     */
    suspend fun cleanupGeneratedImages(deleteFavorites: Boolean): ImageCleanupReport = withContext(Dispatchers.IO) {
        val initialBytes = fileStore.usedBytes()
        if (deleteFavorites) {
            val allImages = dao.allImages()
            val totalCount = allImages.size
            allImages.forEach { image ->
                val file = fileStore.resolve(image.relativePath)
                if (file.exists()) file.delete()
            }
            dao.purgeAllGenerations()
            fileStore.clearAllGenerations()
            cleanupOnStartup()
            val finalBytes = fileStore.usedBytes()
            ImageCleanupReport(
                deletedImagesCount = totalCount,
                freedBytes = (initialBytes - finalBytes).coerceAtLeast(0L),
                retainedFavoritesCount = 0,
            )
        } else {
            val unFavorited = dao.allUnfavoritedImages()
            val allImages = dao.allImages()
            val favoritesCount = (allImages.size - unFavorited.size).coerceAtLeast(0)

            unFavorited.forEach { image ->
                val file = fileStore.resolve(image.relativePath)
                if (file.exists()) file.delete()
            }
            unFavorited.map { it.id }.chunked(500).forEach { chunk ->
                dao.deleteImages(chunk)
            }
            val emptyGenIds = dao.findEmptyGenerationIds()
            emptyGenIds.forEach { genId ->
                fileStore.deleteGeneration(genId)
            }
            emptyGenIds.chunked(500).forEach { chunk ->
                dao.purgeGenerations(chunk)
            }
            cleanupOnStartup()
            val finalBytes = fileStore.usedBytes()
            ImageCleanupReport(
                deletedImagesCount = unFavorited.size,
                freedBytes = (initialBytes - finalBytes).coerceAtLeast(0L),
                retainedFavoritesCount = favoritesCount,
            )
        }
    }

    /**
     * 启动时恢复与清理（规划书 7.2）。
     *
     * - 清理没有数据库记录的图片目录与所有中间文件；
     * - 回收不再被任何生成记录引用的参考图与 vibe 文件（内容寻址，不能随记录一起删）；
     * - 把上次进程被系统回收时卡在 `Generating` 的任务标记为失败，
     *   并按规划书 9.1 的口径说明”服务端可能已经接受任务”；
     * - 识别数据库记录指向但磁盘上已缺失的图片文件。
     *
     * 参考图的存活集合由两部分组成：数据库里所有被引用的路径，以及
     * [LiveReferencePathsProvider] 给出的"还没提交但正在编辑"的那几张。
     * 两者缺一都会误删用户还在用的文件。
     */
    suspend fun cleanupOnStartup(): StartupReport {
        val knownIds = dao.allGenerationIds().toSet()
        // 参考图的存活集合必须来自完整查询：漏一条就会误删别的历史还在用的文件。
        val referencedPaths = dao.allReferencePaths().toSet() + liveReferencePaths.provide()
        val cleanup = fileStore.cleanupOrphans(knownIds, referencedPaths)

        // 流式预览图是纯缓存：上次进程被回收时可能留下几张，直接全部清掉。
        fileStore.clearAllPreviews()

        // 标记删除但没能完成清理的记录（例如进程在撤销窗口内被杀）在启动时收尾。
        // 撤销窗口是有意设计成短暂的，跨重启保留会让“已删除”的记录长期占着磁盘。
        dao.allDeletedIds().forEach { generationId ->
            fileStore.deleteGeneration(generationId)
            dao.purgeGeneration(generationId)
        }

        val stuck = dao.findGenerationsByStatus(GenerationStatus.GENERATING.name)
        stuck.forEach { entity ->
            dao.updateStatus(
                generationId = entity.id,
                status = GenerationStatus.FAILED.name,
                updatedAt = clock(),
                errorCode = ErrorCode.TIMEOUT_UNCERTAIN.name,
                errorMessage = "应用进程在生成过程中被系统回收，结果状态不确定。",
                correlationId = null,
            )
        }

        var missingFiles = 0
        knownIds.forEach { generationId ->
            dao.findImages(generationId).forEach { image ->
                if (!fileStore.exists(image.relativePath)) missingFiles++
            }
        }

        return StartupReport(
            removedGenerationDirs = cleanup.removedGenerationDirs,
            removedIncomingDirs = cleanup.removedIncomingDirs,
            removedReferenceFiles = cleanup.removedReferenceFiles,
            recoveredStuckGenerations = stuck.size,
            missingImageFiles = missingFiles,
        )
    }

    /** 展开 Prompt Randomizer 模板，得到本次实际提交的提示词（规划书 8.3）。 */
    fun resolvePromptTemplate(template: String): String =
        PromptRandomizer.resolve(template, random)

    private suspend fun failGeneration(generationId: String, error: AppError) {
        dao.updateStatus(
            generationId = generationId,
            status = GenerationStatus.FAILED.name,
            updatedAt = clock(),
            errorCode = error.code.name,
            errorMessage = error.detail,
            correlationId = error.correlationId,
        )
    }

    private fun describe(violations: List<ParamViolation>): String =
        violations.joinToString(separator = "; ") { it.toString() }

    /**
     * Vibe 参考图 → 请求体里的 base64。
     *
     * ## 为什么先编码再发送
     * 官方网页的做法是先把图编码成 `.vibe` 二进制，再把它的 base64 放进
     * `reference_image_multiple`。OpenAPI 没有说明这个数组收的是原始图片还是编码产物，
     * 因此这里按官方行为实现；**这一层是可摘除的** —— 真机核对后如果服务端直接收原图，
     * 把本函数换成一次 [referenceEncoder.encodeBase64] 即可，其余流程不用动。
     *
     * ## 缓存键含模型与信息量
     * `encode-vibe` 的请求体里有 `model` 与 `information_extracted`，两者都可能影响产物，
     * 因此都进缓存键（多存几个副本的代价远小于"跨参数复用错的 vibe"）。
     */
    private suspend fun vibeBase64For(
        reference: ReferenceImage,
        token: String,
        model: ImageModel,
    ): Outcome<String> {
        val cacheKey = vibeCacheKey(reference, model)
        val cached = fileStore.resolve(fileStore.vibeRelativePath(cacheKey))
        if (cached.isFile) {
            return encodeFileBase64(cached)
        }

        val informationExtracted = reference.informationExtracted ?: DEFAULT_VIBE_INFORMATION_EXTRACTED
        val imageBase64 = when (
            val outcome = referenceEncoder.encodeBase64(reference.relativePath, null)
        ) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> return outcome
        }

        val bytes = when (val outcome = api.encodeVibe(token, model, imageBase64, informationExtracted)) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> return outcome
        }

        return try {
            val file = fileStore.writeVibe(cacheKey, bytes)
            encodeFileBase64(file)
        } catch (e: IOException) {
            Outcome.Failure(AppError.of(ErrorCode.STORAGE_FULL, detail = e.message.orEmpty()))
        }
    }

    /**
     * 这张 Vibe 是否已经有 `.vibe` 产物（缓存命中）。
     *
     * 费用预估要用它：官方规则里"编码一张 vibe"是一次性 2 Anlas，
     * 只有真的还需要编码的图才该计入本次费用。判定条件与 [vibeBase64For] 完全一致
     * （同一个缓存键），因此不会出现"按钮说免费、实际扣了 2"的偏差。
     */
    fun isVibeEncoded(reference: ReferenceImage, model: ImageModel): Boolean =
        fileStore.resolve(fileStore.vibeRelativePath(vibeCacheKey(reference, model))).isFile

    /** 缓存键：模型 + 图片内容 + 信息量，取哈希后当文件名（避免把模型 id 直接拼进路径）。 */
    private fun vibeCacheKey(reference: ReferenceImage, model: ImageModel): String {        val raw = listOf(
            model.apiModelId,
            reference.sha256,
            (reference.informationExtracted ?: DEFAULT_VIBE_INFORMATION_EXTRACTED).toString(),
        ).joinToString(separator = "|")
        return Hashing.sha256(raw.toByteArray())
    }

    private fun encodeFileBase64(file: File): Outcome<String> = try {
        Outcome.Success(Base64.encodeToString(file.readBytes(), Base64.NO_WRAP))
    } catch (e: IOException) {
        Outcome.Failure(AppError.of(ErrorCode.REFERENCE_DECODE_FAILED, detail = e.message.orEmpty()))
    }

    /**
     * base64 → 可显示的图片字节（**中间预览专用**）。
     *
     * 与 final 图不同，中间图只需要能被界面显示，因此：
     * - 剥掉可能的 `data:image/...;base64,` 前缀（有的服务端会给）；
     * - 接受常见图片魔数（PNG / JPEG / WebP / GIF），不要求 PNG ——
     *   服务端为省带宽常把中间图压成 JPEG，若这里只认 PNG，预览会全部被丢弃；
     * - 顺手把魔数与长度记进流式诊断日志（DEBUG），这些值不含图片内容。
     */
    private fun decodePreviewBase64(raw: String): ByteArray? {
        val payload = raw.substringAfter("base64,", missingDelimiterValue = raw)
        val bytes = try {
            Base64.decode(payload, Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (BuildConfig.DEBUG) {
            val magic = bytes.take(4).joinToString(separator = "") { "%02x".format(it) }
            Log.d("PocketNaiStream", "preview magic=$magic bytes=${bytes.size}")
        }
        return bytes.takeIf { hasImageSignature(it) }
    }

    /** 常见图片格式的魔数检测（只看文件头 4–12 字节）。 */
    private fun hasImageSignature(bytes: ByteArray): Boolean {
        if (PngValidator.hasSignature(bytes)) return true
        if (bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) return true
        if (bytes.size > 12 &&
            bytes.copyOfRange(0, 4).decodeToString() == "RIFF" &&
            bytes.copyOfRange(8, 12).decodeToString() == "WEBP"
        ) {
            return true
        }
        return bytes.size > 6 && bytes.copyOfRange(0, 3).decodeToString() == "GIF"
    }

    /** base64 → PNG 字节（**final 图专用**）：不是合法 base64、或没有 PNG 签名时返回 null。 */
    private fun decodePngBase64(base64: String): ByteArray? {
        val payload = base64.substringAfter("base64,", missingDelimiterValue = base64)
        val bytes = try {
            Base64.decode(payload, Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            return null
        }
        return bytes.takeIf { PngValidator.hasSignature(it) }
    }

    /**
     * 把一张流式最终图写进本次任务的中间目录。
     *
     * 序号与文件命名跟 ZIP 解包产物保持一致，因此后续的
     * [GenerationFileStore.commitImages] 与自定义分辨率裁切完全不区分图片来自哪条链路。
     */
    private fun storeIncomingImage(
        generationId: String,
        ordinal: Int,
        bytes: ByteArray,
    ): ZipImageExtractor.ExtractedImage {
        val file = File(fileStore.newIncomingDir(generationId), "%04d.png".format(Locale.ROOT, ordinal))
        file.writeBytes(bytes)
        val dimensions = PngValidator.readDimensions(file)
        return ZipImageExtractor.ExtractedImage(
            ordinal = ordinal,
            file = file,
            byteSize = bytes.size.toLong(),
            sha256 = Hashing.sha256(bytes),
            width = dimensions?.width ?: 0,
            height = dimensions?.height ?: 0,
        )
    }

    /**
     * 流式收尾但没有可用图片时的诊断文案。
     *
     * 只含结构信息（帧数、事件名）—— 首次真机验证就是靠它确认服务端的真实结构，
     * 记录在技术决策记录 §24。
     */
    private fun describeStreamMismatch(completed: GenerationStreamEvent.Completed?): String =
        if (completed == null) {
            "流式响应没有产生任何图片（连接提前结束）"
        } else {
            "流式响应没有可用的图片（帧数 ${completed.frames}，未识别 ${completed.unknownFrames}，" +
                "事件：${completed.labels.joinToString(separator = "; ").take(300)}）"
        }

    /**
     * 每类参考图在提交时要被做成什么形状。
     *
     * - 图生图起点图：**铺满**输出尺寸（裁掉多余边缘）；
     * - Precise Reference：**黑边补齐**到官方要求的三种画布之一。
     *   导入时已经补过一次，这里再套一次是恒等变换 —— 保持这条路径一致，
     *   比"导入时补过就跳过"更不容易在将来出错。
     */
    private fun transformFor(
        reference: ReferenceImage,
        normalized: GenerationParams,
    ): ImageTransform = when (reference.role) {
        ReferenceRole.IMG2IMG -> ImageTransform.Cover(normalized.size.toPixelSize())
        ReferenceRole.DIRECTOR -> ImageTransform.Letterbox(
            ImageGeometry.directorCanvas(PixelSize(reference.width, reference.height)),
        )
        // Vibe 走 encode-vibe 编码，几何变换在编码前不做：官方也是把原图交给编码接口。
        ReferenceRole.VIBE -> ImageTransform.Letterbox(
            PixelSize(reference.width, reference.height),
        )
        // 蒙版**不做任何几何变换**：生成它的时候就与输出尺寸严格一致，
        // 一旦在这里缩放就会与底图错位（而且是那种"看着差不多、其实整体偏了几像素"的错位）。
        ReferenceRole.INPAINT_MASK -> ImageTransform.Letterbox(
            PixelSize(reference.width, reference.height),
        )
    }

    /**
     * 参考图问题 → 错误码。
     *
     * 文件丢失单独成一类：它对应的动作是"重新选一张图"，而其余问题（数量超限、
     * 缺起点图）说明的是界面状态与请求不一致，属于需要用户回去改参数的情况。
     */
    private fun referenceError(violations: List<ReferenceViolation>): AppError =
        if (violations.any { it is ReferenceViolation.FileMissing }) {
            AppError.of(ErrorCode.REFERENCE_MISSING)
        } else {
            AppError.of(
                code = ErrorCode.INVALID_PARAMS,
                detail = violations.joinToString(separator = "; ") { it.toString() },
            )
        }

    data class StartupReport(
        val removedGenerationDirs: Int,
        val removedIncomingDirs: Int,
        val recoveredStuckGenerations: Int,
        val missingImageFiles: Int,
        /** 回收的参考图与 vibe 文件数（内容寻址，因此不能随记录删除，只能在这里回收）。 */
        val removedReferenceFiles: Int = 0,
    )

    private companion object {
        /** 写入前的粗略空间估算：每像素最多约 4 字节的 PNG 未压缩上限，留足余量。 */
        const val ESTIMATED_BYTES_PER_PIXEL = 4L

        /** Vibe 的 Information Extracted 缺省值。官方默认值未核对，取"全部提取"。 */
        const val DEFAULT_VIBE_INFORMATION_EXTRACTED = 1.0

        /** 中间预览只有一张：新的预览覆盖旧的，序号固定为 1。 */
        const val PREVIEW_ORDINAL = 1
    }
}
