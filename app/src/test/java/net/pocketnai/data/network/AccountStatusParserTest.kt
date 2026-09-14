package net.pocketnai.data.network

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * `GET /user/data` 的解析。
 *
 * 夹具来自 2026-09-14 用真实账户抓取到的响应（只保留相关字段、去掉个人内容）。
 * 这里特意断言 **tier 在 subscription 下且是整数**：初版实现按顶层字符串解析，
 * 结果在真机上拿不到任何订阅信息。
 */
class AccountStatusParserTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `从 subscription 下读取整数 tier 与激活状态`() {
        val body = """
            {
              "priority": {"maxPriorityActions": 0, "taskPriority": 0},
              "subscription": {
                "tier": 0,
                "active": false,
                "paymentProcessor": null,
                "expiresAt": 0,
                "accountType": 0
              },
              "information": {"emailVerified": true, "trialImagesLeft": 0}
            }
        """.trimIndent()

        val status = AccountStatusParser.parse(body, json)

        assertThat(status.connected).isTrue()
        assertThat(status.tier).isEqualTo(0)
        assertThat(status.subscriptionActive).isFalse()
    }

    @Test
    fun `付费订阅被识别为已激活`() {
        val body = """{"subscription":{"tier":3,"active":true}}"""
        val status = AccountStatusParser.parse(body, json)

        assertThat(status.tier).isEqualTo(3)
        assertThat(status.subscriptionActive).isTrue()
    }

    @Test
    fun `兼容顶层的 tier 与 active`() {
        val body = """{"tier":2,"active":true}"""
        val status = AccountStatusParser.parse(body, json)

        assertThat(status.tier).isEqualTo(2)
        assertThat(status.subscriptionActive).isTrue()
    }

    @Test
    fun `响应结构变化时仍然判定为已连接`() {
        // 关键契约：Token 有效性只由 HTTP 状态码决定，
        // 解析不到订阅字段绝不能让用户看到“连接失败”。
        val status = AccountStatusParser.parse("""{"unexpected":123}""", json)

        assertThat(status.connected).isTrue()
        assertThat(status.tier).isNull()
        assertThat(status.subscriptionActive).isNull()
    }

    @Test
    fun `非 JSON 响应不抛异常`() {
        val status = AccountStatusParser.parse("<html>gateway error</html>", json)

        assertThat(status.connected).isTrue()
        assertThat(status.tier).isNull()
    }

    @Test
    fun `响应为空字符串时也不抛异常`() {
        val status = AccountStatusParser.parse("", json)

        assertThat(status.connected).isTrue()
    }

    @Test
    fun `读取 information 下的 loginMethod`() {
        // 实测该账号的 loginMethod 为 "sso"（Google 等第三方注册），
        // 界面据此提示改用 Persistent Token。
        val body = """{"information":{"loginMethod":"sso","emailVerified":true}}"""
        assertThat(AccountStatusParser.parse(body, json).loginMethod).isEqualTo("sso")
    }

    @Test
    fun `缺少 loginMethod 时为 null 而不是假定为 sso`() {
        // 不能因为字段缺失就把正常密码账号当成 SSO 账号去误导用户。
        val body = """{"subscription":{"tier":1,"active":true}}"""
        assertThat(AccountStatusParser.parse(body, json).loginMethod).isNull()
    }
}
