package net.pocketnai.ui.home

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import net.pocketnai.domain.openai.ImageProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class GenerationSheetGestureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var scrollOffset = 0
    private var welcome by mutableStateOf(true)
    private var dark by mutableStateOf(false)
    private var chosenProvider: ImageProvider? = null

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "motion-polish").apply { mkdirs() }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun showSheet() {
        compose.setContent {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                if (welcome) {
                    net.pocketnai.ui.connect.WelcomeScreen(onConnect = { chosenProvider = it }, onExplore = { welcome = false })
                    return@MaterialTheme
                }
                GenerationSheetScaffold(
                    inFlight = false,
                    sheetContent = { expanded, headerDrag ->
                        val scroll = rememberScrollState()
                        scrollOffset = scroll.value
                        Column {
                            Text("生成设置", Modifier.fillMaxWidth().height(64.dp).then(headerDrag).testTag("header"))
                            Column(
                                Modifier
                                    .testTag("form")
                                    .collapseSheetOnTopPullDown(scroll)
                                    .verticalScroll(scroll, enabled = expanded),
                            ) {
                                repeat(40) { Text("参数 $it", Modifier.fillMaxWidth().height(48.dp)) }
                            }
                        }
                    },
                ) { Text("画廊") }
            }
        }
    }

    @Test
    fun sheetHeaderDragAntiMisTouchAndLargeTopPullDownCollapse() {
        compose.mainClock.autoAdvance = false
        showSheet()
        compose.mainClock.advanceTimeBy(16)
        compose.onNodeWithText("连接绘画服务").assertIsEnabled()
        // Both actions must fit inside the viewport, even before the introduction finishes.
        val viewport = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val skip = compose.onNodeWithText("先看看").fetchSemanticsNode().boundsInRoot
        assertThat(skip.bottom).isAtMost(viewport.bottom)
        compose.onNodeWithText("先看看").assertIsDisplayed()
        screenshot("welcome-start")
        compose.onNodeWithText("OpenAI").performClick()
        compose.onNodeWithText("连接绘画服务").performClick()
        compose.runOnIdle { assertThat(chosenProvider).isEqualTo(ImageProvider.OPEN_AI) }
        compose.mainClock.advanceTimeBy(900)
        screenshot("welcome-light")
        compose.runOnIdle { dark = true }
        compose.mainClock.advanceTimeBy(32)
        screenshot("welcome-dark")
        compose.onNodeWithText("先看看").performClick()
        compose.runOnIdle { dark = false }
        compose.mainClock.advanceTimeBy(100)

        // 1. 头部 1:1 连续上拉并无停顿收敛至最顶
        val collapsedHeaderTop = compose.onNodeWithTag("header").fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithTag("header").performTouchInput {
            down(center)
            moveBy(Offset(0f, -180f))
        }
        compose.mainClock.advanceTimeBy(32)
        val midDragHeaderTop = compose.onNodeWithTag("header").fetchSemanticsNode().boundsInRoot.top
        assertThat(midDragHeaderTop).isLessThan(collapsedHeaderTop - 100f)
        compose.onNodeWithTag("header").performTouchInput {
            moveBy(Offset(0f, -220f))
            up()
        }
        compose.mainClock.autoAdvance = true
        compose.onNodeWithContentDescription("收起生成设置").assertIsDisplayed()

        // 2. 防误触 #1：在表单顶部发生小/中等幅度下滑（90dp），必须回弹保持展开，绝不误收起
        compose.onNodeWithTag("form").performTouchInput {
            val dist = with(compose.density) { 90.dp.toPx() }
            swipeDown(startY = top + 40f, endY = top + 40f + dist, durationMillis = 180)
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("收起生成设置").assertIsDisplayed()

        // 3. 防误触 #2：先向内滚离顶部（scrollOffset > 0），再大幅下拉滚回顶部（scrollOffset == 0），绝不误收起
        compose.onNodeWithTag("form").performTouchInput { swipeUp() }
        compose.runOnIdle { assertThat(scrollOffset).isGreaterThan(0) }
        repeat(2) {
            if (scrollOffset > 0) {
                compose.onNodeWithTag("form").performTouchInput { swipeDown() }
                compose.waitForIdle()
            }
        }
        compose.runOnIdle { assertThat(scrollOffset).isEqualTo(0) }
        compose.onNodeWithContentDescription("收起生成设置").assertIsDisplayed()

        // 4. 单手便捷收起：表单已处于最顶部时，明确的大幅度下拉（>=280dp 慢拉）直接收起抽屉
        compose.onNodeWithTag("form").performTouchInput {
            val pullDist = with(compose.density) { 300.dp.toPx() }.coerceAtMost(height - 32f)
            swipeDown(startY = top + 16f, endY = top + 16f + pullDist, durationMillis = 420)
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("展开生成设置").assertIsDisplayed()

        // 5. 再次展开并验证返回键收起与滚动记忆保持兼容
        compose.onNodeWithContentDescription("展开生成设置").performClick()
        compose.onNodeWithContentDescription("收起生成设置").assertIsDisplayed()
        compose.onNodeWithTag("form").performTouchInput { swipeUp() }
        compose.runOnIdle { assertThat(scrollOffset).isGreaterThan(0) }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithContentDescription("展开生成设置").assertIsDisplayed()
        assertThat(compose.activity.isFinishing).isFalse()
        compose.onNodeWithContentDescription("展开生成设置").performClick()
        compose.onNodeWithContentDescription("收起生成设置").assertIsDisplayed()
        compose.runOnIdle { assertThat(scrollOffset).isGreaterThan(0) }
    }
}
