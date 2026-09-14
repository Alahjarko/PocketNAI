package net.pocketnai.data.repo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import net.pocketnai.core.AppError
import net.pocketnai.core.ErrorCode
import net.pocketnai.core.Outcome
import net.pocketnai.data.files.GenerationFileStore
import net.pocketnai.data.local.GenerationDao
import net.pocketnai.data.local.Mappers
import net.pocketnai.data.network.NovelAiApi
import net.pocketnai.data.network.NovelAiRequestBuilder
import net.pocketnai.data.network.ZipImageExtractor
import net.pocketnai.data.security.CredentialStore
import net.pocketnai.domain.model.GeneratedImage
import net.pocketnai.domain.model.GalleryItem
import net.pocketnai.domain.model.Generation
import net.pocketnai.domain.model.GenerationParams
import net.pocketnai.domain.model.GenerationStatus
import net.pocketnai.domain.model.GenerationSummary
import net.pocketnai.domain.model.ModelCatalog
import net.pocketnai.domain.model.ParamViolation
import net.pocketnai.domain.model.SeedMode
import net.pocketnai.domain.prompt.PromptRandomizer
import net.pocketnai.domain.prompt.PromptTitle
import java.io.File
import java.io.IOException
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
     * 执行一次生成。
     *
     * 返回冷流：只有真正收集时才发请求，且请求一旦发出就不会被取消重试
     * —— 规划书 4.2 明确“生成不可取消”，离开页面不等同于取消服务端任务。
     */
    fun generate(
        params: GenerationParams,
        promptTemplate: String,
    ): Flow<GenerationEvent> = flow {
        val generationId = idGenerator()
        val startedAt = clock()
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

        val estimatedBytes = normalized.size.totalPixels.toLong() *
            normalized.sampleCount *
            ESTIMATED_BYTES_PER_PIXEL
        if (!fileStore.hasSpaceFor(estimatedBytes)) {
            emit(GenerationEvent.FatalError(generationId, AppError.of(ErrorCode.STORAGE_FULL)))
            return@flow
        }

        val seed = when (normalized.seedMode) {
            SeedMode.FIXED -> normalized.baseSeed
            SeedMode.RANDOM -> random.nextLong(0L, GenerationParams.MAX_SEED + 1)
        }
        val effectiveParams = normalized.copy(baseSeed = seed)
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
                ),
            ),
        )
        emit(GenerationEvent.Started(generationId))

        val archive = fileStore.newArchiveFile(generationId)
        val transport = api.generateImage(
            token = token,
            payload = NovelAiRequestBuilder.build(profile, effectiveParams),
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

        val extracted = extraction as ZipImageExtractor.Result.Success
        val committed = try {
            fileStore.commitImages(generationId, extracted.images)
        } catch (e: IOException) {
            archive.delete()
            fileStore.clearIncoming(generationId)
            val error = AppError.of(ErrorCode.STORAGE_FULL, detail = "写入历史目录失败: ${e.message.orEmpty()}")
            failGeneration(generationId, error)
            emit(GenerationEvent.FatalError(generationId, error))
            return@flow
        }

        // 只有固定 Seed 模式下我们才知道确定值；随机模式的每张图真实 Seed 需要
        // 解析 PNG 元数据（第二层能力），所以这里留空而不是猜一个值。
        val knownSeed = if (effectiveParams.seedMode == SeedMode.FIXED) effectiveParams.baseSeed else null
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

        // 规划书 6.2 第 7 步：数据库提交成功后才删除 ZIP 与中间文件。
        archive.delete()
        fileStore.clearIncoming(generationId)

        images.forEach { emit(GenerationEvent.Final(generationId, it)) }
        emit(GenerationEvent.Completed(generationId, status))
    }.flowOn(Dispatchers.IO)

    /** 详情页“复用参数”：载入参数但绝不自动开始生成（规划书 4.3）。 */
    suspend fun loadParamsForReuse(imageId: String): GenerationParams? =
        loadDetail(imageId)?.generation?.params

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

    /**
     * 启动时恢复与清理（规划书 7.2）。
     *
     * - 清理没有数据库记录的图片目录与所有中间文件；
     * - 回收不再被任何生成记录引用的参考图与 vibe 文件（内容寻址，不能随记录一起删）；
     * - 把上次进程被系统回收时卡在 `Generating` 的任务标记为失败，
     *   并按规划书 9.1 的口径说明”服务端可能已经接受任务”；
     * - 识别数据库记录指向但磁盘上已缺失的图片文件。
     */
    suspend fun cleanupOnStartup(): StartupReport {
        val knownIds = dao.allGenerationIds().toSet()
        // 参考图的存活集合必须来自完整查询：漏一条就会误删别的历史还在用的文件。
        val referencedPaths = dao.allReferencePaths().toSet()
        val cleanup = fileStore.cleanupOrphans(knownIds, referencedPaths)

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
    }
}
