package net.pocketnai.domain.metadata

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import net.pocketnai.domain.model.ImageModel
import net.pocketnai.domain.model.NoiseSchedule
import net.pocketnai.domain.model.Sampler

/**
 * `Source` 里的模型哈希 → 我们的四个模型。
 *
 * ## 表从哪来
 * 2026-09-14 从官方网页前端 bundle 反解（模块 63509 的 `gVZ`），**并用本机数据库交叉验证过**：
 * 11 张真实生成图的 `Source` 与数据库里的 `modelApiId` 完全一致：
 *
 * | 数据库 modelApiId | Source |
 * |---|---|
 * | `nai-diffusion-4-5-full` | `NovelAI Diffusion V4.5 1229B44F` / `4BDE2A90` |
 * | `nai-diffusion-5-curated` | `NovelAI Diffusion V5 DB276663` |
 * | `nai-diffusion-5-full` | `NovelAI Diffusion V5 0ADF9AB7` |
 *
 * ## 为什么必须靠哈希
 * `Source` 里的人话部分（`NovelAI Diffusion V5`）**分不出 Curated 与 Full** ——
 * 两种档位的可读名字是一样的，只有哈希不同。猜档位会让"同 Seed 复现"直接失效。
 *
 * ## 认不出来怎么办
 * 保持当前模型不变，并把 `Source` 原文交给界面展示。**绝不静默映射**成某个 V4.5：
 * 那张图可能来自 V3、SDXL 甚至更早的模型，映射过去会让用户以为参数对上了。
 */
object NovelAiModelHashes {

    /** V5 Full 的哈希；其余 V5 哈希按官方默认走 Curated。 */
    private val V5_FULL = setOf("657484A5", "0ADF9AB7")

    private val V4_5_FULL = setOf("4BDE2A90", "1229B44F", "B9F340FD", "F3D95188")
    private val V4_5_CURATED = setOf("C02D4F98", "5AB81C7C", "B5A2A797")

    /** 更早的 V4 / SDXL 等：我们支持不了，但认得出来"它是哪个"以便如实告知。 */
    private val V4_FULL = setOf("37442FCA", "4F49EC75", "CA4B7203", "79F47848", "F6302A9D")
    private val V4_CURATED_PREVIEW = setOf("7ABFFA2A", "C1CCBA86", "770A9E12")

    /**
     * `Source` → 模型。
     *
     * 返回 null 表示"不是我们支持的四个模型之一"；调用方应保留 [source] 原文用于展示。
     */
    fun resolve(source: String?): ImageModel? {
        if (source.isNullOrBlank()) return null
        val hash = hashOf(source)

        if (source.contains("NovelAI Diffusion V5")) {
            return if (hash in V5_FULL) ImageModel.V5_FULL else ImageModel.V5_CURATED
        }

        val isV4Family = source.contains("NovelAI Diffusion V4") ||
            source.contains("NovelAI Diffusion V4.5") ||
            source.contains("DiffusionModelMetaName.NAIv4next")
        if (!isV4Family) return null

        return when {
            hash in V4_5_FULL -> ImageModel.V4_5_FULL
            hash in V4_5_CURATED -> ImageModel.V4_5_CURATED
            // 官方前端的 default 分支：只认得出"是 V4.5"时按 Curated 处理。
            hash !in V4_FULL && hash !in V4_CURATED_PREVIEW &&
                (source.contains("V4.5") || source.contains("NAIv4next")) ->
                ImageModel.V4_5_CURATED

            else -> null
        }
    }

    /** 取出 `Source` 末尾那一段 8 位十六进制哈希（`NovelAI Diffusion V5 0ADF9AB7`）。 */
    private fun hashOf(source: String): String? =
        source.trim().takeLastWhile { it.isLetterOrDigit() || it == '-' }
            .takeIf { it.length == HASH_LENGTH }
            ?.uppercase()

    private const val HASH_LENGTH = 8
}

