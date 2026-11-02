package net.pocketnai.data.openai

import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import net.pocketnai.core.AppError
import net.pocketnai.core.ErrorCode
import net.pocketnai.core.Hashing
import net.pocketnai.data.files.GenerationFileStore
import net.pocketnai.data.local.GenerationDao
import net.pocketnai.data.local.Mappers
import net.pocketnai.data.network.ZipImageExtractor
import net.pocketnai.data.repo.GenerationEvent
import net.pocketnai.domain.model.*
import net.pocketnai.domain.openai.*
import java.io.File
import java.util.UUID

/** Provider-specific request path; shared generations/images preserve all existing gallery behavior. */
class OpenAiImageRepository(
    private val api: OpenAiImagesTransport,
    private val dao: GenerationDao,
    private val files: GenerationFileStore,
    private val key: () -> String?,
    private val sfwEnabled: () -> Boolean = { false },
) {
    fun generate(draft: OpenAiImageDraft) = flow<GenerationEvent> {
        val id = UUID.randomUUID().toString()
        val params = draft.params
        if (net.pocketnai.domain.safety.SfwPolicy.blocks(sfwEnabled(), params.prompt)) {
            emit(GenerationEvent.FatalError(id, AppError.of(ErrorCode.INVALID_PARAMS,
                detail = net.pocketnai.domain.safety.SfwPolicy.BLOCK_MESSAGE)))
            return@flow
        }
        params.validate()?.let {
            emit(GenerationEvent.FatalError(id, AppError.of(ErrorCode.INVALID_PARAMS, detail = it)))
            return@flow
        }
        val secret = key()?.takeIf(String::isNotBlank)
        if (secret == null) {
            emit(GenerationEvent.FatalError(id, AppError.of(ErrorCode.TOKEN_INVALID, detail = "请先在设置中保存 OpenAI API 密钥")))
            return@flow
        }
        if (draft.references.size > 16 || draft.references.any { !files.exists(it.relativePath) }) {
            emit(GenerationEvent.FatalError(id, AppError.of(ErrorCode.REFERENCE_MISSING, detail = "参考图已不存在，或超过16张")))
            return@flow
        }
        if (draft.references.sumOf { files.resolve(it.relativePath).length() } > 24L * 1024 * 1024) {
            emit(GenerationEvent.FatalError(id, AppError.of(ErrorCode.REFERENCE_TOO_LARGE,
                detail = "参考图总大小需小于24 MiB，请减少张数或降低分辨率")))
            return@flow
        }
        val inputs = draft.references.map { ref ->
            val file = files.resolve(ref.relativePath)
            if (file.length() > 14L * 1024 * 1024) {
                emit(GenerationEvent.FatalError(id, AppError.of(ErrorCode.REFERENCE_TOO_LARGE, detail = "单张参考图需小于14 MiB")))
                return@flow
            }
            "data:image/png;base64," + Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        }
        if (!files.hasSpaceFor(32L * 1024 * 1024 * params.count)) {
            emit(GenerationEvent.FatalError(id, AppError.of(ErrorCode.STORAGE_FULL)))
            return@flow
        }
        val now = System.currentTimeMillis()
        val (width, height) = params.dimensions
        // Legacy columns remain populated for backwards-compatible table layout; OpenAI snapshot is authoritative.
        val legacy = GenerationParams.defaultsFor(ModelCatalog.defaultProfile()).copy(
            prompt = params.prompt, negativePrompt = "", characters = emptyList(),
            size = ImageSizePreset(width, height), sampleCount = params.count,
            qualityTags = QualityTagsOption.NONE,
        )
        var generation = Generation(id, now, now, GenerationStatus.GENERATING,
            params.prompt.trim().take(60), params.prompt, legacy, 1,
            mode = if (inputs.isEmpty()) GenerationMode.TXT2IMG else GenerationMode.IMG2IMG,
            openAi = OpenAiImageSnapshot(params))
        dao.upsertGeneration(Mappers.toEntity(generation))
        dao.insertReferences(draft.references.mapIndexed { index, ref ->
            Mappers.toEntity(ref.copy(role = ReferenceRole.IMG2IMG, ordinal = index), id)
        })
        emit(GenerationEvent.Started(id))
        val saved = mutableListOf<GeneratedImage>()
        try {
            if (net.pocketnai.domain.safety.SfwPolicy.blocks(sfwEnabled(), params.prompt)) {
                val error = AppError.of(ErrorCode.INVALID_PARAMS, detail = net.pocketnai.domain.safety.SfwPolicy.BLOCK_MESSAGE)
                dao.updateStatus(id, GenerationStatus.FAILED.name, System.currentTimeMillis(), error.code.name, error.detail, null)
                emit(GenerationEvent.FatalError(id, error))
                return@flow
            }
            val response = api.generate(secret, params, inputs)
            generation = generation.copy(openAi = response.snapshot)
            dao.upsertGeneration(Mappers.toEntity(generation))
            response.data.forEachIndexed { index, raw ->
                val image = runCatching {
                    require(raw.length <= 48 * 1024 * 1024)
                    val bytes = Base64.decode(raw, Base64.DEFAULT)
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    require(options.outWidth > 0 && options.outHeight > 0 &&
                        options.outWidth <= 3840 && options.outHeight <= 3840 &&
                        options.outWidth.toLong() * options.outHeight <= 8_294_400)
                    val extension = when (options.outMimeType) {
                        "image/png" -> "png"; "image/jpeg" -> "jpeg"; "image/webp" -> "webp"
                        else -> error("Unsupported image format")
                    }
                    val temp = File(files.newIncomingDir(id), "${index + 1}.$extension")
                    temp.writeBytes(bytes)
                    val extracted = ZipImageExtractor.ExtractedImage(index + 1, temp, bytes.size.toLong(),
                        Hashing.sha256(bytes), options.outWidth, options.outHeight)
                    val committed = files.commitImages(id, listOf(extracted), extension).single()
                    GeneratedImage(UUID.randomUUID().toString(), id, index + 1, null,
                        "generations/$id/${(index + 1).toString().padStart(4, '0')}.$extension",
                        committed.width, committed.height, committed.byteSize, committed.sha256,
                        createdAt = System.currentTimeMillis())
                }.getOrNull()
                if (image != null) {
                    dao.upsertImages(listOf(Mappers.toImageEntity(image)))
                    saved += image
                }
            }
            if (saved.isEmpty()) throw OpenAiImageFailure(true, "OpenAI 已返回结果，但图片无法保存或解码，可能已计费，未重试")
            val status = if (saved.size == params.count) GenerationStatus.SUCCEEDED else GenerationStatus.PARTIAL
            dao.updateStatus(id, status.name, System.currentTimeMillis(), null, null, null)
            saved.forEach { emit(GenerationEvent.Final(id, it)) }
            if (saved.size < params.count) emit(GenerationEvent.ItemError(id, null,
                AppError.of(ErrorCode.TIMEOUT_UNCERTAIN, detail = "仅保存了 ${saved.size}/${params.count} 张，已保留可用图片，缺失图片不会补发")))
            emit(GenerationEvent.Completed(id, status))
        } catch (e: CancellationException) {
            throw e
        } catch (e: OpenAiImageFailure) {
            val error = AppError.of(if (e.uncertain) ErrorCode.TIMEOUT_UNCERTAIN else ErrorCode.INVALID_PARAMS, detail = e.message)
            dao.updateStatus(id, if (saved.isEmpty()) GenerationStatus.FAILED.name else GenerationStatus.PARTIAL.name,
                System.currentTimeMillis(), error.code.name, error.detail, null)
            emit(GenerationEvent.FatalError(id, error))
        } catch (_: Exception) {
            val error = AppError.of(ErrorCode.TIMEOUT_UNCERTAIN, detail = "图片处理未完成，请先核对画廊；本次请求不会重试")
            dao.updateStatus(id, if (saved.isEmpty()) GenerationStatus.FAILED.name else GenerationStatus.PARTIAL.name,
                System.currentTimeMillis(), error.code.name, error.detail, null)
            emit(GenerationEvent.FatalError(id, error))
        } finally {
            files.clearIncoming(id)
        }
    }.flowOn(Dispatchers.IO)
}
