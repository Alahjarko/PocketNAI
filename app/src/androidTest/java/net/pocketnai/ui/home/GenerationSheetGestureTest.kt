package net.pocketnai.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsEnabled
import androidx.test.platform.app.InstrumentationRegistry
import net.pocketnai.domain.openai.ImageProvider
import java.io.File
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

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
                            Column(Modifier.testTag("form").verticalScroll(scroll, enabled = expanded)) {
                                repeat(40) { Text("参数 $it", Modifier.fillMaxWidth().height(48.dp)) }
                            }
                        }
                    },
                ) { Text("画廊") }
            }
        }
    }

    @Test
    fun contentScrollAndOverscrollNeverCollapseSheet() {
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
        compose.onNodeWithText(ImageProvider.OPEN_AI.label).performClick()
        compose.onNodeWithText("连接绘画服务").performClick()
        compose.runOnIdle { assertThat(chosenProvider).isEqualTo(ImageProvider.OPEN_AI) }
        compose.mainClock.advanceTimeBy(900)
        screenshot("welcome-light")
        compose.runOnIdle { dark = true }
        compose.mainClock.advanceTimeBy(32)
        screenshot("welcome-dark")
        compose.onNodeWithText("先看看").performClick()
        compose.runOnIdle { dark = false }
        compose.mainClock.autoAdvance = true
        compose.onNodeWithContentDescription("展开生成设置").performClick()
        // 表单顶部向下滚是最容易误收起的场景。
        repeat(2) { compose.onNodeWithTag("form").performTouchInput { swipeDown() } }
        compose.onNodeWithContentDescription("收起生成设置").assertIsDisplayed()
        compose.onNodeWithTag("form").performTouchInput { swipeUp() }
        compose.runOnIdle { assertThat(scrollOffset).isGreaterThan(0) }
        compose.onNodeWithContentDescription("收起生成设置").assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithContentDescription("展开生成设置").assertIsDisplayed()
        assertThat(compose.activity.isFinishing).isFalse()
        compose.onNodeWithContentDescription("展开生成设置").performClick()
        compose.onNodeWithContentDescription("收起生成设置").assertIsDisplayed()
        compose.runOnIdle { assertThat(scrollOffset).isGreaterThan(0) }
    }
}
