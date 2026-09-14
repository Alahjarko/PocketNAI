package net.pocketnai.data.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * `GET /user/data` 响应的解析。
 *
 * 单独成类的原因：这是纯 Kotlin，不依赖 Android 与 OkHttp，
 * 因此可以用真实抓到的响应片段写单元测试，而不必联网。
 *
 * 已用真实账户核对的响应结构（2026-09-14，仅摘录相关字段）：
 * ```json
 * {
 *   "priority": { ... },
 *   "subscription": { "tier": 0, "active": false, "accountType": 0, ... },
 *   "information": { "emailVerified": true, "trialImagesLeft": 0, ... }
 * }
 * ```
 *
 * 解析策略刻意保守：任何字段缺失或类型不符都返回 null，**绝不影响“Token 是否有效”的结论**，
 * 因为 Token 有效性只由 HTTP 状态码决定。
 */
object AccountStatusParser {

    fun parse(body: String, json: Json): AccountStatus {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: return AccountStatus(connected = true)

        val subscription = root["subscription"] as? JsonObject
        val information = root["information"] as? JsonObject

        return AccountStatus(
            connected = true,
            tier = firstInt(
                subscription?.get("tier"),
                root["tier"],
            ),
            subscriptionActive = firstBoolean(
                subscription?.get("active"),
                root["active"],
            ),
            // 只读不推断：拿不到就是 null，绝不因为缺失就假定账号是 SSO。
            loginMethod = firstString(
                information?.get("loginMethod"),
                root["loginMethod"],
            ),
        )
    }

    private fun firstInt(vararg candidates: Any?): Int? {
        candidates.forEach { candidate ->
            val value = (candidate as? JsonPrimitive)?.intOrNull
            if (value != null) return value
        }
        return null
    }

    private fun firstString(vararg candidates: Any?): String? {
        candidates.forEach { candidate ->
            val value = (candidate as? JsonPrimitive)?.contentOrNull
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    private fun firstBoolean(vararg candidates: Any?): Boolean? {
        candidates.forEach { candidate ->
            val value = (candidate as? JsonPrimitive)?.booleanOrNull
            if (value != null) return value
        }
        return null
    }
}
