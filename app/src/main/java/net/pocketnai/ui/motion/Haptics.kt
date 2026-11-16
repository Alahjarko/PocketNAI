package net.pocketnai.ui.motion

import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * 轻量触觉反馈封装：直接调用系统 [View.performHapticFeedback]，零权限、零堆分配，
 * 且默认遵循系统“触感反馈”开关（不传 IGNORE_VIEW_SETTING）。
 */
@Stable
internal class PocketHaptics(private val view: View) {
    private var lastFrequentTickMs: Long = 0L

    fun confirm() {
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.KEYBOARD_TAP
        }
        view.performHapticFeedback(code)
    }

    fun tick() {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    /** 滑块档位、连续涂选等高频微振：内置最小间隔节流，避免快速拖动产生密集 Binder 调用。 */
    fun frequentTick(minIntervalMs: Long = 28L) {
        val now = SystemClock.uptimeMillis()
        if (now - lastFrequentTickMs < minIntervalMs) return
        lastFrequentTickMs = now
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
        } else {
            HapticFeedbackConstants.CLOCK_TICK
        }
        view.performHapticFeedback(code)
    }

    fun longPress() {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    fun gestureStart() {
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.GESTURE_START
        } else {
            HapticFeedbackConstants.CLOCK_TICK
        }
        view.performHapticFeedback(code)
    }

    fun gestureEnd() {
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.GESTURE_END
        } else {
            HapticFeedbackConstants.CLOCK_TICK
        }
        view.performHapticFeedback(code)
    }
}

@Composable
internal fun rememberPocketHaptics(): PocketHaptics {
    val view = LocalView.current
    return remember(view) { PocketHaptics(view) }
}
