package net.pocketnai.domain.auth

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 分步诊断：把 BLAKE2b 与 Argon2 的中间结果分别与参考实现对账。
 *
 * 保留这个用例是有价值的 —— 将来若派生结果出现偏差，它能立刻指出是
 * "salt 就错了"（BLAKE2b / preSalt 组装问题）还是"只有 Argon2 错了"（参数问题），
 * 不必再从头排查。
 * 期望值由 Aedial 参考实现 + argon2-cffi 计算得到（虚构输入）。
 */
class DerivationDiagnosticTest {

    private val deriver = NovelAiAccessKeyDeriver()

    private val email = "test@example.com"
    private val password = "correct horse battery staple"

    @Test
    fun `preSalt 组装与参考实现一致`() {
        assertThat(deriver.buildPreSalt(email = email, password = password))
            .isEqualTo("correctest@example.comnovelai_data_access_key")
    }

    @Test
    fun `BLAKE2b-128 的 salt 与参考实现一致`() {
        val salt = deriver.blake2b128(
            deriver.buildPreSalt(email = email, password = password).toByteArray(Charsets.UTF_8),
        )
        assertThat(salt.toHex()).isEqualTo("c42cabfdb533b8f1e3a615fb4585692e")
    }

    @Test
    fun `Argon2id 的原始输出与参考实现一致`() {
        val raw = deriver.argon2id(
            password = password.toByteArray(Charsets.UTF_8),
            salt = hexToBytes("c42cabfdb533b8f1e3a615fb4585692e"),
        )
        assertThat(raw.toHex()).isEqualTo(
            "fab453039e13100a5fc56733a08ba96807c143b0b87eb94f9d9e6ba147b6f531" +
                "f17531f00e6f9366f1dc90e40e6dc1ee4e61ad976803ce920e025c773fbe16a0",
        )
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
}
