package net.pocketnai.domain.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * 一次"检查更新"里我们真正需要的字段。
 *
 * 版本判据用 [versionCode]（构建号）：它来自 CI 发布的 Release tag `build-<n>`，
 * 与 APK 自己的 `versionCode` 同源。**不比较版本名** —— 名字是给人看的，
 * 字符串比较迟早会遇到 "0.10" < "0.9" 这类陷阱。
 */
data class UpdateRelease(
    val versionCode: Int,
    /** 给人看的版本名（CI 写在 Release 标题里，如 `0.1.42`）；读不到时回退成 tag。 */
    val versionName: String,
    val tagName: String,
    /** APK 附件的直接下载地址。 */
    val downloadUrl: String,
    val sizeBytes: Long?,
    /** Release 说明，界面只展示第一行。 */
    val notes: String?,
)

/**
 * 检查结果。
 *
 * 只有两态：拿到了比当前更新的构建（[Available]）或没有（[UpToDate]）。
 * "检查失败"不在这里表达 —— 它由网络层的 Outcome 表达，界面据此区分
 * "已是最新"和"没查成"：把后者说成前者，用户会以为真的没问题。
 */
sealed interface UpdateStatus {
    data class Available(val release: UpdateRelease) : UpdateStatus

    data object UpToDate : UpdateStatus
}

/**
 * 更新判定的纯逻辑：tag 解析、Release JSON 解析、新旧比较。
 *
 * 放在 domain（不依赖 Android）是为了能在 JVM 单测里直接断言 ——
 * 这些解析面对的是外部输入（GitHub 响应），畸形数据的处理必须有测试钉住。
 */
object UpdateEvaluator {

    /** APK 附件的固定名（CI 用它上传，也用于"永远指向最新构建"的分享链接）。 */
    const val APK_ASSET_NAME = "PocketNAI.apk"

    /** CI 的 Release tag 形如 `build-42`。认不出返回 null：宁可"没有更新"，也不猜。 */
    fun parseBuildNumber(tagName: String?): Int? {
        val match = BUILD_TAG.matchEntire(tagName?.trim().orEmpty()) ?: return null
        // 超出 Int 范围（或前导零）都会在这里变成 null，不必额外判断。
        return match.groupValues[1].toIntOrNull()
    }

    fun evaluate(currentVersionCode: Int, release: UpdateRelease): UpdateStatus =
        if (release.versionCode > currentVersionCode) {
            UpdateStatus.Available(release)
        } else {
            UpdateStatus.UpToDate
        }

    /**
     * `GET /repos/{owner}/{repo}/releases/latest` 的响应体 → [UpdateRelease]。
     *
     * 读不到关键信息（tag 不合规 / 没有 APK 附件 / 没有下载地址）时返回 null，
     * 由调用方当作"检查失败"处理 —— **绝不猜一个下载地址出来**。
     *
     * 附件名优先精确匹配 [APK_ASSET_NAME]，匹配不到再退而取第一个 `.apk`：
     * 以后即使改了附件名，检测也不至于静默失效。
     */
    fun parseRelease(responseBody: String, json: Json): UpdateRelease? {
        val root = runCatching { json.parseToJsonElement(responseBody) }
            .getOrNull() as? JsonObject ?: return null

        val tagName = root.string("tag_name") ?: return null
        val versionCode = parseBuildNumber(tagName) ?: return null

        val assets = root["assets"] as? JsonArray ?: return null
        val asset = assets.asSequence()
            .mapNotNull { it as? JsonObject }
            .firstOrNull { it.string("name") == APK_ASSET_NAME }
            ?: assets.asSequence()
                .mapNotNull { it as? JsonObject }
                .firstOrNull { it.string("name")?.endsWith(".apk") == true }
            ?: return null

        val downloadUrl = asset.string("browser_download_url") ?: return null

        return UpdateRelease(
            versionCode = versionCode,
            versionName = root.string("name")?.takeIf { it.isNotBlank() } ?: tagName,
            tagName = tagName,
            downloadUrl = downloadUrl,
            sizeBytes = (asset["size"] as? JsonPrimitive)?.longOrNull,
            notes = root.string("body")?.takeIf { it.isNotBlank() },
        )
    }

    /** 前导零与 `build-0` 都不接受：CI 的构建号从 1 开始且单调递增。 */
    private val BUILD_TAG = Regex("""build-([1-9][0-9]*)""")

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull
}