/**
 * 把 PNG 文本块解析成 [NovelAiImageMetadata]。
 *
 * ## 纪律
 * - 只认已核对的字段名，**不做模糊猜测**；
 * - 任何类型不符都当作缺失（`as? JsonPrimitive` + `xxxOrNull`），不抛异常；
 * - **不保留 `Comment` 原文**，也不把它写进日志（AGENTS.md 的安全约束：
 *   元数据里可能有用户不想外泄的内容，日志只允许记长度/哈希）。
 */
object NovelAiMetadataParser {

    /** 关键字匹配：`Generation_time` 与 `Generation time` 都要认（PNG 与 WebP 写法不同）。 */
    private fun normalizeKeyword(keyword: String): String =
        keyword.lowercase().filter { it.isLetterOrDigit() }

    private const val KEY_SOFTWARE = "software"
    private const val KEY_SOURCE = "source"
    private const val KEY_DESCRIPTION = "description"
    private const val KEY_COMMENT = "comment"
    private const val KEY_GENERATION_TIME = "generationtime"

    /**
     * 解析。
     *
     * 返回 null 表示"这不是一张带 NovelAI 元数据的图"（没有 `Software: NovelAI`）。
     * 其余情况一律返回对象 —— 哪怕只读到一个 `Software` 字段，也好让界面说清楚
     * "是 NovelAI 的图，但参数不完整"。
     */
    fun parse(chunks: List<PngTextChunks.TextChunk>, json: Json): NovelAiImageMetadata? {
        val byKey = HashMap<String, String>(chunks.size)
        // 后面的同名块覆盖前面的：官方把 Comment 放在最前，重复的情况没见过，取最后一个更安全。
        chunks.forEach { byKey[normalizeKeyword(it.keyword)] = it.text }

        val software = byKey[KEY_SOFTWARE]
        // 判据与官方前端一致：只看 `Software` 里有没有 NovelAI。
        // 没有它就返回 null —— "这张图不是 NovelAI 生成的或元数据已被剥离"。
        if (software == null || !software.contains(NovelAiImageMetadata.SOFTWARE_MARKER, true)) {
            return null
        }

        val source = byKey[KEY_SOURCE]

        val commentText = byKey[KEY_COMMENT]
        val comment = commentText?.let { parseComment(it, json) }

        val description = byKey[KEY_DESCRIPTION]

        // 提示词优先级与官方前端一致：`Description` 是"用户提交的原文"，
        // 因此对带 Randomizer 的图它是模板原文（展开值在 actual_prompts 里）。
        // Comment.prompt 与 v4 caption 只作为回退 —— 实测三者对普通提示词完全相同。
        val prompt = firstNonBlank(
            description,
            comment?.string("prompt"),
            comment?.let { captionOf(it["v4_prompt"]) },
        )
        val negativePrompt = firstNonBlank(
            comment?.let { captionOf(it["v4_negative_prompt"]) },
            comment?.string("uc"),
        )
        val actualPrompt = firstNonBlank(
            comment?.obj("actual_prompts")?.obj("prompt")?.string("base_caption"),
            comment?.obj("actual_prompts")?.string("prompt"),
        )
        val actualNegativePrompt = firstNonBlank(
            comment?.obj("actual_prompts")?.obj("uc")?.string("base_caption"),
            comment?.obj("actual_prompts")?.string("uc"),
        )

        val sourceModel = NovelAiModelHashes.resolve(source)

        return NovelAiImageMetadata(
            software = software,
            source = source,
            description = description,
            generationTimeSeconds = byKey[KEY_GENERATION_TIME]?.toDoubleOrNull(),
            prompt = prompt,
            negativePrompt = negativePrompt,
            actualPrompt = actualPrompt,
            actualNegativePrompt = actualNegativePrompt,
            characters = charactersOf(comment),
            settings = settingsOf(comment, source, sourceModel),
            usedVibeReferences = (comment?.array("reference_strength_multiple")?.size ?: 0) > 0 ||
                comment?.string("reference_image") != null,
            usedDirectorReferences = (comment?.array("director_reference_strengths")?.size ?: 0) > 0,
            usedBaseImage = comment?.string("image") != null ||
                comment?.string("image_") != null ||
                comment?.bool("add_original_image") == true,
        )
    }

