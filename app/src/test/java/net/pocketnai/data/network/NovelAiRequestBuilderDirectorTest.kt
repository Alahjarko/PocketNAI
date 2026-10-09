package net.pocketnai.data.network

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.pocketnai.domain.model.DirectorReferenceKind
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
 * Precise Reference 的请求体（官方 `director_reference_*` 五个数组）。
 *
 * 重点：数组之间**按下标一一对应**，任何一处错位都会让"这个角色的强度"落到"那个角色"上，
 * 而服务端不会报错 —— 结果只是"参数好像没生效"。
 */
class NovelAiRequestBuilderDirectorTest {

    private val v45 = ModelCatalog.profileOf(ImageModel.V4_5_CURATED)

    private fun director(
        id: String,
        ordinal: Int,
        kind: DirectorReferenceKind? = DirectorReferenceKind.CHARACTER,
        strength: Double? = null,
        fidelity: Double? = null,
        information: Double? = null,
        width: Int = 1024,
        height: Int = 1536,
    ) = ReferenceImage(
        id = id,
        role = ReferenceRole.DIRECTOR,
        ordinal = ordinal,
        relativePath = "references/$id.png",
        width = width,
        height = height,
        byteSize = 1000,
        sha256 = id,
        createdAt = 0L,
        strength = strength,
        secondaryStrength = fidelity,
        informationExtracted = information,
        directorKind = kind,
    )

    private fun params() = GenerationParams.defaultsFor(v45).copy(
        prompt = "1girl",
        negativePrompt = "lowres",
        qualityTags = QualityTagsOption.NONE,
    )

    private fun build(
        references: List<ReferenceImage>,
        encoded: List<String>,
        mode: GenerationMode = GenerationMode.PRECISE_REFERENCE,
    ): JsonObject = NovelAiRequestBuilder.build(
        profile = v45,
        request = GenerationRequest(params = params(), mode = mode, references = references),
        upstreamImages = mapOf(ReferenceRole.DIRECTOR to encoded),
    )

    private fun parameters(json: JsonObject) = json.getValue("parameters").jsonObject

    @Test
    fun `三个数值数组与图片逐张对应`() {
        val json = build(
            listOf(
                director("a", 0, strength = 0.3, fidelity = 0.4, information = 0.5),
                director("b", 1, strength = 0.6, fidelity = 0.7, information = 0.8),
            ),
            listOf("A", "B"),
        )
        val parameters = parameters(json)

        fun values(key: String) = parameters.getValue(key).jsonArray.map { it.jsonPrimitive.double }

        assertThat(values("director_reference_strength_values")).containsExactly(0.3, 0.6).inOrder()
        assertThat(values("director_reference_secondary_strength_values")).containsExactly(0.4, 0.7).inOrder()
        assertThat(values("director_reference_information_extracted")).containsExactly(0.5, 0.8).inOrder()
    }
}
