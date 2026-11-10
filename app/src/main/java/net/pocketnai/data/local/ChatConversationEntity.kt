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
    val pinnedAt: Long? = null,
)

/** History never selects or decodes message bodies. */
data class ChatSummary(val id: String, val title: String, val updatedAt: Long, val pinnedAt: Long?, val hasDraft: Boolean)

@Entity(tableName = "chat_drafts")
data class ChatDraftEntity(
    @PrimaryKey val conversationId: String,
    val text: String,
    val selectionStart: Int,
    val selectionEnd: Int,
    val attachmentsJson: String,
)

@Dao
interface ChatConversationDao {
    @Query("SELECT c.id, c.title, c.updatedAt, c.pinnedAt, EXISTS(SELECT 1 FROM chat_drafts d WHERE d.conversationId = c.id) AS hasDraft FROM chat_conversations c ORDER BY c.pinnedAt DESC, c.updatedAt DESC, c.id")
    fun observeSummaries(): Flow<List<ChatSummary>>
    @Query("SELECT * FROM chat_conversations ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ChatConversationEntity>>
    @Query("SELECT * FROM chat_conversations WHERE id = :id")
    suspend fun find(id: String): ChatConversationEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entity: ChatConversationEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun ensureConversation(entity: ChatConversationEntity)
    @Query("UPDATE chat_conversations SET title = :title WHERE id = :id")
    suspend fun rename(id: String, title: String)
    @Query("UPDATE chat_conversations SET pinnedAt = :time WHERE id = :id")
    suspend fun pin(id: String, time: Long?)
    @Query("SELECT * FROM chat_drafts WHERE conversationId = :id")
    suspend fun draft(id: String): ChatDraftEntity?
    @Query("SELECT * FROM chat_drafts")
    suspend fun allDrafts(): List<ChatDraftEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveDraft(draft: ChatDraftEntity)
    @Query("DELETE FROM chat_drafts WHERE conversationId = :id")
    suspend fun deleteDraft(id: String)
    @Query("DELETE FROM chat_conversations WHERE id = :id")
    suspend fun delete(id: String)
}
