package net.pocketnai.data.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import net.pocketnai.data.local.ChatConversationDao
import net.pocketnai.data.local.ChatConversationEntity
import net.pocketnai.domain.chat.ChatConversation
import net.pocketnai.domain.chat.ChatEntry
import net.pocketnai.domain.chat.ChatFailure

class ChatStore(private val dao: ChatConversationDao) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
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
        dao.save(ChatConversationEntity(chat.id, chat.title, chat.updatedAt, encoded))
    }
    suspend fun delete(id: String) = dao.delete(id)
}
