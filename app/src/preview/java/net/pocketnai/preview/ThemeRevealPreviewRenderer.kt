package net.pocketnai.preview

import android.graphics.Bitmap
import android.view.ViewGroup
import app.cash.paparazzi.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import com.android.resources.Density
import java.io.File
import java.lang.reflect.Proxy
import kotlinx.serialization.json.*
import net.pocketnai.domain.model.ThemeMode
import net.pocketnai.ui.settings.ColorModeCard
import net.pocketnai.ui.theme.*
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Production controller, real animation clock and last real Layoutlib frame as the snapshot.
 * Layoutlib is not an Android GPU; this does not claim to exercise phone readback or measure FPS.
 */
object ThemeRevealPreviewRenderer {
    private class Replay(val name: String, val initial: ThemeMode, val events: List<Pair<Long, ThemeMode>>,
        val cancelAt: Long? = null, val failCapture: Boolean = false) : SnapshotHandler {
        val writer = HtmlReportWriter()
        var pixels: IntArray? = null
        var width = 0
        var height = 0
        var captures = 0
        var firstCaptureFrame = -1
        var compositions = 0
        var mode = initial
        var origin = Offset.Zero
        var frames = 0
        val near = mutableListOf<Int>()
        val far = mutableListOf<Int>()
        val colors = mutableMapOf<ThemeMode, Int>()
        override fun newFrameHandler(snapshot: Snapshot, frameCount: Int, fps: Int): SnapshotHandler.FrameHandler {
            val delegate = writer.newFrameHandler(snapshot, frameCount, fps)
            // Android's compile bootclasspath excludes java.desktop. Keep this host-only bridge
            // reflective rather than adding AWT stubs/dependencies to Android compilation.
            return Proxy.newProxyInstance(SnapshotHandler.FrameHandler::class.java.classLoader,
                arrayOf(SnapshotHandler.FrameHandler::class.java)) { _, method, args ->
                if (method.name == "handle") {
                    val image = args!![0]
                    width = image.javaClass.getMethod("getWidth").invoke(image) as Int
                    height = image.javaClass.getMethod("getHeight").invoke(image) as Int
                    val ints = Int::class.javaPrimitiveType!!
                    val frame = image.javaClass.getMethod("getRGB", ints, ints, ints, ints,
                        IntArray::class.java, ints, ints).invoke(image, 0, 0, width, height, null, 0, width) as IntArray
                    pixels = frame
                    val x = origin.x.toInt().coerceIn(0, width - 1)
                    val y = (origin.y.toInt() + 140).coerceIn(0, height - 1)
                    near += frame[y * width + x]
                    far += frame[(height - 3) * width + 2]
                    frames++
                }
                method.invoke(delegate, *(args ?: emptyArray()))
            } as SnapshotHandler.FrameHandler
        }
        override fun close() = writer.close()
    }

