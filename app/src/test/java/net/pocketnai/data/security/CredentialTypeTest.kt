package net.pocketnai.data.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 凭据类型的降级解析（《双认证模式实施计划》阶段 C）。
 *
 * 这是升级兼容的关键一环：旧安装只存了密文，没有 `credential_type` 元数据键。
 * 如果这里解析错方向（例如把未知值当账号会话），用户会被引导去"重新登录"，
 * 而实际上他手里有一个完全可用的 PST。
 *
 * 因为真实的 Keystore 读写需要 Android 环境，这里只覆盖纯逻辑部分；
 * 端到端兼容性在模拟器上用一个旧格式凭据升级安装来验证。
 */
class CredentialTypeTest {

    @Test
    fun `缺少类型元数据时按 Persistent Token 解释`() {
        // 升级前保存的凭据只可能是 PST —— 这是唯一不打扰用户的降级方向。
        assertThat(CredentialType.fromNameOrDefault(null))
            .isEqualTo(CredentialType.PERSISTENT_API_TOKEN)
    }

    @Test
    fun `元数据损坏时同样按 Persistent Token 降级`() {
        assertThat(CredentialType.fromNameOrDefault(""))
            .isEqualTo(CredentialType.PERSISTENT_API_TOKEN)
        assertThat(CredentialType.fromNameOrDefault("NOT_A_REAL_TYPE"))
            .isEqualTo(CredentialType.PERSISTENT_API_TOKEN)
        assertThat(CredentialType.fromNameOrDefault("account_session"))
            .isEqualTo(CredentialType.PERSISTENT_API_TOKEN)
    }

    @Test
    fun `两个已知类型都能正确解析`() {
        assertThat(CredentialType.fromNameOrDefault("PERSISTENT_API_TOKEN"))
            .isEqualTo(CredentialType.PERSISTENT_API_TOKEN)
        assertThat(CredentialType.fromNameOrDefault("ACCOUNT_SESSION"))
            .isEqualTo(CredentialType.ACCOUNT_SESSION)
    }

    @Test
    fun `类型的存储名保持稳定`() {
        // 名字会落盘，改名等同于让旧数据失去类型信息。
        assertThat(CredentialType.PERSISTENT_API_TOKEN.name).isEqualTo("PERSISTENT_API_TOKEN")
        assertThat(CredentialType.ACCOUNT_SESSION.name).isEqualTo("ACCOUNT_SESSION")
    }
}
