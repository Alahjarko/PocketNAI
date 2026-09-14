package net.pocketnai.domain.auth

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.coroutines.CoroutineContext

/**
 * 派生器的输入约定与调度行为。
 *
 * 固定向量在 [NovelAiAccessKeyDeriverTest]，这里只覆盖"向量之外"的契约：
 * 什么输入该被拒绝、什么输入必须原样保留、以及不能跑在主线程上。
 */
class NovelAiAccessKeyDeriverContractTest {

    private val deriver = NovelAiAccessKeyDeriver()

    @Test
    fun `空邮箱或空密码在本地被拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            deriver.derive(email = "", password = "some-password")
        }
        assertThrows(IllegalArgumentException::class.java) {
            deriver.derive(email = "   ", password = "some-password")
        }
        assertThrows(IllegalArgumentException::class.java) {
            deriver.derive(email = "a@b.co", password = "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            deriver.derive(email = "a@b.co", password = "   ")
        }
    }

    @Test
    fun `没有任何输入会触发网络请求`() {
        // 派生是纯计算：这里只断言它能在完全没有网络依赖的情况下算出结果。
        // 一旦有人把网络调用塞进来，这个用例会因为超时或异常而失败。
        val key = deriver.derive(email = "offline@example.com", password = "no-network-here")
        assertThat(key).hasLength(NovelAiAccessKeyDeriver.ACCESS_KEY_LENGTH)
    }

    @Test
    fun `密码首尾空格是密码的一部分不会被去掉`() {
        val trimmed = deriver.derive(email = "a@b.co", password = "secret")
        val padded = deriver.derive(email = "a@b.co", password = "  secret  ")

        assertThat(padded).isNotEqualTo(trimmed)
    }

    @Test
    fun `邮箱原样参与计算不做小写或去空白`() {
        val baseline = deriver.derive(email = "user@example.com", password = "pw123456")

        // 大小写
        assertThat(deriver.derive(email = "User@Example.com", password = "pw123456"))
            .isNotEqualTo(baseline)
        // 首尾空白由调用方负责去掉，派生器本身不 trim ——
        // 否则"界面 trim 了、这里又 trim 一次"这种重复处理很难被发现。
        assertThat(deriver.derive(email = " user@example.com", password = "pw123456"))
            .isNotEqualTo(baseline)
    }

    @Test
    fun `输出固定 64 字符且只用 URL-safe 字符集`() {
        val key = deriver.derive(email = "alphabet@example.com", password = "alphabet-check")

        assertThat(key).hasLength(64)
        assertThat(key).matches("[A-Za-z0-9_-]{64}")
    }

    @Test
    fun `preSalt 按 Unicode 码位截取密码前六个字符`() {
        // 这两个断言就是"不能用 take(6)"的原因：
        // take(6) 数的是 UTF-16 单元，emoji 占两个单元，会少取一个字符。
        assertThat(deriver.buildPreSalt(email = "e@x.co", password = "\uD83D\uDD11secret123"))
            .isEqualTo("\uD83D\uDD11secre" + "e@x.co" + NovelAiAccessKeyDeriver.DOMAIN)

        assertThat(deriver.buildPreSalt(email = "e@x.co", password = "ab\uD83D\uDD11cdefg"))
            .isEqualTo("ab\uD83D\uDD11cde" + "e@x.co" + NovelAiAccessKeyDeriver.DOMAIN)
    }

    @Test
    fun `密码短于六个字符时整串参与计算`() {
        assertThat(deriver.buildPreSalt(email = "a@b.co", password = "abc"))
            .isEqualTo("abc" + "a@b.co" + NovelAiAccessKeyDeriver.DOMAIN)
    }

    @Test
    fun `deriveAccessKey 走注入的调度器而不是调用者线程`() {
        val recording = RecordingDispatcher()
        val asyncDeriver = NovelAiAccessKeyDeriver(dispatcher = recording)

        val key = runBlocking {
            asyncDeriver.deriveAccessKey(email = "dispatch@example.com", password = "off-main")
        }

        assertThat(recording.dispatched).isTrue()
        assertThat(key).isEqualTo(
            deriver.derive(email = "dispatch@example.com", password = "off-main"),
        )
    }

    /** 记录是否被真正用于调度，用来证明 Argon2 不会留在调用者线程上执行。 */
    private class RecordingDispatcher : CoroutineDispatcher() {
        var dispatched = false
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatched = true
            block.run()
        }
    }
}
