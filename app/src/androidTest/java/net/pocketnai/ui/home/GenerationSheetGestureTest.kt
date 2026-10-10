package net.pocketnai.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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

    private fun showSheet() {
        compose.setContent {
            MaterialTheme {
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
        showSheet()
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
