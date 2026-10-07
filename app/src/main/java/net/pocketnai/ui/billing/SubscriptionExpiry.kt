package net.pocketnai.ui.billing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import java.time.ZoneId

/** 仅页面可见时按分钟更新显示，不增加账号请求。 */
@Composable
fun rememberSubscriptionExpiry(expiresAtEpochSeconds: Long?): SubscriptionExpiryText {
    val owner = LocalLifecycleOwner.current
    val zone = ZoneId.systemDefault()
    val expiry by produceState(
        initialValue = formatSubscriptionExpiry(expiresAtEpochSeconds, System.currentTimeMillis(), zone),
        expiresAtEpochSeconds, owner, zone,
    ) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            do {
                value = formatSubscriptionExpiry(expiresAtEpochSeconds, System.currentTimeMillis(), zone)
                if (!value.pending) break
                delay(60_000L - System.currentTimeMillis().mod(60_000L))
            } while (true)
        }
    }
    return expiry
}
