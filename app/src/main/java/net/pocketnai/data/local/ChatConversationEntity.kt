package net.pocketnai.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "chat_conversations")
data class ChatConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val updatedAt: Long,
    /** 对话与图片索引，不含 API Key；思考及 tool_call_id 随上下文一起持久化。 */
    val entriesJson: String,
)

@Dao
interface ChatConversationDao {
    @Query("SELECT * FROM chat_conversations ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ChatConversationEntity>>
    @Query("SELECT * FROM chat_conversations WHERE id = :id")
    suspend fun find(id: String): ChatConversationEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entity: ChatConversationEntity)
    @Query("DELETE FROM chat_conversations WHERE id = :id")
    suspend fun delete(id: String)
}
