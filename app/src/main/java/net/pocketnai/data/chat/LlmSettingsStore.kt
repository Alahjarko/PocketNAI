package net.pocketnai.data.chat

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import net.pocketnai.data.security.SecretBox
import net.pocketnai.domain.chat.LlmConfig
import net.pocketnai.domain.chat.ChatSearchConfig

class LlmSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("pocketnai_llm", Context.MODE_PRIVATE)
    private val box = SecretBox("pocketnai_llm_secret_key")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _config = MutableStateFlow(runCatching {
        json.decodeFromString<LlmConfig>(prefs.getString("config", "")!!)
    }.getOrDefault(LlmConfig()))
    val config = _config.asStateFlow()
    private val _searchConfig = MutableStateFlow(runCatching {
        json.decodeFromString<ChatSearchConfig>(prefs.getString("search_config", "")!!)
    }.getOrDefault(ChatSearchConfig()))
    val searchConfig = _searchConfig.asStateFlow()
    fun saveSearch(config: ChatSearchConfig, newKey: String = "") {
        val encrypted = newKey.trim().takeIf { it.isNotEmpty() }?.let(box::encrypt)
        val edit = prefs.edit().putString("search_config", json.encodeToString(config))
        encrypted?.let { edit.putString("search_key_ciphertext", it).putString("search_key_base_url", config.baseUrl.trim().trimEnd('/')) }
        if (encrypted == null && (config.baseUrl.trim().trimEnd('/') != _searchConfig.value.baseUrl.trim().trimEnd('/') || config.provider != _searchConfig.value.provider))
            edit.remove("search_key_ciphertext").remove("search_key_base_url")
        if (!edit.commit()) throw IllegalStateException("搜索配置保存失败")
        _searchConfig.value = config
    }
    fun searchKey(): String? {
        if (prefs.getString("search_key_base_url", null) != _searchConfig.value.baseUrl.trim().trimEnd('/')) return null
        return prefs.getString("search_key_ciphertext", null)?.let(box::decrypt)
    }
    fun hasSearchKey() = prefs.contains("search_key_ciphertext")
    fun clearSearchKey() { prefs.edit().remove("search_key_ciphertext").remove("search_key_base_url").commit() }
    fun save(config: LlmConfig, newKey: String = "") {
        val encrypted = newKey.trim().takeIf { it.isNotEmpty() }?.let(box::encrypt)
        val edit = prefs.edit().putString("config", json.encodeToString(config))
        encrypted?.let { edit.putString("key_ciphertext", it).putString("key_base_url", config.baseUrl.trim().trimEnd('/')) }
        if (encrypted == null && config.baseUrl.trim().trimEnd('/') != _config.value.baseUrl.trim().trimEnd('/')) {
            edit.remove("key_ciphertext").remove("key_base_url")
        }
        if (!edit.commit()) throw IllegalStateException("配置保存失败")
        _config.value = config
    }
    fun apiKey(): String? {
        val bound = prefs.getString("key_base_url", null)
        if (bound != null && bound != _config.value.baseUrl.trim().trimEnd('/')) return null
        return prefs.getString("key_ciphertext", null)?.let(box::decrypt)
    }
    fun hasKey(): Boolean = prefs.contains("key_ciphertext")
    fun clearKey() { prefs.edit().remove("key_ciphertext").remove("key_base_url").commit() }
}
