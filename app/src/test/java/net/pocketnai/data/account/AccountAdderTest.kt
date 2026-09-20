package net.pocketnai.data.account

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import net.pocketnai.core.AppError
import net.pocketnai.core.ErrorCode
import net.pocketnai.core.Outcome
import net.pocketnai.data.network.AccountSession
import net.pocketnai.data.network.AccountStatus
import net.pocketnai.data.network.GenerationStreamEvent
import net.pocketnai.data.network.NovelAiApi
import net.pocketnai.data.network.NovelAiAuthApi
import net.pocketnai.data.security.CredentialHint
import net.pocketnai.data.security.CredentialStore
import net.pocketnai.data.security.CredentialType
import net.pocketnai.data.security.StoredCredential
import net.pocketnai.domain.account.SavedAccount
import net.pocketnai.domain.auth.AccessKeyDeriver
import net.pocketnai.domain.billing.SubscriptionBalance
import net.pocketnai.domain.model.ImageModel
import org.junit.Test
import java.io.File

/**
 * 多账号"添加账号"的两条来源（技术决策记录 §32）。
 *
 * 这里钉住的核心纪律：**先验证、后保存、再切换** ——
 * 验证失败绝不能留下凭据，登录失败绝不能去碰复验接口。
 */
class AccountAdderTest {

    private val store = FakeCredentialStore()
    private val api = FakeNovelAiApi()
    private val authApi = FakeAuthApi()
    private var derivedKeys = mutableListOf<Pair<String, String>>()
    private val deriver = AccessKeyDeriver { email, password ->
        derivedKeys += email to password
        "AK_$email"
    }

    private fun adder() = AccountAdder(
        credentialStore = store,
        api = api,
        authApi = authApi,
        accessKeyDeriver = deriver,
        clock = { 0L },
    )

    // ---- 粘贴 PST ----

    @Test
    fun `粘贴 PST：验证通过后落盘并切到新账号`() = runBlocking {
        // 已有一个激活账号：新账号落盘时默认不激活，必须由 AccountAdder 显式切换。
        store.seedActiveAccount("旧账号")

        val outcome = adder().addWithToken(name = "小号", token = "  pst-token-1  ")

        assertThat(outcome).isInstanceOf(Outcome.Success::class.java)
        assertThat(store.savedCredentials).hasSize(1)
        assertThat(store.savedCredentials.single().token).isEqualTo("pst-token-1")
        assertThat(store.savedCredentials.single().type)
            .isEqualTo(CredentialType.PERSISTENT_API_TOKEN)
        assertThat(store.switchCalls).hasSize(1)
        assertThat(store.activeAccountId()).isEqualTo(store.switchCalls.single())
    }

    @Test
    fun `粘贴 PST：验证失败不落盘也不切换`() = runBlocking {
        store.seedActiveAccount("旧账号")
        api.accountStatusResult = Outcome.Failure(AppError.of(ErrorCode.TOKEN_INVALID))

        val outcome = adder().addWithToken(name = "小号", token = "bad-token")

        assertThat(outcome).isInstanceOf(Outcome.Failure::class.java)
        assertThat(store.savedCredentials).isEmpty()
        assertThat(store.switchCalls).isEmpty()
        assertThat(store.activeAccountId()).isEqualTo("acc-seed")
    }

    @Test
    fun `粘贴 PST：空 Token 直接失败且不发请求`() = runBlocking {
        val outcome = adder().addWithToken(name = "小号", token = "   ")

        assertThat(outcome).isInstanceOf(Outcome.Failure::class.java)
        assertThat(api.accountStatusCalls).isEqualTo(0)
        assertThat(store.savedCredentials).isEmpty()
    }

    // ---- 账号登录 ----

    @Test
    fun `账号登录：派生、登录、复验、落盘四步全走通`() = runBlocking {
        store.seedActiveAccount("旧账号")

        val outcome = adder().addWithLogin(
            name = "主号",
            email = " user@example.com ",
            password = " pass word ",
        )

        assertThat(outcome).isInstanceOf(Outcome.Success::class.java)
        // 邮箱去空白、密码原样（首尾空格是密码的一部分）。
        assertThat(derivedKeys).containsExactly("user@example.com" to " pass word ")
        assertThat(authApi.lastAccessKey).isEqualTo("AK_user@example.com")
        // 落盘的是登录拿到的 Access Token，不是派生值。
        assertThat(store.savedCredentials.single().token).isEqualTo("access-token-1")
        assertThat(store.savedCredentials.single().type).isEqualTo(CredentialType.ACCOUNT_SESSION)
        assertThat(store.switchCalls).hasSize(1)
    }

