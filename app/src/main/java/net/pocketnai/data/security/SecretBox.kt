package net.pocketnai.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 通用小密钥盒：给"非 NovelAI Token 的敏感值"（例如代理密码）做 Keystore 加密。
 *
 * 与 [KeystoreCredentialStore] 刻意分开：
 * - 它**不碰**凭据文件与那三个"不得更改"的常量（`pocketnai_secure` / `pocketnai_token_key` /
 *   `token_iv`），改动互不影响；
 * - 自己的 alias 独立，删掉它只会丢失代理密码，不会牵动已保存的 Token。
 *
 * 输出格式：`base64( iv(12B) || ciphertext+tag )`。
 */
class SecretBox(private val alias: String = DEFAULT_ALIAS) {

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + ciphertext
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    /** 解密失败（数据被清、alias 变了）返回 null，由调用方当作"没设置过"。 */
    fun decrypt(encoded: String): String? = runCatching {
        val packed = Base64.decode(encoded, Base64.NO_WRAP)
        if (packed.size <= IV_BYTES) return null
        val iv = packed.copyOfRange(0, IV_BYTES)
        val ciphertext = packed.copyOfRange(IV_BYTES, packed.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
        String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }.getOrNull()

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val DEFAULT_ALIAS = "pocketnai_extra_secret_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
