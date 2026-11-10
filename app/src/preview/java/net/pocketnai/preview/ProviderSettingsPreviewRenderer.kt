package net.pocketnai.preview

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.pocketnai.domain.chat.*
import net.pocketnai.domain.model.ThemeMode
import net.pocketnai.ui.chat.ChatConnectionOverview
import net.pocketnai.ui.settings.*
import net.pocketnai.ui.theme.PocketNaiTheme
import org.junit.runner.Description
import org.junit.runners.model.Statement

object ProviderSettingsPreviewRenderer {
    fun render() {
        val populated = LlmProviderCatalog(listOf(
            LlmProvider("one", "创作工作室", models = listOf(LlmModel("thinking-model"), LlmModel("vision-model"))),
            LlmProvider("two", "备用服务", models = listOf(LlmModel("fast-model"))),
            LlmProvider("three", "名称很长的独立模型提供商用于验证折行", models = emptyList()),
        ), activeProviderId = "one", activeModelId = "thinking-model")
        val profiles = listOf(
            Triple("light", DeviceConfig.PIXEL_5, ThemeMode.LIGHT),
            Triple("dark", DeviceConfig.PIXEL_5, ThemeMode.DARK),
            Triple("large-text", DeviceConfig.PIXEL_5.copy(fontScale = 1.5f), ThemeMode.LIGHT),
            Triple("tablet", DeviceConfig.PIXEL_5.copy(screenWidth = 1600, screenHeight = 2200), ThemeMode.LIGHT),
        )
        profiles.forEach { (profile, device, mode) ->
            val renderer = Paparazzi(deviceConfig = device.copy(softButtons = false))
            renderer.apply(object : Statement() {
                override fun evaluate() {
                    listOf(false, true).forEach { empty -> renderer.snapshot("providers-$profile-${if (empty) "empty" else "configured"}") {
                        PocketNaiTheme(mode, dynamicColor = false) {
                            ChatConnectionOverview(if (empty) LlmProviderCatalog() else populated, setOf("one", "two"),
                                "绘伴", false, null, {}, {}, {}, {}, {}, {}, {})
                        }
                    } }
                    if (profile == "light" || profile == "large-text") renderer.snapshot("providers-$profile-entry") {
                        PocketNaiTheme(mode, dynamicColor = false) {
                            Surface {
                                Column(Modifier.fillMaxSize().padding(16.dp)) {
                                    SettingsCard {
                                        SettingsNavigationRow("消耗统计", "Anlas · OpenAI", Icons.Default.QueryStats, {})
                                        SettingsNavigationRow("连接与人格", "模型服务、人格与工具", Icons.Default.Hub, {})
                                        SettingsNavigationRow("存储管理", "本地图片与历史", Icons.Default.Storage, {})
                                    }
                                }
                            }
                        }
                    }
                }
            }, Description.createTestDescription(ProviderSettingsPreviewRenderer::class.java, profile)).evaluate()
        }
    }
}
