package net.pocketnai.preview

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.pocketnai.domain.model.*
import net.pocketnai.ui.common.ResolutionSelector
import net.pocketnai.ui.openai.OpenAiResolutionSelector
import net.pocketnai.ui.theme.PocketNaiTheme
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Production resolution controls only; no account, ViewModel or generated images. */
object ResolutionPreviewRenderer {
    fun render() {
        listOf("resolution-controls", "resolution-large-text", "resolution-invalid").forEach { name ->
            val renderer = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(softButtons = false,
                fontScale = if (name == "resolution-large-text") 1.5f else 1f,
                screenHeight = 2600))
            renderer.apply(object : Statement() {
                override fun evaluate() {
                    renderer.snapshot(name) {
                        val profile = ModelCatalog.defaultProfile()
                        var naiSize by remember { mutableStateOf(profile.sizeFor(ResolutionTier.WALLPAPER, ImageOrientation.LANDSCAPE)!!) }
                        val tier = profile.tierOf(naiSize)!!
                        var openAiSize by remember { mutableStateOf(if (name == "resolution-invalid") "3840x3840" else "3840x2160") }
                        var custom by remember { mutableStateOf(true) }
                        PocketNaiTheme(ThemeMode.LIGHT, dynamicColor = false) {
                            Surface(Modifier.fillMaxSize()) {
                                Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Text("NovelAI", style = MaterialTheme.typography.titleLarge)
                                    ResolutionSelector(tier = tier, size = naiSize,
                                        availableTiers = profile.availableTiers(), availableOrientations = profile.availableOrientations(tier),
                                        onTierChange = { naiSize = profile.sizeInTier(it, naiSize)!! },
                                        onOrientationChange = { naiSize = profile.sizeFor(tier, it)!! },
                                        custom = null, plan = null, customError = null, constraints = profile.sizeConstraints,
                                        onCustomEnabled = {}, onCustomDisabled = {}, onCustomWidthChange = {}, onCustomHeightChange = {},
                                        onCustomSwap = {}, onExactOutputChange = {})
                                    HorizontalDivider()
                                    Text("OpenAI", style = MaterialTheme.typography.titleLarge)
                                    OpenAiResolutionSelector(openAiSize, custom, true, { custom = it }, { openAiSize = it })
                                }
                            }
                        }
                    }
                }
            }, Description.createTestDescription(ResolutionPreviewRenderer::class.java, name)).evaluate()
        }
    }
}
