package net.pocketnai.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AnlasTransactionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: AnlasTransactionEntity)

    @Query("SELECT * FROM anlas_transactions ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): Flow<List<AnlasTransactionEntity>>

    @Query("SELECT * FROM anlas_transactions ORDER BY createdAt DESC")
    suspend fun allTransactions(): List<AnlasTransactionEntity>

    @Query("DELETE FROM anlas_transactions")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM anlas_transactions")
    suspend fun count(): Int
}
