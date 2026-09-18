package net.pocketnai.data.proxy

import android.content.Context
import android.util.Base64
import net.pocketnai.domain.proxy.ProxyEndpoint
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 内置公益节点清单：从 assets 里的**加密**文件加载（AES-256-GCM），加载后内存缓存。
 *
 * ## 关于"不泄露"的边界（重要）
 * 加密密钥派生自代码里的固定串 —— 它能挡住"随手提取"（strings / 反编译常量
 * 拿不到明文清单），但**挡不住专业逆向**。因此把这批凭据当作**可轮换**的：
 * 一旦怀疑外泄，去代理服务商后台换密码，再用 `scripts/encrypt-proxy-nodes.mjs`
 * 重新生成 [ASSET_NAME] 即可。真正的用量与并发约束应放在**代理服务商后台**，
 * 不要只依赖客户端的每日统计。
 *
 * 文件格式：`base64( iv(12B) || ciphertext+tag )`，明文为多行 `host:port:user:pass`。
 */
class PublicProxyNodes(private val context: Context) {

    @Volatile
    private var cache: List<ProxyEndpoint>? = null

    fun all(): List<ProxyEndpoint> = cache ?: synchronized(this) {
        cache ?: load().also { cache = it }
    }

    private fun load(): List<ProxyEndpoint> = runCatching {
        val encoded = context.assets.open(ASSET_NAME)
            .bufferedReader()
            .use { it.readText() }
            .trim()
        val packed = Base64.decode(encoded, Base64.NO_WRAP)
        require(packed.size > IV_BYTES) { "proxy nodes payload too short" }

        val key = MessageDigest.getInstance("SHA-256")
            .digest(KEY_SEED.toByteArray(Charsets.UTF_8))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, packed.copyOfRange(0, IV_BYTES)),
        )
        val plain = String(cipher.doFinal(packed.copyOfRange(IV_BYTES, packed.size)), Charsets.UTF_8)

        plain.lineSequence().mapNotNull(::parseEndpoint).toList()
    }.getOrDefault(emptyList())

    private fun parseEndpoint(line: String): ProxyEndpoint? {
        val parts = line.trim().split(':')
        if (parts.size != 4) return null
        val port = parts[1].toIntOrNull() ?: return null
        if (parts[0].isBlank() || port !in 1..65535) return null
        return ProxyEndpoint(host = parts[0], port = port, username = parts[2], password = parts[3])
    }

    private companion object {
        const val ASSET_NAME = "public_proxy_nodes.enc"

        /** 与 `scripts/encrypt-proxy-nodes.mjs` 中的 KEY_SEED 保持一致。 */
        const val KEY_SEED = "PocketNAI public proxy v1"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
