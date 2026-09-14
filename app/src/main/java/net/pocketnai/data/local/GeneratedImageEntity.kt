package net.pocketnai.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 单张最终图片表（规划书 7.2）。
 *
 * 只保存文件索引与参数，不保存 PNG 二进制。
 * 删除生成记录时图片行级联删除；磁盘文件由文件存储层单独清理。
 */
@Entity(
    tableName = "generated_images",
    foreignKeys = [
        ForeignKey(
            entity = GenerationEntity::class,
            parentColumns = ["id"],
            childColumns = ["generationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("generationId")],
)
data class GeneratedImageEntity(
    @PrimaryKey val id: String,
    val generationId: String,
    /** 同批序号，从 1 开始，对应文件名 0001.png。 */
    val ordinal: Int,
    /** 仅固定 Seed 模式下非空；随机模式的真实 Seed 需解析 PNG 元数据（第二层能力）。 */
    val seed: Long?,
    /** 相对应用私有目录的相对路径，避免设备迁移后绝对路径失效。 */
    val relativePath: String,
    val width: Int,
    val height: Int,
    val byteSize: Long,
    val sha256: String,
    val metadataJson: String?,
    val createdAt: Long,
    /** 已复制到系统相册后的 MediaStore URI。 */
    val exportedUri: String?,
)
