package net.pocketnai.ui.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.pocketnai.core.AppError
import net.pocketnai.core.Outcome
import net.pocketnai.data.network.AccountStatus
import net.pocketnai.data.network.NovelAiApi
import net.pocketnai.data.network.NovelAiAuthApi
import net.pocketnai.data.security.CredentialHint
import net.pocketnai.data.security.CredentialStore
import net.pocketnai.data.security.CredentialType
import net.pocketnai.data.security.SessionState
import net.pocketnai.data.security.StoredCredential
import net.pocketnai.domain.auth.AccessKeyDeriver

/** 连接页的两种方式。默认 PST —— 它是官方推荐的第三方接入方式。 */
enum class AuthMode {
    PERSISTENT_TOKEN,
    ACCOUNT_LOGIN,
}

/** 需要单独提示（而不是走通用错误文案）的情况。 */
enum class ConnectNotice {
    /** 已经拿到会话，但用 `/user/data` 复验失败，因此没有保存。 */
    SESSION_OBTAINED_BUT_UNVERIFIED,
}

/**
 * 连接流程（规划书 4.1 与《双认证模式实施计划》阶段 D）。
 *
 * ## 两条路径的共同原则
 * - **先验证、后保存**：任何凭据都必须先通过 `/user/data` 才落盘；验证失败不覆盖已有凭据。
 * - **不自动重试**：登录与验证都是一次性请求，失败只提示用户手动重来。
 * - **敏感值生命周期尽可能短**：密码、派生的 Access Key、临时 Access Token
 *   都只活在这一段协程里，`finally` 一定会清掉界面上的密码。
 *
 * ## 已知限制
 * Kotlin 的 `String` 不可变、无法主动擦除，因此"清空"只能解除引用并清掉界面状态，
 * 不能承诺内存里绝对无残留。这里不做过度承诺。
 */
