package net.pocketnai.data.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import net.pocketnai.core.AppError
import net.pocketnai.core.ErrorCode
import net.pocketnai.core.Outcome
import net.pocketnai.domain.billing.SubscriptionBalance
import net.pocketnai.domain.billing.V5UsageLimit

/**
 * `GET /user/subscription` 的响应解析（规划 §6.3）。
 *
 * ## 两条纪律
 * 1. **解析失败不构造"0 余额"** —— 那会让界面显示一个错误的余额，
 *    比"余额暂不可用"危险得多；
 * 2. **`usage` 缺失是合法的** —— 它表示账户没有可展示的 V5 额度，不是错误；
 *    但 `usage` 存在而字段类型不对时，余额本身仍然成功，只是不带额度。
 *
 * 类型判断全部走安全取值（`as? JsonPrimitive` + `xxxOrNull`），
 * 不用会在类型错误时抛异常的强制属性链。
 */
object SubscriptionBalanceParser {

    fun parse(
        body: String,
        json: Json,
        fetchedAtMillis: Long,
    ): Outcome<SubscriptionBalance> {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }
            .getOrNull()
            ?: return invalid()

        val training = root["trainingStepsLeft"] as? JsonObject ?: return invalid()
        val subscription = training.longOrNull("fixedTrainingStepsLeft") ?: return invalid()
        val purchased = training.longOrNull("purchasedTrainingSteps") ?: return invalid()
        // 负数说明响应结构不是我们以为的那样；宁可报"无法读取"也不显示离谱的余额。
        if (subscription < 0 || purchased < 0) return invalid()

        return Outcome.Success(
            SubscriptionBalance(
                rawTier = root.intOrNull("tier"),
                active = root.booleanOrNull("active"),
                expiresAtEpochSeconds = root.longOrNull("expiresAt"),
                isGracePeriod = root.booleanOrNull("isGracePeriod"),
                subscriptionAnlas = subscription,
                purchasedAnlas = purchased,
                v5UsageLimit = parseUsageOrNull(root["usage"]),
                fetchedAtMillis = fetchedAtMillis,
            ),
        )
    }

    /** `usage` 缺失或结构不对时返回 null：余额照常可用，只是没有额度信息。 */
    private fun parseUsageOrNull(element: JsonElement?): V5UsageLimit? {
        val usage = element as? JsonObject ?: return null
        // percent 是这项的核心字段；读不到就不展示额度，不猜 0。
        val percent = usage.intOrNull("percent") ?: return null
        return V5UsageLimit(
            rawPercent = percent,
            isNegative = usage.booleanOrNull("isNegative") ?: false,
            timeUntilNextPercentSeconds = usage.longOrNull("timeUntilNextPercent"),
        )
    }

    private fun invalid(): Outcome.Failure =
        Outcome.Failure(AppError.of(ErrorCode.ACCOUNT_RESPONSE_INVALID))

    /** 布尔：只接受 JSON 布尔的字面量，字符串 `"true"` 不接受。 */
    private fun JsonObject.booleanOrNull(key: String): Boolean? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        return primitive.booleanOrNull
    }

    private fun JsonObject.intOrNull(key: String): Int? =
        longOrNull(key)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

    /**
     * 整数：**拒绝字符串形式**（`"10000"` 不算）。
     *
     * 比 `AccountStatusParser` 严格是有意的：那里读错的后果只是少显示一条登录方式，
     * 而这里读错的后果是界面上出现一个错误的余额数字。
     * 允许 `10000.0` 这类没有小数部分的浮点写法，只是兼容，不改变语义。
     */
    private fun JsonObject.longOrNull(key: String): Long? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        val value = primitive.content.toDoubleOrNull() ?: return null
        if (!value.isFinite()) return null
        if (value != value.toLong().toDouble()) return null
        return value.toLong()
    }
}
