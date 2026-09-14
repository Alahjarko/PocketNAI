package net.pocketnai.data.network

import net.pocketnai.core.Outcome

/**
 * NovelAI 账号登录的协议边界（《双认证模式实施计划》阶段 B）。
 *
 * 刻意与 [NovelAiApi] 分开，不把登录塞进图片生成接口：
 * 认证是可拆除、可降级的独立适配器，生成协议核心不应该知道账号密码的存在。
 */
interface NovelAiAuthApi {

    /**
     * 用**本地派生**的 Access Key 换取短期 Access Token。
     *
     * 约定：
     * - 只发送 `key` 字段，不发送邮箱，也不发送原始密码；
     * - 不带 `Authorization` 头（登录的目的就是拿到它）；
     * - 零次自动重试 —— 401 说明凭据确实不对，重试没有意义；
     * - 任何失败都不会把 Access Key 或响应体写进日志与错误明细。
     */
    suspend fun login(accessKey: String): Outcome<AccountSession>
}

/** 一次账号会话。只保存真正放进 `Authorization: Bearer` 的值。 */
data class AccountSession(
    val accessToken: String,
)
