package net.pocketnai.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import net.pocketnai.core.Hashing
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 凭据来源。决定界面文案、以及失效后该引导用户做什么。 */
enum class CredentialType {
    /** 用户在 NovelAI 网页生成的 Persistent API Token。官方推荐的第三方接入方式。 */
    PERSISTENT_API_TOKEN,

    /** 邮箱 + 密码在本地派生出 Access Key 后换来的短期 Access Token。 */
    ACCOUNT_SESSION,
    ;

    companion object {
        /**
         * 从存储里的名字解析类型。
         *
         * 缺失或无法识别时一律按 [PERSISTENT_API_TOKEN] 处理：
         * 升级前保存的凭据本来就只可能是 PST，这是唯一安全且不打扰用户的降级方向。
         */
        fun fromNameOrDefault(raw: String?): CredentialType =
            entries.firstOrNull { it.name == raw } ?: PERSISTENT_API_TOKEN
    }
}

/**
 * 一份已保存的凭据。
 *
 * [token] 是真正放进 `Authorization: Bearer` 的值 —— 生成链路只关心这一个字段，
 * 因此不需要为两种凭据复制两套 API。
 */
data class StoredCredential(
    val token: String,
    val type: CredentialType,
    /** 仅用于会话信息与诊断，**不作为拒绝请求的依据**（服务端返回 401 才是）。 */
    val createdAtEpochMillis: Long? = null,
)

/** 用于界面展示的脱敏标识，不含任何 Token 片段。 */
data class CredentialHint(
    val type: CredentialType,
    val length: Int,
    val fingerprint: String,
)

/**
 * 凭据的本地存储抽象。
 *
 * 只允许把 Token 放在受保护的本地存储里，禁止写入普通首选项、数据库、
 * 图片元数据、剪贴板或日志（规划书第 10 节）。
 * Access Key、密码等中间值**一律不允许**出现在这里。
 */
interface CredentialStore {

    fun hasCredential(): Boolean

    /** 读取凭据；密钥失效或数据损坏时返回 null 并清理残留。 */
    fun load(): StoredCredential?

    fun save(credential: StoredCredential)

    fun clear()

    /** 界面展示用的脱敏标识，不包含任何 Token 片段。 */
    fun hint(): CredentialHint?
}

/**
 * 基于 Android Keystore 的 AES/GCM 实现。
 *
 * ## 与旧版本的兼容（阶段 C 的硬要求）
 * 加密格式与存储位置**完全不变**，因此升级上来的旧安装不需要用户重新输入 PST：
 * - 首选项文件仍是 `pocketnai_secure`；
 * - Keystore alias 仍是 `pocketnai_token_key`；
 * - 密文键仍是 `token_iv` / `token_ciphertext`；
 * - `res/xml` 里的备份排除规则不需要改动。
 *
 * 新增的只是两个**非敏感**元数据键：`credential_type` 与 `credential_created_at`。
 * 旧安装的密文存在但没有 `credential_type`，按 [CredentialType.PERSISTENT_API_TOKEN] 解释；
 * 元数据损坏时同样按 PST 降级 —— 凭据本身能解密就继续可用。
 *
 * 密钥失效（例如用户重设锁屏）时捕获异常、清空并回到"未连接"，不崩溃。
 */
class KeystoreCredentialStore(context: Context) : CredentialStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun hasCredential(): Boolean =
        prefs.contains(KEY_CIPHERTEXT) && prefs.contains(KEY_IV)

    override fun load(): StoredCredential? {
        val ivEncoded = prefs.getString(KEY_IV, null) ?: return null
        val cipherEncoded = prefs.getString(KEY_CIPHERTEXT, null) ?: return null
        val plaintext = try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(GCM_TAG_LENGTH_BITS, Base64.decode(ivEncoded, Base64.NO_WRAP)),
            )
            String(cipher.doFinal(Base64.decode(cipherEncoded, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            // 密钥失效或密文损坏：清掉残留，回到未连接状态。
            clear()
            return null
        } catch (e: IllegalArgumentException) {
            clear()
            return null
        }
        if (plaintext.isEmpty()) return null

        return StoredCredential(
            token = plaintext,
            type = CredentialType.fromNameOrDefault(prefs.getString(KEY_TYPE, null)),
            createdAtEpochMillis = prefs.getLong(KEY_CREATED_AT, 0L).takeIf { it > 0L },
        )
    }

    override fun save(credential: StoredCredential) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(credential.token.toByteArray(Charsets.UTF_8))

        val editor = prefs.edit()
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(KEY_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .putString(KEY_TYPE, credential.type.name)
            .putInt(KEY_HINT_LENGTH, credential.token.length)
            .putString(
                KEY_HINT_FINGERPRINT,
                Hashing.sha256(credential.token.toByteArray(Charsets.UTF_8)).take(8),
            )
        credential.createdAtEpochMillis?.let { editor.putLong(KEY_CREATED_AT, it) }
        editor.apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    override fun hint(): CredentialHint? {
        if (!hasCredential()) return null
        val length = prefs.getInt(KEY_HINT_LENGTH, -1)
        val fingerprint = prefs.getString(KEY_HINT_FINGERPRINT, null) ?: return null
        if (length <= 0) return null
        return CredentialHint(
            type = CredentialType.fromNameOrDefault(prefs.getString(KEY_TYPE, null)),
            length = length,
            fingerprint = fingerprint,
        )
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                // 不要求用户认证：生成任务可能在应用回到前台之外继续完成。
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"

        /** **不得更改**：改了会让旧安装的凭据无法解密。 */
        const val KEY_ALIAS = "pocketnai_token_key"

        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128
        const val KEY_SIZE_BITS = 256

        /** **不得改名**：改名必须同步 `res/xml` 里的备份排除规则。 */
        const val PREFS_NAME = "pocketnai_secure"

        const val KEY_IV = "token_iv"
        const val KEY_CIPHERTEXT = "token_ciphertext"

        // 以下为非敏感元数据。
        const val KEY_TYPE = "credential_type"
        const val KEY_CREATED_AT = "credential_created_at"
        const val KEY_HINT_LENGTH = "token_hint_length"
        const val KEY_HINT_FINGERPRINT = "token_hint_fingerprint"
    }
}
