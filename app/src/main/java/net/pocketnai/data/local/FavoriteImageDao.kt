package net.pocketnai.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteImageDao {

    /**
     * 全部收藏的图片 id。
     *
     * 画廊一次性取回整份集合（而不是每张卡片查一次）：收藏量级是"用户手动点的"，
     * 几百条字符串在内存里判存在比发几百次查询便宜得多，
     * 而且瀑布流滚动时不会再碰数据库。
     */
    @Query("SELECT imageId FROM favorite_images")
    fun observeFavoriteImageIds(): Flow<List<String>>

    /** 详情页只关心这一张，单独查一次即可。 */
    @Query("SELECT EXISTS(SELECT 1 FROM favorite_images WHERE imageId = :imageId)")
    fun observeIsFavorite(imageId: String): Flow<Boolean>

    /**
     * 用 [OnConflictStrategy.IGNORE]：重复收藏同一个 id 是无操作。
     *
     * 收藏是幂等的开关，没有"后写覆盖先写"的语义，用 REPLACE 只会带来
     * SQLite "先 DELETE 再 INSERT" 那种不必要的写入。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(entity: FavoriteImageEntity)

    @Query("DELETE FROM favorite_images WHERE imageId = :imageId")
    suspend fun remove(imageId: String)

    @Query("SELECT COUNT(*) FROM favorite_images")
    suspend fun count(): Int
}
