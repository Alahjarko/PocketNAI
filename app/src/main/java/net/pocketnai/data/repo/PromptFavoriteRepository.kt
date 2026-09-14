package net.pocketnai.data.repo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import net.pocketnai.data.local.PromptFavoriteDao
import net.pocketnai.data.local.PromptFavoriteEntity
import net.pocketnai.domain.model.DEFAULT_FAVORITE_CATEGORY
import net.pocketnai.domain.model.PromptFavorite
import net.pocketnai.domain.model.PromptFavoriteKind
import net.pocketnai.domain.model.PromptTarget
import net.pocketnai.domain.prompt.PromptComposition
import java.util.UUID

/**
 * 收藏提示词的读写。
 *
 * 按规划书 8.4：首版只存本地，不访问 NovelAI 的故事或用户数据同步接口。
 * Token 与收藏内容没有任何关系，这里也不碰任何凭据。
 */
class PromptFavoriteRepository(
    private val dao: PromptFavoriteDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {

    fun observeFavorites(): Flow<List<PromptFavorite>> =
        dao.observeFavorites().map { rows -> rows.map(::toDomain) }

    /**
     * 新增一条收藏。
     *
     * 内容完全相同（同目标、同类型）时不重复插入 —— 反复点收藏不应该堆出一串一样的条目。
     * 返回是否真的写入了。
     */
    suspend fun add(
        kind: PromptFavoriteKind,
        content: String,
        name: String,
        category: String,
        target: PromptTarget,
    ): Boolean = withContext(Dispatchers.IO) {
        val trimmedContent = content.trim()
        if (trimmedContent.isEmpty()) return@withContext false

        val normalizedName = name.trim().ifEmpty {
            PromptComposition.defaultName(trimmedContent) ?: return@withContext false
        }
        val normalizedCategory = category.trim()

        if (dao.countSame(target = target.name, kind = kind.name, content = trimmedContent) > 0) {
            return@withContext false
        }

        val now = clock()
        dao.insert(
            PromptFavoriteEntity(
                id = idGenerator(),
                kind = kind.name,
                name = normalizedName,
                content = trimmedContent,
                category = normalizedCategory,
                target = target.name,
                createdAt = now,
                updatedAt = now,
                lastUsedAt = now,
            ),
        )
        true
    }

    /** 记录一次使用，用于把常用收藏排到前面。 */
    suspend fun markUsed(id: String) {
        withContext(Dispatchers.IO) { dao.markUsed(id, clock()) }
    }

    suspend fun delete(id: String) {
        withContext(Dispatchers.IO) { dao.delete(id) }
    }

    private fun toDomain(entity: PromptFavoriteEntity): PromptFavorite = PromptFavorite(
        id = entity.id,
        kind = PromptFavoriteKind.entries.firstOrNull { it.name == entity.kind }
            ?: PromptFavoriteKind.PROMPT,
        name = entity.name,
        content = entity.content,
        category = entity.category.ifEmpty { DEFAULT_FAVORITE_CATEGORY },
        target = PromptTarget.fromNameOrDefault(entity.target),
        createdAt = entity.createdAt,
        updatedAt = entity.updatedAt,
        lastUsedAt = entity.lastUsedAt,
    )
}
