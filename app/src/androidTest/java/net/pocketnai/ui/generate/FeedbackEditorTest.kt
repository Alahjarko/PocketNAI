package net.pocketnai.ui.generate

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.text.TextRange
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import net.pocketnai.domain.model.CharacterPrompt
import net.pocketnai.domain.model.GenerationParams
import net.pocketnai.domain.model.ImageSizePreset
import net.pocketnai.domain.model.ModelCatalog
import net.pocketnai.domain.model.ThemeMode
import net.pocketnai.ui.theme.PocketNaiTheme
import net.pocketnai.ui.common.WeightHighlightedTextField
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class FeedbackEditorTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `角色补全保留右侧权重且逆向默认折叠`() {
        var updated = CharacterPrompt(id = "one", prompt = "blu1.1::honkai: star rail,official art::", negativePrompt = "hat")
        compose.setContent {
            var character by remember { mutableStateOf(updated) }
            var state by remember { mutableStateOf(GenerateViewModel.UiState(GenerationParams.defaultsFor(ModelCatalog.defaultProfile()))) }
            MaterialTheme {
                MultiCharacterSection(listOf(character), {}, {}, { _, next -> character = next; updated = next },
                    suggestionState = state,
                    onSuggestionQuery = { fragment, target -> state = state.copy(
                        suggestions = if (fragment.isNotBlank()) listOf("blue hair") else emptyList(),
                        suggestionQuery = fragment, suggestionTarget = target,
                    ) },
                )
            }
        }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        compose.onAllNodes(hasSetTextAction())[0].performClick().performTextInputSelection(TextRange(3))
        compose.onNodeWithText("blue hair").performClick()
        compose.runOnIdle { assertThat(updated.prompt).isEqualTo("blue hair, 1.1::honkai: star rail,official art::") }
        compose.onNodeWithText("角色专属排除词 · 已填写").performClick()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(2)
        compose.onNodeWithText("hat").assertIsDisplayed()
    }

    @Test fun `二维画布按竖向比例显示且点击保留xy`() {
        var updated = CharacterPrompt(id = "one", prompt = "girl")
        compose.setContent {
            var character by remember { mutableStateOf(updated) }
            MaterialTheme {
                CharacterPositionDialog(listOf(character), ImageSizePreset(832, 1216),
                    onUpdate = { _, next -> character = next; updated = next }, onDismiss = {})
            }
        }
        val bounds = compose.onNodeWithTag("character-position-canvas").fetchSemanticsNode().boundsInRoot
        assertThat(bounds.width / bounds.height).isWithin(0.01f).of(832f / 1216)
        compose.onNodeWithTag("character-position-canvas").performTouchInput {
            click(Offset(width * 0.8f, height * 0.2f))
        }
        compose.runOnIdle {
            assertThat(updated.centerX).isWithin(0.01).of(0.8)
            assertThat(updated.centerY).isWithin(0.01).of(0.2)
        }
    }

    @Test fun `角色提示词超过三行时输入框自动长高`() {
        compose.setContent {
            var character by remember { mutableStateOf(CharacterPrompt(prompt = "girl")) }
            MaterialTheme {
                MultiCharacterSection(listOf(character), {}, {}, { _, next -> character = next })
            }
        }
        val field = compose.onAllNodes(hasSetTextAction())[0]
        val before = field.fetchSemanticsNode().boundsInRoot.height
        field.performTextReplacement((1..12).joinToString("\n") { "long role tag $it" })
        val after = field.fetchSemanticsNode().boundsInRoot.height
        assertThat(after).isGreaterThan(before * 2)
    }

    @Test fun `更多建议可展开20条候选`() {
        val state = GenerateViewModel.UiState(
            GenerationParams.defaultsFor(ModelCatalog.defaultProfile()),
            suggestions = (1..20).map { "blue $it" }, suggestionQuery = "blu", suggestionTarget = "base",
        )
        compose.setContent {
            MaterialTheme {
                PromptTagSuggestions(
                    androidx.compose.ui.text.input.TextFieldValue("blu", TextRange(3)), true, "base", state,
                    onQuery = { _, _ -> }, onFieldChange = {},
                )
            }
        }
        compose.onNodeWithText("blue 9").assertDoesNotExist()
        compose.onNodeWithText("更多建议（20）").performClick()
        compose.onNodeWithText("blue 20").assertExists()
    }

    @Test fun `多标签权重换行后每行都有底纹`() {
        val prompt = "1.1::honkai: star rail,official art," + "long weighted tag, ".repeat(16) + "::"
        compose.setContent {
            PocketNaiTheme(themeMode = ThemeMode.DARK, dynamicColor = false) {
                androidx.compose.material3.Surface {
                    WeightHighlightedTextField(
                        androidx.compose.ui.text.input.TextFieldValue(prompt), {}, label = null,
                        modifier = androidx.compose.ui.Modifier.testTag("highlight-field"),
                    )
                }
            }
        }
        val image = compose.onNodeWithTag("highlight-field").captureToImage()
        val pixels = image.toPixelMap()
        val highlightedRows = (0 until pixels.height).filter { y ->
            (0 until pixels.width).count { x ->
                val color = pixels[x, y]
                color.red > color.green * 1.3f && color.red > color.blue * 1.3f
            } > 10
        }
        val lines = highlightedRows.zipWithNext().count { (a, b) -> b > a + 1 } + 1
        assertThat(lines).isAtLeast(3)
        val target = java.io.File(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "feedback-weight-highlight.png")
        target.parentFile?.mkdirs()
        target.outputStream().use { image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun `负数绿色与连续未闭合权重在明暗主题下均显示`() {
        var theme by mutableStateOf(ThemeMode.LIGHT)
        val prompt = "-1::hat, glasses\n1.5::tag, tag 1.2::tag::\n1.8::tag"
        compose.setContent {
            PocketNaiTheme(themeMode = theme, dynamicColor = false) {
                androidx.compose.material3.Surface {
                    WeightHighlightedTextField(
                        androidx.compose.ui.text.input.TextFieldValue(prompt), {}, label = "权重反馈验证",
                        modifier = androidx.compose.ui.Modifier.testTag("signed-highlight-field"),
                    )
                }
            }
        }
        listOf(ThemeMode.LIGHT, ThemeMode.DARK).forEach { mode ->
            compose.runOnIdle { theme = mode }
            val pixels = compose.onNodeWithTag("signed-highlight-field").captureToImage().toPixelMap()
            val greenRows = (0 until pixels.height).filter { y ->
                (0 until pixels.width).count { x ->
                    val c = pixels[x, y]
                    c.green > c.red + 0.035f && c.green > c.blue + 0.035f
                } > 10
            }
            val redRows = (0 until pixels.height).filter { y ->
                (0 until pixels.width).count { x ->
                    val c = pixels[x, y]
                    c.red > c.green + 0.035f && c.red > c.blue + 0.035f
                } > 10
            }
            assertThat(greenRows).isNotEmpty()
            assertThat(redRows).isNotEmpty()
            assertThat(greenRows.last()).isLessThan(redRows.first())
            assertThat(redRows.zipWithNext().count { (a, b) -> b > a + 1 } + 1).isAtLeast(2)
            // 在字形上方的底纹行取样：同一行两段红色之间必须有空隙。
            val y = redRows.first() + 2
            val redColumns = (0 until pixels.width).filter { x ->
                val c = pixels[x, y]
                c.red > c.green + 0.035f && c.red > c.blue + 0.035f
            }
            assertThat(redColumns.zipWithNext().count { (a, b) -> b > a + 1 } + 1).isEqualTo(2)
            val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            val target = java.io.File(instrumentation.targetContext.cacheDir, "feedback-signed-${mode.name}.png")
            val screen = instrumentation.uiAutomation.takeScreenshot()
            target.outputStream().use { screen.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            screen.recycle()
        }
    }
}
