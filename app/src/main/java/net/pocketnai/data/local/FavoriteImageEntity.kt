package net.pocketnai.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * 收藏的图片。
 *
 * 单独开一张表而不是给 `generated_images` 加一列，理由与 `prompt_favorites` 相同：
 * 加列要动既有表（`ALTER TABLE` 本身安全，但每次动它都要重新核对 Room 的表校验），
 * 而收藏是个纯增量的功能，没有理由让既有表承担迁移风险。
 *
 * 外键指向 `generated_images` 并带 `ON DELETE CASCADE`：图片被物理删除时收藏行跟着消失，
 * 不需要在启动清理里再补一条"删掉指向已不存在图片的收藏"。删除生成记录走的是
 * "先标记 deletedAt、确认后才 purge"，而标记删除期间图片行仍在、收藏也仍在，
 * 撤销删除后收藏自然还在 —— 这正是用户期望的语义。
 *
 * 主键是**图片 id**（`generated_images.id`），不是生成记录 id：
 * 瀑布流与详情页都是"一张图"，一批四张里收藏其中一张是正常需求。
 */
@Entity(
    tableName = "favorite_images",
    foreignKeys = [
        ForeignKey(
            entity = GeneratedImageEntity::class,
            parentColumns = ["id"],
            childColumns = ["imageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class FavoriteImageEntity(
    @PrimaryKey val imageId: String,
    val createdAt: Long,
)
