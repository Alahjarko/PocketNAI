package net.pocketnai.data.repo

import net.pocketnai.core.AppError
import net.pocketnai.domain.model.GeneratedImage
import net.pocketnai.domain.model.GenerationStatus

/**
 * 生成过程对上层暴露的事件（规划书 6.3）。
 *
 * 这套事件模型同时服务于普通（ZIP）传输与流式传输：
 * - 首版只实现普通传输，因此只会发出 [Started]、[Final]、[Completed]、[FatalError]；
 * - [Intermediate] 为流式中间预览预留，等 `StreamingGenerationTransport` 落地后再使用；
 * - [ItemError] 表达“某一张失败但整批仍可部分成功”，用于 Partial 状态。
 *
 * 事件里的图片一律给**文件路径**而不是字节数组，避免把整批图片同时留在内存里。
 */
sealed interface GenerationEvent {

    val generationId: String

    data class Started(override val generationId: String) : GenerationEvent

    /** 中间预览图：只用于界面展示，不写入正式历史目录。 */
    data class Intermediate(
        override val generationId: String,
        val ordinal: Int,
        val previewPath: String,
        val progress: Double?,
    ) : GenerationEvent

    data class Final(
        override val generationId: String,
        val image: GeneratedImage,
    ) : GenerationEvent

    data class ItemError(
        override val generationId: String,
        val ordinal: Int?,
        val error: AppError,
    ) : GenerationEvent

    data class Completed(
        override val generationId: String,
        val status: GenerationStatus,
    ) : GenerationEvent

    data class FatalError(
        override val generationId: String,
        val error: AppError,
    ) : GenerationEvent
}
