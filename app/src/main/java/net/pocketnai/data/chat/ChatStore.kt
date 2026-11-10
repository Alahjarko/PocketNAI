package net.pocketnai.data.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import net.pocketnai.data.local.ChatConversationDao
import net.pocketnai.data.local.ChatConversationEntity
import net.pocketnai.data.local.ChatDraftEntity
import net.pocketnai.domain.chat.ChatAttachment
import net.pocketnai.domain.chat.ChatConversation
import net.pocketnai.domain.chat.ChatEntry
import net.pocketnai.domain.chat.ChatFailure

class ChatStore(private val dao: ChatConversationDao) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun observeSummaries() = dao.observeSummaries()
    suspend fun rename(id: String, title: String) = dao.rename(id, title.trim().take(120).ifBlank { "新对话" })
    suspend fun pin(id: String, pinned: Boolean) = dao.pin(id, if (pinned) System.currentTimeMillis() else null)
    data class Draft(val text: String = "", val start: Int = 0, val end: Int = 0, val attachments: List<ChatAttachment> = emptyList())
    suspend fun draft(id: String): Draft = withContext(Dispatchers.IO) { dao.draft(id)?.let {
        Draft(it.text, it.selectionStart.coerceIn(0, it.text.length), it.selectionEnd.coerceIn(0, it.text.length),
            json.decodeFromString<List<ChatAttachment>>(it.attachmentsJson))
    } ?: Draft() }
    suspend fun saveDraft(chat: ChatConversation, draft: Draft) = withContext(Dispatchers.IO) {
        val row = if (draft.text.isEmpty() && draft.attachments.isEmpty()) null else
            ChatDraftEntity(chat.id, draft.text, draft.start, draft.end, json.encodeToString(draft.attachments))
        dao.putDraft(ChatConversationEntity(chat.id, chat.title, chat.updatedAt, "[]"), row)
    }
    suspend fun clearDraft(id: String) = dao.deleteDraft(id)
    suspend fun draftAttachments(): List<ChatAttachment> = withContext(Dispatchers.IO) {
        dao.allDrafts().flatMap { json.decodeFromString<List<ChatAttachment>>(it.attachmentsJson) }
    }
    suspend fun imageHistory() = withContext(Dispatchers.IO) {
        observeAll().first().flatMap { it.entries.asReversed().flatMap { entry -> entry.images } }.distinctBy { it.imageId }
    }
    suspend fun liveAttachmentIds() = withContext(Dispatchers.IO) {
        (observeAll().first().flatMap { it.entries }.flatMap { it.attachments } + draftAttachments()).map { it.id }.toSet()
    }
    fun observeAll() = flow {
        // 缓存归每个订阅所有，避免新 token/新消息让全部历史重新解析。
        val cache = mutableMapOf<String, Pair<ChatConversationEntity, ChatConversation>>()
        dao.observeAll().collect { rows ->
            cache.keys.retainAll(rows.map { it.id }.toSet())
            emit(rows.map { row ->
                cache[row.id]?.takeIf { it.first == row }?.second ?: ChatConversation(row.id, row.title, row.updatedAt,
                    runCatching { json.decodeFromString<List<ChatEntry>>(row.entriesJson) }.getOrDefault(emptyList()))
                    .also { cache[row.id] = row to it }
            })
        }
    }.flowOn(Dispatchers.IO)
    suspend fun load(id: String): ChatConversation? = withContext(Dispatchers.IO) { dao.find(id)?.let {
        ChatConversation(it.id, it.title, it.updatedAt, json.decodeFromString<List<ChatEntry>>(it.entriesJson))
    } }
    suspend fun save(chat: ChatConversation) = withContext(Dispatchers.IO) {
        val encoded = json.encodeToString(chat.entries)
        if (encoded.toByteArray(Charsets.UTF_8).size > 1536 * 1024) throw ChatFailure("对话记录过长，请新建对话；已保存的历史保留")
        dao.save(ChatConversationEntity(chat.id, chat.title, chat.updatedAt, encoded, dao.pinnedAt(chat.id)))
    }
    suspend fun delete(id: String) = dao.deleteWithDraft(id)
}
