package net.pocketnai.data.repo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.pocketnai.core.AppError
import net.pocketnai.core.ErrorCode
import net.pocketnai.core.Outcome
import net.pocketnai.data.network.NovelAiApi
import net.pocketnai.data.security.CredentialStore
import net.pocketnai.domain.billing.SubscriptionBalance

/** 余额状态（余额规划 §7.1）。 */
sealed interface BalanceState {

    /** 还没有凭据，或凭据已被清除。 */
    data object Unavailable : BalanceState

    /** 正在刷新。[previous] 是仍在显示的上一次结果。 */
    data class Loading(val previous: SubscriptionBalance?) : BalanceState

    data class Available(
        val balance: SubscriptionBalance,
        /** 已经超过新鲜期，界面要说明这是旧数据。 */
        val stale: Boolean,
    ) : BalanceState

    /** 刷新失败。[previous] 保留最后一次成功值，绝不清零。 */
    data class RefreshFailed(
        val previous: SubscriptionBalance?,
        val error: AppError,
        val failedAtMillis: Long,
    ) : BalanceState

    /**
     * 仍然可以显示给用户的余额。
     *
     * 加载中与刷新失败时都回退到上一次成功值 —— 界面不会因为一次刷新失败就把余额清空。
     * 从未成功读取过时才是 null。
     */
    val knownBalance: SubscriptionBalance?
        get() = when (this) {
            is Available -> balance
            is Loading -> previous
            is RefreshFailed -> previous
            Unavailable -> null
        }
}

/** 刷新原因，决定要不要走缓存判断（余额规划 §7.3）。 */
enum class BalanceRefreshReason {
    CONNECTED,
    APP_FOREGROUND,
    USER_REQUESTED,
    GENERATION_COMPLETED,
    INSUFFICIENT_ANLAS,
}

/**
 * 账户余额仓库（余额规划 §7）。
 *
 * 与 [GenerationRepository] 分开：生成仓库负责任务、文件与历史；
 * 余额是**账户级**状态，生成页与设置页都要消费，塞进生成仓库会让两者的生命周期纠缠在一起。
 *
 * ## 不变量
 * - 余额只在内存里缓存，**不写 Room、不写 SharedPreferences**：避免上一个账号的余额
 *   跨会话残留给下一个账号看到；
 * - 失败时保留上一次成功值并标为旧数据，绝不用 0 覆盖；
 * - 并发的刷新请求合并成一次真实 HTTP 请求；
 * - 读取失败不影响生成：调用方不得因余额异常阻止用户点击生成。
 */
class AccountBalanceRepository(
    private val api: NovelAiApi,
    private val credentialStore: CredentialStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    private val _state = MutableStateFlow<BalanceState>(BalanceState.Unavailable)
    val state: StateFlow<BalanceState> = _state.asStateFlow()

    /** 进行中的刷新。多个调用者共享同一个 Deferred，因此不会重复发请求。 */
    private var inFlight: Deferred<Outcome<SubscriptionBalance>>? = null

    /** 保护 [inFlight] 的"检查并创建"，保证并发调用只会产生一个请求。 */
    private val refreshMutex = Mutex()

    /**
     * 刷新余额。
     *
     * [force] 为 false 且缓存仍新鲜时**直接返回缓存**，不发请求 ——
     * 前台切回、重复进入页面都不该反复打这个接口。
     */
    suspend fun refresh(
        reason: BalanceRefreshReason,
        force: Boolean = false,
    ): Outcome<SubscriptionBalance> {
        val current = latestOrNull()
        if (!force && current != null && isFresh(current)) {
            return Outcome.Success(current)
        }

        val token = credentialStore.load()?.token
        if (token.isNullOrEmpty()) {
            _state.value = BalanceState.Unavailable
            return Outcome.Failure(AppError.of(ErrorCode.TOKEN_INVALID))
        }

        val deferred = refreshMutex.withLock {
            inFlight ?: scope.async {
                _state.value = BalanceState.Loading(previous = latestOrNull())
                api.fetchSubscriptionBalance(token).also { apply(it, token) }
            }.also { inFlight = it }
        }

        return try {
            deferred.await()
        } finally {
            if (inFlight === deferred) inFlight = null
        }
    }

    private fun apply(outcome: Outcome<SubscriptionBalance>, requestedToken: String) {
        // 请求发出后凭据可能已被换掉。这时**任何结果都不能写入**：
        // 成功会把上一个账号的余额显示给新账号，失败会污染新账号的错误状态。
        if (credentialStore.load()?.token != requestedToken) return

        when (outcome) {
            is Outcome.Success -> _state.value = BalanceState.Available(
                balance = outcome.value,
                stale = false,
            )

            is Outcome.Failure -> {
                // 保留上一次成功值：失败时绝不能把界面上的余额清零。
                // 401 也**不在这里删凭据** —— 由连接页的统一路径决定"需要重新连接"。
                _state.value = BalanceState.RefreshFailed(
                    previous = latestOrNull(),
                    error = outcome.error,
                    failedAtMillis = clock(),
                )
            }
        }
    }

    /** 最后一次成功读取的余额；从未成功时为 null。 */
    fun latestOrNull(): SubscriptionBalance? = when (val current = _state.value) {
        is BalanceState.Available -> current.balance
        is BalanceState.Loading -> current.previous
        is BalanceState.RefreshFailed -> current.previous
        BalanceState.Unavailable -> null
    }

    /** 把当前值标记为旧数据（例如应用长时间在后台）。 */
    fun invalidate() {
        val current = _state.value
        if (current is BalanceState.Available) {
            _state.value = current.copy(stale = true)
        }
    }

    /** 凭据被清除或切换账号时必须调用：绝不能让上一个账号的余额留在界面上。 */
    fun clear() {
        _state.value = BalanceState.Unavailable
    }

    fun isFresh(
        balance: SubscriptionBalance,
        maxAgeMillis: Long = BALANCE_FRESH_MS,
    ): Boolean = clock() - balance.fetchedAtMillis < maxAgeMillis

    companion object {
        /** 余额的新鲜期。超过它，前台切回时才会重新读取。 */
        const val BALANCE_FRESH_MS: Long = 5 * 60 * 1000L
    }
}