    /** `Comment` 不是合法 JSON 时返回 null：宁可"只认得出是 NovelAI 图"，也不要猜。 */
    private fun parseComment(text: String, json: Json): JsonObject? =
        runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()

    /** `v4_prompt.caption.base_caption`；不同版本可能把 caption 省略成字符串。 */
    private fun captionOf(element: JsonElement?): String? {
        val root = element as? JsonObject ?: return null
        val caption = root["caption"]
        return when (caption) {
            is JsonObject -> caption.string("base_caption")
            is JsonPrimitive -> caption.contentOrNull
            else -> null
        }
    }

    private fun charactersOf(comment: JsonObject?): List<ImportedCharacter> {
        val positive = charCaptionsOf(comment?.obj("v4_prompt"))
        if (positive.isEmpty()) return emptyList()
        val negative = charCaptionsOf(comment?.obj("v4_negative_prompt"))
        return positive.mapIndexed { index, caption ->
            val negativeCaption = negative.getOrNull(index)
            ImportedCharacter(
                prompt = caption.text,
                negativePrompt = negativeCaption?.text?.takeIf { it.isNotBlank() },
                centerX = caption.centerX,
                centerY = caption.centerY,
            )
        }
    }

    private data class RawCaption(val text: String, val centerX: Double?, val centerY: Double?)

    private fun charCaptionsOf(prompt: JsonObject?): List<RawCaption> {
        val captions = prompt?.obj("caption")?.array("char_captions") ?: return emptyList()
        return captions.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val text = item.string("char_caption") ?: return@mapNotNull null
            val center = (item.array("centers")?.firstOrNull() as? JsonObject)
            RawCaption(
                text = text,
                centerX = center?.double("x"),
                centerY = center?.double("y"),
            )
        }
    }

    private fun settingsOf(
        comment: JsonObject?,
        source: String?,
        sourceModel: ImageModel?,
    ): ImportedSettings {
        if (comment == null && source.isNullOrBlank()) return ImportedSettings.EMPTY

        val samplerRaw = comment?.string("sampler")
        val scheduleRaw = comment?.string("noise_schedule")

        return ImportedSettings(
            model = sourceModel,
            unsupportedModelSource = source?.takeIf { sourceModel == null && it.isNotBlank() },
            width = comment?.int("width"),
            height = comment?.int("height"),
            steps = comment?.int("steps"),
            guidance = comment?.double("scale"),
            cfgRescale = comment?.double("cfg_rescale"),
            sampler = samplerRaw?.let { Sampler.fromApiValue(it) },
            unsupportedSampler = samplerRaw?.takeIf { Sampler.fromApiValue(it) == null },
            noiseSchedule = scheduleRaw?.let { NoiseSchedule.fromApiValue(it) },
            unsupportedNoiseSchedule = scheduleRaw?.takeIf { NoiseSchedule.fromApiValue(it) == null },
            seed = comment?.long("seed"),
            sampleCount = comment?.int("n_samples"),
        )
    }

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }

    // ---- JSON 安全取值：类型不符一律当作缺失，绝不抛异常 ----

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String): Int? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        // 元数据里 steps 可能是 23 或 23.0，两种都要认；字符串形式不接受。
        if (primitive.isString) return null
        return primitive.intOrNull ?: primitive.doubleOrNull?.takeIf { it == it.toInt().toDouble() }
            ?.toInt()
    }

    private fun JsonObject.long(key: String): Long? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        return primitive.longOrNull ?: primitive.doubleOrNull?.takeIf { it == it.toLong().toDouble() }
            ?.toLong()
    }

    private fun JsonObject.double(key: String): Double? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        return primitive.doubleOrNull
    }

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray
}
