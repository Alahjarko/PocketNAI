package net.pocketnai.ui.generate

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import net.pocketnai.domain.model.CharacterPrompt
import net.pocketnai.domain.model.GenerationParams
import net.pocketnai.domain.model.ModelCatalog
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
}
