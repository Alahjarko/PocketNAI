package net.pocketnai.preview

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.pocketnai.domain.model.ThemeMode
import net.pocketnai.domain.openai.OpenAiImageParams
import net.pocketnai.domain.openai.OpenAiImagePricing
import net.pocketnai.ui.openai.OpenAiCostEstimate
import net.pocketnai.ui.theme.PocketNaiTheme
import org.junit.runner.Description
import org.junit.runners.model.Statement

object OpenAiPricingPreviewRenderer {
    fun render() {
        listOf("openai-pricing", "openai-pricing-large-text", "openai-pricing-dark").forEach { name ->
            val renderer = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(softButtons = false,
                screenHeight = if (name.endsWith("large-text")) 3100 else 2600,
                fontScale = if (name.endsWith("large-text")) 1.5f else 1f))
            renderer.apply(object : Statement() {
                override fun evaluate() {
                    renderer.snapshot(name) {
                        PocketNaiTheme(if (name.endsWith("dark")) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false) {
                            Surface(Modifier.fillMaxSize()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Text("OpenAI · 生成前预算", style = MaterialTheme.typography.titleLarge)
                                    listOf(
                                        OpenAiImageParams(quality = "low") to 0,
                                        OpenAiImageParams(quality = "high") to 0,
                                        OpenAiImageParams(size = "3840x2160", quality = "max", count = 3) to 2,
                                        OpenAiImageParams(quality = "auto") to 0,
                                        OpenAiImageParams(size = "auto") to 0,
                                    ).forEach { (params, references) ->
                                        Text("${params.size} · ${params.quality}", style = MaterialTheme.typography.labelLarge)
                                        OpenAiCostEstimate(OpenAiImagePricing.estimate(params, references))
                                    }
                                }
                            }
                        }
                    }
                }
            }, Description.createTestDescription(OpenAiPricingPreviewRenderer::class.java, name)).evaluate()
        }
    }
}
