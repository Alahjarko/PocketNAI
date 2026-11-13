package net.pocketnai.data.mcp

import android.content.Context
import android.util.Base64
import net.pocketnai.data.security.SecretBox
import java.security.SecureRandom

class McpAccess(context: Context) {
    private val prefs = context.getSharedPreferences("pocketnai_mcp", Context.MODE_PRIVATE)
    private val box = SecretBox("pocketnai_mcp_secret_key")
    @Synchronized fun token(): String {
        prefs.getString("access_ciphertext", null)?.let { box.decrypt(it) }?.let { return it }
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val raw = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        check(prefs.edit().putString("access_ciphertext", box.encrypt(raw)).commit()) { "连接令牌未能保存" }
        return raw
    }
    @Synchronized fun reset() { check(prefs.edit().remove("access_ciphertext").commit()) { "连接令牌未能重置" } }
}
