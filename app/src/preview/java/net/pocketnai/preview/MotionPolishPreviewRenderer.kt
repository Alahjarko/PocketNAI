package net.pocketnai.preview

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min
import androidx.compose.ui.unit.dp
import net.pocketnai.domain.model.ThemeMode
import net.pocketnai.ui.generate.ReferenceOriginContent
import net.pocketnai.ui.settings.ColorModeCard
import net.pocketnai.ui.theme.PocketNaiDarkColors
import net.pocketnai.ui.theme.PocketNaiLightColors
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Production color interpolation and source-link layout, using a neutral geometry thumbnail. */
object MotionPolishPreviewRenderer {
    fun render() {
        listOf(false, true).forEach { large ->
            val renderer = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(softButtons = false,
                fontScale = if (large) 1.5f else 1f))
            renderer.apply(object : Statement() {
                override fun evaluate() {
                    val frames = listOf(0, 6)
                    frames.forEach { frame ->
                        renderer.snapshot("theme-${if (large) "large" else "normal"}-$frame") {
                            MaterialTheme(colorScheme = if (frame == 0) PocketNaiLightColors else PocketNaiDarkColors) {
                                Surface(Modifier.fillMaxSize()) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                        ColorModeCard(if (frame < 3) ThemeMode.LIGHT else ThemeMode.DARK) {}
                                        Text("参考图", style = MaterialTheme.typography.titleMedium)
                                        ReferenceOriginContent(true, {}) {
                                            Canvas(Modifier.fillMaxSize()) {
                                                drawRect(Color(0xffb7d5df))
                                                drawCircle(Color(0xff5b7793), size.minDimension * 0.3f, Offset(size.width / 2, size.height / 2))
                                            }
                                        }
                                        ReferenceOriginContent(false, {}) {}
                                        Text("保留参考图与编辑位置", style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        }
                    }
                }
            }, Description.createTestDescription(MotionPolishPreviewRenderer::class.java, "motion-polish-$large")).evaluate()
        }
    }
}
