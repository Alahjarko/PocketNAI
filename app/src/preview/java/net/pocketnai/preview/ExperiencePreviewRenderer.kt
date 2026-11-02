package net.pocketnai.preview

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.pocketnai.core.AppError
import net.pocketnai.core.ErrorCode
import net.pocketnai.data.repo.BalanceState
import net.pocketnai.domain.billing.*
import net.pocketnai.domain.model.ThemeMode
import net.pocketnai.ui.motion.GenerationOrb
import net.pocketnai.ui.motion.WavyQuotaBar
import net.pocketnai.ui.settings.ColorModeCard
import net.pocketnai.ui.settings.NovelAiAccountCard
import net.pocketnai.ui.theme.PocketNaiTheme
import org.junit.runner.Description
import org.junit.runners.model.Statement

object ExperiencePreviewRenderer {
    fun render() {
        val now = 1_793_577_600L
        val balance = SubscriptionBalance(3, true, 0, now + 7200, false, 12000, 345,
            V5UsageLimit(37, false, 60), now * 1000)
        val presets = listOf("account-light" to ThemeMode.LIGHT, "account-dark" to ThemeMode.DARK,
            "account-large-text" to ThemeMode.LIGHT, "account-expired" to ThemeMode.LIGHT,
            "account-disconnected" to ThemeMode.LIGHT, "account-stale" to ThemeMode.LIGHT,
            "account-unknown" to ThemeMode.LIGHT)
        presets.forEach { (name, theme) ->
            val device = if (name == "account-large-text") DeviceConfig.PIXEL_5.copy(fontScale = 1.5f) else DeviceConfig.PIXEL_5
            withRenderer(name, device) { renderer ->
                renderer.snapshot(name) {
                    val disconnected = name == "account-disconnected"
                    val expired = name == "account-expired"
                    val shown = when {
                        expired -> balance.copy(expiresAtEpochSeconds = now - 1, v5UsageLimit = V5UsageLimit(99, false, 60))
                        name == "account-unknown" -> balance.copy(rawTier = null, accountType = null, expiresAtEpochSeconds = null)
                        else -> balance
                    }
                    val state: BalanceState = if (disconnected) BalanceState.Unavailable
                        else if (name == "account-stale") BalanceState.RefreshFailed(shown, AppError.of(ErrorCode.UNKNOWN), now * 1000)
                        else BalanceState.Available(shown, false)
                    PocketNaiTheme(theme, dynamicColor = false) {
                        Surface(Modifier.fillMaxSize()) {
                            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("设置", style = MaterialTheme.typography.headlineSmall)
                                Text("NovelAI 账户", style = MaterialTheme.typography.titleSmall)
                                NovelAiAccountCard(!disconnected, "我的 NovelAI", "a1b2…c3d4", state,
                                    SubscriptionStatusResolver.resolve(shown.rawTier, shown.accountType, shown.expiresAtEpochSeconds, now), {}, {}, {}, {})
                                ColorModeCard(if (name == "account-large-text") ThemeMode.SYSTEM else theme) {}
                            }
                        }
                    }
                }
            }
        }
        // Equal-time frames from the exact production geometry: no screenshot mockup or emulator.
        withRenderer("orb-animation", DeviceConfig.PIXEL_5.copy(screenWidth = 720, screenHeight = 640)) { renderer ->
            repeat(84) { frame ->
                val phase = frame / 84f
                renderer.snapshot("orb-frame-${frame.toString().padStart(3, '0')}") {
                    PocketNaiTheme(ThemeMode.LIGHT, dynamicColor = false) {
                        Surface(Modifier.fillMaxSize()) {
                            Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center) {
                                GenerationOrb(Modifier.size(72.dp), phaseOverride = phase)
                                Spacer(Modifier.height(12.dp))
                                Text("生成中", style = MaterialTheme.typography.labelMedium)
                                Spacer(Modifier.height(32.dp))
                                WavyQuotaBar(0.37f, Modifier.fillMaxWidth(), phaseOverride = phase * 2)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun withRenderer(name: String, device: DeviceConfig, action: (Paparazzi) -> Unit) {
        val renderer = Paparazzi(deviceConfig = device.copy(softButtons = false))
        renderer.apply(object : Statement() { override fun evaluate() = action(renderer) },
            Description.createTestDescription(ExperiencePreviewRenderer::class.java, name)).evaluate()
    }
}
