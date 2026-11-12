package net.pocketnai.ui.gallery

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import net.pocketnai.ui.PocketNaiPageFrame
import net.pocketnai.ui.home.GenerationSheetScaffold
import androidx.compose.foundation.clickable
import androidx.compose.foundation.pager.HorizontalPager
import net.pocketnai.ui.detail.rememberDetailPager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.*
import androidx.test.platform.app.InstrumentationRegistry
import coil.compose.AsyncImage
import com.google.common.truth.Truth.assertThat
import net.pocketnai.core.ErrorCode
import net.pocketnai.domain.model.*
import net.pocketnai.ui.motion.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** An isolated presentation flow: local geometric pictures, no account, Room or HTTP. */
class GalleryMotionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /** Decode/cache checks need Android's running main looper, not Layoutlib. */
    private fun checkSfwImageCache(file: File) = kotlinx.coroutines.runBlocking {
        val cache = net.pocketnai.ui.safety.SfwImageMemoryCache(compose.activity, 4 * 1024 * 1024)
        val loader = coil.ImageLoader.Builder(compose.activity).memoryCache(cache).eventListener(object : coil.EventListener {
            override fun transformStart(request: coil.request.ImageRequest, input: Bitmap) {
                check(maxOf(input.width, input.height) <= 96)
            }
        }).build()
        val original = file.readBytes()
        try {
            kotlinx.coroutines.withTimeout(10_000) {
                val masked = buildSharedImageRequest(compose.activity, file, true)
                val clear = buildSharedImageRequest(compose.activity, file, false)
                assertThat(masked.memoryCacheKey).isNotEqualTo(clear.memoryCacheKey)
                assertThat(masked.placeholderMemoryCacheKey).isEqualTo(masked.memoryCacheKey)
                val first = loader.execute(masked) as coil.request.SuccessResult
                val small = (first.drawable as android.graphics.drawable.BitmapDrawable).bitmap
                assertThat(maxOf(small.width, small.height)).isEqualTo(48)
                assertThat(small.allocationByteCount).isAtMost(48 * 48 * 4)
                assertThat((loader.execute(masked) as coil.request.SuccessResult).dataSource).isEqualTo(coil.decode.DataSource.MEMORY_CACHE)
                // Large normal thumbnails must not displace a tiny protected SFW display copy.
                repeat(6) { index ->
                    cache[coil.memory.MemoryCache.Key("normal-$index")] = coil.memory.MemoryCache.Value(
                        Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888))
                }
                assertThat(cache.size).isAtMost(cache.maxSize)
                assertThat((loader.execute(masked) as coil.request.SuccessResult).dataSource).isEqualTo(coil.decode.DataSource.MEMORY_CACHE)
                val revealed = loader.execute(clear.newBuilder().size(coil.size.Size.ORIGINAL).allowHardware(false).build()) as coil.request.SuccessResult
                val full = (revealed.drawable as android.graphics.drawable.BitmapDrawable).bitmap
                assertThat(full.width).isEqualTo(400)
                assertThat(full.height).isEqualTo(600)
                assertThat((loader.execute(masked) as coil.request.SuccessResult).dataSource).isEqualTo(coil.decode.DataSource.MEMORY_CACHE)
                cache.trimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
                assertThat(cache.size).isEqualTo(0)
                cache.clear() // Explicit clear also removes still-live weak references.
                assertThat(cache[masked.memoryCacheKey!!]).isNull()
            }
            assertThat(file.readBytes().toList()).isEqualTo(original.toList())
        } finally {
            loader.shutdown()
        }
    }

    @Test fun 任务原位成图及详情往返不会误报成功() {
        val dir = File(compose.activity.cacheDir, "motion-${UUID.randomUUID()}").also { it.mkdirs() }
        val file = File(dir, "sample.png")
        val bitmap = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(android.graphics.Color.rgb(223, 217, 207))
            val paint = Paint().apply { color = android.graphics.Color.rgb(67, 91, 96) }
            drawCircle(210f, 280f, 115f, paint)
            paint.color = android.graphics.Color.rgb(48, 48, 48); paint.textSize = 24f
            drawText("MOTION SAMPLE", 82f, 535f, paint)
        }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        val backTarget = ImageMotionState().apply { open("gallery", "first") }
        backTarget.prepareReturn("second")
        assertThat(backTarget.currentImageId).isEqualTo("second")
        assertThat(backTarget.returning?.imageId).isEqualTo("second")
        // Preparation does not lock selection; cancelling and then paging must still work.
        backTarget.prepareReturn("third")
        assertThat(backTarget.returning?.imageId).isEqualTo("third")
        backTarget.returnToSource()
        backTarget.selectImage("late-update")
        assertThat(backTarget.currentImageId).isEqualTo("third")
        checkSfwImageCache(file)
        val now = System.currentTimeMillis()
        val params = GenerationParams.defaultsFor(ModelCatalog.defaultProfile()).copy(
            size = ImageSizePreset(832, 1216), sampleCount = 1,
        )
        val job = Generation("new", now, now, GenerationStatus.GENERATING, "new", "sfw", params, 1)
        fun image(id: String, time: Long, ratio: Boolean = false) = GalleryItem(id, id, 1, file.path,
            if (ratio) 1216 else 832, if (ratio) 832 else 1216, file.length(), 1, false, time,
            GenerationStatus.SUCCEEDED, GenerationMode.TXT2IMG, id, params.model.apiModelId, "sfw", "", 1)
        val registry = GalleryMotionSlots()
        var images by mutableStateOf(listOf(image("old-a", now - 3 * 86400000L), image("old-b", now - 3 * 86400000L - 1000, true), image("old-c", now - 3 * 86400000L - 2000, true)) +
            (0..41).map { image("deep-$it", now - 4 * 86400000L - it * 1000, it % 3 != 0) })
        var jobs by mutableStateOf(emptyList<GenerationSummary>())
        var showLab by mutableStateOf(false)
        var labStatus by mutableStateOf("RUNNING")
        var labImage by mutableStateOf<GalleryItem?>(null)
        var labId by mutableStateOf("lab-running")
        var labOpened = false
        var finished = false
        var showFilters by mutableStateOf(false)
        var filter by mutableStateOf(GalleryFilter.None)
        var filterFontScale by mutableFloatStateOf(1f)
        var showMedia by mutableStateOf(false)
        val gifFile = File(dir, "two-frames.gif").apply {
            writeBytes(android.util.Base64.decode("R0lGODlhIAAgAIEAAP8AAAAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQAFAAAACwAAAAAIAAgAAAINQABCBxIsKDBgwgTKlzIsKHDhxAjSpxIsaLFixgzatzIsaPHjyBDihxJsqTJkyhTqlzJUmRAACH5BAEUAAEALAAAAAAgACAAgQAA/wAAAAAAAAAAAAg1AAEIHEiwoMGDCBMqXMiwocOHECNKnEixosWLGDNq3Mixo8ePIEOKHEmypMmTKFOqXMlSZEAAOw==", android.util.Base64.DEFAULT))
        }
        lateinit var motion: ImageMotionState
        compose.mainClock.autoAdvance = false
        try {
            compose.setContent {
                MaterialTheme {
                    ImageMotionRoot {
                        motion = LocalImageMotion.current!!
                        if (showMedia) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("GIF · 画廊首帧")
                                Box(Modifier.size(128.dp).testTag("gif-gallery")) {
                                    GalleryMotionCard(GalleryMotionSlot("gif", now,
                                        image("gif", now).copy(relativePath = gifFile.path, width = 32, height = 32), null, false),
                                        gifFile, null, false, false, {}, {}, {}, {})
                                }
                                Text("GIF · 详情播放")
                                net.pocketnai.ui.safety.SfwAsyncImage(gifFile, "动图", animate = true,
                                    modifier = Modifier.size(128.dp).testTag("gif-playing"))
                                Text("SFW · 静态遮罩")
                                CompositionLocalProvider(net.pocketnai.ui.safety.LocalSfwDisplay provides
                                    net.pocketnai.ui.safety.SfwDisplayState(enabled = true, ready = true)) {
                                    net.pocketnai.ui.safety.SfwAsyncImage(gifFile, "遮罩", sensitive = true, animate = true,
                                        modifier = Modifier.size(128.dp).testTag("gif-masked"))
                                }
                            }
                            return@ImageMotionRoot
                        }
                        if (showFilters) {
                            // Dialog/Popup have their own density owner; scale typography explicitly
                            // so the large-text pass really changes text inside both windows.
                            val typography = MaterialTheme.typography
                            fun larger(style: androidx.compose.ui.text.TextStyle) = style.copy(
                                fontSize = style.fontSize * filterFontScale, lineHeight = style.lineHeight * filterFontScale)
                            MaterialTheme(typography = typography.copy(titleLarge = larger(typography.titleLarge),
                                bodyLarge = larger(typography.bodyLarge), bodyMedium = larger(typography.bodyMedium),
                                labelLarge = larger(typography.labelLarge))) {
                                GalleryFilterDialog(filter, { filter = filter.copy(modelApiIds = it) },
                                    { filter = filter.copy(mode = it) }, { filter = filter.copy(favoritesOnly = it) },
                                    { filter = GalleryFilter.None }, {}, { showFilters = false })
                            }
                            return@ImageMotionRoot
                        }
                        if (showLab) {
                            net.pocketnai.ui.artistlab.ArtistLabResultImage(
                                labId, 0, labStatus, labImage, 832f / 1216f, { file }, { labOpened = true },
                                Modifier.width(180.dp).testTag("lab-image"),
                            )
                            return@ImageMotionRoot
                        }
                        val nav = rememberNavController()
                        NavHost(nav, "home", enterTransition = { imagePageEnter() }, exitTransition = { imagePageExit() }) {
                            composable("home") {
                                ImageMotionRoute(this, "home") {
                                    PocketNaiPageFrame(nav, "home", false, false) {
                                        GenerationSheetScaffold(false, sheetContent = { _, _ ->
                                            Box(Modifier.fillMaxWidth().height(900.dp).background(Color.Magenta)) {
                                                Text("面板越界检测样本")
                                            }
                                        }) { padding ->
                                            val sections = registry.sections(images, jobs, true)
                                            val grid = rememberLazyStaggeredGridState()
                                            Box(Modifier.fillMaxSize().padding(padding)) {
                                            GalleryMotionGrid(sections, { file }, emptyMap(), false, emptySet(),
                                                onClick = { motion.open("home", it.imageId); nav.navigate("detail") },
                                                onLongClick = {}, onRevealed = { registry.finish(it); if (it == "slot-new-1") finished = true },
                                                onDismissFailure = {}, modifier = Modifier.testTag("gallery-grid"), state = grid)
                                            GalleryScrollToTop(grid, Modifier.align(androidx.compose.ui.Alignment.BottomEnd).padding(16.dp))
                                            }
                                        }
                                    }
                                }
                            }
                            composable("detail") {
                                ImageMotionRoute(this, "home") {
                                    val initial = remember { motion.currentImageId!! }
                                    val order = remember { images.map { it.imageId } }
                                    var loadedOrder by remember { mutableStateOf(emptyList<String>()) }
                                    var selected by remember { mutableStateOf<String?>(null) }
                                    LaunchedEffect(Unit) {
                                        withFrameNanos { }
                                        loadedOrder = order
                                        selected = initial
                                    }
                                    val pager = rememberDetailPager(initial, order, loadedOrder, selected) {
                                        selected = it
                                        motion.selectImage(it)
                                    }
                                    Column {
                                        Text("返回", Modifier.clickable {
                                            motion.selectImage(order[pager.currentPage])
                                            motion.returnToSource()
                                            nav.popBackStack()
                                        }.padding(24.dp))
                                        HorizontalPager(pager, beyondViewportPageCount = 1,
                                            key = { order[it] }, modifier = Modifier.fillMaxSize().testTag("detail-pager")) { page ->
                                            val id = order[page]
                                            val item = images.first { it.imageId == id }
                                            AsyncImage(sharedImageRequest(file), "大图-$id",
                                                modifier = Modifier.fillMaxWidth().aspectRatio(item.aspectRatio).sharedImage(id))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            compose.mainClock.advanceTimeBy(400)
            val oldPosition = compose.onNodeWithTag("slot-old-a-1").fetchSemanticsNode().boundsInRoot.topLeft
            compose.runOnUiThread { jobs = listOf(GenerationSummary(job, 0)) }
            compose.mainClock.advanceTimeBy(1600)
            compose.onNodeWithContentDescription("生成中").assertExists()
            assertThat(compose.onNodeWithTag("slot-old-a-1").fetchSemanticsNode().boundsInRoot.topLeft).isNotEqualTo(oldPosition)
            val displaced = compose.onNodeWithTag("slot-old-a-1").fetchSemanticsNode().boundsInRoot.topLeft
            assertThat(displaced.x).isGreaterThan(oldPosition.x)
            assertThat(displaced.y).isWithin(1f).of(oldPosition.y)
            val pendingBounds = compose.onNodeWithTag("slot-new-1").fetchSemanticsNode().boundsInRoot
            screenshot("pending")
            // Status arrives before the independently observed image list: keep the exact slot.
            compose.runOnUiThread { jobs = listOf(GenerationSummary(job.copy(status = GenerationStatus.SUCCEEDED), 1)) }
            compose.mainClock.advanceTimeBy(300)
            compose.onNodeWithContentDescription("生成中").assertExists()
            val pendingFrame = compose.onNodeWithTag("slot-new-1").captureToImage().asAndroidBitmap()
            val pendingPixel = pendingFrame.getPixel(pendingFrame.width / 5, pendingFrame.height / 5)
            compose.runOnUiThread { images = listOf(image("new", now)) + images }
            compose.waitUntil(5000) {
                compose.mainClock.advanceTimeBy(16)
                compose.onAllNodesWithContentDescription("图片已就绪").fetchSemanticsNodes().isNotEmpty()
            }
            compose.mainClock.advanceTimeBy(80)
            val earlyFrame = compose.onNodeWithTag("slot-new-1").captureToImage().asAndroidBitmap()
            assertThat(earlyFrame.getPixel(earlyFrame.width / 5, earlyFrame.height / 5)).isNotEqualTo(pendingPixel)
            screenshot("revealing-early")
            compose.mainClock.advanceTimeBy(240)
            compose.runOnIdle { assertThat(finished).isTrue() }
            assertThat(compose.onNodeWithTag("slot-new-1").fetchSemanticsNode().boundsInRoot).isEqualTo(pendingBounds)
            screenshot("revealed")
            val lowerBounds = compose.onNodeWithTag("slot-old-c-1").fetchSemanticsNode().boundsInRoot
            compose.onNodeWithTag("slot-old-c-1").performClick()
            compose.mainClock.advanceTimeBy(160)
            assertThat(motion.currentImageId).isEqualTo("old-c")
            // Only the selected picture participates, even though the pager preloads its neighbours.
            compose.onAllNodesWithTag("shared-image:home:old-b", useUnmergedTree = true).assertCountEquals(0)
            compose.onAllNodesWithTag("shared-image:home:deep-0", useUnmergedTree = true).assertCountEquals(0)
            screenshot("expanding")
            compose.mainClock.advanceTimeBy(400)
            compose.onNodeWithText("返回").performClick()
            compose.mainClock.advanceTimeBy(160)
            assertThat(motion.currentImageId).isEqualTo("old-c")
            compose.onAllNodesWithTag("shared-image:home:old-b", useUnmergedTree = true).assertCountEquals(0)
            screenshot("return-lower-row")
            // The moving sheet must not paint through the navigation bar while both are fading.
            val bar = compose.onNodeWithTag("bottom-navigation", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val frame = compose.onRoot().captureToImage().asAndroidBitmap()
            val sheetBleedsIntoBar = (bar.top.toInt() until bar.bottom.toInt() step 8).any { y ->
                (bar.left.toInt() until bar.right.toInt() step 8).any { x ->
                    val pixel = frame.getPixel(x.coerceIn(0, frame.width - 1), y.coerceIn(0, frame.height - 1))
                    android.graphics.Color.red(pixel) - android.graphics.Color.green(pixel) > 35 &&
                        android.graphics.Color.blue(pixel) - android.graphics.Color.green(pixel) > 35
                }
            }
            assertThat(sheetBleedsIntoBar).isFalse()
            compose.mainClock.advanceTimeBy(400)
            assertThat(motion.returning).isNull()
            assertThat(compose.onNodeWithTag("slot-old-c-1").fetchSemanticsNode().boundsInRoot).isEqualTo(lowerBounds)
            // Saved scroll position must be measured before deciding whether the return target is offscreen.
            compose.onNodeWithTag("gallery-grid").performScrollToIndex(6)
            compose.mainClock.advanceTimeBy(400)
            val deepBounds = compose.onNodeWithTag("slot-deep-4-1").fetchSemanticsNode().boundsInRoot
            compose.onNodeWithTag("slot-deep-4-1").performClick()
            compose.mainClock.advanceTimeBy(500)
            assertThat(motion.currentImageId).isEqualTo("deep-4")
            compose.onNodeWithText("返回").performClick()
            compose.mainClock.advanceTimeBy(500)
            assertThat(compose.onNodeWithTag("slot-deep-4-1").fetchSemanticsNode().boundsInRoot).isEqualTo(deepBounds)
            compose.onNodeWithTag("slot-deep-4-1").performClick()
            compose.mainClock.advanceTimeBy(500)
            compose.onNodeWithTag("detail-pager").performTouchInput { swipeRight(durationMillis = 250) }
            compose.mainClock.advanceTimeBy(1000)
            assertThat(motion.currentImageId).isEqualTo("deep-3")
            compose.onNodeWithText("返回").performClick()
            compose.mainClock.advanceTimeBy(500)
            assertThat(motion.currentImageId).isEqualTo("deep-3")
            compose.onNodeWithTag("slot-deep-3-1").assertIsDisplayed()
            compose.onNodeWithTag("gallery-grid").performScrollToIndex(35)
            compose.mainClock.advanceTimeBy(400)
            compose.onNodeWithContentDescription("返回顶部").assertIsDisplayed().performClick()
            compose.mainClock.advanceTimeBy(2000)
            compose.onNodeWithTag("slot-new-1").assertIsDisplayed()
            compose.onNodeWithContentDescription("返回顶部").assertDoesNotExist()
            // Lab cleanup removes an image but intentionally retains its successful request.
            // The gallery Flow can arrive before the updated image count.
            compose.runOnUiThread { images = images.filterNot { it.imageId == "new" } }
            compose.mainClock.advanceTimeBy(400)
            compose.onNodeWithTag("slot-new-1").assertDoesNotExist()
            compose.onAllNodesWithContentDescription("生成中").assertCountEquals(0)
            compose.runOnUiThread { jobs = jobs.map { it.copy(imageCount = 0) } }
            compose.mainClock.advanceTimeBy(400)
            compose.onNodeWithTag("slot-new-1").assertDoesNotExist()
            screenshot("cleaned")
            // An uncertain result ends the indicator without a success check or a retry action.
            compose.runOnUiThread { jobs = jobs + GenerationSummary(job.copy(id = "failed", createdAt = now + 1000), 0) }
            compose.mainClock.advanceTimeBy(400)
            compose.runOnUiThread { jobs = jobs.map { if (it.generation.id == "failed") it.copy(generation = it.generation.copy(
                status = GenerationStatus.FAILED, errorCode = ErrorCode.TIMEOUT_UNCERTAIN)) else it } }
            compose.mainClock.advanceTimeBy(400)
            compose.onNodeWithText("结果待确认").assertExists()
            compose.onAllNodesWithContentDescription("生成中").assertCountEquals(0)
            screenshot("uncertain")
            // The lab uses the same reveal mechanism. Keep its planned bounds across decoding,
            // terminate uncertain results, and do not replay the reveal for historical cards.
            compose.runOnUiThread { showLab = true }
            compose.mainClock.advanceTimeBy(1000)
            compose.onNodeWithContentDescription("生成中").assertExists()
            val labBounds = compose.onNodeWithTag("lab-image").fetchSemanticsNode().boundsInRoot
            screenshot("lab-pending")
            compose.runOnUiThread { labStatus = "SUCCEEDED"; labImage = image("lab-result", now) }
            compose.waitUntil(5000) {
                compose.mainClock.advanceTimeBy(100)
                compose.onAllNodesWithContentDescription("生成中").fetchSemanticsNodes().isEmpty() &&
                    compose.onAllNodesWithContentDescription("图片已就绪").fetchSemanticsNodes().isEmpty()
            }
            assertThat(compose.onNodeWithTag("lab-image").fetchSemanticsNode().boundsInRoot).isEqualTo(labBounds)
            compose.onNodeWithContentDescription("第 1 张").performClick()
            assertThat(labOpened).isTrue()
            screenshot("lab-revealed")
            compose.runOnUiThread { labId = "lab-uncertain"; labImage = null; labStatus = "RUNNING" }
            compose.mainClock.advanceTimeBy(300)
            compose.onNodeWithContentDescription("生成中").assertExists()
            compose.runOnUiThread { labStatus = "UNCERTAIN" }
            compose.mainClock.advanceTimeBy(300)
            compose.onNodeWithText("结果待确认").assertExists()
            compose.onAllNodesWithContentDescription("生成中").assertCountEquals(0)
            compose.onAllNodesWithContentDescription("图片已就绪").assertCountEquals(0)
            // Compare actual screen coordinates across separate dialog/popup windows.
            compose.mainClock.autoAdvance = true
            fun screenBounds(tag: String): Rect {
                val coordinates = compose.onNodeWithTag(tag).fetchSemanticsNode().layoutInfo.coordinates
                return compose.runOnIdle { Rect(coordinates.localToScreen(Offset.Zero),
                    Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat())) }
            }
            val flare = net.pocketnai.domain.openai.OpenAiImageParams.FLARE
            val sunburst = net.pocketnai.domain.openai.OpenAiImageParams.SUNBURST
            for (fontScale in listOf(1f, 1.35f)) {
                compose.runOnUiThread { showFilters = true; filter = GalleryFilter.None; filterFontScale = fontScale }
                compose.mainClock.advanceTimeBy(500)
                val dialog = screenBounds("gallery-filter-dialog")
                val anchor = screenBounds("gallery-model-anchor")
                val modeAnchor = screenBounds("gallery-mode-anchor")
                compose.onNodeWithText("全部模型").performClick()
                compose.mainClock.advanceTimeBy(500)
                val menu = screenBounds("gallery-model-menu")
                for (model in listOf(flare, sunburst, flare, sunburst)) {
                    compose.onNodeWithTag("gallery-model-$model").performScrollTo().performClick()
                    compose.mainClock.advanceTimeBy(400)
                    assertThat(screenBounds("gallery-filter-dialog")).isEqualTo(dialog)
                    assertThat(screenBounds("gallery-model-anchor")).isEqualTo(anchor)
                    assertThat(screenBounds("gallery-mode-anchor")).isEqualTo(modeAnchor)
                    assertThat(screenBounds("gallery-model-menu")).isEqualTo(menu)
                    if (filter.modelApiIds == setOf(sunburst)) {
                        compose.mainClock.advanceTimeBy(400)
                        compose.waitForIdle()
                        android.os.SystemClock.sleep(150)
                        val command = "screencap -p /data/local/tmp/pocketnai-filter-${if (fontScale == 1f) "normal" else "large"}.png"
                        android.os.ParcelFileDescriptor.AutoCloseInputStream(
                            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)).use { it.readBytes() }
                    }
                }
                println("FILTER_STABLE textScale=$fontScale dialog=$dialog anchor=$anchor menu=$menu")
                compose.onAllNodesWithText("全部模型").onLast().performClick()
                compose.mainClock.advanceTimeBy(300)
                compose.runOnUiThread { showFilters = false }
                compose.mainClock.advanceTimeBy(300)
            }
            compose.runOnUiThread { labId = "lab-history"; labImage = image("historical", now); labStatus = "SUCCEEDED" }
            compose.mainClock.advanceTimeBy(300)
            compose.onAllNodesWithContentDescription("生成中").assertCountEquals(0)
            compose.onAllNodesWithContentDescription("图片已就绪").assertCountEquals(0)
            compose.runOnUiThread { showMedia = true }
            compose.mainClock.autoAdvance = true
            compose.onNodeWithContentDescription("查看动图").assertIsDisplayed()
            fun centerColor(tag: String): Int {
                val bitmap = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
                return try { bitmap.getPixel(bitmap.width / 2, bitmap.height / 2) } finally { bitmap.recycle() }
            }
            compose.waitUntil(5000) { centerColor("gif-gallery") == android.graphics.Color.RED }
            compose.waitUntil(5000) { centerColor("gif-masked") == android.graphics.Color.RED }
            val maskedColor = centerColor("gif-masked")
            val colors = mutableSetOf<Int>()
            repeat(16) {
                android.os.SystemClock.sleep(80)
                val color = centerColor("gif-playing")
                if (color == android.graphics.Color.RED || color == android.graphics.Color.BLUE) {
                    if (colors.add(color)) screenshot("gif-frame-${colors.size}")
                }
                assertThat(centerColor("gif-gallery")).isEqualTo(android.graphics.Color.RED)
                assertThat(centerColor("gif-masked")).isEqualTo(maskedColor)
            }
            assertThat(colors).containsExactly(android.graphics.Color.RED, android.graphics.Color.BLUE)
        } finally { dir.deleteRecursively() }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(100) // SurfaceFlinger must present the manually advanced frame.
        val output = File(instrumentation.targetContext.cacheDir, "motion-$name.png")
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }
}
