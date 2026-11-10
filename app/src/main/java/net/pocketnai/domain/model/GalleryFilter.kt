package net.pocketnai.domain.model

/**
 * 画廊的筛选条件（技术决策记录第 23 节）。
 *
 * 四个维度互相独立、可同时生效；[GalleryFilter.None] 表示不筛选。
 */
data class GalleryFilter(
    /** 提示词关键词。见 [GallerySearch] 的匹配规则。 */
    val query: String = "",
    /** 按原始模型 API ID 筛选，同时支持 NovelAI 和 OpenAI。 */
    val modelApiIds: Set<String> = emptySet(),
    /** 只看某种生成模式（文生图 / 图生图 / Precise Reference / 局部重绘）。 */
    val mode: GenerationMode? = null,
    /** 只看收藏。 */
    val favoritesOnly: Boolean = false,
) {
    /** 是否加了任何条件。界面用它决定"没有结果"该怎么措辞。 */
    val isActive: Boolean
        get() = query.isNotBlank() || modelApiIds.isNotEmpty() || mode != null || favoritesOnly

    companion object {
        val None = GalleryFilter()
    }
}

/** 使用两种服务已有的模型定义，避免把 OpenAI 塞进 NovelAI 的参数枚举。 */
object GalleryModels {
    val apiIds: List<String> = ModelCatalog.models.map { it.apiModelId } +
        net.pocketnai.domain.openai.OpenAiImageParams.MODELS

    fun labelOf(apiId: String): String = ImageModel.fromApiModelId(apiId)?.displayName
        ?: if (apiId in net.pocketnai.domain.openai.OpenAiImageParams.MODELS)
            net.pocketnai.domain.openai.OpenAiImageParams(model = apiId).modelLabel else apiId
}

/**
 * 画廊筛选的匹配规则。
 *
 * 图片查询在数据库筛选后按需读取；GalleryQueries与此处共用关键词归一化。
 * 活动占位和超长检索回退仍使用这个纯函数，迁移/设备回归核对SQL语义一致。
 *
 * ## 关键词规则
 * - 大小写不敏感；
 * - `_` 与空格**互相等同**：NovelAI 的标签写 `silver_hair`，而用户手打时会写
 *   "silver hair"，不做归一化就永远搜不到；
 * - 空格分隔的多个词是**全部命中**（AND）而不是任一命中：搜 `silver blue`
 *   应当缩小范围，而不是把它当成一句必须原样出现的短语。
 */
object GallerySearch {

    fun matches(item: GalleryItem, filter: GalleryFilter): Boolean {
        if (filter.favoritesOnly && !item.favorite) return false
        if (filter.modelApiIds.isNotEmpty() && item.modelApiId !in filter.modelApiIds) return false
        if (filter.mode != null && item.mode != filter.mode) return false
        if (filter.query.isNotBlank() && !matchesQuery(item, filter.query)) return false
        return true
    }

    /** 尚无图片的任务也使用相同条件；未生成的图片不属于收藏。 */
    fun matches(generation: Generation, filter: GalleryFilter): Boolean {
        if (filter.favoritesOnly) return false
        val model = generation.openAi?.params?.model ?: generation.params.model.apiModelId
        if (filter.modelApiIds.isNotEmpty() && model !in filter.modelApiIds) return false
        if (filter.mode != null && generation.mode != filter.mode) return false
        val prompt = generation.openAi?.params?.prompt ?: generation.params.prompt
        return filter.query.isBlank() || terms(filter.query).all { normalize(prompt).contains(it) }
    }

    private fun matchesQuery(item: GalleryItem, query: String): Boolean {
        val haystack = normalize(item.prompt)
        return terms(query).all { haystack.contains(it) }
    }

    /** 关键词按空格切分，顺带支持用户顺手打出来的逗号。 */
    fun terms(query: String): List<String> =
        query.split(' ', ',', '\n', '\t', '，')
            .map { normalize(it) }
            .filter { it.isNotEmpty() }

    fun normalize(text: String): String =
        text.lowercase().replace('_', ' ')
}
