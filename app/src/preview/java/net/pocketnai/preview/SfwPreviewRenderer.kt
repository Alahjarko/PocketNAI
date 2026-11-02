package net.pocketnai.preview

import android.graphics.Bitmap
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import coil.size.Size
import kotlinx.coroutines.runBlocking
import net.pocketnai.domain.model.ThemeMode
import net.pocketnai.domain.openai.OpenAiImageParams
import net.pocketnai.ui.gallery.GalleryModelOptions
import net.pocketnai.ui.safety.StrongSfwBlur
import net.pocketnai.ui.settings.SfwModeCard
import net.pocketnai.ui.theme.PocketNaiTheme
import org.junit.runner.Description
import org.junit.runners.model.Statement

object SfwPreviewRenderer {
    fun render() {
        listOf("sfw-options", "sfw-options-large-text", "sfw-gaussian-sample").forEach { name ->
            val renderer = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(softButtons = false,
                fontScale = if (name.endsWith("large-text")) 1.5f else 1f))
            renderer.apply(object : Statement() {
                override fun evaluate() {
                    // Only neutral geometry, processed by the production transformation. No generated content.
                    val source = if (name == "sfw-gaussian-sample") Bitmap.createBitmap(IntArray(320 * 240) { index ->
                        val x = index % 320; val y = index / 320
                        if (x in 80..240 && y in 60..180) { if ((x / 8 + y / 8) % 2 == 0) 0xff704b93.toInt() else 0xfff6dc89.toInt() }
                        else if (x < 160) 0xff9bd6df.toInt() else 0xffe8b8a5.toInt()
                    }, 320, 240, Bitmap.Config.ARGB_8888) else null
                    val blurred = source?.let { runBlocking { StrongSfwBlur().transform(it, Size.ORIGINAL) } }
                    renderer.snapshot(name) {
                        PocketNaiTheme(ThemeMode.LIGHT, dynamicColor = false) {
                            Surface(Modifier.fillMaxSize()) {
                                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    SfwModeCard(true) {}
                                    if (source != null && blurred != null) {
                                        Text("显示副本 · 原图不改写", style = MaterialTheme.typography.titleMedium)
                                        Text("原始几何样本")
                                        Image(source.asImageBitmap(), null, Modifier.fillMaxWidth().aspectRatio(4f / 3))
                                        Text("SFW 高斯模糊")
                                        Image(blurred.asImageBitmap(), null, Modifier.fillMaxWidth().aspectRatio(4f / 3))
                                    } else {
                                        Text("模型筛选", style = MaterialTheme.typography.titleMedium)
                                        Card { Column { GalleryModelOptions(setOf(OpenAiImageParams.FLARE, OpenAiImageParams.SUNBURST), {}, {}) } }
                                    }
                                }
                            }
                        }
                    }
                    blurred?.recycle(); source?.recycle()
                }
            }, Description.createTestDescription(SfwPreviewRenderer::class.java, name)).evaluate()
        }
    }
}
