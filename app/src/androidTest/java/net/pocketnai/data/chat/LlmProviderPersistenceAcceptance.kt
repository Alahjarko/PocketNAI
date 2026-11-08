package net.pocketnai.data.chat

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.pocketnai.data.security.SecretBox
import net.pocketnai.domain.chat.ChatFailure
import net.pocketnai.domain.chat.ChatSearchConfig
import net.pocketnai.domain.chat.ChatToolPermission
import net.pocketnai.domain.chat.LlmConfig
import net.pocketnai.domain.chat.LlmDialect
import net.pocketnai.domain.chat.LlmModel
import net.pocketnai.domain.chat.LlmProvider
import java.util.UUID

/** Called by an existing acceptance test; no new test-method budget or real network/credentials. */
internal fun verifyLlmProviderPersistence(base: Context) {
    val prefix = "provider-acceptance-${UUID.randomUUID()}-"
    val backing = base.getSharedPreferences(prefix + "pocketnai_llm", Context.MODE_PRIVATE)
    var failNextCommit = false
    val prefs = object : SharedPreferences by backing {
        override fun edit(): SharedPreferences.Editor {
            val editor = backing.edit()
            return object : SharedPreferences.Editor by editor {
                override fun commit(): Boolean {
                    val result = editor.commit()
                    if (failNextCommit) {
                        failNextCommit = false
                        return false // Android may have changed the in-memory map even when disk writing fails.
                    }
                    return result
                }
            }
        }
    }
    val context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            check(name == "pocketnai_llm")
            return prefs
        }
    }
    val json = Json { encodeDefaults = true }
    fun rejected(block: () -> Unit) {
        assertThat(runCatching(block).exceptionOrNull()).isInstanceOf(ChatFailure::class.java)
    }
    try {
        val legacyKey = "offline-provider-legacy-placeholder"
        val legacy = LlmConfig(
            baseUrl = "https://legacy.example/api/", model = "", dialect = LlmDialect.KIMI,
            thinking = false, maxTokens = 4096, autoGenerate = true, assistantName = "共同人格",
            toolPermission = ChatToolPermission.FULL,
        )
        val ciphertext = SecretBox("pocketnai_llm_secret_key").encrypt(legacyKey)
        val original = json.encodeToString(legacy)
        assertThat(backing.edit().putString("config", original).putString("key_ciphertext", ciphertext).commit()).isTrue()

        failNextCommit = true
        val failedMigration = LlmSettingsStore(context)
        assertThat(failedMigration.catalog.value.providers).isEmpty()
        assertThat(backing.getString("config", null)).isEqualTo(original)
        assertThat(backing.getString("key_ciphertext", null)).isEqualTo(ciphertext)
        assertThat(backing.contains("provider_catalog")).isFalse()

        val store = LlmSettingsStore(context)
        val first = store.catalog.value.providers.single()
        assertThat(first.models.single()).isEqualTo(LlmModel("", LlmDialect.KIMI, false, 4096))
        assertThat(store.catalog.value.activeModelId).isEqualTo("")
        assertThat(store.config.value).isEqualTo(legacy.copy(baseUrl = legacy.baseUrl.trimEnd('/')))
        assertThat(store.apiKey()).isEqualTo(legacyKey)
        assertThat(backing.contains("key_ciphertext")).isFalse()
        assertThat(backing.all.values).contains(ciphertext)
        assertThat(backing.getString("provider_catalog", null)).doesNotContain(legacyKey)
        assertThat(backing.getString("config", null)).doesNotContain(legacyKey)

        store.saveProvider(first.copy(models = listOf(LlmModel("shared-model", LlmDialect.KIMI, false, 4096))))
        store.selectModel(first.id, "shared-model")
        val second = LlmProvider("second-provider", "第二服务", "https://second.example/prefix/", listOf(
            LlmModel("shared-model", LlmDialect.DEEPSEEK, true, 8192), LlmModel("other-model", thinking = false),
        ))
        val secondKey = "offline-provider-second-placeholder"
        store.saveProvider(second, secondKey)
        assertThat(store.catalog.value.activeProviderId).isEqualTo(first.id)
        assertThat(store.apiKey()).isEqualTo(legacyKey)
        assertThat(store.hasProviderKey(second.id)).isTrue()
        assertThat(store.providerKey(second.id, " https://second.example/prefix/// ")).isEqualTo(secondKey)
        assertThat(store.providerKey(second.id, "https://second.example/elsewhere")).isNull()
        assertThat(store.providerKey("missing", second.baseUrl)).isNull()
        val previousRevision = store.credentialRevision.value
        val sameCatalog = store.catalog.value
        store.saveProvider(second, secondKey)
        assertThat(store.catalog.value).isEqualTo(sameCatalog)
        assertThat(store.credentialRevision.value).isGreaterThan(previousRevision)

        val before = backing.all.toMap()
        val beforeCatalog = store.catalog.value
        rejected { store.saveProvider(second.copy(baseUrl = "https://elsewhere.example/api")) }
        assertThat(backing.all).isEqualTo(before)
        assertThat(store.catalog.value).isEqualTo(beforeCatalog)
        rejected { store.saveProvider(second.copy(id = "../key_ciphertext"), secondKey) }
        rejected { store.saveProvider(second.copy(models = listOf(LlmModel("duplicate"), LlmModel("duplicate")))) }
        rejected { store.selectModel(second.id, "missing-model") }
        rejected { store.saveProvider(second.copy(name = " ")) }
        rejected { store.saveProvider(second.copy(baseUrl = "http://second.example/api")) }
        rejected { store.saveProvider(second.copy(models = listOf(LlmModel("")))) }
        rejected { store.saveProvider(second.copy(models = listOf(LlmModel("model", maxTokens = 0)))) }
        assertThat(backing.all).isEqualTo(before)

        store.selectModel(second.id, "shared-model")
        assertThat(store.config.value.model).isEqualTo("shared-model")
        assertThat(store.config.value.dialect).isEqualTo(LlmDialect.DEEPSEEK)
        assertThat(store.config.value.maxTokens).isEqualTo(8192)
        assertThat(store.config.value.assistantName).isEqualTo(legacy.assistantName)
        assertThat(store.config.value.toolPermission).isEqualTo(ChatToolPermission.FULL)
        assertThat(store.apiKey()).isEqualTo(secondKey)
        store.selectModel(first.id, "shared-model")
        assertThat(store.config.value.model).isEqualTo("shared-model")
        assertThat(store.config.value.dialect).isEqualTo(LlmDialect.KIMI)
        assertThat(store.config.value.thinking).isFalse()
        assertThat(store.apiKey()).isEqualTo(legacyKey)
        store.selectModel(second.id, "shared-model")
        assertThat(store.apiKey()).isEqualTo(secondKey)
        // A stale single-provider form may change globals only, not the active endpoint/model.
        store.savePreferences(legacy.copy(assistantName = "新人格", autoGenerate = false, toolPermission = ChatToolPermission.ASK))
        assertThat(store.config.value.baseUrl).isEqualTo("https://second.example/prefix")
        assertThat(store.config.value.model).isEqualTo("shared-model")
        assertThat(store.config.value.assistantName).isEqualTo("新人格")
        assertThat(store.config.value.toolPermission).isEqualTo(ChatToolPermission.ASK)

        val durableCatalog = store.catalog.value
        val durableConfig = store.config.value
        val durablePrefs = backing.all.toMap()
        failNextCommit = true
        rejected { store.selectModel(first.id, "shared-model") }
        assertThat(store.catalog.value).isEqualTo(durableCatalog)
        assertThat(store.config.value).isEqualTo(durableConfig)
        assertThat(backing.all).isEqualTo(durablePrefs)
        val restarted = LlmSettingsStore(context)
        assertThat(restarted.catalog.value).isEqualTo(durableCatalog)
        assertThat(restarted.config.value).isEqualTo(durableConfig)
        assertThat(restarted.apiKey()).isEqualTo(secondKey)

        store.saveSearch(ChatSearchConfig(enabled = true), "offline-search-placeholder")
        store.clearKey()
        assertThat(store.hasKey()).isFalse()
        assertThat(store.apiKey()).isNull()
        assertThat(store.hasProviderKey(first.id)).isTrue()
        assertThat(store.searchKey()).isEqualTo("offline-search-placeholder")
        store.saveProvider(second.copy(baseUrl = "https://replacement.example/api"), secondKey)
        assertThat(store.providerKey(second.id, second.baseUrl)).isNull()
        assertThat(store.apiKey()).isEqualTo(secondKey)

        store.saveProvider(second.copy(baseUrl = "https://replacement.example/api", models = listOf(LlmModel("other-model"))))
        assertThat(store.catalog.value.activeProviderId).isNull()
        assertThat(store.catalog.value.activeModelId).isNull()
        assertThat(store.config.value.model).isEmpty()
        assertThat(store.apiKey()).isNull()
        assertThat(store.hasProviderKey(second.id)).isTrue()
        store.selectModel(second.id, "other-model")
        store.deleteProvider(second.id)
        assertThat(store.catalog.value.activeProviderId).isNull()
        assertThat(store.apiKey()).isNull()
        assertThat(store.catalog.value.providers.map { it.id }).containsExactly(first.id)
        assertThat(store.hasProviderKey(second.id)).isFalse()
        assertThat(store.hasProviderKey(first.id)).isTrue()
        assertThat(store.config.value.assistantName).isEqualTo("新人格")

        assertThat(backing.edit().putString("provider_catalog", "{malformed").commit()).isTrue()
        val corrupted = LlmSettingsStore(context)
        val corruptSnapshot = backing.all.toMap()
        assertThat(corrupted.apiKey()).isNull()
        assertThat(corrupted.configurationError.value).isNotNull()
        rejected { corrupted.saveProvider(second, secondKey) }
        rejected { corrupted.savePreferences(legacy) }
        rejected { corrupted.save(legacy, legacyKey) }
        rejected { corrupted.clearKey() }
        assertThat(backing.all).isEqualTo(corruptSnapshot)

        // Fresh installs auto-select the first model without inventing a default legacy provider.
        assertThat(backing.edit().clear().commit()).isTrue()
        val fresh = LlmSettingsStore(context)
        assertThat(fresh.catalog.value.providers).isEmpty()
        fresh.saveProvider(first.copy(models = emptyList()), legacyKey)
        assertThat(fresh.catalog.value.activeProviderId).isNull()
        fresh.saveProvider(first.copy(models = listOf(LlmModel("first-model"))))
        assertThat(fresh.catalog.value.activeModelId).isEqualTo("first-model")
        fresh.saveProvider(second, secondKey)
        assertThat(fresh.catalog.value.activeProviderId).isEqualTo(first.id)
        // Legacy callers retain their save API, but cannot silently rebind an existing credential.
        rejected { fresh.save(fresh.config.value.copy(baseUrl = "https://changed.example/api")) }
        fresh.save(fresh.config.value.copy(model = "compat-model", thinking = false))
        assertThat(LlmSettingsStore(context).config.value.model).isEqualTo("compat-model")
        assertThat(fresh.apiKey()).isEqualTo(legacyKey)
    } finally {
        backing.edit().clear().commit()
    }
}