    fun render() {
        val cases = listOf(
            Replay("reveal-light-dark", ThemeMode.LIGHT, listOf(160L to ThemeMode.DARK)),
            Replay("reveal-dark-light", ThemeMode.DARK, listOf(160L to ThemeMode.LIGHT)),
            Replay("reveal-dark-system-light", ThemeMode.DARK, listOf(160L to ThemeMode.SYSTEM)),
            Replay("reveal-same-system", ThemeMode.LIGHT, listOf(160L to ThemeMode.SYSTEM)),
            Replay("reveal-rapid", ThemeMode.LIGHT, listOf(160L to ThemeMode.DARK, 256L to ThemeMode.LIGHT, 352L to ThemeMode.DARK)),
            Replay("reveal-cancel", ThemeMode.LIGHT, listOf(160L to ThemeMode.DARK), cancelAt = 320L),
            Replay("reveal-pending-reversal", ThemeMode.LIGHT, listOf(160L to ThemeMode.DARK, 160L to ThemeMode.LIGHT)),
            Replay("reveal-capture-failure", ThemeMode.LIGHT, listOf(160L to ThemeMode.DARK), failCapture = true),
        )
        cases.forEach { replay ->
            val renderer = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(
                screenWidth = 480, screenHeight = 960, xdpi = 160, ydpi = 160,
                density = Density.MEDIUM, softButtons = false), snapshotHandler = replay)
            renderer.apply(object : Statement() {
                override fun evaluate() {
                    val view = ComposeView(renderer.context).apply {
                        layoutParams = ViewGroup.LayoutParams(-1, -1)
                        setContent {
                            var mode by remember { mutableStateOf(replay.initial) }
                            CompositionLocalProvider(LocalThemeSnapshotter provides {
                                val frame = checkNotNull(replay.pixels) { "No real rendered frame to capture" }
                                replay.captures++
                                if (replay.firstCaptureFrame < 0) replay.firstCaptureFrame = replay.frames
                                check(!replay.failCapture) { "Injected unsupported snapshot backend" }
                                Bitmap.createBitmap(frame, replay.width, replay.height, Bitmap.Config.ARGB_8888).asImageBitmap()
                            }) {
                                PocketNaiTheme(mode, dynamicColor = false) {
                                    val reveal = checkNotNull(LocalThemeReveal.current)
                                    val background = MaterialTheme.colorScheme.background
                                    SideEffect {
                                        replay.compositions++
                                        replay.mode = mode
                                        replay.colors[mode] = background.toArgb()
                                    }
                                    LaunchedEffect(Unit) {
                                        val start = withFrameNanos { it }
                                        var elapsed = 0L
                                        replay.events.forEach { (millis, next) ->
                                            while (elapsed < millis * 1_000_000) elapsed = withFrameNanos { it - start }
                                            replay.origin = checkNotNull(reveal.center(next)) { "Missing actual button origin" }
                                            reveal.select(next) { mode = next }
                                        }
                                        replay.cancelAt?.let { millis ->
                                            while (withFrameNanos { it - start } < millis * 1_000_000) { }
                                            reveal.cancelVisual()
                                        }
                                    }
                                    Column(Modifier.fillMaxSize().background(background).padding(16.dp)) {
                                        ColorModeCard(mode) { mode = it }
                                    }
                                }
                            }
                        }
                    }
                    renderer.gif(view, name = replay.name, start = 0, end = 1000, fps = 60)
                    check(replay.mode == replay.events.last().second) { "Lost final preference: ${replay.name}" }
                    check(replay.compositions <= 8) { "Per-frame page composition: ${replay.compositions}" }
                    val finalColor = replay.colors.getValue(replay.mode)
                    check(replay.far.last() == finalColor) { "Stale overlay: ${replay.name}" }
                    when (replay.name) {
                        "reveal-same-system" -> check(replay.captures == 0) { "Same-color mode captured a frame" }
                        "reveal-pending-reversal" -> check(replay.captures == 0) { "Superseded pending capture was not cancelled" }
                        "reveal-capture-failure" -> check(replay.captures == 1) { "Fallback did not reach snapshot backend" }
                        "reveal-rapid" -> check(replay.captures == 3) { "Quick reversals were not captured" }
                        "reveal-cancel" -> check(replay.far[24] == finalColor) { "Cancel retained the old page" }
                        else -> {
                            check(replay.captures == 1)
                            val nearChanged = replay.near.indexOfFirst { it == finalColor }
                            val farChanged = replay.far.indexOfFirst { it == finalColor }
                            check(nearChanged >= replay.firstCaptureFrame && nearChanged + 2 < farChanged) {
                                "No outward reveal: near=$nearChanged far=$farChanged"
                            }
                            check(farChanged - replay.firstCaptureFrame in 21..28) {
                                "Reveal is not approximately 400ms: capture=${replay.firstCaptureFrame} far=$farChanged"
                            }
                        }
                    }
                }
            }, Description.createTestDescription(ThemeRevealPreviewRenderer::class.java, replay.name)).evaluate()
        }
        val report = buildJsonObject {
            put("snapshot_source", "last actual Layoutlib frame; phone GPU readback not exercised")
            put("animation_clock", "production Animatable, 400ms, simulated 60Hz")
            put("physical_fps_measurement", false)
            putJsonArray("replays") { cases.forEach { replay -> add(buildJsonObject {
                put("name", replay.name); put("frames", replay.frames)
                put("captures", replay.captures); put("body_compositions", replay.compositions)
                put("button_x", replay.origin.x); put("button_y", replay.origin.y)
                put("capture_frame", replay.firstCaptureFrame)
                put("final_preference", replay.mode.name)
                put("near_changed_frame", replay.near.indexOfFirst { it == replay.colors[replay.mode] })
                put("far_changed_frame", replay.far.indexOfFirst { it == replay.colors[replay.mode] })
            }) } }
        }
        File(System.getProperty("paparazzi.report.dir"), "theme-reveal-work.json").writeText(report.toString())
        println("Circular theme reveal: $report")
    }
}
