package net.pocketnai.domain.model

import net.pocketnai.core.ErrorCode

/** 生成记录状态（规划书 7.1）。 */
enum class GenerationStatus {
    GENERATING,
    SUCCEEDED,

    /** 只保存了部分已验证的最终图片。 */
    PARTIAL,
    FAILED,
}

/**
 * 一次生成任务（规划书 7.1）。
 *
 * [promptTemplate] 是用户输入的原文（可能含 Randomizer 语法），
 * [params] 里的 prompt 是本次实际提交给 NovelAI 的展开结果。
 */
data class Generation(
    val id: String,
    val createdAt: Long,
    val updatedAt: Long,
    val status: GenerationStatus,
    val title: String,
    val promptTemplate: String,
    val params: GenerationParams,
    /** 请求体快照版本，用于 NovelAI 更新接口后做迁移。 */
    val requestSnapshotVersion: Int,
    /** 本次使用的生成模式（纯文生图 / 图生图）。 */
    val mode: GenerationMode = GenerationMode.TXT2IMG,
    /**
     * 本次使用的参考图（含图生图起点）。
     *
     * 与 [params] 分开保存：参考图是文件索引，不属于"参数"，也不参与请求体的纯文本部分。
     * 详情页与"复用参数"都依赖它，否则只带回参数而不带回图是没有意义的。
     */
    val references: List<ReferenceImage> = emptyList(),
    val errorCode: ErrorCode? = null,
    val errorMessage: String? = null,
    val correlationId: String? = null,
) {
    val isActive: Boolean get() = status == GenerationStatus.GENERATING

    /** 详情页与界面摘要用：本次是否挂了参考图。 */
    val hasReferences: Boolean get() = references.isNotEmpty()
}

/** 单张最终图片（规划书 7.2）。数据库只保存索引与参数，不保存 PNG 二进制。 */
data class GeneratedImage(
    val id: String,
    val generationId: String,
    /** 同批内的顺序号，从 1 开始，对应内部文件名 0001.png。 */
    val ordinal: Int,
    /**
     * 该图实际使用的 Seed。
     *
     * 首版只有在固定 Seed 模式下才拿得到确定值；随机模式下每张图的实际 Seed 需要解析
     * PNG 元数据（规划书 2.2 第二层能力）才能得到，因此此处允许为空，
     * 不用“基 Seed + 序号”猜测出一个错误值。
     */
    val seed: Long?,
    val privateFilePath: String,
    val width: Int,
    val height: Int,
    val byteSize: Long,
    val sha256: String,
    val metadataJson: String? = null,
    val createdAt: Long,
    /** 已复制到系统相册后记录的 MediaStore URI；为空表示尚未导出。 */
    val exportedUri: String? = null,
)
