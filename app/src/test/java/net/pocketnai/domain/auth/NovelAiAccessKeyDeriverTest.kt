package net.pocketnai.domain.auth

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Access Key 派生的固定测试向量。
 *
 * ## 这些期望值是怎么来的
 *
 * 全部由 Aedial 的参考实现（`novelai_api/utils.py` 的 `argon_hash` / `get_access_key`）
 * 对**虚构账号**计算得到，而不是用本仓库的 Kotlin 代码算出来的 —— 否则就是自己证明自己。
 * 参考实现使用 argon2-cffi（C 实现），这里使用 Bouncy Castle（纯 Java），
 * 两条独立实现路径逐字节一致才有意义。
 *
 * 生成时逐字提取参考实现的函数体执行，没有人工转写。
 * **输入全部是虚构值**，不含任何真实邮箱、密码、PST 或 Access Key。
 *
 * ## 几个用例专门针对容易写错的地方
 *
 * - `password-1` / `password-5` / `password-6` / `password-7`：preSalt 里取密码前 6 个**码位**，
 *   密码短于 6 时取整串。边界值必须覆盖，这类"差一个字符"的错法不会自己暴露。
 * - `specials`：首尾空格、引号、反斜杠、标点都要原样参与计算；密码**不能**被 trim。
 * - `emoji-leading` / `emoji-inside`：参考实现按 Unicode 码位切，Kotlin 的 `take(6)`
 *   按 UTF-16 单元切。若实现误用 `take`，这两个用例会失败，其余用例仍然通过。
 */
@RunWith(Parameterized::class)
class NovelAiAccessKeyDeriverTest(
    private val caseName: String,
    private val email: String,
    private val password: String,
    private val expectedAccessKey: String,
) {

    private val deriver = NovelAiAccessKeyDeriver()

    @Test
    fun `派生结果与参考实现逐字节一致`() {
        assertThat(deriver.derive(email = email, password = password))
            .isEqualTo(expectedAccessKey)
    }

    companion object {
        private const val BACKSLASH = "\\"

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun vectors(): List<Array<Any>> = listOf(
            arrayOf(
                "ascii-basic",
                "test@example.com",
                "correct horse battery staple",
                "-rRTA54TEApfxWczoIupaAfBQ7C4frlPnZ5roUe29THxdTHwDm-TZvHckOQObcHu",
            ),
            arrayOf(
                "password-1",
                "a@b.co",
                "x",
                "JQktjDTioh5HHgJBByNVP6xGskFNDZ4oCfBhfW4Xj8MXpTCIsEGYlhenvH8MBgSI",
            ),
            arrayOf(
                "password-5",
                "a@b.co",
                "short",
                "wr30FHn1YH8FOBhjuq5OfYkCOEXoEUwmAUKYP4qrtHrPmgvPo6DjmEIXAn2TGggN",
            ),
            arrayOf(
                "password-6",
                "a@b.co",
                "sixchr",
                "cH2wKijHMz7HTWn8WdLa8Kgr3RNq2zIQpBGiFG3MDAd66C9Ire7EzLzcYDsNS0pB",
            ),
            arrayOf(
                "password-7",
                "a@b.co",
                "sevench",
                "x5Bz7Ezn0Rio0xMDobxgg9z_zIMYwYZuHGkeI1S5FFXvGOFXgbdzz1tZ8dc0zdj-",
            ),
            arrayOf(
                "specials",
                "weird+tag@example.org",
                " p@ss\"w" + BACKSLASH + "ord,!# ",
                "vcdas85cmuol8d7gg9TQPQxtHu1BMziRp7BFSlRfykM1uFaDGoV2EMsU6Ywkncmh",
            ),
            arrayOf(
                "email-plus",
                "user+filter@gmail.com",
                "pass word",
                "4hefj2nwdqJuOST6KiLGrTh065yeuo7rbdmaKlY785wLxwN87eE7m55b7A4mNs3a",
            ),
            arrayOf(
                "cjk",
                "用户@例子.中国",
                "密码密码",
                "9XdhLCqljVqek9QNRKm0-b5gVQXJbtTX_1EHo10frohhB4oZHrOpToyga4_WNbxY",
            ),
            arrayOf(
                "cyrillic",
                "unicode@example.com",
                "пароль",
                "EwPmfmhzvJ_5U6B-Noxwwv9io-3B_YT2Hz7wi2AxRDqj4YPccZ9v9PkwPMj5Jh3E",
            ),
            // 以下两个用例覆盖"必须按码位而不是 UTF-16 单元截取前 6 个字符"。
            arrayOf(
                "emoji-leading",
                "emoji@example.com",
                "\uD83D\uDD11secret123",
                "7cr6rBuBX9iGwxuSjBf5_DqV1_eEUISjpTZcmjJxYbvxrzc2uVbgi3zOVHMF1XZE",
            ),
            arrayOf(
                "emoji-inside",
                "emoji@example.com",
                "ab\uD83D\uDD11cdefg",
                "eGruqT5nDL4bXKlAxSl8sPanQ6JYe-gzEbMDlLDwo-F-w407HRUAzwr7rP3kpp5E",
            ),
            arrayOf(
                "tab-and-quotes",
                "quote@example.com",
                "it's\ta test",
                "u7aiS3c3SizXXnbLkKEkEgX2uxhzqqMveM_sKnYBvXEiiolfrK937d3TyvSLVxBj",
            ),
            arrayOf(
                "long-password",
                "long@example.com",
                "x".repeat(200),
                "nEnioQ-c_ygf4imHOzQAa_fNb-2lnOhbDgA6XF5l-rijEEmFnSwzT2Lo-1zZ_hsX",
            ),
        )
    }
}
