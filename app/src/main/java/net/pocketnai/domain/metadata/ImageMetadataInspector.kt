package net.pocketnai.domain.metadata

import net.pocketnai.domain.image.ReferenceSource

/**
 * 从**用户选中的原始图片**里读出 NovelAI 元数据。
 *
 * ## 为什么必须在这里做
 * 参考图的归一化流程（`ReferenceImageProcessor`）会解码再重新编码 PNG，
 * 重新编码后 `tEXt` 等文本块全部丢失。因此读取只能发生在归一化**之前**，
 * 输入必须是用户选择的原始文件 —— 从 `files/references/<sha256>.png` 里再也读不回来。
 *
 * ## 这是一次完全本地的操作
 * 不联网、不需要登录、不消耗 Anlas。失败也只意味着"这次没有可导入的参数"，
 * **绝不影响把这张图当参考图用** —— 调用方必须按这个语义降级。
 */
interface ImageMetadataInspector {

    suspend fun inspect(source: ReferenceSource): MetadataProbeResult
}

/**
 * 探查结果。
 *
 * 刻意分成四种而不是"一个可空的元数据对象"：
 * - [Unsupported] 与 [NotFound] 的界面文案完全不同（"这个格式首版不支持"vs"这张图没有元数据"）；
 * - [NoSoftwareMarker] 用来兜住"有文本块但没有 NovelAI 标记"的情况 ——
 *   直接说"没有元数据"会让用户以为读取失败了。
 */
sealed interface MetadataProbeResult {

    /** 确认是 NovelAI 图片，且读出了元数据。 */
    data class Found(val metadata: NovelAiImageMetadata) : MetadataProbeResult

    /** 有 PNG 文本块，但没有 `Software: NovelAI`：不是 NovelAI 的图，或元数据已被剥离。 */
    data object NotNovelAi : MetadataProbeResult

    /** 是图片，但格式不支持（首版只做 PNG 文本块）。 */
    data class Unsupported(val mimeType: String?) : MetadataProbeResult

    /** 文件读不出来（URI 授权失效、文件被删）。 */
    data object Unreadable : MetadataProbeResult
}
