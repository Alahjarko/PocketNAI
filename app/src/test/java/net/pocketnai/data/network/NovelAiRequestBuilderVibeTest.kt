package net.pocketnai.data.network

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.pocketnai.domain.model.GenerationMode
import net.pocketnai.domain.model.GenerationParams
import net.pocketnai.domain.model.GenerationRequest
import net.pocketnai.domain.model.ImageModel
import net.pocketnai.domain.model.ModelCatalog
import net.pocketnai.domain.model.QualityTagsOption
import net.pocketnai.domain.model.ReferenceImage
import net.pocketnai.domain.model.ReferenceRole
import org.junit.Test

/**
 * Vibe Transfer 的请求体（`reference_*_multiple` 三个数组）。
 *
 * 与 Precise Reference 一样，数组按下标一一对应，缺项不能跳过。
 */
class NovelAiRequestBuilderVibeTest {

    private val v45 = ModelCatalog.profileOf(ImageModel.V4_5_CURATED)

    private fun vibe(
        id: String,
        ordinal: Int,
        strength: Double? = null,
        information: Double? = null,
    ) = ReferenceImage(
        id = id,
        role = ReferenceRole.VIBE,
        ordinal = ordinal,
        relativePath = "references/$id.png",
        width = 1000,
        height = 1000,
        byteSize = 100,
        sha256 = id,
        createdAt = 0L,
        strength = strength,
        informationExtracted = information,
        vibeRelativePath = "vibes/$id.vibe",
    )

    private fun params() = GenerationParams.defaultsFor(v45).copy(
        prompt = "1girl",
        negativePrompt = "lowres",
        qualityTags = QualityTagsOption.NONE,
    )

    private fun build(
        references: List<ReferenceImage>,
        upstream: Map<ReferenceRole, List<String>>,
        mode: GenerationMode = GenerationMode.TXT2IMG,
    ): JsonObject = NovelAiRequestBuilder.build(
        profile = v45,
        request = GenerationRequest(params = params(), mode = mode, references = references),
        upstreamImages = upstream,
    )

    private fun parameters(json: JsonObject) = json.getValue("parameters").jsonObject

    @Test
    fun `两个滑块与图片逐张对应`() {
        val parameters = parameters(
            build(
                listOf(
                    vibe("a", 0, strength = 0.3, information = 0.4),
                    vibe("b", 1, strength = 0.8, information = 0.9),
                ),
                mapOf(ReferenceRole.VIBE to listOf("A", "B")),
            ),
        )

        assertThat(
            parameters.getValue("reference_strength_multiple").jsonArray.map { it.jsonPrimitive.double },
        ).containsExactly(0.3, 0.8).inOrder()
        assertThat(
            parameters.getValue("reference_information_extracted_multiple").jsonArray
                .map { it.jsonPrimitive.double },
        ).containsExactly(0.4, 0.9).inOrder()
    }
}
