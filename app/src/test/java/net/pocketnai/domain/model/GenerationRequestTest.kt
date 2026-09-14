package net.pocketnai.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

/**
 * 参考图相关的参数校验与存储降级（《参考图功能规划书》5.1）。
 *
 * 这里守的是"什么情况下**不允许**提交"：选了图生图却没有起点图、挂了超过上限的参考图、
 * 参考图文件已经被清理掉。这些若放过去，用户会拿到一张空白图或一句笼统的参数错误。
 */
class GenerationRequestTest {

    private val profile = ModelCatalog.profileOf(ImageModel.V4_5_CURATED)

    private lateinit var params: GenerationParams

    @Before
    fun setUp() {
        params = GenerationParams.defaultsFor(profile).copy(prompt = "1girl")
    }

    private fun reference(
        id: String,
        role: ReferenceRole,
        ordinal: Int = 0,
    ) = ReferenceImage(
        id = id,
        role = role,
        ordinal = ordinal,
        relativePath = "references/$id.png",
        width = 832,
        height = 1216,
        byteSize = 1024,
        sha256 = id,
        createdAt = 0L,
    )

    // ---- 模式与起点 ----

    @Test
    fun `纯文生图没有任何参考图也是合法的`() {
        val request = GenerationRequest(params = params)

        assertThat(request.validate(profile)).isEmpty()
    }

    @Test
    fun `图生图缺少起点图时不允许提交`() {
        val request = GenerationRequest(
            params = params,
            mode = GenerationMode.IMG2IMG,
        )

        assertThat(request.validate(profile))
            .containsExactly(ReferenceViolation.MissingImg2ImgSource)
    }

    @Test
    fun `图生图带上起点图就合法`() {
        val request = GenerationRequest(
            params = params,
            mode = GenerationMode.IMG2IMG,
            references = listOf(reference("a", ReferenceRole.IMG2IMG)),
        )

        assertThat(request.validate(profile)).isEmpty()
        assertThat(request.img2imgSource?.id).isEqualTo("a")
    }

    @Test
    fun `起点图只能有一张`() {
        val request = GenerationRequest(
            params = params,
            mode = GenerationMode.IMG2IMG,
            references = listOf(
                reference("a", ReferenceRole.IMG2IMG, ordinal = 0),
                reference("b", ReferenceRole.IMG2IMG, ordinal = 1),
            ),
        )

        assertThat(request.validate(profile))
            .contains(ReferenceViolation.TooMany(ReferenceRole.IMG2IMG, count = 1, limit = 1))
    }

    // ---- 数量上限 ----

    @Test
    fun `Vibe 参考图超过上限时不允许提交`() {
        val tooMany = (0..profile.maxVibeReferences).map {
            reference("vibe-$it", ReferenceRole.VIBE, ordinal = it)
        }

        val request = GenerationRequest(params = params, references = tooMany)

        assertThat(request.validate(profile)).contains(
            ReferenceViolation.TooMany(
                role = ReferenceRole.VIBE,
                count = profile.maxVibeReferences + 1,
                limit = profile.maxVibeReferences,
            ),
        )
    }

    @Test
    fun `恰好达到上限是允许的`() {
        val atLimit = (0 until profile.maxVibeReferences).map {
            reference("vibe-$it", ReferenceRole.VIBE, ordinal = it)
        }

        assertThat(GenerationRequest(params = params, references = atLimit).validate(profile))
            .isEmpty()
    }

    @Test
    fun `Precise Reference 超过上限时不允许提交`() {
        val tooMany = (0..profile.maxDirectorReferences).map {
            reference("cr-$it", ReferenceRole.DIRECTOR, ordinal = it)
        }

        val request = GenerationRequest(params = params, references = tooMany)

        assertThat(request.validate(profile)).contains(
            ReferenceViolation.TooMany(
                role = ReferenceRole.DIRECTOR,
                count = profile.maxDirectorReferences + 1,
                limit = profile.maxDirectorReferences,
            ),
        )
    }

    // ---- 文件缺失 ----

    @Test
    fun `参考图文件已不在本机时不允许提交`() {
        val request = GenerationRequest(
            params = params,
            references = listOf(reference("gone", ReferenceRole.VIBE)),
        )

        val violations = request.validate(profile) { it.id != "gone" }

        assertThat(violations).containsExactly(ReferenceViolation.FileMissing("gone"))
    }

    // ---- 顺序与分组 ----

    @Test
    fun `按角色取参考图时按顺序号排序`() {
        val request = GenerationRequest(
            params = params,
            references = listOf(
                reference("vibe-2", ReferenceRole.VIBE, ordinal = 2),
                reference("vibe-0", ReferenceRole.VIBE, ordinal = 0),
                reference("cr", ReferenceRole.DIRECTOR, ordinal = 0),
                reference("vibe-1", ReferenceRole.VIBE, ordinal = 1),
            ),
        )

        assertThat(request.referencesOf(ReferenceRole.VIBE).map { it.id })
            .containsExactly("vibe-0", "vibe-1", "vibe-2")
            .inOrder()
        assertThat(request.referencesOf(ReferenceRole.DIRECTOR).map { it.id })
            .containsExactly("cr")
    }

    // ---- 存储降级 ----

    @Test
    fun `缺少模式列的旧记录按纯文生图解读`() {
        // v4 之前落库的记录没有 mode 列，升级后必须仍是"纯文生图"，
        // 否则老历史的详情页会显示成图生图。
        assertThat(GenerationMode.fromNameOrDefault(null)).isEqualTo(GenerationMode.TXT2IMG)
        assertThat(GenerationMode.fromNameOrDefault("")).isEqualTo(GenerationMode.TXT2IMG)
        assertThat(GenerationMode.fromNameOrDefault("不存在的模式")).isEqualTo(GenerationMode.TXT2IMG)
        assertThat(GenerationMode.fromNameOrDefault("IMG2IMG")).isEqualTo(GenerationMode.IMG2IMG)
    }

    @Test
    fun `Director 的取用方式按 API 取值解析并降级`() {
        assertThat(DirectorReferenceKind.fromApiValueOrDefault("character"))
            .isEqualTo(DirectorReferenceKind.CHARACTER)
        assertThat(DirectorReferenceKind.fromApiValueOrDefault("character&style"))
            .isEqualTo(DirectorReferenceKind.CHARACTER_AND_STYLE)
        // 取值无法识别时按只取角色处理：这个方向不会把画风也一起带进请求。
        assertThat(DirectorReferenceKind.fromApiValueOrDefault(null))
            .isEqualTo(DirectorReferenceKind.CHARACTER)
    }
}
