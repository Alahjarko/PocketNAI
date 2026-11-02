package net.pocketnai.domain.openai

import kotlinx.serialization.Serializable
import kotlin.math.ceil
import kotlin.math.round

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
    val officialRates = OpenAiImageRates()
    // Local planning allowance only: GPT Image 2.5 input-image tokenization is not published.
    const val REFERENCE_ALLOWANCE_USD = 0.05
    private val qualityGrid = mapOf("low" to 16, "medium" to 24, "high" to 48, "xhigh" to 64, "max" to 96)

    /** Official GPT Image 2.5 calculator, checked 2026-11-02; see docs/openai-pricing-2026-11-02.md. */
    fun outputTokens(params: OpenAiImageParams): Long? {
        if (params.model !in OpenAiImageParams.MODELS || !OpenAiImageParams.validSize(params.size)) return null
        val grid = qualityGrid[params.quality] ?: return null
        val (width, height) = params.dimensions
        val longEdge = maxOf(width, height)
        // Preserve the calculator's floating-point operation order, including near-half values.
        // round() uses ties-to-even, unlike roundToInt().
        val shortGrid = round(grid / (longEdge.toDouble() / minOf(width, height)))
        return ceil(grid * shortGrid * (2_000_000.0 + width.toLong() * height) / 4_000_000.0).toLong()
    }

    fun estimate(params: OpenAiImageParams, referenceCount: Int): OpenAiImageEstimate? {
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
