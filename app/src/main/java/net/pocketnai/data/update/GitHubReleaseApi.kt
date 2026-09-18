package net.pocketnai.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import net.pocketnai.core.AppError
import net.pocketnai.core.ErrorCode
import net.pocketnai.core.Outcome
import net.pocketnai.domain.update.UpdateEvaluator
import net.pocketnai.domain.update.UpdateRelease
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * 从 GitHub Releases 读"最新构建"。
 *
 * ## 为什么是 GitHub API
 * 应用没有自己的服务器；CI 把每次提交的构建发布成一个 Release，
 * 这里是唯一的"版本公告板"，也是自动更新能匿名工作的前提（仓库公开）。
 *
 * ## 纪律
 * - 只发一个匿名 GET，**不带任何凭据、不带任何用户数据**（URL 里只有仓库名）；
 * - 只读 `releases/latest`，不列全量、不下载；
 * - 任何失败都映射成 [ErrorCode]，由界面如实显示"没查成"，
 *   **绝不把它当作"已是最新"**。
 */
class GitHubReleaseApi(
    private val repo: String,
    private val client: OkHttpClient,
    private val json: Json,
    private val baseUrl: String = DEFAULT_BASE_URL,
) {

    /** `owner/name` 形式的仓库标识（装配时的常量，见 BuildConfig.UPDATE_REPO）。 */
    suspend fun latestRelease(): Outcome<UpdateRelease> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/repos/$repo/releases/latest")
            .header("Accept", "application/vnd.github+json")
            // GitHub 要求请求带 User-Agent，缺了会直接 403。
            .header("User-Agent", USER_AGENT)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                when {
                    !response.isSuccessful -> Outcome.Failure(
                        AppError.of(ErrorCode.UPDATE_CHECK_FAILED, detail = "http ${response.code}"),
                    )

                    else -> {
                        val body = response.body?.string()
                        val release = body?.let { UpdateEvaluator.parseRelease(it, json) }
                        if (release == null) {
                            Outcome.Failure(
                                AppError.of(ErrorCode.UPDATE_CHECK_FAILED, detail = "unrecognized release"),
                            )
                        } else {
                            Outcome.Success(release)
                        }
                    }
                }
            }
        } catch (e: IOException) {
            Outcome.Failure(AppError.of(ErrorCode.NETWORK_UNAVAILABLE))
        }
    }

    private companion object {
        const val DEFAULT_BASE_URL = "https://api.github.com"
        const val USER_AGENT = "PocketNAI-Android"
    }
}
