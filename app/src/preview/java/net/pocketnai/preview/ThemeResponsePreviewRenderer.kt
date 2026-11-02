package net.pocketnai.preview

import android.view.ViewGroup
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.pocketnai.domain.model.ThemeMode
import net.pocketnai.ui.settings.ColorModeCard
import net.pocketnai.ui.theme.PocketNaiTheme
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Real animation-clock A/B replay; counts UI work, never claims physical-device FPS. */
object ThemeResponsePreviewRenderer {
    private class Work {
        var bodyCompositions = 0
        var bodyMeasures = 0
        var cardMeasures = 0
        var switches = 0
    }

    fun render() {
        val results = listOf(true, false).map { legacy ->
            val work = Work()
            val name = if (legacy) "theme-response-before" else "theme-response-after"
            val renderer = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(softButtons = false))
            renderer.apply(object : Statement() {
                override fun evaluate() {
                    val view = ComposeView(renderer.context).apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        setContent {
                            var mode by remember { mutableStateOf(ThemeMode.LIGHT) }
                            LaunchedEffect(Unit) {
                                val start = withFrameNanos { it }
                                listOf(160L to ThemeMode.DARK, 256L to ThemeMode.LIGHT, 352L to ThemeMode.DARK).forEach { (millis, next) ->
                                    while (withFrameNanos { it - start } < millis * 1_000_000) { }
                                    mode = next
                                    work.switches++
                                }
                            }
                            if (legacy) LegacyPocketNaiTheme(mode, dynamicColor = false) {
                                Fixture(mode, { mode = it }, work, legacy = true)
                            } else PocketNaiTheme(mode, dynamicColor = false) {
                                Fixture(mode, { mode = it }, work, legacy = false)
                            }
                        }
                    }
                    renderer.gif(view, name = name, start = 0L, end = 1200L, fps = 60)
                    check(work.switches == 3) { "Animation-clock replay did not finish: ${work.switches}" }
                }
            }, Description.createTestDescription(ThemeResponsePreviewRenderer::class.java, name)).evaluate()
            work
        }
        val before = results[0]; val after = results[1]
        check(after.bodyCompositions <= 8) { "Theme animation reached page composition: ${after.bodyCompositions}" }
        check(before.bodyCompositions > after.bodyCompositions) { "A/B replay did not observe the old global animation" }
        val report = buildJsonObject {
            put("baseline", "0.1.28 theme and color-mode card, preview-only copies")
            put("switches_per_replay", 3)
            put("simulated_frames_per_replay", 73)
            put("before_body_compositions", before.bodyCompositions)
            put("after_body_compositions", after.bodyCompositions)
            put("before_body_measures", before.bodyMeasures)
            put("after_body_measures", after.bodyMeasures)
            put("before_card_measures", before.cardMeasures)
            put("after_card_measures", after.cardMeasures)
            put("physical_fps_measurement", false)
            put("actual_touch_latency_measurement", false)
        }
        File(System.getProperty("paparazzi.report.dir"), "theme-response-work.json").writeText(report.toString())
        println("Theme replay work: $report")
    }

    @Composable
    private fun Fixture(mode: ThemeMode, onSelect: (ThemeMode) -> Unit, work: Work, legacy: Boolean) {
        SideEffect { work.bodyCompositions++ }
        val background = MaterialTheme.colorScheme.background
        val ink = MaterialTheme.colorScheme.onBackground
        Column(Modifier.fillMaxSize().background(background).layout { measurable, constraints ->
            work.bodyMeasures++
            val p = measurable.measure(constraints)
            layout(p.width, p.height) { p.place(0, 0) }
        }.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.fillMaxWidth().layout { measurable, constraints ->
                work.cardMeasures++
                val p = measurable.measure(constraints)
                layout(p.width, p.height) { p.place(0, 0) }
            }) {
                if (legacy) LegacyColorModeCard(mode, onSelect) else ColorModeCard(mode, onSelect)
            }
            repeat(6) { index ->
                Card { Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("设置条目 ${index + 1}", color = ink, style = MaterialTheme.typography.titleSmall)
                    Text("固定页面内容 · 不订阅按钮动画时钟", color = ink, style = MaterialTheme.typography.bodyMedium)
                } }
            }
        }
    }
}
