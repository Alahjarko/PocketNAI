package net.pocketnai.domain.prompt

import kotlin.random.Random
import net.pocketnai.domain.model.GenerationParams
import net.pocketnai.domain.model.PromptFavorite
import net.pocketnai.domain.model.applyQualityTags

/** Freeze local macros and legacy random choices once for every generation entry point. */
object PromptTemplates {
    data class Prepared(val positive: String, val negative: String)
    data class Frozen(val params: GenerationParams, val prepared: Prepared)

    fun resolve(params: GenerationParams, favorites: List<PromptFavorite>, random: Random): GenerationParams =
        prepare(params, favorites, random).params

    /** Web order: presets, macros in every caption, then automatic text extraction in the builder. */
    fun prepare(params: GenerationParams, favorites: List<PromptFavorite>, random: Random): Frozen {
        fun pair(raw: String, wrapped: String): Pair<String, String> {
            val text = PromptMacros.resolve(raw, favorites)
            val decorated = PromptMacros.resolve(wrapped, favorites)
            val seed = if (PromptRandomizer.hasOptions(text)) random.nextLong() else 0L
            return PromptRandomizer.resolve(text, Random(seed)) to PromptRandomizer.resolve(decorated, Random(seed))
        }
        val quality = applyQualityTags(params.prompt, params.qualityTags, params.model)
        val negative = NovelAiPromptPresets.resolveNegative(params.model, params.undesiredContentPresetIndex, quality, params.negativePrompt)
        val positivePair = pair(params.prompt, quality)
        val negativePair = pair(params.negativePrompt, negative)
        val resolved = params.copy(prompt = positivePair.first, negativePrompt = negativePair.first,
            characters = params.characters.map {
                it.copy(prompt = pair(it.prompt, it.prompt).first, negativePrompt = pair(it.negativePrompt, it.negativePrompt).first)
            })
        return Frozen(resolved, Prepared(positivePair.second, negativePair.second))
    }
}
