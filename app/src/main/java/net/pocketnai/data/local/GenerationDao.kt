package net.pocketnai.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface GenerationDao {

    /** 未删除的生成记录，按时间倒序，带图片数量。 */
    @Query(
        """
        SELECT g.*, (
            SELECT COUNT(*) FROM generated_images i WHERE i.generationId = g.id
        ) AS imageCount
        FROM generations g
        WHERE g.deletedAt IS NULL
        ORDER BY g.createdAt DESC
        """,
    )
    fun observeGenerations(): Flow<List<GenerationWithCount>>

    /** 瀑布流的图片卡片：只包含属于未删除记录、且状态为成功或部分成功的最终图片。 */
    @Query(
        """
        SELECT
            i.id AS imageId,
            i.generationId AS generationId,
            i.ordinal AS ordinal,
            i.relativePath AS relative_path,
            i.width AS width,
            i.height AS height,
            i.byteSize AS byte_size,
            i.seed AS seed,
            i.exportedUri AS exported_uri,
            i.createdAt AS image_created_at,
            g.status AS status,
            g.mode AS mode,
            g.title AS title,
            g.modelApiId AS model_api_id,
            g.prompt AS prompt,
            g.negativePrompt AS negativePrompt,
            g.sampleCount AS sample_count
        FROM generated_images i
        JOIN generations g ON g.id = i.generationId
        WHERE g.deletedAt IS NULL
        ORDER BY i.createdAt DESC, i.ordinal ASC
        """,
    )
    fun observeGalleryImages(): Flow<List<GalleryImageRow>>

    @Query("SELECT * FROM generations WHERE id = :generationId")
    suspend fun findGeneration(generationId: String): GenerationEntity?

    // ---- 参考图 ----
    //
    // 参考图与生成记录分开查询，而不是在画廊 / 历史的查询里 join：
    // 那两条查询已经被瀑布流依赖，加 join 会让"一张图对应多行"的语义无处安放。
    // 需要参考图的只有详情页与"复用参数"，按需查一次即可。

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReferences(entities: List<ReferenceImageEntity>)

    @Query("SELECT * FROM reference_images WHERE generationId = :generationId ORDER BY ordinal ASC")
    suspend fun findReferences(generationId: String): List<ReferenceImageEntity>

    /**
     * 所有仍被引用的参考图 / vibe 文件路径，供启动清理使用。
     *
     * 返回的是"路径集合"而不是文件名：文件商店按相对路径判活，
     * 这样它不需要知道内容寻址的命名规则。
     */
    @Query(
        """
        SELECT relativePath FROM reference_images
        UNION
        SELECT vibeRelativePath FROM reference_images WHERE vibeRelativePath IS NOT NULL
        """,
    )
    suspend fun allReferencePaths(): List<String>

    @Query("SELECT * FROM generated_images WHERE generationId = :generationId ORDER BY ordinal ASC")
    suspend fun findImages(generationId: String): List<GeneratedImageEntity>

    @Query("SELECT * FROM generated_images WHERE id = :imageId")
    suspend fun findImage(imageId: String): GeneratedImageEntity?

    @Query("SELECT id FROM generations")
    suspend fun allGenerationIds(): List<String>

    @Query("SELECT * FROM generations WHERE status = :status AND deletedAt IS NULL")
    suspend fun findGenerationsByStatus(status: String): List<GenerationEntity>

    /**
     * 刻意用 [OnConflictStrategy.IGNORE] 而不是 REPLACE。
     *
     * SQLite 的 REPLACE 实现是“先 DELETE 再 INSERT”，而 `generated_images` 对
     * `generations` 有 ON DELETE CASCADE —— 一旦对已存在的生成记录重插一次，
     * 该记录下所有图片行都会被级联删除。用 IGNORE 时重复插入是无操作，
     * 已存在的主键保持原样，不会波及子表。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun upsertGeneration(entity: GenerationEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun upsertImages(entities: List<GeneratedImageEntity>)

    @Query(
        """
        UPDATE generations
        SET status = :status,
            updatedAt = :updatedAt,
            errorCode = :errorCode,
            error_message = :errorMessage,
            correlationId = :correlationId
        WHERE id = :generationId
        """,
    )
    suspend fun updateStatus(
        generationId: String,
        status: String,
        updatedAt: Long,
        errorCode: String?,
        errorMessage: String?,
        correlationId: String?,
    )

    @Query("UPDATE generated_images SET exportedUri = :exportedUri WHERE id = :imageId")
    suspend fun updateExportedUri(imageId: String, exportedUri: String?)

    /** 删除第一步：只打标记，界面立即隐藏，文件与记录都还在，因此可以撤销。 */
    @Query("UPDATE generations SET deletedAt = :deletedAt, updatedAt = :deletedAt WHERE id = :generationId")
    suspend fun markDeleted(generationId: String, deletedAt: Long)

    @Query("UPDATE generations SET deletedAt = NULL, updatedAt = :updatedAt WHERE id = :generationId")
    suspend fun restore(generationId: String, updatedAt: Long)

    /** 删除第二步：确认无法撤销后，物理删除记录（图片行级联删除）。 */
    @Query("DELETE FROM generations WHERE id = :generationId")
    suspend fun purgeGeneration(generationId: String)

    @Query("SELECT id FROM generations WHERE deletedAt IS NOT NULL AND deletedAt < :before")
    suspend fun findDeletedBefore(before: Long): List<String>

    @Query("SELECT id FROM generations WHERE deletedAt IS NOT NULL")
    suspend fun allDeletedIds(): List<String>

    @Query("SELECT COUNT(*) FROM generations")
    suspend fun countGenerations(): Int

    @Query("SELECT * FROM generated_images WHERE id NOT IN (SELECT imageId FROM favorite_images)")
    suspend fun allUnfavoritedImages(): List<GeneratedImageEntity>

    @Query("SELECT * FROM generated_images")
    suspend fun allImages(): List<GeneratedImageEntity>

    @Query("DELETE FROM generated_images WHERE id IN (:imageIds)")
    suspend fun deleteImages(imageIds: List<String>)

    @Query(
        """
        SELECT g.id FROM generations g
        WHERE (SELECT COUNT(*) FROM generated_images i WHERE i.generationId = g.id) = 0
        """,
    )
    suspend fun findEmptyGenerationIds(): List<String>

    @Query("DELETE FROM generations WHERE id IN (:generationIds)")
    suspend fun purgeGenerations(generationIds: List<String>)

    @Query("DELETE FROM generations")
    suspend fun purgeAllGenerations()
}
