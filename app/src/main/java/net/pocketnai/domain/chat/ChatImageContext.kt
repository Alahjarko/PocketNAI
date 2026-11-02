package net.pocketnai.domain.chat

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.pocketnai.domain.metadata.NovelAiImageMetadata
import net.pocketnai.domain.model.Generation

@Serializable
data class ChatContextCharacter(val prompt: String, val negativePrompt: String = "", val x: Double? = null, val y: Double? = null)

/** 仅生成参数白名单；不包含账户、凭据、路径、原始 Comment 或参考图文件。 */
@Serializable
data class ChatImageContext(
    val provider: String,
    val prompt: String,
    val negativePrompt: String = "",
    val model: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val steps: Int? = null,
    val guidance: Double? = null,
    val seed: Long? = null,
    val sampler: String? = null,
    val quality: String? = null,
    val characters: List<ChatContextCharacter> = emptyList(),
    val truncated: Boolean = false,
) {
    fun redacted(secret: String?): ChatImageContext {
        if (secret.isNullOrBlank()) return this
        fun clean(value: String) = value.replace(secret, "[API Key 已隐藏]")
        return copy(provider = clean(provider), prompt = clean(prompt), negativePrompt = clean(negativePrompt),
            model = model?.let(::clean), sampler = sampler?.let(::clean), quality = quality?.let(::clean),
            characters = characters.map { it.copy(prompt = clean(it.prompt), negativePrompt = clean(it.negativePrompt)) })
    }

    fun bounded(): ChatImageContext {
        fun clip(value: String, count: Int): String {
            var end = value.length.coerceAtMost(count)
            if (end in 1 until value.length && value[end - 1].isHighSurrogate() && value[end].isLowSurrogate()) end--
            return value.substring(0, end)
        }
        val result = copy(provider = clip(provider, 24), prompt = clip(prompt, 6000), negativePrompt = clip(negativePrompt, 3000),
            model = model?.let { clip(it, 120) }, sampler = sampler?.let { clip(it, 80) }, quality = quality?.let { clip(it, 32) },
            guidance = guidance?.takeIf { it.isFinite() },
            characters = characters.take(32).map { it.copy(prompt = clip(it.prompt, 256), negativePrompt = clip(it.negativePrompt, 128),
                x = it.x?.takeIf { value -> value.isFinite() }, y = it.y?.takeIf { value -> value.isFinite() }) })
        return result.copy(truncated = truncated || result != this)
    }

    fun asReferenceText(): String = Json.encodeToString(bounded())

    companion object {
        fun fromGeneration(generation: Generation, imageSeed: Long?, width: Int, height: Int): ChatImageContext {
            generation.openAi?.params?.let { p ->
                return ChatImageContext("OpenAI", p.prompt, model = p.model, width = width, height = height, quality = p.quality)
            }
            val p = generation.params
            return ChatImageContext("NovelAI", p.prompt, p.negativePrompt, p.model.apiModelId,
                width, height, p.steps, p.guidance, imageSeed, p.sampler.apiValue,
                characters = p.characters.filterNot { it.isBlank }.map { ChatContextCharacter(it.prompt, it.negativePrompt,
                    it.centerX.takeIf { _ -> p.useCharacterCoordinates }, it.centerY.takeIf { _ -> p.useCharacterCoordinates }) })
        }

        fun fromPng(metadata: NovelAiImageMetadata): ChatImageContext? {
            if (!metadata.hasImportableContent) return null
            val p = metadata.settings
            return ChatImageContext("NovelAI", metadata.actualPrompt ?: metadata.prompt.orEmpty(),
                metadata.actualNegativePrompt ?: metadata.negativePrompt.orEmpty(), p.model?.apiModelId,
                p.width, p.height, p.steps, p.guidance, p.seed, p.sampler?.apiValue,
                characters = metadata.characters.map { ChatContextCharacter(it.actualPrompt ?: it.prompt,
                    it.actualNegativePrompt ?: it.negativePrompt.orEmpty(), it.centerX, it.centerY) })
        }
    }
}
