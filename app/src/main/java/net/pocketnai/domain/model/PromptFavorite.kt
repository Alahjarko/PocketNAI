package net.pocketnai.domain.model

/** 收藏的两种粒度（规划书 8.4 的 Prompt Chunk）。 */
enum class PromptFavoriteKind {
    /** 整条提示词：保存提示词框的全部内容，用于快速切换整套配方。 */
    PROMPT,

    /** 单个标签/片段：从长提示词里挑出的一段，用于逐块拼装。 */
    TAG,
}

/** 收藏要填回哪个输入框。 */
enum class PromptTarget {
    POSITIVE,
    NEGATIVE,
    ;

    companion object {
        fun fromNameOrDefault(name: String?): PromptTarget =
            entries.firstOrNull { it.name == name } ?: POSITIVE
    }
}

/**
 * 一条本地收藏的提示词片段。
 *
 * 只存本地，不访问 NovelAI 的故事或用户数据同步接口（规划书 8.4）。
 * [lastUsedAt] 用于把最近用过的排在前面，让"快速填入"用起来真的快。
 */
data class PromptFavorite(
    val id: String,
    val kind: PromptFavoriteKind,
    val name: String,
    val content: String,
    /** 分组名；空字符串表示未分组。 */
    val category: String,
    val target: PromptTarget,
    val createdAt: Long,
    val updatedAt: Long,
    val lastUsedAt: Long,
)

/** 未分组收藏的显示名。 */
const val DEFAULT_FAVORITE_CATEGORY: String = "未分组"
