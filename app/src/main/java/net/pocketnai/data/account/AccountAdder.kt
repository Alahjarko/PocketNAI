package net.pocketnai.data.account

import net.pocketnai.core.AppError
import net.pocketnai.core.ErrorCode
import net.pocketnai.core.Outcome
import net.pocketnai.data.network.NovelAiApi
import net.pocketnai.data.network.NovelAiAuthApi
import net.pocketnai.data.security.CredentialStore
import net.pocketnai.data.security.CredentialType
import net.pocketnai.data.security.StoredCredential
import net.pocketnai.domain.account.SavedAccount
import net.pocketnai.domain.auth.AccessKeyDeriver

/**
 * 多账号的"添加账号"：粘贴 PST 与邮箱密码登录两种来源，共用同一条纪律 ——
 * **先验证、后保存、再切换**（与连接页一致，AGENTS.md 凭据一节）。
 *
 * 早期的添加路径把用户粘的 Token **不验证**就直接落盘并切换，粘错一个字符就会把
 * 会话带进"已保存但不可用"的状态，而且切换之后才暴露。这里两种来源都先过
 * `/user/data`：验证失败不落盘、不切换，错误原样返回给界面。
 */
class AccountAdder(
    private val credentialStore: CredentialStore,
    private val api: NovelAiApi,
    private val authApi: NovelAiAuthApi,
    private val accessKeyDeriver: AccessKeyDeriver,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** 粘贴 PST 添加。 */
    suspend fun addWithToken(name: String, token: String): Outcome<SavedAccount> {
        val trimmed = token.trim()
        if (trimmed.isBlank()) {
            return Outcome.Failure(AppError.of(ErrorCode.TOKEN_INVALID, detail = "Token 为空"))
        }
        return when (val verified = api.fetchAccountStatus(trimmed)) {
            is Outcome.Failure -> verified
            is Outcome.Success -> saveAndSwitch(
                name = name,
                credential = StoredCredential(
                    token = trimmed,
                    type = CredentialType.PERSISTENT_API_TOKEN,
                    createdAtEpochMillis = clock(),
                ),
            )
        }
    }

    /**
     * 邮箱 + 密码登录添加。顺序与连接页相同：本地派生 Access Key → `/user/login`
     * 换 Token → `/user/data` 复验 → 才落盘。登录失败不会去复验，复验失败不会落盘。
     */
    suspend fun addWithLogin(name: String, email: String, password: String): Outcome<SavedAccount> {
        val mail = email.trim()
        if (mail.isBlank() || password.isEmpty()) {
            return Outcome.Failure(AppError.of(ErrorCode.UNKNOWN, detail = "邮箱或密码为空"))
        }
        val accessKey = accessKeyDeriver.deriveAccessKey(email = mail, password = password)
        val accessToken = when (val login = authApi.login(accessKey)) {
            is Outcome.Failure -> return login
            is Outcome.Success -> login.value.accessToken
        }
        return when (val verified = api.fetchAccountStatus(accessToken)) {
            is Outcome.Failure -> verified
            is Outcome.Success -> saveAndSwitch(
                name = name,
                credential = StoredCredential(
                    token = accessToken,
                    type = CredentialType.ACCOUNT_SESSION,
                    createdAtEpochMillis = clock(),
                ),
            )
        }
    }

    private fun saveAndSwitch(name: String, credential: StoredCredential): Outcome<SavedAccount> {
        val saved = credentialStore.saveAccount(name = name, credential = credential)
        // 已有激活账号时新账号默认不激活；"添加"的语义是切过去用，因此显式切换。
        // 切换失败（理论上不会：刚写的密文读不回）时保留已保存的账号，由界面提示。
        if (!saved.isActive && !credentialStore.switchAccount(saved.id)) {
            return Outcome.Failure(
                AppError.of(ErrorCode.UNKNOWN, detail = "账号已保存但切换失败"),
            )
        }
        return Outcome.Success(saved)
    }
}
