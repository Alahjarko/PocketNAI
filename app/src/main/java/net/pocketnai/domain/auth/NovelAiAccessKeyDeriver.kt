package net.pocketnai.domain.auth

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.digests.Blake2bDigest
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.util.Base64

/**
 * NovelAI Access Key 的本地派生。
 *
 * 输入邮箱与密码，输出 64 字符的 Access Key（此值只用于向 NovelAI 换取短期 Access Token）。
 * 密码与派生结果都**不得持久化**，调用方必须在用完后尽快解除引用。
 *
 * ## 算法（必须逐项匹配参考实现，不允许采用库的默认值）
 * ```
 * domain     = "novelai_data_access_key"
 * preSalt    = password 的前 6 个码位 + email + domain
 * salt       = BLAKE2b-128(preSalt UTF-8)          // 16 字节，不是 16 个十六进制字符
 * raw        = Argon2id(version 1.3, t=2, m=1953 KiB, p=1, out=64 bytes)
 * accessKey  = URL-safe Base64(raw) 的前 64 个字符
 * ```
 *
 * ## 两个容易写错的地方
 *
 * 1. **内存参数是 1953 KiB**（`floor(2_000_000 / 1024)`），不是 1954、不是 2000、更不是 2000000。
 * 2. **前 6 个字符按 Unicode 码位切，不是按 UTF-16 单元切。**
 *    参考实现是 Python，`password[:6]` 数的是码位；Kotlin 的 `take(6)` 数的是 UTF-16 单元，
 *    对 BMP 之外的字符（emoji 等代理对）两者结果不同，派生出的 Key 会完全不一样。
 *    因此这里显式按码位截取，并有用例 `emoji-leading` / `emoji-inside` 钉住这个行为。
 *
 * ## 输入约定
 * - **密码原样参与计算**：不去空白、不改大小写、不做任何规范化。首尾空格也是密码的一部分。
 * - **邮箱由调用方负责去掉用户误输入的首尾空白**；本类不 trim、也不转小写，
 *   以免在某些账号上悄悄改变派生结果。
 *
 * 该类是纯 Kotlin，不引用任何 Android API，可以在 JVM 单元测试里直接断言。
 */
class NovelAiAccessKeyDeriver(
    /** 派生耗时由 Argon2 的参数决定，必须离开主线程执行。 */
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : AccessKeyDeriver {

    /**
     * 同步派生。会消耗可感知的 CPU 与内存（Argon2 约 2 MB、两次迭代），
     * **不要在主线程调用**；不确定时用 [deriveAsync]。
     *
     * @throws IllegalArgumentException 邮箱或密码为空白时。
     */
    fun derive(email: String, password: String): String {
        require(email.isNotBlank()) { "邮箱不能为空" }
        require(password.isNotBlank()) { "密码不能为空" }

        val preSalt = buildPreSalt(email = email, password = password)
        val salt = blake2b128(preSalt.toByteArray(Charsets.UTF_8))
        val raw = argon2id(
            password = password.toByteArray(Charsets.UTF_8),
            salt = salt,
        )
        // URL-safe Base64。参考实现用的是带填充的 urlsafe_b64encode，
        // 但 64 字节的 Base64 结果里填充只出现在末尾，而这里只取前 64 个字符，
        // 因此带不带填充对结果没有影响。
        val encoded = Base64.getUrlEncoder().encodeToString(raw)
        return encoded.take(ACCESS_KEY_LENGTH)
    }

    /** 在 [dispatcher] 上执行的派生，供 ViewModel 使用，避免误在主线程跑 Argon2。 */
    override suspend fun deriveAccessKey(email: String, password: String): String =
        withContext(dispatcher) { derive(email, password) }

    /**
     * `password 的前 6 个码位 + email + domain`。
     *
     * 按码位而不是 UTF-16 单元截取，见类文档里的说明。
     */
    internal fun buildPreSalt(email: String, password: String): String =
        firstCodePoints(password, PASSWORD_PREFIX_CODE_POINTS) + email + DOMAIN

    /** 取字符串的前 [count] 个 Unicode 码位。密码短于 [count] 时返回整串，与参考实现一致。 */
    private fun firstCodePoints(value: String, count: Int): String {
        var index = 0
        var taken = 0
        while (index < value.length && taken < count) {
            index += Character.charCount(value.codePointAt(index))
            taken++
        }
        return value.substring(0, index)
    }

    internal fun blake2b128(input: ByteArray): ByteArray {
        // 注意单位：Bouncy Castle 的 Blake2bDigest(int) 接收的是**比特**数，
        // 传 16 会得到 16 比特 = 2 字节的摘要（其余字节保持为零），
        // 派生结果会整体跑偏。这里显式换算成比特，避免踩这个坑。
        val digest = Blake2bDigest(SALT_LENGTH_BYTES * Byte.SIZE_BITS)
        digest.update(input, 0, input.size)
        val salt = ByteArray(SALT_LENGTH_BYTES)
        digest.doFinal(salt, 0)
        return salt
    }

    internal fun argon2id(password: ByteArray, salt: ByteArray): ByteArray {
        val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(ARGON2_ITERATIONS)
            .withMemoryAsKB(ARGON2_MEMORY_KIB)
            .withParallelism(ARGON2_PARALLELISM)
            .withSalt(salt)
            .build()

        val generator = Argon2BytesGenerator()
        generator.init(parameters)
        val output = ByteArray(ARGON2_OUTPUT_BYTES)
        generator.generateBytes(password, output)
        return output
    }

    companion object {
        const val DOMAIN: String = "novelai_data_access_key"

        /** preSalt 里取密码前多少个码位。 */
        const val PASSWORD_PREFIX_CODE_POINTS: Int = 6

        /** BLAKE2b 输出长度（字节）。 */
        const val SALT_LENGTH_BYTES: Int = 16

        const val ARGON2_ITERATIONS: Int = 2

        /** `floor(2_000_000 / 1024)` = 1953，不能写成 1954 或 2000。 */
        const val ARGON2_MEMORY_KIB: Int = 2_000_000 / 1024

        const val ARGON2_PARALLELISM: Int = 1

        const val ARGON2_OUTPUT_BYTES: Int = 64

        /** 最终 Access Key 的字符数。 */
        const val ACCESS_KEY_LENGTH: Int = 64
    }
}
