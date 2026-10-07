package net.pocketnai.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "artist_lab_runs")
data class ArtistLabRunEntity(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val configJson: String,
    val status: String,
    val message: String,
)

@Entity(tableName = "artist_lab_draws", foreignKeys = [
    ForeignKey(entity = ArtistLabRunEntity::class, parentColumns = ["id"], childColumns = ["runId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = GeneratedImageEntity::class, parentColumns = ["id"], childColumns = ["imageId"], onDelete = ForeignKey.SET_NULL),
], indices = [Index("runId"), Index("imageId")])
data class ArtistLabDrawEntity(
    @PrimaryKey val id: String,
    val runId: String,
    val ordinal: Int,
    val mixJson: String,
    val status: String,
    val generationId: String? = null,
    val imageId: String? = null,
    val message: String = "",
)

@Entity(tableName = "artist_lab_mix_favorites")
data class ArtistMixFavoriteEntity(@PrimaryKey val prompt: String, val createdAt: Long)

@Dao
interface ArtistLabDao {
    @Query("SELECT * FROM artist_lab_runs ORDER BY createdAt DESC")
    fun observeRuns(): Flow<List<ArtistLabRunEntity>>
    @Query("SELECT * FROM artist_lab_draws WHERE runId = :runId ORDER BY ordinal")
    fun observeDraws(runId: String): Flow<List<ArtistLabDrawEntity>>
    @Query("SELECT * FROM artist_lab_draws WHERE runId = :runId ORDER BY ordinal")
    suspend fun draws(runId: String): List<ArtistLabDrawEntity>
    @Query("SELECT * FROM artist_lab_draws WHERE status = 'RUNNING'")
    suspend fun interruptedDraws(): List<ArtistLabDrawEntity>
    @Query("SELECT * FROM artist_lab_runs WHERE id = :id")
    suspend fun run(id: String): ArtistLabRunEntity?
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRun(run: ArtistLabRunEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertDraws(draws: List<ArtistLabDrawEntity>)
    @Update
    suspend fun updateDraw(draw: ArtistLabDrawEntity)
    @Query("UPDATE artist_lab_runs SET status = :status, message = :message WHERE id = :id")
    suspend fun updateRun(id: String, status: String, message: String)
    @Query("UPDATE artist_lab_runs SET status = 'PAUSED', message = '上次运行已中断，请核对后手动继续' WHERE status = 'RUNNING'")
    suspend fun pauseInterruptedRuns()
    @Transaction
    suspend fun create(run: ArtistLabRunEntity, draws: List<ArtistLabDrawEntity>) {
        insertRun(run); insertDraws(draws)
    }
    @Query("SELECT * FROM artist_lab_mix_favorites ORDER BY createdAt DESC")
    fun observeMixFavorites(): Flow<List<ArtistMixFavoriteEntity>>
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun saveMix(favorite: ArtistMixFavoriteEntity)
    @Query("DELETE FROM artist_lab_mix_favorites WHERE prompt = :prompt")
    suspend fun removeMix(prompt: String)
}
