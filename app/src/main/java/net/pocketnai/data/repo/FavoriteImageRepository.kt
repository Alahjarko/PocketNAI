package net.pocketnai.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import net.pocketnai.data.local.FavoriteImageDao
import net.pocketnai.data.local.FavoriteImageEntity

/**
 * 图片收藏。
 *
 * 与 [GenerationRepository] 分开：收藏是**围绕图片的一张关联表**，
 * 不参与生成链路（不碰请求、不碰文件、不碰历史写入）。塞进生成仓库会让
 * 那个本来就承担"网络 + 文件 + 数据库"三件事的类再长出一块无关的东西，
 * 而这里只有增删与两条查询。
 *
 * 外键带 `ON DELETE CASCADE`，因此图片被物理删除后收藏行自动消失，
 * 这里不需要任何清理逻辑（见 [FavoriteImageEntity]）。
 */
class FavoriteImageRepository(
    private val dao: FavoriteImageDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * 全部收藏的图片 id。
     *
     * 返回集合而不是列表：画廊要在滚动时对每张卡片判"是否收藏"，
     * 集合的判存在是 O(1)，列表则是每张卡片一次线性扫描。
     */
    fun observeFavoriteIds(): Flow<Set<String>> =
        dao.observeFavoriteImageIds().map { it.toSet() }

    /** 详情页只需要这一张的状态。 */
    fun observeIsFavorite(imageId: String): Flow<Boolean> = dao.observeIsFavorite(imageId)

    /** 收藏与取消收藏共用一个入口，避免两处各写一遍"该插还是该删"。 */
    suspend fun setFavorite(imageId: String, favorite: Boolean) {
        if (favorite) {
            dao.add(FavoriteImageEntity(imageId = imageId, createdAt = clock()))
        } else {
            dao.remove(imageId)
        }
    }
}
