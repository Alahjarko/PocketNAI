package net.pocketnai.data.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * “是否已连接”与“用的是哪种凭据”的进程内状态。
 *
 * 存在的意义是让界面能对连接/断开立即作出反应，而不必轮询 [CredentialStore]。
 * 它不保存 Token 本身，只保存一个布尔值和凭据类型 —— 后者用于在会话失效时
 * 给出正确的引导（"重新登录"还是"重新获取 PST"）。
 */
class SessionState(
    connected: Boolean,
    type: CredentialType? = null,
) {

    private val _connected = MutableStateFlow(connected)

    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _credentialType = MutableStateFlow(type)

    val credentialType: StateFlow<CredentialType?> = _credentialType.asStateFlow()

    fun update(connected: Boolean, type: CredentialType? = null) {
        _connected.value = connected
        _credentialType.value = type
    }
}
