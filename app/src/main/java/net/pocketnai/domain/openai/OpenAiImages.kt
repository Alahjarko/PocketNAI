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
) {
    val modelLabel: String get() = if (model == SUNBURST) "GPT Image 2.5 Sunburst" else "GPT Image 2.5 Flare"
    val dimensions: Pair<Int, Int> get() = size.split('x').let {
        (it.getOrNull(0)?.toIntOrNull() ?: 1024) to (it.getOrNull(1)?.toIntOrNull() ?: 1024)
    }

    fun validate(): String? = when {
        model !in MODELS -> "请选择 Flare 或 Sunburst 模型"
        prompt.isBlank() || prompt.length > 32_000 -> "图片描述应为 1～32000 字符"
        quality !in QUALITIES -> "不支持的图片质量"
        outputFormat !in FORMATS -> "不支持的图片格式"
        background !in BACKGROUNDS -> "不支持的背景设置"
        background == "transparent" && outputFormat == "jpeg" -> "透明背景需要 PNG 或 WebP 格式"
        compression !in 0..100 -> "压缩质量应为 0～100"
        moderation !in listOf("auto", "low") -> "不支持的内容审核设置"
        count !in 1..10 -> "一次可生成 1～10 张"
        size != "auto" && !validSize(size) -> "自定义尺寸须为16的倍数，宽高比在1:3～3:1，每边不超过3840，总像素655360～8294400"
        else -> null
    }

    companion object {
        const val FLARE = "gpt-image-2.5-flare"
        const val SUNBURST = "gpt-image-2.5-sunburst"
        val MODELS = listOf(FLARE, SUNBURST)
        val QUALITIES = listOf("low", "medium", "high", "xhigh", "max", "auto")
        val FORMATS = listOf("png", "jpeg", "webp")
        val BACKGROUNDS = listOf("auto", "opaque", "transparent")
        fun validSize(size: String): Boolean {
            if (!Regex("[1-9][0-9]{1,3}x[1-9][0-9]{1,3}").matches(size)) return false
            val (w, h) = size.split('x').map(String::toInt)
            return w % 16 == 0 && h % 16 == 0 && w <= 3840 && h <= 3840 &&
                w.toLong() * h in 655_360L..8_294_400L && w <= 3 * h && h <= 3 * w
        }
    }
}

@Serializable
data class OpenAiImageDraft(
    val params: OpenAiImageParams = OpenAiImageParams(),
    val references: List<ReferenceImage> = emptyList(),
)

@Serializable
data class OpenAiImageUsage(val textInput: Long, val imageInput: Long, val imageOutput: Long) {
    /** Images API has no cached-input discount. This is a local token estimate, not an invoice. */
    val estimatedUsd: Double get() = (textInput * 5.0 + imageInput * 8.0 + imageOutput * 30.0) / 1_000_000
}

@Serializable
data class OpenAiImageSnapshot(
    val params: OpenAiImageParams,
    val usage: OpenAiImageUsage? = null,
    val responseSize: String? = null,
    val responseQuality: String? = null,
    val responseBackground: String? = null,
)

object OpenAiImageCodec {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun encode(snapshot: OpenAiImageSnapshot): String = json.encodeToString(snapshot)
    fun decode(raw: String?): OpenAiImageSnapshot? = raw?.let {
        runCatching { json.decodeFromString<OpenAiImageSnapshot>(it) }.getOrNull()
    }
}
