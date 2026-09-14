package net.pocketnai.core

import java.security.MessageDigest

/**
 * 日志脱敏工具（规划书第 10 节）。
 *
 * 规则：
 * - Token 永远不进入日志，最多输出长度与不可逆指纹。
 * - Prompt 默认只记录长度与哈希，不记录原文。
 * - Authorization 等敏感请求头统一替换为 [MASK]。
 *
 * 这里是纯 Kotlin 实现，因此 Debug 与 Release 使用同一套脱敏逻辑，
 * 不允许出现“Debug 可以打印明文 Token”的分支。
 */
object LogRedaction {

    const val MASK: String = "***redacted***"

    private val SENSITIVE_HEADERS: Set<String> = setOf(
        "authorization",
        "proxy-authorization",
        "cookie",
        "set-cookie",
        "x-api-key",
    )

    /** 只暴露 Token 的长度与短指纹，用于排查“是否换了 Token”一类问题。 */
    fun describeSecret(secret: String?): String {
        if (secret.isNullOrEmpty()) return "empty"
        return "len=${secret.length} fp=${shortFingerprint(secret)}"
    }

    /**
     * Prompt 的日志表示：长度 + 哈希前缀 + 单词数，不含原文。
     * 保留长度足以判断“是否被截断”，哈希足以判断“两次请求是否同一提示词”。
     */
    fun describePrompt(prompt: String?): String {
        if (prompt.isNullOrEmpty()) return "empty"
        return "len=${prompt.length} words=${prompt.count { it.isWhitespace() } + 1} fp=${shortFingerprint(prompt)}"
    }

    /** 请求头脱敏，供网络拦截器使用。 */
    fun sanitizeHeader(name: String, value: String): String =
        if (name.lowercase() in SENSITIVE_HEADERS) MASK else value

    /** 稳定的短指纹：SHA-256 前 8 个十六进制字符。 */
    fun shortFingerprint(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .take(4)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