class ConnectViewModel(
    private val credentialStore: CredentialStore,
    private val api: NovelAiApi,
    private val authApi: NovelAiAuthApi,
    private val accessKeyDeriver: AccessKeyDeriver,
    private val sessionState: SessionState,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    data class UiState(
        val mode: AuthMode = AuthMode.PERSISTENT_TOKEN,

        // ---- PST 表单 ----
        val tokenInput: String = "",

        // ---- 账号表单 ----
        val emailInput: String = "",
        val passwordInput: String = "",
        val passwordVisible: Boolean = false,

        val submitting: Boolean = false,
        val error: AppError? = null,
        val notice: ConnectNotice? = null,

        val connected: Boolean = false,
        val hint: CredentialHint? = null,
        val account: AccountStatus? = null,
    ) {
        val canSubmitToken: Boolean
            get() = !submitting && tokenInput.isNotBlank()

        val canSubmitLogin: Boolean
            get() = !submitting && emailInput.isNotBlank() && passwordInput.isNotEmpty()

        /** 已连接卡片用：当前保存的是哪种凭据。 */
        val credentialType: CredentialType? get() = hint?.type
    }

    private val _state = MutableStateFlow(
        UiState(
            connected = credentialStore.hasCredential(),
            hint = credentialStore.hint(),
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    // ---- 模式与输入 ----

    /**
     * 切换连接方式。
     *
     * 除了切标签还要清掉密码与上一轮提示：密码不该因为用户点了一下标签就留在内存里，
     * 上一条错误也不该带到另一个表单上。
     */
    fun onModeChange(mode: AuthMode) {
        if (_state.value.mode == mode) return
        _state.update {
            it.copy(
                mode = mode,
                passwordInput = "",
                passwordVisible = false,
                error = null,
                notice = null,
            )
        }
    }

    fun onTokenInputChange(value: String) {
        _state.update { it.copy(tokenInput = value.trim(), error = null, notice = null) }
    }

    /** 邮箱只去掉用户误输入的首尾空白；不转小写，避免改变派生结果。 */
    fun onEmailInputChange(value: String) {
        _state.update { it.copy(emailInput = value.trim(), error = null, notice = null) }
    }

    /** 密码**原样保留**：首尾空格也是密码的一部分，trim 会让派生结果与官方不一致。 */
    fun onPasswordInputChange(value: String) {
        _state.update { it.copy(passwordInput = value, error = null, notice = null) }
    }

    fun onTogglePasswordVisibility() {
        _state.update { it.copy(passwordVisible = !it.passwordVisible) }
    }

    /** 离开页面、进入后台或断开连接时调用，确保密码不在界面状态里停留。 */
    fun clearSensitiveInput() {
        _state.update { it.copy(passwordInput = "", passwordVisible = false) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    fun dismissNotice() {
        _state.update { it.copy(notice = null) }
    }

    // ---- PST ----

    fun connectWithPersistentToken() {
        val token = _state.value.tokenInput
        if (token.isBlank() || _state.value.submitting) return

        // 同步置位"提交中"，而不是等协程内部再置位。
        // 否则连点两次时两个协程都会起步 —— 提交保护必须与点击同一时刻生效，
        // 不能依赖调度器的立即执行行为。
        _state.update { it.copy(submitting = true, error = null, notice = null) }
        viewModelScope.launch {
            when (val outcome = api.fetchAccountStatus(token)) {
                is Outcome.Success -> {
                    // 验证成功才落地，且只保存 Token 本身与脱敏指纹。
                    credentialStore.save(
                        StoredCredential(
                            token = token,
                            type = CredentialType.PERSISTENT_API_TOKEN,
                            createdAtEpochMillis = clock(),
                        ),
                    )
                    sessionState.update(connected = true, type = CredentialType.PERSISTENT_API_TOKEN)
                    _state.update {
                        it.copy(
                            tokenInput = "",
                            submitting = false,
                            connected = true,
                            hint = credentialStore.hint(),
                            account = outcome.value,
                            error = null,
                        )
                    }
                }

                is Outcome.Failure -> {
                    // 失败不覆盖已有凭据，也不清空用户已输入的内容。
                    _state.update { it.copy(submitting = false, error = outcome.error) }
                }
            }
        }
    }

    // ---- 账号登录 ----

    /**
     * 邮箱 + 密码登录。
     *
     * 顺序严格按计划书 §6.2：
     * 本地派生 Access Key → `/user/login` 换 Token → 用该 Token 调 `/user/data` 复验 → 才保存。
     *
     * 三个关键约定：
     * - 登录失败**不**调用 `/user/data`；
     * - `/user/data` 失败**不**保存会话（避免把"部分可用"的响应当成已连接）；
     * - `/user/data` 失败**不**重发登录，保留邮箱方便重试，但清空密码。
     */
    fun loginWithAccount() {
        val snapshot = _state.value
        if (!snapshot.canSubmitLogin) return

        val email = snapshot.emailInput.trim()
        val password = snapshot.passwordInput

        // 同上：提交保护必须同步生效，否则重复点击会发出两个登录请求。
        _state.update { it.copy(submitting = true, error = null, notice = null) }
        viewModelScope.launch {
            var accessKey: String? = null
            var accessToken: String? = null
            try {
                accessKey = accessKeyDeriver.deriveAccessKey(email = email, password = password)

                when (val loginOutcome = authApi.login(accessKey)) {
                    is Outcome.Failure -> {
                        _state.update { it.copy(submitting = false, error = loginOutcome.error) }
                        return@launch
                    }

                    is Outcome.Success -> accessToken = loginOutcome.value.accessToken
                }

                val token = accessToken
                when (val verified = api.fetchAccountStatus(token)) {
                    is Outcome.Success -> {
                        credentialStore.save(
                            StoredCredential(
                                token = token,
                                type = CredentialType.ACCOUNT_SESSION,
                                createdAtEpochMillis = clock(),
                            ),
                        )
                        sessionState.update(connected = true, type = CredentialType.ACCOUNT_SESSION)
                        _state.update {
                            it.copy(
                                submitting = false,
                                connected = true,
                                hint = credentialStore.hint(),
                                account = verified.value,
                                error = null,
                                notice = null,
                            )
                        }
                    }

                    is Outcome.Failure -> {
                        // 已取得会话但复验失败：不保存、不自动重登，也不保留密码。
                        _state.update {
                            it.copy(
                                submitting = false,
                                error = verified.error,
                                notice = ConnectNotice.SESSION_OBTAINED_BUT_UNVERIFIED,
                            )
                        }
                    }
                }
            } finally {
                // 成功、失败、协程被取消都会走到这里。
                accessKey = null
                accessToken = null
                _state.update { it.copy(passwordInput = "", passwordVisible = false) }
            }
        }
    }

    // ---- 断开 ----

    /** 只清凭据，不影响历史图片、提示词收藏与生成草稿。 */
    fun disconnect() {
        credentialStore.clear()
        sessionState.update(connected = false, type = null)
        _state.update {
            it.copy(
                connected = false,
                hint = null,
                account = null,
                tokenInput = "",
                passwordInput = "",
                passwordVisible = false,
            )
        }
    }
}
