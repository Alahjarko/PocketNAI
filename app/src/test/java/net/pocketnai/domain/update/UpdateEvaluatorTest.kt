package net.pocketnai.domain.update

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * 更新判定的纯逻辑（tag 解析 / Release 响应解析 / 新旧比较）。
 *
 * 响应样例按 GitHub `releases/latest` 的真实形状构造（字段名与层级一致）：
 * 这套解析面对的是外部系统，畸形输入必须有测试钉住，
 * 而且**认不出就必须返回"没有可用更新"，绝不猜一个下载地址出来**。
 */
class UpdateEvaluatorTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun releaseJson(
        tag: String = "build-42",
        name: String = "0.1.42",
        assets: String = DEFAULT_ASSETS,
        body: String = "构建 #42 · 提交 1a2b3c4 · feat: 示例",
    ): String = """
        {
          "url": "https://api.github.com/repos/Alahjarko/PocketNAI/releases/100",
          "tag_name": "$tag",
          "name": "$name",
          "body": "$body",
          "draft": false,
          "prerelease": false,
          "assets": $assets
        }
    """.trimIndent()

    // ---- tag 解析 ----

    @Test
    fun `CI 的 build 标签解析出构建号`() {
        assertThat(UpdateEvaluator.parseBuildNumber("build-42")).isEqualTo(42)
        assertThat(UpdateEvaluator.parseBuildNumber(" build-7 ")).isEqualTo(7)
    }

    @Test
    fun `不合规的标签一律返回 null`() {
        assertThat(UpdateEvaluator.parseBuildNumber(null)).isNull()
        assertThat(UpdateEvaluator.parseBuildNumber("")).isNull()
        assertThat(UpdateEvaluator.parseBuildNumber("build-")).isNull()
        assertThat(UpdateEvaluator.parseBuildNumber("build-0")).isNull()
        assertThat(UpdateEvaluator.parseBuildNumber("build-007")).isNull()
        assertThat(UpdateEvaluator.parseBuildNumber("v1.2.3")).isNull()
        assertThat(UpdateEvaluator.parseBuildNumber("build-42-extra")).isNull()
        assertThat(UpdateEvaluator.parseBuildNumber("build-99999999999")).isNull()
    }

    // ---- Release 响应解析 ----

    @Test
    fun `正常的 Release 响应解析出构建号、版本名与下载地址`() {
        val release = UpdateEvaluator.parseRelease(releaseJson(), json)

        assertThat(release).isNotNull()
        assertThat(release!!.versionCode).isEqualTo(42)
        assertThat(release.versionName).isEqualTo("0.1.42")
        assertThat(release.tagName).isEqualTo("build-42")
        assertThat(release.downloadUrl).endsWith("/build-42/PocketNAI.apk")
        assertThat(release.sizeBytes).isEqualTo(22_569_980L)
        assertThat(release.notes).contains("构建 #42")
    }

    @Test
    fun `没有 apk 附件时返回 null`() {
        val assets = """[{"name": "source.zip", "browser_download_url": "https://example.com/a.zip"}]"""

        assertThat(UpdateEvaluator.parseRelease(releaseJson(assets = assets), json)).isNull()
    }

    @Test
    fun `附件名不认识时回退取第一个 apk`() {
        val assets = """[
            {"name": "checksums.txt", "browser_download_url": "https://example.com/sums.txt"},
            {"name": "PocketNAI-debug.apk", "browser_download_url": "https://example.com/renamed.apk"}
        ]"""

        val release = UpdateEvaluator.parseRelease(releaseJson(assets = assets), json)
        assertThat(release?.downloadUrl).isEqualTo("https://example.com/renamed.apk")
    }

    @Test
    fun `多个 apk 附件时优先取约定名`() {
        val assets = """[
            {"name": "PocketNAI-other.apk", "browser_download_url": "https://example.com/other.apk"},
            {"name": "PocketNAI.apk", "browser_download_url": "https://example.com/agreed.apk"}
        ]"""

        val release = UpdateEvaluator.parseRelease(releaseJson(assets = assets), json)
        assertThat(release?.downloadUrl).isEqualTo("https://example.com/agreed.apk")
    }

    @Test
    fun `缺少下载地址时返回 null`() {
        val assets = """[{"name": "PocketNAI.apk", "size": 123}]"""

        assertThat(UpdateEvaluator.parseRelease(releaseJson(assets = assets), json)).isNull()
    }

    @Test
    fun `畸形 JSON 不抛异常只返回 null`() {
        assertThat(UpdateEvaluator.parseRelease("{ not json", json)).isNull()
        assertThat(UpdateEvaluator.parseRelease("[]", json)).isNull()
        assertThat(UpdateEvaluator.parseRelease("{}", json)).isNull()
    }

    @Test
    fun `tag 不合规的 Release 不被当成更新源`() {
        assertThat(UpdateEvaluator.parseRelease(releaseJson(tag = "latest"), json)).isNull()
    }

    // ---- 新旧比较 ----

    @Test
    fun `构建号更大才算有更新`() {
        val release = UpdateEvaluator.parseRelease(releaseJson(), json)!!

        assertThat(UpdateEvaluator.evaluate(41, release))
            .isInstanceOf(UpdateStatus.Available::class.java)
        assertThat(UpdateEvaluator.evaluate(42, release)).isEqualTo(UpdateStatus.UpToDate)
        assertThat(UpdateEvaluator.evaluate(43, release)).isEqualTo(UpdateStatus.UpToDate)
    }

    private companion object {
        const val DEFAULT_ASSETS =
            """[{"name": "PocketNAI.apk", "size": 22569980, """ +
                """"browser_download_url": "https://github.com/Alahjarko/PocketNAI/""" +
                """releases/download/build-42/PocketNAI.apk"}]"""
    }
}