    @Test
    fun `账号登录：登录失败不碰复验接口`() = runBlocking {
        authApi.loginResult = Outcome.Failure(AppError.of(ErrorCode.LOGIN_CREDENTIALS_INVALID))

        val outcome = adder().addWithLogin(name = "主号", email = "a@b.c", password = "pw")

        assertThat(outcome).isInstanceOf(Outcome.Failure::class.java)
        assertThat(api.accountStatusCalls).isEqualTo(0)
        assertThat(store.savedCredentials).isEmpty()
    }

    @Test
    fun `账号登录：复验失败不落盘`() = runBlocking {
        api.accountStatusResult = Outcome.Failure(AppError.of(ErrorCode.TOKEN_INVALID))

        val outcome = adder().addWithLogin(name = "主号", email = "a@b.c", password = "pw")

        assertThat(outcome).isInstanceOf(Outcome.Failure::class.java)
        assertThat(authApi.loginCalls).isEqualTo(1)
        assertThat(store.savedCredentials).isEmpty()
        assertThat(store.switchCalls).isEmpty()
    }

    @Test
    fun `账号登录：空邮箱或空密码直接失败且不派生`() = runBlocking {
        val outcome = adder().addWithLogin(name = "主号", email = "  ", password = "pw")

        assertThat(outcome).isInstanceOf(Outcome.Failure::class.java)
        assertThat(derivedKeys).isEmpty()
        assertThat(authApi.loginCalls).isEqualTo(0)
    }

    // ---- 假实现 ----

    private class FakeCredentialStore : CredentialStore {
        val savedCredentials = mutableListOf<StoredCredential>()
        val switchCalls = mutableListOf<String>()
        private val accounts = mutableListOf<SavedAccount>()
        private var activeId: String? = null

        /** 预置一个已激活账号，模拟"已经有账号在用"的场景。 */
        fun seedActiveAccount(name: String) {
            accounts += SavedAccount(
                id = "acc-seed",
                name = name,
                type = CredentialType.PERSISTENT_API_TOKEN,
                tokenFingerprint = "seedfp",
                createdAtEpochMillis = 0L,
                isActive = true,
            )
            activeId = "acc-seed"
        }

        override fun hasCredential(): Boolean = activeId != null

        override fun load(): StoredCredential? = savedCredentials.lastOrNull()

        override fun save(credential: StoredCredential) {
            savedCredentials += credential
        }

        override fun clear() {
            activeId = null
        }

        override fun hint(): CredentialHint? =
            savedCredentials.lastOrNull()?.let {
                CredentialHint(type = it.type, length = it.token.length, fingerprint = "fp")
            }

        override fun listAccounts(): List<SavedAccount> = accounts.toList()

        override fun activeAccountId(): String? = activeId

        override fun saveAccount(name: String, credential: StoredCredential): SavedAccount {
            savedCredentials += credential
            val account = SavedAccount(
                id = "acc-${accounts.size + 1}",
                name = name,
                type = credential.type,
                tokenFingerprint = "fp",
                createdAtEpochMillis = 0L,
                isActive = activeId == null,
            )
            accounts += account
            if (activeId == null) activeId = account.id
            return account
        }

        override fun switchAccount(accountId: String): Boolean {
            switchCalls += accountId
            activeId = accountId
            return true
        }
    }

    private class FakeNovelAiApi : NovelAiApi {
        var accountStatusResult: Outcome<AccountStatus> = Outcome.Success(AccountStatus(connected = true))
        var accountStatusCalls = 0

        override suspend fun fetchAccountStatus(token: String): Outcome<AccountStatus> {
            accountStatusCalls++
            return accountStatusResult
        }

        override suspend fun fetchSubscriptionBalance(token: String): Outcome<SubscriptionBalance> =
            error("本测试不涉及余额读取")

        override suspend fun generateImage(
            token: String,
            payload: JsonObject,
            destinationZip: File,
        ): Outcome<Unit> = error("本测试不涉及生成")

        override fun generateImageStream(token: String, payload: JsonObject): Flow<GenerationStreamEvent> =
            flowOf()

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

        override suspend fun upscaleImage(
            token: String,
            imageBase64: String,
            destinationFile: File,
        ): Outcome<Unit> = error("本测试不涉及超分")
    }

    private class FakeAuthApi : NovelAiAuthApi {
        var loginResult: Outcome<AccountSession> = Outcome.Success(AccountSession("access-token-1"))
        var loginCalls = 0
        var lastAccessKey: String? = null

        override suspend fun login(accessKey: String): Outcome<AccountSession> {
            loginCalls++
            lastAccessKey = accessKey
            return loginResult
        }
    }
}
