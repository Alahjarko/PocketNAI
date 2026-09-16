package net.pocketnai.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GallerySearchTest {

    private fun item(
        prompt: String = "1girl, silver hair, blue eyes",
        model: ImageModel? = ImageModel.V4_5_CURATED,
        mode: GenerationMode = GenerationMode.TXT2IMG,
        favorite: Boolean = false,
    ) = GalleryItem(
        imageId = "image-1",
        generationId = "generation-1",
        ordinal = 1,
        relativePath = "generations/generation-1/0001.png",
        width = 832,
        height = 1216,
        byteSize = 2048L,
        seed = 42L,
        exported = false,
        createdAt = 0L,
        status = GenerationStatus.SUCCEEDED,
        mode = mode,
        title = "1girl",
        model = model,
        prompt = prompt,
        negativePrompt = "lowres",
        sampleCount = 1,
        favorite = favorite,
    )

    @Test
    fun `空筛选条件放行所有图片`() {
        assertThat(GallerySearch.matches(item(), GalleryFilter.None)).isTrue()
    }

    @Test
    fun `关键词匹配正向提示词的子串`() {
        val filter = GalleryFilter(query = "silver")
        assertThat(GallerySearch.matches(item(), filter)).isTrue()
        assertThat(GallerySearch.matches(item(prompt = "1girl, red hair"), filter)).isFalse()
    }

    @Test
    fun `关键词忽略大小写`() {
        assertThat(GallerySearch.matches(item(prompt = "1Girl, SILVER hair"), GalleryFilter(query = "silver")))
            .isTrue()
    }

    @Test
    fun `下划线与空格互相等同`() {
        // NovelAI 的标签写 silver_hair，而用户手打时会写 silver hair。
        val filter = GalleryFilter(query = "silver hair")
        assertThat(GallerySearch.matches(item(prompt = "1girl, silver_hair"), filter)).isTrue()
        assertThat(GallerySearch.matches(item(prompt = "1girl, silver hair"), filter)).isTrue()
        // 反向同理：用下划线搜空格分隔的词也要命中。
        assertThat(
            GallerySearch.matches(
                item(prompt = "1girl, silver hair"),
                GalleryFilter(query = "silver_hair"),
            ),
        ).isTrue()
    }

    @Test
    fun `多个关键词是全部命中而不是任一命中`() {
        val filter = GalleryFilter(query = "silver blue")
        assertThat(GallerySearch.matches(item(), filter)).isTrue()
        assertThat(GallerySearch.matches(item(prompt = "1girl, silver hair"), filter)).isFalse()
    }

    @Test
    fun `关键词里的逗号按分隔符处理`() {
        assertThat(GallerySearch.matches(item(), GalleryFilter(query = "silver, blue"))).isTrue()
    }

    @Test
    fun `只有空白的关键词等同于没有关键词`() {
        assertThat(GallerySearch.matches(item(), GalleryFilter(query = "   "))).isTrue()
    }

    @Test
    fun `模型筛选只放行同一个模型`() {
        val filter = GalleryFilter(model = ImageModel.V5_FULL)
        assertThat(GallerySearch.matches(item(), filter)).isFalse()
        assertThat(GallerySearch.matches(item(model = ImageModel.V5_FULL), filter)).isTrue()
    }

    @Test
    fun `模式筛选只放行同一种模式`() {
        val filter = GalleryFilter(mode = GenerationMode.IMG2IMG)
        assertThat(GallerySearch.matches(item(), filter)).isFalse()
        assertThat(GallerySearch.matches(item(mode = GenerationMode.IMG2IMG), filter)).isTrue()
    }

    @Test
    fun `仅看收藏时未收藏的图片被挡掉`() {
        val filter = GalleryFilter(favoritesOnly = true)
        assertThat(GallerySearch.matches(item(favorite = false), filter)).isFalse()
        assertThat(GallerySearch.matches(item(favorite = true), filter)).isTrue()
    }

    @Test
    fun `多个条件同时生效`() {
        val filter = GalleryFilter(
            query = "silver",
            model = ImageModel.V4_5_CURATED,
            mode = GenerationMode.TXT2IMG,
            favoritesOnly = true,
        )
        assertThat(GallerySearch.matches(item(favorite = true), filter)).isTrue()
        // 任一条不满足就整体不匹配。
        assertThat(GallerySearch.matches(item(favorite = false), filter)).isFalse()
        assertThat(GallerySearch.matches(item(favorite = true, mode = GenerationMode.INPAINT), filter)).isFalse()
        assertThat(GallerySearch.matches(item(favorite = true, model = ImageModel.V5_FULL), filter)).isFalse()
        assertThat(GallerySearch.matches(item(favorite = true, prompt = "1girl"), filter)).isFalse()
    }

    @Test
    fun `isActive 只在真的加了条件时为真`() {
        assertThat(GalleryFilter.None.isActive).isFalse()
        assertThat(GalleryFilter(query = "  ").isActive).isFalse()
        assertThat(GalleryFilter(query = "cat").isActive).isTrue()
        assertThat(GalleryFilter(favoritesOnly = true).isActive).isTrue()
        assertThat(GalleryFilter(mode = GenerationMode.INPAINT).isActive).isTrue()
        assertThat(GalleryFilter(model = ImageModel.V5_CURATED).isActive).isTrue()
    }

    @Test
    fun `模型未知的图片不会被模型筛选误放行`() {
        // 认不出模型的旧记录（model = null）在按模型筛选时应当被挡掉，而不是当成"全部"。
        assertThat(
            GallerySearch.matches(
                item(model = null),
                GalleryFilter(model = ImageModel.V4_5_CURATED),
            ),
        ).isFalse()
    }
}
