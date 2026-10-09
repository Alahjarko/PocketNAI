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
        val now = System.currentTimeMillis()
        val params = GenerationParams.defaultsFor(ModelCatalog.defaultProfile()).copy(
            size = ImageSizePreset(832, 1216), sampleCount = 1,
        )
        val job = Generation("new", now, now, GenerationStatus.GENERATING, "new", "sfw", params, 1)
        fun image(id: String, time: Long, ratio: Boolean = false) = GalleryItem(id, id, 1, file.path,
            if (ratio) 1216 else 832, if (ratio) 832 else 1216, file.length(), 1, false, time,
            GenerationStatus.SUCCEEDED, GenerationMode.TXT2IMG, id, params.model, "sfw", "", 1)
        val registry = GalleryMotionSlots()
        var images by mutableStateOf(listOf(image("old-a", now - 3 * 86400000L), image("old-b", now - 3 * 86400000L - 1000, true), image("old-c", now - 3 * 86400000L - 2000, true)) +
            (0..11).map { image("deep-$it", now - 4 * 86400000L - it * 1000, it % 3 != 0) })
        var jobs by mutableStateOf(emptyList<GenerationSummary>())
        var finished = false
        lateinit var motion: ImageMotionState
        compose.mainClock.autoAdvance = false
        try {
            compose.setContent {
                MaterialTheme {
                    ImageMotionRoot {
                        motion = LocalImageMotion.current!!
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
                                            GalleryMotionGrid(sections, { file }, emptyMap(), false, emptySet(),
                                                onClick = { motion.open("home", it.imageId); nav.navigate("detail") },
                                                onLongClick = {}, onRevealed = { registry.finish(it); if (it == "slot-new-1") finished = true },
                                                onDismissFailure = {}, modifier = Modifier.testTag("gallery-grid").padding(padding))
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
            compose.runOnUiThread { images = listOf(image("new", now)) + images }
            compose.waitUntil(5000) { compose.mainClock.advanceTimeBy(100); finished }
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
            compose.onNodeWithTag("gallery-grid").performScrollToIndex(0)
            compose.mainClock.advanceTimeBy(400)
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
