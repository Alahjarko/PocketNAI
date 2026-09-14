package net.pocketnai.data.repo

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.pocketnai.core.AppError
import net.pocketnai.core.ErrorCode
import net.pocketnai.core.Outcome
import net.pocketnai.data.network.AccountStatus
import net.pocketnai.data.network.NovelAiApi
import net.pocketnai.data.network.SubscriptionBalanceParser
import net.pocketnai.data.security.CredentialHint
import net.pocketnai.data.security.CredentialStore
import net.pocketnai.data.security.CredentialType
import net.pocketnai.data.security.StoredCredential
import net.pocketnai.domain.billing.SubscriptionBalance
import net.pocketnai.domain.model.ImageModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Test
import java.io.File

/**
 * 余额仓库（余额规划 §12.3）。
 *
 * 重点守三件事：**不重复发请求**、**失败不清零**、**换账号不留旧余额**。
 * 这些都是"看起来对、实际会在真机上出事"的地方。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountBalanceRepositoryTest {

    private val fakeApi = FakeApi()
    private val credentials = FakeCredentialStore(
        StoredCredential(token = "fake-token-for-tests", type = CredentialType.PERSISTENT_API_TOKEN),
    )
    private var now = 0L

    private fun repository(scope: kotlinx.coroutines.CoroutineScope) = AccountBalanceRepository(
        api = fakeApi,
        credentialStore = credentials,
        clock = { now },
        scope = scope,
    )

    private fun balanceResponse(subscription: Long = 10_000, purchased: Long = 2_000): SubscriptionBalance =
        (SubscriptionBalanceParser.parse(
            body = """
                {"tier":0,"active":false,"trainingStepsLeft":
                  {"fixedTrainingStepsLeft":$subscription,"purchasedTrainingSteps":$purchased},
                 "usage":{"percent":87,"isNegative":false,"timeUntilNextPercent":3600}}
            """.trimIndent(),
            json = Json { ignoreUnknownKeys = true },
            fetchedAtMillis = now,
        ) as Outcome.Success).value

    // ---- 基本路径 ----

    @Test
    fun `首次刷新读回余额`() = runTest {
        fakeApi.result = Outcome.Success(balanceResponse())
        val repository = repository(backgroundScope)

        val outcome = repository.refresh(BalanceRefreshReason.CONNECTED, force = true)
        advanceUntilIdle()

        assertThat(outcome).isInstanceOf(Outcome.Success::class.java)
        assertThat(repository.latestOrNull()?.totalAnlas).isEqualTo(12_000L)
        assertThat(fakeApi.calls).isEqualTo(1)
        assertThat(repository.state.value).isInstanceOf(BalanceState.Available::class.java)
    }

    @Test
    fun `没有凭据时不发网络请求`() = runTest {
        credentials.credential = null
        val repository = repository(backgroundScope)

        val outcome = repository.refresh(BalanceRefreshReason.USER_REQUESTED, force = true)
        advanceUntilIdle()

        assertThat((outcome as Outcome.Failure).error.code).isEqualTo(ErrorCode.TOKEN_INVALID)
        assertThat(fakeApi.calls).isEqualTo(0)
        assertThat(repository.state.value).isEqualTo(BalanceState.Unavailable)
    }

    @Test
    fun `五分钟内的前台事件不重复请求`() = runTest {
        fakeApi.result = Outcome.Success(balanceResponse())
        val repository = repository(backgroundScope)
        repository.refresh(BalanceRefreshReason.CONNECTED, force = true)
        advanceUntilIdle()

        now += 60_000L
        repository.refresh(BalanceRefreshReason.APP_FOREGROUND)

        assertThat(fakeApi.calls).isEqualTo(1)
    }

    @Test
    fun `超过新鲜期后前台事件会重新请求`() = runTest {
        fakeApi.result = Outcome.Success(balanceResponse())
        val repository = repository(backgroundScope)
        repository.refresh(BalanceRefreshReason.CONNECTED, force = true)
        advanceUntilIdle()

        now += AccountBalanceRepository.BALANCE_FRESH_MS + 1
        repository.refresh(BalanceRefreshReason.APP_FOREGROUND)
        advanceUntilIdle()

        assertThat(fakeApi.calls).isEqualTo(2)
    }

    @Test
    fun `手动刷新无视缓存`() = runTest {
        fakeApi.result = Outcome.Success(balanceResponse())
        val repository = repository(backgroundScope)
        repository.refresh(BalanceRefreshReason.CONNECTED, force = true)
        advanceUntilIdle()

        repository.refresh(BalanceRefreshReason.USER_REQUESTED, force = true)
        advanceUntilIdle()

        assertThat(fakeApi.calls).isEqualTo(2)
    }

    @Test
    fun `两个并发刷新合并成一次请求`() = runTest {
        fakeApi.result = Outcome.Success(balanceResponse())
        val repository = repository(backgroundScope)

        val first = async { repository.refresh(BalanceRefreshReason.GENERATION_COMPLETED, force = true) }
        val second = async { repository.refresh(BalanceRefreshReason.INSUFFICIENT_ANLAS, force = true) }
        first.await()
        second.await()
        advanceUntilIdle()

        assertThat(fakeApi.calls).isEqualTo(1)
    }

    // ---- 失败处理 ----

    @Test
    fun `刷新失败保留上一次余额并标记失败`() = runTest {
        fakeApi.result = Outcome.Success(balanceResponse(subscription = 500))
        val repository = repository(backgroundScope)
        repository.refresh(BalanceRefreshReason.CONNECTED, force = true)
        advanceUntilIdle()

        fakeApi.result = Outcome.Failure(AppError.of(ErrorCode.SERVER_ERROR))
        val outcome = repository.refresh(BalanceRefreshReason.USER_REQUESTED, force = true)
        advanceUntilIdle()

        assertThat(outcome).isInstanceOf(Outcome.Failure::class.java)
        // 关键：失败绝不能把界面上的余额清零。
        assertThat(repository.latestOrNull()?.subscriptionAnlas).isEqualTo(500L)
        assertThat(repository.state.value).isInstanceOf(BalanceState.RefreshFailed::class.java)
    }

    @Test
    fun `从未成功过时刷新失败不给出余额`() = runTest {
        fakeApi.result = Outcome.Failure(AppError.of(ErrorCode.NETWORK_UNAVAILABLE))
        val repository = repository(backgroundScope)

        repository.refresh(BalanceRefreshReason.USER_REQUESTED, force = true)
        advanceUntilIdle()

        assertThat(repository.latestOrNull()).isNull()
        assertThat(repository.state.value).isInstanceOf(BalanceState.RefreshFailed::class.java)
    }

    @Test
    fun `失败的错误里不含凭据`() = runTest {
        fakeApi.result = Outcome.Failure(AppError.of(ErrorCode.TOKEN_INVALID, detail = "HTTP 401"))
        val repository = repository(backgroundScope)

        val outcome = repository.refresh(BalanceRefreshReason.USER_REQUESTED, force = true)
        advanceUntilIdle()

        val error = (outcome as Outcome.Failure).error
        assertThat(error.detail.orEmpty()).doesNotContain("fake-token-for-tests")
        assertThat(error.correlationId.orEmpty()).doesNotContain("fake-token-for-tests")
    }

    @Test
    fun `401 不删除本地凭据`() = runTest {
        fakeApi.result = Outcome.Failure(AppError.of(ErrorCode.TOKEN_INVALID))
        val repository = repository(backgroundScope)

        repository.refresh(BalanceRefreshReason.USER_REQUESTED, force = true)
        advanceUntilIdle()

        // 是否"需要重新连接"由连接页统一决定，余额仓库不擅自清凭据。
        assertThat(credentials.credential).isNotNull()
        assertThat(credentials.cleared).isFalse()
    }

    // ---- 账号切换 ----

    @Test
    fun `切换账号后旧余额立即消失`() = runTest {
        fakeApi.result = Outcome.Success(balanceResponse(subscription = 9_999))
        val repository = repository(backgroundScope)
        repository.refresh(BalanceRefreshReason.CONNECTED, force = true)
        advanceUntilIdle()
        assertThat(repository.latestOrNull()).isNotNull()

        repository.clear()

        assertThat(repository.latestOrNull()).isNull()
        assertThat(repository.state.value).isEqualTo(BalanceState.Unavailable)
    }

    @Test
    fun `刷新期间凭据被换掉时不写入旧账号的余额`() = runTest {
        fakeApi.result = Outcome.Success(balanceResponse(subscription = 9_999))
        val repository = repository(backgroundScope)

        // 请求发出后、结果写入前，用户换了凭据。
        fakeApi.onCall = { credentials.credential = StoredCredential("other", CredentialType.ACCOUNT_SESSION) }
        repository.refresh(BalanceRefreshReason.USER_REQUESTED, force = true)
        advanceUntilIdle()

        assertThat(repository.latestOrNull()).isNull()
    }

    @Test
    fun `invalidate 把当前值标记为旧数据`() = runTest {
        fakeApi.result = Outcome.Success(balanceResponse())
        val repository = repository(backgroundScope)
        repository.refresh(BalanceRefreshReason.CONNECTED, force = true)
        advanceUntilIdle()

        repository.invalidate()

        assertThat((repository.state.value as BalanceState.Available).stale).isTrue()
    }

    // ---- 测试替身 ----

    private class FakeApi : NovelAiApi {
        var result: Outcome<SubscriptionBalance> = Outcome.Failure(AppError.of(ErrorCode.UNKNOWN))
        var calls = 0
        var onCall: (() -> Unit)? = null

        override suspend fun fetchSubscriptionBalance(token: String): Outcome<SubscriptionBalance> {
            calls++
            onCall?.invoke()
            return result
        }

        override suspend fun fetchAccountStatus(token: String): Outcome<AccountStatus> =
            error("本测试不涉及账户状态")

        override suspend fun generateImage(
            token: String,
            payload: JsonObject,
            destinationZip: File,
        ): Outcome<Unit> = error("本测试不涉及生成")

        override suspend fun suggestTags(
            token: String,
            model: ImageModel,
            prompt: String,
        ): Outcome<List<String>> = error("本测试不涉及标签建议")

        override suspend fun encodeVibe(
            token: String,
            model: ImageModel,
            imageBase64: String,
            informationExtracted: Double,
        ): Outcome<ByteArray> = error("本测试不涉及 Vibe 编码")
    }

    private class FakeCredentialStore(var credential: StoredCredential?) : CredentialStore {
        var cleared = false

        override fun hasCredential(): Boolean = credential != null

        override fun load(): StoredCredential? = credential

        override fun save(credential: StoredCredential) {
            this.credential = credential
        }

        override fun clear() {
            cleared = true
            credential = null
        }

        override fun hint(): CredentialHint? = null
    }
}
