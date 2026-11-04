package net.pocketnai.benchmark

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.os.Bundle
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil.Coil
import coil.EventListener
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import net.pocketnai.domain.model.*
import net.pocketnai.domain.safety.SfwPolicy
import net.pocketnai.ui.gallery.*
import net.pocketnai.ui.motion.ImageMotionRoot
import net.pocketnai.ui.safety.*
import net.pocketnai.ui.theme.PocketNaiTheme
import org.json.JSONObject

/** Offline 100 flagged + 50 ordinary checkerboard images; never opens the user's database. */
class GalleryBenchmarkActivity : ComponentActivity() {
    private val requests = AtomicInteger()
    private val memoryHits = AtomicInteger()
    private val transforms = AtomicInteger()
    private val transformNanos = AtomicLong()
    private val largestTransformInput = AtomicInteger()
    private val errors = AtomicInteger()
    private val seen = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val timings = java.util.Collections.synchronizedList(mutableListOf<LongArray>())
    private val frameThread = HandlerThread("benchmark-frames")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        frameThread.start()
        window.addOnFrameMetricsAvailableListener({ _, frame, _ ->
            timings.add(longArrayOf(frame.getMetric(FrameMetrics.TOTAL_DURATION),
                frame.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION), frame.getMetric(FrameMetrics.DRAW_DURATION)))
        }, Handler(frameThread.looper))
        val protectedCache = intent.getBooleanExtra("protected", false)
        val loader = ImageLoader.Builder(this).apply {
            if (protectedCache) memoryCache { SfwImageMemoryCache(this@GalleryBenchmarkActivity) }
        }.eventListenerFactory {
            object : EventListener {
                private var started = 0L
                override fun onStart(request: ImageRequest) { requests.incrementAndGet() }
                override fun onSuccess(request: ImageRequest, result: SuccessResult) {
                    seen.add(request.data.toString())
                    if (result.dataSource == coil.decode.DataSource.MEMORY_CACHE) memoryHits.incrementAndGet()
                }
                override fun onError(request: ImageRequest, result: coil.request.ErrorResult) { errors.incrementAndGet() }
                override fun transformStart(request: ImageRequest, input: Bitmap) {
                    transforms.incrementAndGet()
                    largestTransformInput.accumulateAndGet(maxOf(input.width, input.height), ::maxOf)
                    started = System.nanoTime()
                }
                override fun transformEnd(request: ImageRequest, output: Bitmap) {
                    transformNanos.addAndGet(System.nanoTime() - started)
                }
            }
        }.build()
        Coil.setImageLoader(loader)
        val masked = intent.getBooleanExtra("sfw", true)
        val auto = intent.getBooleanExtra("auto", false)
        val label = intent.getStringExtra("label")?.takeIf { it.matches(Regex("[a-zA-Z0-9_-]+")) } ?: "manual"
        setContent {
            var items by remember { mutableStateOf<List<GalleryItem>>(emptyList()) }
            LaunchedEffect(Unit) { items = withContext(Dispatchers.IO) { fixtures() } }
            PocketNaiTheme {
                if (items.isEmpty()) Text("Preparing 150 offline fixtures") else {
                    val sfw = remember(items, masked) {
                        val marked = items.filter { it.sensitivePrompt }
                        SfwDisplayState(masked, true, marked.map { it.relativePath }.toSet(),
                            marked.map { it.imageId }.toSet(), items.map { it.relativePath }.toSet())
                    }
                    CompositionLocalProvider(LocalSfwDisplay provides sfw) {
                        ImageMotionRoot {
                            val grid = rememberLazyStaggeredGridState()
                            val sections = remember(items) { GalleryMotionSlots().sections(items, emptyList(), false) }
                            var phase by remember { mutableStateOf("READY") }
                            val density = LocalDensity.current.density
                            LaunchedEffect(items, auto) {
                                if (!auto) return@LaunchedEffect
                                delay(1500)
                                repeat(2) { pass ->
                                    val cpu = Process.getElapsedCpuTime()
                                    val start = SystemClock.elapsedRealtime()
                                    val requested = requests.get(); val hits = memoryHits.get()
                                    val transformed = transforms.get(); val nanos = transformNanos.get()
                                    val gc = Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull() ?: 0
                                    val allocations = Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull() ?: 0
                                    timings.clear()
                                    phase = if (pass == 0) "COLD" else "WARM"
                                    Log.i("GalleryBench", "START $label $phase")
                                    // Traverse all 150 items at a reproducible ~3300 physical px/s.
                                    while (grid.canScrollForward) grid.animateScrollBy(1200f * density, tween(1100, easing = LinearEasing))
                                    while (grid.canScrollBackward) grid.animateScrollBy(-1200f * density, tween(1100, easing = LinearEasing))
                                    delay(500)
                                    val memory = Debug.MemoryInfo().also(Debug::getMemoryInfo)
                                    val frames = synchronized(timings) { timings.toList() }
                                    fun percentile(column: Int, fraction: Double): Double {
                                        val values = frames.map { it[column] }.sorted()
                                        return if (values.isEmpty()) 0.0 else values[((values.size-1)*fraction).toInt()]/1_000_000.0
                                    }
                                    val result = JSONObject().put("label", label).put("phase", phase).put("sfw", masked)
                                        .put("images", items.size).put("flagged", items.count { it.sensitivePrompt })
                                        .put("wallMs", SystemClock.elapsedRealtime() - start)
                                        .put("cpuMs", Process.getElapsedCpuTime() - cpu).put("pssKb", memory.totalPss)
                                        .put("requests", requests.get()-requested).put("memoryHits", memoryHits.get()-hits)
                                        .put("transforms", transforms.get()-transformed)
                                        .put("transformMs", (transformNanos.get()-nanos)/1_000_000.0)
                                        .put("largestTransformInput", largestTransformInput.get()).put("errors", errors.get())
                                        .put("gcCount", (Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull() ?: 0)-gc)
                                        .put("uniqueImagesSeen", seen.size).put("protectedCache", protectedCache)
                                        .put("cacheBytes", loader.memoryCache?.size).put("cacheBudget", loader.memoryCache?.maxSize)
                                        .put("frames", frames.size).put("over8ms", frames.count { it[0]>8_333_333L })
                                        .put("over16ms", frames.count { it[0]>16_666_667L })
                                        .put("frameP95Ms", percentile(0,.95)).put("frameP99Ms", percentile(0,.99))
                                        .put("layoutP95Ms", percentile(1,.95)).put("drawP95Ms", percentile(2,.95))
                                        .put("allocatedBytes", (Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull() ?: 0)-allocations)
                                    File(getExternalFilesDir(null), "$label-$phase.json").writeText(result.toString(2))
                                    Log.i("GalleryBench", "RESULT $result")
                                    delay(1000)
                                }
                                phase = "DONE"
                            }
                            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                                Text("150 images · 100 tagged / 50 normal · SFW=$masked · $phase", Modifier.padding(12.dp))
                                Box(Modifier.weight(1f)) {
                                    GalleryMotionGrid(sections, { File(it.relativePath) }, emptyMap(), false, emptySet(),
                                        {}, {}, {}, {}, state = grid)
                                }
                                Text("Offline production gallery fixture", Modifier.fillMaxWidth().height(112.dp).padding(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        frameThread.quitSafely()
        super.onDestroy()
    }

    private fun fixtures(): List<GalleryItem> = (0 until 150).map { index ->
        val (width, height) = when (index % 3) { 0 -> 832 to 1216; 1 -> 1216 to 832; else -> 1024 to 1024 }
        val file = File(filesDir, "generations/bench-$index.png").also { it.parentFile!!.mkdirs() }
        if (!file.exists()) {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint()
            for (y in 0 until height step 32) for (x in 0 until width step 32) {
                paint.color = AndroidColor.HSVToColor(floatArrayOf((index * 37 % 360).toFloat(), .3f + ((x/32+y/32)%2)*.45f, .9f))
                canvas.drawRect(x.toFloat(), y.toFloat(), (x+32).toFloat(), (y+32).toFloat(), paint)
            }
            paint.color = AndroidColor.BLACK; paint.textSize = 80f
            canvas.drawText("GRID $index", 40f, height/2f, paint)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val prompt = if (index % 3 != 2) "checkerboard landscape, 0.001::nsfw::" else "checkerboard landscape"
        GalleryItem("bench-$index", "bench-$index", 1, file.absolutePath, width, height, file.length(), 1, false,
            1793740000000L-index*1000, GenerationStatus.SUCCEEDED, GenerationMode.TXT2IMG, "Grid $index",
            ImageModel.V4_5_CURATED.apiModelId, prompt, "", 1, sensitivePrompt = SfwPolicy.containsSensitivePrompt(prompt))
    }
}
