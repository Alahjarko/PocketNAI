package net.pocketnai.domain.proxy

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 代理配置的纯逻辑：剪贴板解析与"是否实际生效"的判定。
 *
 * 解析面对的是用户粘贴的外部文本，畸形输入必须原样拒绝（返回 null），
 * 不做任何"猜一半"的容错 —— 半个代理配置比没有更糟。
 */
class ProxySettingsTest {

    @Test
    fun `剪贴板一行能解析成完整配置`() {
        val parsed = ProxySettings.parseClipboardLine("1.2.3.4:8080:alice:s3cret")

        assertThat(parsed).isNotNull()
        assertThat(parsed!!.mode).isEqualTo(ProxyMode.CUSTOM)
        assertThat(parsed.type).isEqualTo(ProxyType.SOCKS5)
        assertThat(parsed.host).isEqualTo("1.2.3.4")
        assertThat(parsed.port).isEqualTo(8080)
        assertThat(parsed.username).isEqualTo("alice")
        assertThat(parsed.password).isEqualTo("s3cret")
    }

    @Test
    fun `两端空白会被去掉`() {
        val parsed = ProxySettings.parseClipboardLine("  1.2.3.4:8080:alice:s3cret  ")

        assertThat(parsed?.host).isEqualTo("1.2.3.4")
        assertThat(parsed?.password).isEqualTo("s3cret")
    }

    @Test
    fun `不完整的行一律返回 null`() {
        assertThat(ProxySettings.parseClipboardLine("")).isNull()
        assertThat(ProxySettings.parseClipboardLine("1.2.3.4:8080:alice")).isNull()
        assertThat(ProxySettings.parseClipboardLine("1.2.3.4:8080:alice:pass:extra")).isNull()
        assertThat(ProxySettings.parseClipboardLine("1.2.3.4:notaport:alice:pass")).isNull()
        assertThat(ProxySettings.parseClipboardLine("1.2.3.4:0:alice:pass")).isNull()
        assertThat(ProxySettings.parseClipboardLine(":8080:alice:pass")).isNull()
    }

    @Test
    fun `active 需要开关打开且所选模式可用`() {
        val off = ProxySettings(enabled = false, mode = ProxyMode.PUBLIC)
        assertThat(off.active).isFalse()

        val publicOn = ProxySettings(enabled = true, mode = ProxyMode.PUBLIC)
        assertThat(publicOn.active).isTrue()

        val customIncomplete = ProxySettings(enabled = true, mode = ProxyMode.CUSTOM)
        assertThat(customIncomplete.active).isFalse()

        val customReady = ProxySettings(
            enabled = true,
            mode = ProxyMode.CUSTOM,
            host = "1.2.3.4",
            port = 1080,
        )
        assertThat(customReady.active).isTrue()
    }

    @Test
    fun `公益额度是 750 MB`() {
        assertThat(PublicProxyQuota.DAILY_LIMIT_BYTES).isEqualTo(750L * 1024 * 1024)
    }
}
