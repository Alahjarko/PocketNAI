package net.pocketnai.data.chat

import android.content.Context
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.pocketnai.data.security.SecretBox
import net.pocketnai.domain.chat.ChatFailure
import net.pocketnai.domain.chat.ChatProtocol
import net.pocketnai.domain.chat.ChatSearchConfig
import net.pocketnai.domain.chat.LlmConfig
import net.pocketnai.domain.chat.LlmModel
import net.pocketnai.domain.chat.LlmProvider
import net.pocketnai.domain.chat.LlmProviderCatalog
import java.security.MessageDigest
import java.util.UUID

class LlmSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("pocketnai_llm", Context.MODE_PRIVATE)
    private val box = SecretBox("pocketnai_llm_secret_key")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val state = MutableStateFlow(synchronized(LOCK) { loadState() })
    // Both .value projections read the same committed snapshot, including in synchronous collectors.
    val config: StateFlow<LlmConfig> = Projection(state) { it.config }
    val catalog: StateFlow<LlmProviderCatalog> = Projection(state) { it.catalog }
    /** Ciphertext-only edits still invalidate UI key-presence indicators. Never exposes the key. */
    val credentialRevision: StateFlow<Long> = Projection(state) { it.credentialRevision }
    val configurationError: StateFlow<String?> = Projection(state) {
        if (it.blocked) "已保存的对话配置无法读取，未覆盖原数据" else null
    }
    private val _searchConfig = MutableStateFlow(runCatching {
        json.decodeFromString<ChatSearchConfig>(prefs.getString("search_config", "")!!)
    }.getOrDefault(ChatSearchConfig()))
    val searchConfig = _searchConfig.asStateFlow()

    fun saveSearch(config: ChatSearchConfig, newKey: String = "") = synchronized(LOCK) {
        val encrypted = encrypt(newKey)
        val changes = mutableMapOf<String, String?>("search_config" to json.encodeToString(config))
        if (encrypted != null) {
            changes["search_key_ciphertext"] = encrypted
            changes["search_key_base_url"] = normalizeUrl(config.baseUrl)
        } else if (normalizeUrl(config.baseUrl) != normalizeUrl(_searchConfig.value.baseUrl) || config.provider != _searchConfig.value.provider) {
            changes["search_key_ciphertext"] = null
            changes["search_key_base_url"] = null
        }
        commit(changes)
        _searchConfig.value = config
    }

    fun searchKey(): String? = synchronized(LOCK) {
        if (prefs.getString("search_key_base_url", null) != normalizeUrl(_searchConfig.value.baseUrl)) return@synchronized null
        prefs.getString("search_key_ciphertext", null)?.let(box::decrypt)
    }

    fun hasSearchKey(): Boolean = synchronized(LOCK) { prefs.contains("search_key_ciphertext") }
    fun clearSearchKey() = synchronized(LOCK) {
        commit(mapOf("search_key_ciphertext" to null, "search_key_base_url" to null))
    }

    /** Compatibility entry point: update the selected model, preserving all other providers/models. */
    fun save(config: LlmConfig, newKey: String = "") = synchronized(LOCK) {
        val current = writableState()
        val selected = current.catalog.providers.firstOrNull { it.id == current.catalog.activeProviderId }
        val model = LlmModel(config.model, config.dialect, config.thinking, config.maxTokens)
        val models = selected?.models.orEmpty().toMutableList()
        val index = models.indexOfFirst { it.id == model.id }
        if (index < 0) models.add(model) else models[index] = model
        val provider = LlmProvider(selected?.id ?: UUID.randomUUID().toString(), selected?.name ?: "默认服务", normalizeUrl(config.baseUrl), models)
        val changes = keyChanges(provider, newKey)
        val providers = current.catalog.providers.toMutableList()
        val providerIndex = providers.indexOfFirst { it.id == provider.id }
        if (providerIndex < 0) providers.add(provider) else providers[providerIndex] = provider
        persist(LlmProviderCatalog(providers, provider.id, model.id), config, changes)
    }

    /** Shared preferences only: stale forms cannot overwrite a newly selected endpoint/model. */
    fun savePreferences(config: LlmConfig) = synchronized(LOCK) {
        val current = writableState()
        persist(current.catalog, current.config.copy(
            assistantName = config.assistantName,
            autoGenerate = config.autoGenerate,
            toolPermission = config.toolPermission,
        ))
    }

    fun saveProvider(provider: LlmProvider, newKey: String = "") = synchronized(LOCK) {
        val current = writableState()
        validateProvider(provider)
        validateProviderInput(provider)
        val normalized = provider.copy(name = provider.name.trim(), baseUrl = normalizeUrl(provider.baseUrl), models = provider.models.toList())
        val changes = keyChanges(normalized, newKey)
        val providers = current.catalog.providers.toMutableList()
        val index = providers.indexOfFirst { it.id == normalized.id }
        val previous = providers.getOrNull(index)
        if (index < 0) providers.add(normalized) else providers[index] = normalized
        var next = current.catalog.copy(providers = providers)
        // Only the first provider/model is automatically selected, never a second provider.
        if (current.catalog.providers.isEmpty() ||
            (current.catalog.providers.size == 1 && previous?.models?.isEmpty() == true && current.catalog.activeProviderId == null)
        ) {
            normalized.models.firstOrNull()?.let { next = next.copy(activeProviderId = normalized.id, activeModelId = it.id) }
        }
        if (next.activeProviderId == normalized.id && normalized.models.none { it.id == next.activeModelId }) {
            next = next.copy(activeProviderId = null, activeModelId = null)
        }
        persist(next, current.config, changes)
    }

    fun deleteProvider(id: String) = synchronized(LOCK) {
        validateId(id)
        val current = writableState()
        var next = current.catalog.copy(providers = current.catalog.providers.filterNot { it.id == id })
        if (next.activeProviderId == id) next = next.copy(activeProviderId = null, activeModelId = null)
        persist(next, current.config, providerSecretSlots(id).associateWith { null })
    }

    fun selectModel(providerId: String, modelId: String) = synchronized(LOCK) {
        val current = writableState()
        val provider = current.catalog.providers.firstOrNull { it.id == providerId }
            ?: throw ChatFailure("服务商不存在")
        if (provider.models.none { it.id == modelId }) throw ChatFailure("模型不存在")
        persist(current.catalog.copy(activeProviderId = providerId, activeModelId = modelId), current.config)
    }

    fun hasProviderKey(id: String): Boolean = synchronized(LOCK) {
        val current = state.value
        if (current.blocked) return@synchronized false
        val provider = current.catalog.providers.firstOrNull { it.id == id } ?: return@synchronized false
        prefs.contains(secretSlot(id, provider.baseUrl))
    }

    /** The caller must supply the endpoint it will actually use; a different URL never gets a key. */
    fun providerKey(id: String, baseUrl: String): String? = synchronized(LOCK) {
        val current = state.value
        if (current.blocked) return@synchronized null
        val provider = current.catalog.providers.firstOrNull { it.id == id } ?: return@synchronized null
        if (normalizeUrl(provider.baseUrl) != normalizeUrl(baseUrl)) return@synchronized null
        prefs.getString(secretSlot(id, baseUrl), null)?.let(box::decrypt)
    }

    fun apiKey(): String? = synchronized(LOCK) {
        val current = state.value
        val id = current.catalog.activeProviderId ?: return@synchronized null
        providerKey(id, current.config.baseUrl)
    }

    fun hasKey(): Boolean = synchronized(LOCK) {
        state.value.catalog.activeProviderId?.let(::hasProviderKey) ?: false
    }

    fun clearKey() = synchronized(LOCK) {
        val current = writableState()
        val id = current.catalog.activeProviderId ?: return@synchronized
        commit(providerSecretSlots(id).associateWith { null })
        state.value = current.copy(credentialRevision = state.value.credentialRevision + 1)
    }

    private fun keyChanges(provider: LlmProvider, newKey: String): Map<String, String?> {
        val slots = providerSecretSlots(provider.id)
        val target = secretSlot(provider.id, provider.baseUrl)
        // Check before encrypting, editing prefs, or publishing either flow.
        if (newKey.isBlank() && slots.any { it != target }) throw ChatFailure("更换服务地址后请填写新的 API Key")
        val encrypted = encrypt(newKey) ?: return emptyMap()
        return slots.associateWith<String, String?> { null }.toMutableMap().apply { put(target, encrypted) }
    }

    private fun encrypt(newKey: String): String? = try {
        newKey.trim().takeIf { it.isNotEmpty() }?.let(box::encrypt)
    } catch (_: Exception) {
        throw ChatFailure("密钥保存失败")
    }

    private fun writableState(): StoredState {
        val current = loadState()
        if (current.blocked) throw ChatFailure("已保存的对话配置无法读取，未覆盖原数据")
        return current
    }

    private fun persist(catalog: LlmProviderCatalog, preferences: LlmConfig, secrets: Map<String, String?> = emptyMap()) {
        validateCatalog(catalog)
        val config = effectiveConfig(catalog, preferences)
        commit(secrets + mapOf("provider_catalog" to json.encodeToString(catalog), "config" to json.encodeToString(config)))
        state.value = StoredState(catalog, config, credentialRevision = state.value.credentialRevision + if (secrets.isNotEmpty()) 1 else 0)
    }

    private fun loadState(): StoredState {
        // A malformed catalog is not an empty catalog. Keep the original bytes and refuse mutations.
        val fallback = runCatching {
            prefs.getString("config", null)?.let { json.decodeFromString<LlmConfig>(it) }
        }.getOrNull() ?: LlmConfig()
        return try {
            val storedConfig = prefs.getString("config", null)?.let { json.decodeFromString<LlmConfig>(it) } ?: LlmConfig()
            if (prefs.contains("provider_catalog")) {
                val catalog = json.decodeFromString<LlmProviderCatalog>(prefs.getString("provider_catalog", null) ?: error("catalog"))
                validateCatalog(catalog)
                StoredState(catalog, effectiveConfig(catalog, storedConfig))
            } else if (prefs.contains("config") || prefs.contains("key_ciphertext")) {
                val model = LlmModel(storedConfig.model, storedConfig.dialect, storedConfig.thinking, storedConfig.maxTokens)
                val provider = LlmProvider(UUID.randomUUID().toString(), "默认服务", normalizeUrl(storedConfig.baseUrl), listOf(model))
                val catalog = LlmProviderCatalog(listOf(provider), provider.id, model.id)
                val config = effectiveConfig(catalog, storedConfig)
                val changes = mutableMapOf<String, String?>(
                    "provider_catalog" to json.encodeToString(catalog), "config" to json.encodeToString(config),
                    "key_ciphertext" to null, "key_base_url" to null,
                )
                // Reuse the encrypted bytes directly, including legacy unbound keys. Never serialize plaintext.
                prefs.getString("key_ciphertext", null)?.let { ciphertext ->
                    val bound = prefs.getString("key_base_url", null) ?: provider.baseUrl
                    changes[secretSlot(provider.id, bound)] = ciphertext
                }
                commit(changes)
                StoredState(catalog, config)
            } else StoredState(LlmProviderCatalog(), storedConfig)
        } catch (_: Exception) {
            StoredState(LlmProviderCatalog(), fallback.copy(model = ""), blocked = true)
        }
    }

    private fun effectiveConfig(catalog: LlmProviderCatalog, preferences: LlmConfig): LlmConfig {
        val provider = catalog.providers.firstOrNull { it.id == catalog.activeProviderId }
        val model = provider?.models?.firstOrNull { it.id == catalog.activeModelId }
        val defaults = LlmConfig()
        return preferences.copy(
            baseUrl = provider?.baseUrl ?: defaults.baseUrl,
            model = model?.id ?: "",
            dialect = model?.dialect ?: defaults.dialect,
            thinking = model?.thinking ?: defaults.thinking,
            maxTokens = model?.maxTokens ?: defaults.maxTokens,
        )
    }

    private fun validateId(id: String) {
        if (!SAFE_ID.matches(id)) throw ChatFailure("服务商标识无效")
    }

    private fun validateProvider(provider: LlmProvider) {
        validateId(provider.id)
        if (provider.models.map { it.id }.distinct().size != provider.models.size) throw ChatFailure("模型标识不能重复")
    }

    /** New provider edits are strict; loading/migration must preserve legacy incomplete models. */
    private fun validateProviderInput(provider: LlmProvider) {
        if (provider.name.isBlank() || provider.name.length > 120 || provider.name.any(Char::isISOControl)) {
            throw ChatFailure("服务商名称须为 1–120 个字符")
        }
        ChatProtocol.endpoint(provider.baseUrl, "models")
        provider.models.forEach { model ->
            if (model.id.isBlank() || model.id.length > 512 || model.id.any(Char::isISOControl)) {
                throw ChatFailure("模型标识须为 1–512 个字符")
            }
            if (model.maxTokens !in 1024..65536) throw ChatFailure("最大输出 Tokens 须在 1024–65536 之间")
        }
    }

    private fun validateCatalog(catalog: LlmProviderCatalog) {
        catalog.providers.forEach(::validateProvider)
        if (catalog.providers.map { it.id }.distinct().size != catalog.providers.size) throw ChatFailure("服务商标识不能重复")
        if ((catalog.activeProviderId == null) != (catalog.activeModelId == null)) throw ChatFailure("模型选择无效")
        if (catalog.activeProviderId != null) {
            val provider = catalog.providers.firstOrNull { it.id == catalog.activeProviderId }
            if (provider == null || provider.models.none { it.id == catalog.activeModelId }) throw ChatFailure("模型选择无效")
        }
    }

    private fun providerSecretSlots(id: String): List<String> = prefs.all.keys.filter { it.startsWith("provider.$id.") }

    private fun secretSlot(id: String, baseUrl: String): String {
        validateId(id)
        val digest = MessageDigest.getInstance("SHA-256").digest(normalizeUrl(baseUrl).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "provider.$id.$digest.ciphertext"
    }

    /** All touched values are strings. Restore the in-memory prefs too if Android reports a failed disk write. */
    private fun commit(changes: Map<String, String?>) {
        val before = try { changes.keys.associateWith { prefs.getString(it, null) } } catch (_: Exception) {
            throw ChatFailure("配置保存失败")
        }
        fun write(values: Map<String, String?>): Boolean {
            val edit = prefs.edit()
            values.forEach { (key, value) -> if (value == null) edit.remove(key) else edit.putString(key, value) }
            return edit.commit()
        }
        if (!runCatching { write(changes) }.getOrDefault(false)) {
            runCatching { write(before) }
            throw ChatFailure("配置保存失败")
        }
    }

    private data class StoredState(
        val catalog: LlmProviderCatalog,
        val config: LlmConfig,
        val blocked: Boolean = false,
        val credentialRevision: Long = 0,
    )

    @OptIn(InternalCoroutinesApi::class)
    private class Projection<T>(private val source: StateFlow<StoredState>, private val select: (StoredState) -> T) : StateFlow<T> {
        override val value: T get() = select(source.value)
        override val replayCache: List<T> get() = listOf(value)
        override suspend fun collect(collector: FlowCollector<T>): Nothing {
            source.map(select).distinctUntilChanged().collect(collector)
            awaitCancellation()
        }
    }

    private companion object {
        val LOCK = Any()
        val SAFE_ID = Regex("[A-Za-z0-9_-]{1,128}")
        fun normalizeUrl(url: String) = url.trim().trimEnd('/')
    }
}
