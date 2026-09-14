package net.pocketnai.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PromptFavoriteDao {

    /**
     * 收藏列表：最近用过的排在前面，其次按创建时间倒序。
     *
     * "快速填入"的体验主要由这个排序决定 —— 常用的几条会稳定停在前几位。
     */
    @Query("SELECT * FROM prompt_favorites ORDER BY lastUsedAt DESC, createdAt DESC")
    fun observeFavorites(): Flow<List<PromptFavoriteEntity>>

    @Query("SELECT COUNT(*) FROM prompt_favorites")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: PromptFavoriteEntity)

    @Query("UPDATE prompt_favorites SET lastUsedAt = :usedAt WHERE id = :id")
    suspend fun markUsed(id: String, usedAt: Long)

    /** 同一目标的同名收藏视为重复，用来避免反复收藏同一条内容堆出一串一样的条目。 */
    @Query(
        """
        SELECT COUNT(*) FROM prompt_favorites
        WHERE target = :target AND kind = :kind AND content = :content
        """,
    )
    suspend fun countSame(target: String, kind: String, content: String): Int

    @Query("DELETE FROM prompt_favorites WHERE id = :id")
    suspend fun delete(id: String)
}
