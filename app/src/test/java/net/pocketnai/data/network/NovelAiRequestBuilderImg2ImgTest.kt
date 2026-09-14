package net.pocketnai.data.network

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.pocketnai.domain.model.GenerationMode
import net.pocketnai.domain.model.GenerationParams
import net.pocketnai.domain.model.GenerationRequest
import net.pocketnai.domain.model.ImageModel
import net.pocketnai.domain.model.ImageSizePreset
import net.pocketnai.domain.model.ModelCatalog
import net.pocketnai.domain.model.QualityTagsOption
import net.pocketnai.domain.model.ReferenceImage
import net.pocketnai.domain.model.ReferenceRole
import org.junit.Test

/**
 * 图生图请求体（《参考图功能规划书》阶段 B）。
 *
 * 两条纪律：
 * 1. **纯文生图的请求体必须与加入参考图之前逐字节一致** —— 新增字段最容易悄悄改变
 *    既有行为，所以这里用"整体字符串相等"来钉死，而不是逐字段抽查；
 * 2. 图生图只发官方确认过的字段（`image` / `strength`），默认值未知的字段一律不发，
 *    让服务端用它自己的默认值。
 */
class NovelAiRequestBuilderImg2ImgTest {

    private val profile = ModelCatalog.profileOf(ImageModel.V4_5_CURATED)

    /** 虚构的起点图 base64，与任何真实图片无关。 */
    private val fakeImage = "iVBORw0KGgoAAAANSUhEUg=="

    private fun params(size: ImageSizePreset = ImageSizePreset(1216, 832)) =
        GenerationParams.defaultsFor(profile).copy(
            prompt = "1girl, silver hair",
            negativePrompt = "lowres",
            size = size,
            qualityTags = QualityTagsOption.NONE,
        )

    private fun source(strength: Double?) = ReferenceImage(
        id = "ref-1",
        role = ReferenceRole.IMG2IMG,
        ordinal = 0,
        relativePath = "references/abc.png",
        width = 2560,
        height = 1440,
        byteSize = 1024,
        sha256 = "abc",
        createdAt = 0L,
        strength = strength,
    )

    private fun build(
        mode: GenerationMode = GenerationMode.IMG2IMG,
        image: String? = fakeImage,
        reference: ReferenceImage? = source(strength = 0.6),
    ): JsonObject = NovelAiRequestBuilder.build(
        profile = profile,
        request = GenerationRequest(
            params = params(),
            mode = mode,
            references = listOfNotNull(reference),
        ),
        sourceImageBase64 = image,
    )

    private fun parameters(json: JsonObject) = json.getValue("parameters").jsonObject

    // ---- 纯文生图不受影响 ----

    @Test
    fun `纯文生图的请求体与旧实现逐字节一致`() {
        // 这是本阶段最重要的一条：新增参考图不能让既有生成行为发生任何变化。
        val legacy = NovelAiRequestBuilder.build(profile, params())
        val viaRequest = NovelAiRequestBuilder.build(
            profile = profile,
            request = GenerationRequest(params = params()),
            sourceImageBase64 = null,
        )

        assertThat(viaRequest.toString()).isEqualTo(legacy.toString())
    }

    @Test
    fun `纯文生图不带 image 也不带 strength`() {
        val json = build(mode = GenerationMode.TXT2IMG, image = null, reference = null)

        assertThat(parameters(json)).doesNotContainKey("image")
        assertThat(parameters(json)).doesNotContainKey("strength")
    }

    @Test
    fun `图生图模式但没拿到图片时不发 image 字段`() {
        // 缺图时宁可不发字段（服务端按纯文生图处理），也不发空字符串 ——
        // 那会被判成参数错误，错误信息还指不到真正的原因。
        val json = build(image = null)

        assertThat(parameters(json)).doesNotContainKey("image")
        assertThat(parameters(json)).doesNotContainKey("strength")
    }

    // ---- 图生图字段 ----

    @Test
    fun `图生图带上起点图与 strength`() {
        val json = build()
        val parameters = parameters(json)

        assertThat(parameters.getValue("image").jsonPrimitive.content).isEqualTo(fakeImage)
        assertThat(parameters.getValue("strength").jsonPrimitive.double).isEqualTo(0.6)
    }

    /**
     * action 必须换成 `img2img`。
     *
     * 这是真机验证出来的：沿用 `generate` 时服务端返回 400
     * `image is not allowed for regular generations, use img2img or infill`。
     */
    @Test
    fun `图生图使用 img2img 这个 action`() {
        assertThat(build().getValue("action").jsonPrimitive.content).isEqualTo("img2img")
    }

    @Test
    fun `纯文生图仍然使用 generate 这个 action`() {
        val json = build(mode = GenerationMode.TXT2IMG, image = null, reference = null)

        assertThat(json.getValue("action").jsonPrimitive.content).isEqualTo("generate")
    }

    @Test
    fun `图生图模式但缺图时不换成 img2img action`() {
        // 换了 action 却没有 image，服务端同样会报参数错误。
        val json = build(image = null)

        assertThat(json.getValue("action").jsonPrimitive.content).isEqualTo("generate")
    }

    @Test
    fun `未设置 strength 时用模型默认值`() {
        val json = build(reference = source(strength = null))

        assertThat(parameters(json).getValue("strength").jsonPrimitive.double)
            .isEqualTo(profile.defaultImg2ImgStrength)
    }

    @Test
    fun `越界的 strength 会被夹取到 -0 至 1`() {
        assertThat(parameters(build(reference = source(strength = 5.0))).getValue("strength").jsonPrimitive.double)
            .isEqualTo(1.0)
        assertThat(parameters(build(reference = source(strength = -3.0))).getValue("strength").jsonPrimitive.double)
            .isEqualTo(0.0)
    }

    @Test
    fun `图生图的尺寸仍由 parameters 里的宽高决定`() {
        val json = NovelAiRequestBuilder.build(
            profile = profile,
            request = GenerationRequest(
                params = params(size = ImageSizePreset(832, 1216)),
                mode = GenerationMode.IMG2IMG,
                references = listOf(source(strength = 0.6)),
            ),
            sourceImageBase64 = fakeImage,
        )

        assertThat(parameters(json).getValue("width").jsonPrimitive.content).isEqualTo("832")
        assertThat(parameters(json).getValue("height").jsonPrimitive.content).isEqualTo("1216")
    }

    /**
     * 默认值未知的字段一律不发。
     *
     * `noise` / `extra_noise_seed` / `add_original_image` / `color_correct` 的官方默认值
     * 尚未核对；留空让服务端用它自己的默认值，比我们猜一个值更接近官方行为。
     * 将来核对清楚后如果决定暴露，需要同步改这一条断言。
     *
     * 注意 `img2img` 是 OpenAPI 里那个**嵌套对象**的名字（标注为 inpaint 使用），
     * 与顶层的 `image` / `strength` 不是一回事，因此这里必须仍然不发它。
     */
    @Test
    fun `图生图不擅自发送默认值未知的字段`() {
        val parameters = parameters(build())

        listOf("noise", "extra_noise_seed", "add_original_image", "color_correct", "img2img")
            .forEach { field ->
                assertThat(parameters).doesNotContainKey(field)
            }
    }
}
