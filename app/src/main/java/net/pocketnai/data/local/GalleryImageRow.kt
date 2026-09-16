package net.pocketnai.data.local

import androidx.room.ColumnInfo
import androidx.room.Embedded

/** 生成记录 + 已有图片数，用于瀑布流里的占位卡片与详情页。 */
data class GenerationWithCount(
    @Embedded val generation: GenerationEntity,
    val imageCount: Int,
)

/**
 * 瀑布流卡片需要的联表投影：一次查询拿到图片与它所属任务的展示信息，
 * 避免在列表滚动时为每张卡片再查一次数据库。
 */
data class GalleryImageRow(
    val imageId: String,
    val generationId: String,
    val ordinal: Int,
    @ColumnInfo(name = "relative_path") val relativePath: String,
    val width: Int,
    val height: Int,
    @ColumnInfo(name = "byte_size") val byteSize: Long,
    val seed: Long?,
    @ColumnInfo(name = "exported_uri") val exportedUri: String?,
    @ColumnInfo(name = "image_created_at") val imageCreatedAt: Long,
    val status: String,
    /** [net.pocketnai.domain.model.GenerationMode] 的名字；v3 之前的记录为空。 */
    val mode: String?,
    val title: String,
    @ColumnInfo(name = "model_api_id") val modelApiId: String,
    val prompt: String,
    val negativePrompt: String,
    @ColumnInfo(name = "sample_count") val sampleCount: Int,
)
