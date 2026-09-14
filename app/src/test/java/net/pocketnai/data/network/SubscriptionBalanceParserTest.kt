package net.pocketnai.data.network

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import net.pocketnai.core.ErrorCode
import net.pocketnai.core.Outcome
import org.junit.Test

/**
 * `/user/subscription` 响应解析（余额规划 §12.1）。
 *
 * 最重要的一条纪律是：**解析失败绝不构造"0 余额"**。显示一个错误的余额
 * 比显示"余额暂不可用"危险得多 —— 用户会据此判断还能不能生成。
 */
class SubscriptionBalanceParserTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val fullResponse = """
        {
          "tier": 3,
          "active": true,
          "expiresAt": 1234567890,
          "isGracePeriod": false,
          "trainingStepsLeft": {
            "fixedTrainingStepsLeft": 10000,
            "purchasedTrainingSteps": 2000
          },
          "usage": {
            "percent": 87,
            "isNegative": false,
            "timeUntilNextPercent": 3600
          }
        }
    """.trimIndent()

    private fun parse(body: String) = SubscriptionBalanceParser.parse(body, json, 1_000L)

    private fun success(body: String) = (parse(body) as Outcome.Success).value

    // ---- 正常路径 ----

    @Test
    fun `完整响应解析出两个 Anlas 池与总额`() {
        val balance = success(fullResponse)

        assertThat(balance.subscriptionAnlas).isEqualTo(10_000L)
        assertThat(balance.purchasedAnlas).isEqualTo(2_000L)
        assertThat(balance.totalAnlas).isEqualTo(12_000L)
        assertThat(balance.rawTier).isEqualTo(3)
        assertThat(balance.active).isTrue()
        assertThat(balance.fetchedAtMillis).isEqualTo(1_000L)
    }

    @Test
    fun `usage 正确解析`() {
        val usage = success(fullResponse).v5UsageLimit

        assertThat(usage).isNotNull()
        requireNotNull(usage)
        assertThat(usage.rawPercent).isEqualTo(87)
        assertThat(usage.isNegative).isFalse()
        assertThat(usage.timeUntilNextPercentSeconds).isEqualTo(3600L)
        assertThat(usage.available).isTrue()
    }

    @Test
    fun `usage 缺失时余额仍然可用`() {
        val body = """
            {"tier":0,"active":false,"trainingStepsLeft":
              {"fixedTrainingStepsLeft":100,"purchasedTrainingSteps":0}}
        """.trimIndent()

        val balance = success(body)

        assertThat(balance.totalAnlas).isEqualTo(100L)
        assertThat(balance.v5UsageLimit).isNull()
    }

    @Test
    fun `percent 超过 100 时保留原值`() {
        val body = fullResponse.replace("\"percent\": 87", "\"percent\": 130")

        assertThat(success(body).v5UsageLimit?.rawPercent).isEqualTo(130)
    }

    @Test
    fun `timeUntilNextPercent 为 0 是合法值`() {
        val body = fullResponse.replace("\"timeUntilNextPercent\": 3600", "\"timeUntilNextPercent\": 0")

        assertThat(success(body).v5UsageLimit?.timeUntilNextPercentSeconds).isEqualTo(0L)
    }

    @Test
    fun `usage 结构异常时余额仍然成功只是没有额度`() {
        // percent 读不出来就不展示额度，不猜 0。
        val body = fullResponse.replace("\"percent\": 87", "\"percent\": \"eighty\"")

        val balance = success(body)

        assertThat(balance.totalAnlas).isEqualTo(12_000L)
        assertThat(balance.v5UsageLimit).isNull()
    }

    @Test
    fun `tier 与 active 缺失时保留为空`() {
        val body = """
            {"trainingStepsLeft":{"fixedTrainingStepsLeft":1,"purchasedTrainingSteps":2}}
        """.trimIndent()

        val balance = success(body)

        assertThat(balance.rawTier).isNull()
        assertThat(balance.active).isNull()
    }

    @Test
    fun `未知字段被忽略`() {
        val body = """
            {"unknownTopLevel":123,"trainingStepsLeft":
              {"fixedTrainingStepsLeft":5,"purchasedTrainingSteps":6,"extra":true},
              "usage":{"percent":10,"unexpected":"x"}}
        """.trimIndent()

        assertThat(success(body).totalAnlas).isEqualTo(11L)
    }

    // ---- 失败路径：一律不构造 0 余额 ----

    @Test
    fun `trainingStepsLeft 缺失视为响应无效`() {
        assertThat(failureCode("""{"tier":3,"active":true}"""))
            .isEqualTo(ErrorCode.ACCOUNT_RESPONSE_INVALID)
    }

    @Test
    fun `任一余额字段缺失视为响应无效`() {
        val body = """{"trainingStepsLeft":{"fixedTrainingStepsLeft":100}}"""

        assertThat(failureCode(body)).isEqualTo(ErrorCode.ACCOUNT_RESPONSE_INVALID)
    }

    @Test
    fun `余额写成字符串时不接受`() {
        val body = """
            {"trainingStepsLeft":{"fixedTrainingStepsLeft":"10000","purchasedTrainingSteps":1}}
        """.trimIndent()

        assertThat(failureCode(body)).isEqualTo(ErrorCode.ACCOUNT_RESPONSE_INVALID)
    }

    @Test
    fun `余额为负数时不接受`() {
        val body = """
            {"trainingStepsLeft":{"fixedTrainingStepsLeft":-1,"purchasedTrainingSteps":1}}
        """.trimIndent()

        assertThat(failureCode(body)).isEqualTo(ErrorCode.ACCOUNT_RESPONSE_INVALID)
    }

    @Test
    fun `空响应不接受`() {
        assertThat(failureCode("")).isEqualTo(ErrorCode.ACCOUNT_RESPONSE_INVALID)
    }

    @Test
    fun `HTML 错误页不接受`() {
        assertThat(failureCode("<html><body>502 Bad Gateway</body></html>"))
            .isEqualTo(ErrorCode.ACCOUNT_RESPONSE_INVALID)
    }

    @Test
    fun `失败时不带出响应内容`() {
        val outcome = parse("""{"secret-looking":"abc123"}""") as Outcome.Failure

        assertThat(outcome.error.detail.orEmpty()).doesNotContain("abc123")
    }

    private fun failureCode(body: String): ErrorCode {
        val outcome = parse(body)
        assertThat(outcome).isInstanceOf(Outcome.Failure::class.java)
        return (outcome as Outcome.Failure).error.code
    }
}
