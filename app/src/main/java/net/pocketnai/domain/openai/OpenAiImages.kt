package net.pocketnai.domain.openai

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.pocketnai.domain.model.ReferenceImage

@Serializable
enum class ImageProvider(val label: String) { NOVEL_AI("NovelAI"), OPEN_AI("OpenAI（实验）") }

@Serializable
data class OpenAiImageParams(
    val prompt: String = "",
    val model: String = FLARE,
    val size: String = "1024x1024",
    val quality: String = "low",
    val outputFormat: String = "png",
    val background: String = "auto",
    val compression: Int = 100,
    val moderation: String = "auto",
    val count: Int = 1,
    val complexity: String = "high",
    val frameCount: Int = 9,
    val frameDurationMs: Int = 200,
) {
    val modelLabel: String get() = labelOf(model)
    val supportsComplexity: Boolean get() = model == FLARE_26 || model == SUNBURST_26
    val complexityLabel: String get() = complexityLabelOf(complexity)
    val dimensions: Pair<Int, Int> get() = size.split('x').let {
        (it.getOrNull(0)?.toIntOrNull() ?: 1024) to (it.getOrNull(1)?.toIntOrNull() ?: 1024)
    }

    val formats: List<String> get() = if (supportsComplexity) FORMATS else RASTER_FORMATS
    val isAnimated: Boolean get() = outputFormat == "gif"
    val hasCompression: Boolean get() = outputFormat in listOf("jpeg", "webp")
    fun validate(referenceCount: Int = 0): String? = when {
        model !in MODELS -> "请选择支持的 GPT Image 模型"
        supportsComplexity && complexity !in COMPLEXITIES -> "不支持的图片复杂度"
        prompt.isBlank() || prompt.length > 32_000 -> "图片描述应为 1～32000 字符"
        quality !in QUALITIES -> "不支持的图片质量"
        outputFormat !in formats -> "当前模型不支持此图片格式"
        outputFormat == "svg" && (count != 1 || referenceCount > 0) -> "SVG 仅支持单张文生图，请移除参考图"
        isAnimated && count != 1 -> "GIF 实验功能每次生成一张动图"
        isAnimated && size != "auto" -> "GIF 实验功能使用自动帧尺寸"
        isAnimated && (frameCount !in 2..16 || frameDurationMs !in 20..2000 || frameDurationMs % 10 != 0) -> "GIF 支持 2～16 帧，每帧 20～2000 毫秒，10 毫秒一档"
        background !in BACKGROUNDS -> "不支持的背景设置"
        background == "transparent" && outputFormat == "jpeg" -> "透明背景需要 PNG 或 WebP 格式"
        compression !in 0..100 -> "压缩质量应为 0～100"
        moderation !in listOf("auto", "low") -> "不支持的内容审核设置"
        count !in 1..10 -> "一次可生成 1～10 张"
        else -> sizeError(size)
    }

    companion object {
        const val FLARE = "gpt-image-2.5-flare"
        const val SUNBURST = "gpt-image-2.5-sunburst"
        const val FLARE_26 = "gpt-image-2.6-flare"
        const val SUNBURST_26 = "gpt-image-2.6-sunburst"
        val MODELS = listOf(FLARE_26, SUNBURST_26, FLARE, SUNBURST)
        val COMPLEXITIES = listOf("low", "medium", "high")
        fun complexityLabelOf(value: String): String = when (value) {
            "low" -> "低"; "medium" -> "中"; "high" -> "高"; else -> value
        }
        fun labelOf(model: String): String = when (model) {
            FLARE_26 -> "GPT Image 2.6 Flare"
            SUNBURST_26 -> "GPT Image 2.6 Sunburst"
            FLARE -> "GPT Image 2.5 Flare"
            SUNBURST -> "GPT Image 2.5 Sunburst"
            else -> model
        }
        val QUALITIES = listOf("low", "medium", "high", "xhigh", "max", "auto")
        val RASTER_FORMATS = listOf("png", "jpeg", "webp")
        val FORMATS = RASTER_FORMATS + listOf("svg", "gif")
        val BACKGROUNDS = listOf("auto", "opaque", "transparent")
        const val DIMENSION_STEP = 16
        // Below 480 no aligned companion edge can satisfy both area and aspect ratio.
        const val MIN_SLIDER_DIMENSION = 480
        const val MAX_DIMENSION = 3840
        const val MIN_PIXELS = 655_360L
        const val MAX_PIXELS = 8_294_400L
        val SIZE_PRESETS = listOf("1024x1024", "1536x1024", "1024x1536", "1536x864", "864x1536",
            "3840x2160", "2160x3840", "auto")

        fun validSize(size: String): Boolean = size != "auto" && sizeError(size) == null

        fun sizeError(size: String): String? {
            if (size == "auto") return null
            if (!Regex("[1-9][0-9]{1,3}x[1-9][0-9]{1,3}").matches(size)) return "请输入完整的宽度和高度"
            val (w, h) = size.split('x').map(String::toInt)
            return when {
                w > MAX_DIMENSION || h > MAX_DIMENSION -> "每边最多 $MAX_DIMENSION 像素"
                w % DIMENSION_STEP != 0 || h % DIMENSION_STEP != 0 -> "宽和高都须为 $DIMENSION_STEP 的倍数"
                w > 3 * h || h > 3 * w -> "宽高比须在 1:3～3:1 之间"
                w.toLong() * h < MIN_PIXELS -> "总像素至少 655360，请增大宽度或高度"
                w.toLong() * h > MAX_PIXELS -> "总像素最多 8294400（3840 × 2160），请减小宽度或高度"
                else -> null
            }
        }
    }
}

@Serializable
data class OpenAiImageDraft(
    val params: OpenAiImageParams = OpenAiImageParams(),
    val references: List<ReferenceImage> = emptyList(),
)

@Serializable
data class OpenAiImageUsage(val textInput: Long, val imageInput: Long, val imageOutput: Long)

@Serializable
data class OpenAiImageSnapshot(
    val params: OpenAiImageParams,
    val usage: OpenAiImageUsage? = null,
    val responseSize: String? = null,
    val responseQuality: String? = null,
    val responseBackground: String? = null,
    val pricing: OpenAiImageRates? = null,
    val estimate: OpenAiImageEstimate? = null,
    val returnedImageCount: Int? = null,
) {
    // Null pricing belongs to legacy snapshots; these fixed constructor defaults preserve their rates.
    val estimatedUsd: Double? get() = if (params.outputFormat == "svg") null else usage?.let { (pricing ?: OpenAiImageRates()).cost(it) }
}

object OpenAiImageCodec {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun encode(snapshot: OpenAiImageSnapshot): String = json.encodeToString(snapshot)
    fun decode(raw: String?): OpenAiImageSnapshot? = raw?.let {
        runCatching { json.decodeFromString<OpenAiImageSnapshot>(it) }.getOrNull()
    }
}
