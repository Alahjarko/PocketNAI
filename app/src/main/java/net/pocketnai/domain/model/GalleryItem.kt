package net.pocketnai.domain.model

/**
 * 瀑布流卡片的读模型：一张图片加上它所属生成记录的展示信息。
 *
 * 这是“读取路径”的模型，不参与写入，因此可以按界面需要裁剪字段。
 */
data class GalleryItem(
    val imageId: String,
    val generationId: String,
    val ordinal: Int,
    /** 相对应用私有目录的路径，由文件存储层解析成绝对路径。 */
    val relativePath: String,
    val width: Int,
    val height: Int,
    val byteSize: Long,
    val seed: Long?,
    /** 是否已经复制到系统相册。 */
    val exported: Boolean,
    val createdAt: Long,
    val status: GenerationStatus,
    val title: String,
    val model: ImageModel?,
    val prompt: String,
    val negativePrompt: String,
    val sampleCount: Int,
) {
    /** 卡片占位用的宽高比，用于瀑布流在图片解码前就能排版，避免滚动抖动。 */
    val aspectRatio: Float get() = if (height > 0) width.toFloat() / height.toFloat() else 1f
}

/** 生成记录 + 图片数量，占位卡片和详情页复用。 */
data class GenerationSummary(
    val generation: Generation,
    val imageCount: Int,
)
