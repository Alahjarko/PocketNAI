package net.pocketnai.ui.connect

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.pocketnai.core.AppError
import net.pocketnai.core.ErrorCode
import net.pocketnai.core.Outcome
import net.pocketnai.data.network.AccountSession
import net.pocketnai.data.network.AccountStatus
import net.pocketnai.data.network.NovelAiApi
import net.pocketnai.data.network.NovelAiAuthApi
import net.pocketnai.data.security.CredentialHint
import net.pocketnai.data.security.CredentialStore
import net.pocketnai.data.security.CredentialType
import net.pocketnai.data.security.SessionState
import net.pocketnai.data.security.StoredCredential
import net.pocketnai.domain.auth.AccessKeyDeriver
import net.pocketnai.domain.model.ImageModel
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * 连接状态机（《双认证模式实施计划》阶段 D 的必测清单）。
 *
 * 这些用例守的是**顺序与副作用**，而不是文案：
 * 谁先谁后、失败时有没有少做或多做一步、敏感值有没有被清掉。
 * 登录本身是否正确由阶段 A/B 的测试负责，这里全部用假实现。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private lateinit var credentials: FakeCredentialStore
    private lateinit var api: FakeNovelAiApi
    private lateinit var authApi: FakeAuthApi
    private lateinit var sessionState: SessionState
    private lateinit var viewModel: ConnectViewModel

    /** 派生结果固定，避免每个用例都真的跑一遍 Argon2。 */
    private val deriver = AccessKeyDeriver { _, _ -> FAKE_ACCESS_KEY }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        credentials = FakeCredentialStore()
        api = FakeNovelAiApi()
        authApi = FakeAuthApi()
        sessionState = SessionState(connected = false)
        viewModel = ConnectViewModel(
            credentialStore = credentials,
            api = api,
            authApi = authApi,
            accessKeyDeriver = deriver,
            sessionState = sessionState,
            clock = { 1_700_000_000_000L },
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---- PST ----

    @Test
    fun `PST 验证成功后以 PST 类型保存`() = runTest(dispatcher) {
        api.accountStatusResult = Outcome.Success(AccountStatus(connected = true))

        viewModel.onTokenInputChange("pst-fake-token")
        viewModel.connectWithPersistentToken()
        advanceUntilIdle()

        assertThat(credentials.saved).hasSize(1)
        assertThat(credentials.saved.single().token).isEqualTo("pst-fake-token")
        assertThat(credentials.saved.single().type).isEqualTo(CredentialType.PERSISTENT_API_TOKEN)
        assertThat(viewModel.state.value.connected).isTrue()
        assertThat(sessionState.connected.value).isTrue()
        assertThat(sessionState.credentialType.value).isEqualTo(CredentialType.PERSISTENT_API_TOKEN)
    }

    @Test
    fun `PST 验证失败不保存也不覆盖已有凭据`() = runTest(dispatcher) {
        api.accountStatusResult = Outcome.Failure(AppError.of(ErrorCode.TOKEN_INVALID))

        viewModel.onTokenInputChange("pst-fake-token")
        viewModel.connectWithPersistentToken()
        advanceUntilIdle()

        assertThat(credentials.saved).isEmpty()
        assertThat(credentials.cleared).isFalse()
        assertThat(viewModel.state.value.connected).isFalse()
        assertThat(viewModel.state.value.error?.code).isEqualTo(ErrorCode.TOKEN_INVALID)
    }

    // ---- 账号登录 ----

    @Test
    fun `账号登录在登录与复验都成功后以会话类型保存`() = runTest(dispatcher) {
        authApi.loginResult = Outcome.Success(AccountSession(accessToken = "fake-access-token"))
        api.accountStatusResult = Outcome.Success(AccountStatus(connected = true))

        viewModel.onModeChange(AuthMode.ACCOUNT_LOGIN)
        viewModel.onEmailInputChange("someone@example.com")
        viewModel.onPasswordInputChange("fake-password")
        viewModel.loginWithAccount()
        advanceUntilIdle()

        assertThat(credentials.saved).hasSize(1)
        assertThat(credentials.saved.single().type).isEqualTo(CredentialType.ACCOUNT_SESSION)
        // 保存的必须是登录换来的 Access Token，而不是派生的 Access Key。
        assertThat(credentials.saved.single().token).isEqualTo("fake-access-token")
        assertThat(sessionState.credentialType.value).isEqualTo(CredentialType.ACCOUNT_SESSION)
    }

    @Test
    fun `登录失败时不调用账户状态接口`() = runTest(dispatcher) {
        authApi.loginResult = Outcome.Failure(AppError.of(ErrorCode.LOGIN_CREDENTIALS_INVALID))

        viewModel.onModeChange(AuthMode.ACCOUNT_LOGIN)
        viewModel.onEmailInputChange("someone@example.com")
        viewModel.onPasswordInputChange("fake-password")
        viewModel.loginWithAccount()
        advanceUntilIdle()

        // 登录都没通过，就不该拿一个不存在的 Token 去复验。
        assertThat(api.accountStatusCalls).isEqualTo(0)
        assertThat(credentials.saved).isEmpty()
        assertThat(viewModel.state.value.error?.code)
            .isEqualTo(ErrorCode.LOGIN_CREDENTIALS_INVALID)
    }

    @Test
    fun `已取得会话但复验失败时不保存并给出专门提示`() = runTest(dispatcher) {
        authApi.loginResult = Outcome.Success(AccountSession(accessToken = "fake-access-token"))
        api.accountStatusResult = Outcome.Failure(AppError.of(ErrorCode.NETWORK_UNAVAILABLE))

        viewModel.onModeChange(AuthMode.ACCOUNT_LOGIN)
        viewModel.onEmailInputChange("someone@example.com")
        viewModel.onPasswordInputChange("fake-password")
        viewModel.loginWithAccount()
        advanceUntilIdle()

        assertThat(credentials.saved).isEmpty()
        // 不应该自动重发登录。
        assertThat(authApi.loginCalls).isEqualTo(1)
        assertThat(viewModel.state.value.notice)
            .isEqualTo(ConnectNotice.SESSION_OBTAINED_BUT_UNVERIFIED)
        // 邮箱保留方便重试，密码必须已清空。
        assertThat(viewModel.state.value.emailInput).isEqualTo("someone@example.com")
        assertThat(viewModel.state.value.passwordInput).isEmpty()
    }

    @Test
    fun `重复点击只发出一个登录请求`() = runTest(dispatcher) {
        authApi.loginResult = Outcome.Success(AccountSession(accessToken = "fake-access-token"))
        api.accountStatusResult = Outcome.Success(AccountStatus(connected = true))

        viewModel.onModeChange(AuthMode.ACCOUNT_LOGIN)
        viewModel.onEmailInputChange("someone@example.com")
        viewModel.onPasswordInputChange("fake-password")

        // 连点两次，中间不给协程任何执行机会。
        viewModel.loginWithAccount()
        viewModel.loginWithAccount()
        advanceUntilIdle()

        assertThat(authApi.loginCalls).isEqualTo(1)
        assertThat(credentials.saved).hasSize(1)
    }

    @Test
    fun `登录结束后清空密码`() = runTest(dispatcher) {
        authApi.loginResult = Outcome.Failure(AppError.of(ErrorCode.LOGIN_CREDENTIALS_INVALID))

        viewModel.onModeChange(AuthMode.ACCOUNT_LOGIN)
        viewModel.onEmailInputChange("someone@example.com")
        viewModel.onPasswordInputChange("fake-password")
        viewModel.loginWithAccount()
        advanceUntilIdle()

        assertThat(viewModel.state.value.passwordInput).isEmpty()
        assertThat(viewModel.state.value.passwordVisible).isFalse()
    }

    // ---- 模式切换与断开 ----

    @Test
    fun `切换认证模式会清空密码与错误`() = runTest(dispatcher) {
        viewModel.onModeChange(AuthMode.ACCOUNT_LOGIN)
        viewModel.onEmailInputChange("someone@example.com")
        viewModel.onPasswordInputChange("fake-password")

        viewModel.onModeChange(AuthMode.PERSISTENT_TOKEN)

        assertThat(viewModel.state.value.passwordInput).isEmpty()
        assertThat(viewModel.state.value.error).isNull()
        assertThat(viewModel.state.value.notice).isNull()
        // 邮箱不清：它是非敏感值，留着方便用户切回来继续。
        assertThat(viewModel.state.value.emailInput).isEqualTo("someone@example.com")
    }

    @Test
    fun `清空敏感输入只清密码`() = runTest(dispatcher) {
        viewModel.onModeChange(AuthMode.ACCOUNT_LOGIN)
        viewModel.onEmailInputChange("someone@example.com")
        viewModel.onPasswordInputChange("fake-password")

        viewModel.clearSensitiveInput()

        assertThat(viewModel.state.value.passwordInput).isEmpty()
        assertThat(viewModel.state.value.emailInput).isEqualTo("someone@example.com")
    }

    @Test
    fun `断开连接只清凭据不动其他本地数据`() = runTest(dispatcher) {
        credentials.seedWith(StoredCredential("existing", CredentialType.PERSISTENT_API_TOKEN))
        val fresh = ConnectViewModel(
            credentialStore = credentials,
            api = api,
            authApi = authApi,
            accessKeyDeriver = deriver,
            sessionState = sessionState,
            clock = { 1_700_000_000_000L },
        )
        assertThat(fresh.state.value.connected).isTrue()

        fresh.disconnect()

        assertThat(credentials.cleared).isTrue()
        assertThat(fresh.state.value.connected).isFalse()
        assertThat(fresh.state.value.hint).isNull()
        assertThat(sessionState.connected.value).isFalse()
        assertThat(sessionState.credentialType.value).isNull()
        // 这个 store 只负责凭据；历史、收藏与草稿在别的存储里，不受影响。
        assertThat(credentials.saved).isEmpty()
    }

    @Test
    fun `输入校验阻止空值提交`() = runTest(dispatcher) {
        assertThat(viewModel.state.value.canSubmitToken).isFalse()
        assertThat(viewModel.state.value.canSubmitLogin).isFalse()

        viewModel.onModeChange(AuthMode.ACCOUNT_LOGIN)
        viewModel.onEmailInputChange("someone@example.com")
        assertThat(viewModel.state.value.canSubmitLogin).isFalse()

        viewModel.onPasswordInputChange("x")
        assertThat(viewModel.state.value.canSubmitLogin).isTrue()
    }

    // ---- 假的协作对象 ----

    private class FakeCredentialStore : CredentialStore {
        val saved = mutableListOf<StoredCredential>()
        var cleared = false
        private var current: StoredCredential? = null

        fun seedWith(credential: StoredCredential) {
            current = credential
        }

        override fun hasCredential(): Boolean = current != null

        override fun load(): StoredCredential? = current

        override fun save(credential: StoredCredential) {
            current = credential
            saved += credential
        }

        override fun clear() {
            current = null
            cleared = true
        }

        override fun hint(): CredentialHint? = current?.let {
            CredentialHint(type = it.type, length = it.token.length, fingerprint = "deadbeef")
        }
    }

    private class FakeNovelAiApi : NovelAiApi {
        var accountStatusResult: Outcome<AccountStatus> =
            Outcome.Success(AccountStatus(connected = true))
        var accountStatusCalls = 0

        override suspend fun fetchAccountStatus(token: String): Outcome<AccountStatus> {
            accountStatusCalls++
            return accountStatusResult
        }

        override suspend fun fetchSubscriptionBalance(
            token: String,
        ): Outcome<net.pocketnai.domain.billing.SubscriptionBalance> =
            error("本测试不涉及余额读取")

        override suspend fun generateImage(
            token: String,
            payload: kotlinx.serialization.json.JsonObject,
            destinationZip: java.io.File,
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

        override fun generateImageStream(
            token: String,
            payload: kotlinx.serialization.json.JsonObject,
        ): kotlinx.coroutines.flow.Flow<net.pocketnai.data.network.GenerationStreamEvent> =
            error("本测试不涉及流式生成")
    }

    private class FakeAuthApi : NovelAiAuthApi {
        var loginResult: Outcome<AccountSession> =
            Outcome.Success(AccountSession(accessToken = "fake-access-token"))
        var loginCalls = 0

        override suspend fun login(accessKey: String): Outcome<AccountSession> {
            loginCalls++
            return loginResult
        }
    }

    private companion object {
        const val FAKE_ACCESS_KEY =
            "FAKEACCESSKEY_00000000000000000000000000000000000000000000000000"
    }
}
