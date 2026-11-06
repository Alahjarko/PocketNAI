package net.pocketnai.domain.openai

import kotlinx.serialization.Serializable
import kotlin.math.ceil
import kotlin.math.round
import kotlin.math.roundToLong

/** Standard direct Images API prices; no Batch or Responses caching discount. */
@Serializable
data class OpenAiImageRates(
    val textInputPerMillion: Double = 5.0,
    val imageInputPerMillion: Double = 8.0,
    val imageOutputPerMillion: Double = 30.0,
    val source: String = "OpenAI · 2026-11-02",
) {
    fun cost(usage: OpenAiImageUsage): Double? {
        if (minOf(usage.textInput, usage.imageInput, usage.imageOutput) < 0 ||
            listOf(textInputPerMillion, imageInputPerMillion, imageOutputPerMillion).any { !it.isFinite() || it < 0 }) return null
        return (usage.textInput * textInputPerMillion + usage.imageInput * imageInputPerMillion +
            usage.imageOutput * imageOutputPerMillion) / 1_000_000
    }
}

/** A planning estimate, never an invoice or a spending cap. Amounts are unrounded USD. */
@Serializable
data class OpenAiImageEstimate(
    val outputMinPerImage: Double,
    val outputMaxPerImage: Double,
    val textInputPerImage: Double,
    val referenceAllowancePerImage: Double,
    val count: Int,
    val referenceCount: Int,
) {
    val minimumUsd: Double get() = (outputMinPerImage + textInputPerImage + referenceAllowancePerImage) * count
    val maximumUsd: Double get() = (outputMaxPerImage + textInputPerImage + referenceAllowancePerImage) * count
}

object OpenAiImagePricing {
    val officialRates = OpenAiImageRates(source = "OpenAI · 2026-11-05")
    // Local planning allowance only, not an official per-reference price.
    const val REFERENCE_ALLOWANCE_USD = 0.05
    private val qualityGrid = mapOf("low" to 16, "medium" to 24, "high" to 48, "xhigh" to 64, "max" to 96)

    /** Official 2.5/2.6 calculator, checked 2026-11-05; see docs/openai-image-26-2026-11-05.md. */
    fun outputTokens(params: OpenAiImageParams): Long? {
        if (params.model !in OpenAiImageParams.MODELS || !OpenAiImageParams.validSize(params.size)) return null
        val grid = qualityGrid[params.quality] ?: return null
        val (width, height) = params.dimensions
        val longEdge = maxOf(width, height)
        // Preserve the calculator's floating-point operation order, including near-half values.
        // round() uses ties-to-even, unlike roundToInt().
        val shortGrid = round(grid / (longEdge.toDouble() / minOf(width, height)))
        val base = ceil(grid * shortGrid * (2_000_000.0 + width.toLong() * height) / 4_000_000.0).toLong()
        if (!params.supportsComplexity) return base
        val multiplier = when (params.quality) { "low" -> 4.2; "medium" -> 3.3; else -> 1.0 }
        // The 2.6 multiplier is applied after ceil; JS Math.round uses positive half-up rounding.
        return (base * multiplier).roundToLong()
    }

    fun estimate(params: OpenAiImageParams, referenceCount: Int): OpenAiImageEstimate? {
        // Animation's total token budget and SVG authoring are not the still-image calculator.
        if (params.outputFormat in listOf("gif", "svg")) return null
        if (params.count !in 1..10 || referenceCount !in 0..16 || params.prompt.length > 32_000) return null
        val low = outputTokens(if (params.quality == "auto") params.copy(quality = "low") else params) ?: return null
        val high = outputTokens(if (params.quality == "auto") params.copy(quality = "max") else params) ?: return null
        // Lightweight mixed-language approximation, not a tokenizer. Include inputs per output as a
        // conservative batch budget; actual response usage is already request-wide and is never multiplied.
        val textTokens = ceil(params.prompt.toByteArray(Charsets.UTF_8).size / 3.0).toLong()
        return OpenAiImageEstimate(
            low * officialRates.imageOutputPerMillion / 1_000_000,
            high * officialRates.imageOutputPerMillion / 1_000_000,
            textTokens * officialRates.textInputPerMillion / 1_000_000,
            referenceCount * REFERENCE_ALLOWANCE_USD, params.count, referenceCount,
        )
    }
}
