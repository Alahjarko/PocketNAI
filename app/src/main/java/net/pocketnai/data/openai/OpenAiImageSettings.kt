package net.pocketnai.data.openai

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import net.pocketnai.data.security.SecretBox
import net.pocketnai.domain.openai.*

/** OpenAI credentials have their own Keystore alias and backup-excluded preference file. */
class OpenAiImageSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("pocketnai_openai_images", Context.MODE_PRIVATE)
    private val box = SecretBox("pocketnai_openai_images_key")
    private val _provider = MutableStateFlow(runCatching {
        ImageProvider.valueOf(prefs.getString("provider", null) ?: "NOVEL_AI")
    }.getOrDefault(ImageProvider.NOVEL_AI))
    val provider = _provider.asStateFlow()
    private val _hasKey = MutableStateFlow(prefs.contains("key_ciphertext"))
    val hasKey = _hasKey.asStateFlow()

    fun setProvider(value: ImageProvider) {
        check(prefs.edit().putString("provider", value.name).commit()) { "模式保存失败" }
        _provider.value = value
    }
    fun saveKey(raw: String) {
        val value = raw.trim()
        require(value.isNotEmpty() && value.length <= 1024 && value.none { it.isWhitespace() || it.isISOControl() }) { "API 密钥格式不正确" }
        check(prefs.edit().putString("key_ciphertext", box.encrypt(value)).commit()) { "密钥保存失败" }
        _hasKey.value = true
    }
    fun apiKey(): String? = prefs.getString("key_ciphertext", null)?.let(box::decrypt)
    fun clearKey() {
        check(prefs.edit().remove("key_ciphertext").commit()) { "密钥移除失败" }
        _hasKey.value = false
    }
    fun loadDraft(): OpenAiImageDraft = runCatching {
        OpenAiImageCodec.json.decodeFromString<OpenAiImageDraft>(prefs.getString("draft", "")!!)
    }.getOrDefault(OpenAiImageDraft())
    fun saveDraft(value: OpenAiImageDraft) {
        prefs.edit().putString("draft", OpenAiImageCodec.json.encodeToString(value)).apply()
    }
}
