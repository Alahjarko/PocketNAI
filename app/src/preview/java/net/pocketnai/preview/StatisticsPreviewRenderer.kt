package net.pocketnai.preview

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import androidx.compose.foundation.lazy.rememberLazyListState
import net.pocketnai.domain.billing.*
import net.pocketnai.domain.model.ThemeMode
import net.pocketnai.ui.billing.AnlasStatisticsContent
import net.pocketnai.ui.billing.AnlasStatisticsState
import net.pocketnai.ui.theme.PocketNaiTheme
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

/** Artifact renderer, not a screenshot assertion or a device test. Uses the production composable. */
object StatisticsPreviewRenderer {
    @JvmStatic fun main(args: Array<String>) {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        val today = LocalDate.of(2026, 11, 2)
        val zone = ZoneId.of("Asia/Shanghai")
        val entries = List(24) { index ->
            AnlasLedgerEntry("preview-$index", listOf("GENERATE", "INPAINT", "UPSCALE")[index % 3],
                if (index % 4 == 0) 0 else 5L + index, if (index % 4 == 0) 1 else null,
                429L + index * 5, "NovelAI V4.5 Full 832×1216 " + if (index % 3 == 1) "局部重绘" else "图片生成",
                today.minusDays(index / 3L).atTime(12, 31, 51).atZone(zone).toInstant().toEpochMilli())
        }
        val stats = AnlasStatistics.calculate(entries, AnlasStatsRange.WEEK, today, zone)
        val configurations = listOf(
            Triple("light-phone", DeviceConfig.PIXEL_5, ThemeMode.LIGHT),
            Triple("dark-phone", DeviceConfig.PIXEL_5, ThemeMode.DARK),
            Triple("large-text", DeviceConfig.PIXEL_5.copy(fontScale = 1.5f), ThemeMode.LIGHT),
            Triple("tablet", DeviceConfig.PIXEL_5.copy(screenWidth = 1600, screenHeight = 2200), ThemeMode.LIGHT),
        )
        configurations.forEach { (name, device, theme) ->
            val renderer = Paparazzi(deviceConfig = device.copy(softButtons = false))
            renderer.apply(object : Statement() {
                override fun evaluate() {
                    renderer.snapshot(name) {
                        PocketNaiTheme(themeMode = theme, dynamicColor = false) {
                            AnlasStatisticsContent(AnlasStatisticsState(stats), {}, {}, {})
                        }
                    }
                    renderer.snapshot("$name-ledger") {
                        PocketNaiTheme(themeMode = theme, dynamicColor = false) {
                            AnlasStatisticsContent(AnlasStatisticsState(stats), {}, {}, {},
                                listState = rememberLazyListState(initialFirstVisibleItemIndex = 6))
                        }
                    }
                    if (name == "light-phone") renderer.snapshot("empty") {
                        PocketNaiTheme(themeMode = theme, dynamicColor = false) {
                            AnlasStatisticsContent(AnlasStatisticsState(AnlasStatistics.calculate(emptyList(), AnlasStatsRange.MONTH, today, zone)), {}, {}, {})
                        }
                    }
                }
            }, Description.createTestDescription(StatisticsPreviewRenderer::class.java, name)).evaluate()
        }
        ExperiencePreviewRenderer.render()
        AssistantPreviewRenderer.render()
        SfwPreviewRenderer.render()
        MotionPolishPreviewRenderer.render()
        ResolutionPreviewRenderer.render()
        QueuePreviewRenderer.render()
        OpenAiPricingPreviewRenderer.render()
    }
}
