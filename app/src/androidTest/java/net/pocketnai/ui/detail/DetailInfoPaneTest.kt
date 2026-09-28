package net.pocketnai.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.pocketnai.domain.model.CharacterPrompt
import net.pocketnai.domain.model.GeneratedImage
import net.pocketnai.domain.model.Generation
import net.pocketnai.domain.model.GenerationParams
import net.pocketnai.domain.model.GenerationStatus
import net.pocketnai.domain.model.ModelCatalog
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DetailInfoPaneTest {
    @get:Rule val compose = createComposeRule()

    private fun showDetail(prompt: String = "base prompt", negative: String = "") {
        val generation = Generation(
            id = "detail-test", createdAt = 0, updatedAt = 0, status = GenerationStatus.SUCCEEDED,
            title = "示例", promptTemplate = prompt, requestSnapshotVersion = 1,
            params = GenerationParams.defaultsFor(ModelCatalog.defaultProfile()).copy(
                prompt = prompt,
                characters = listOf(CharacterPrompt(prompt = "blue hair", negativePrompt = negative)),
            ),
        )
        val image = GeneratedImage(
            id = "image-test", generationId = generation.id, ordinal = 1, seed = 42,
            privateFilePath = "unused.png", width = 1024, height = 1024,
            byteSize = 0, sha256 = "unused", createdAt = 0,
        )
        compose.setContent {
            MaterialTheme {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    DetailInfoPane(
                        image, generation, true, null, false, "已保存",
                        onSaveClick = {}, onCopyPrompt = {}, onReuseParams = {}, onInpaint = {},
                        onOpenUpscaleDialog = {}, onDelete = {}, onDismissError = {},
                    )
                }
            }
        }
    }

    @Test
    fun longPromptsDoNotPushActionsBelowTheText() {
        showDetail(prompt = "long prompt, ".repeat(1000))
        compose.onNodeWithText("复用参数").assertIsDisplayed()
        compose.onNodeWithText("高清放大").assertIsDisplayed()
        compose.onNodeWithText("Sampler").assertDoesNotExist()
        compose.onNodeWithText("完整生成参数").performClick()
        compose.onNodeWithText("Sampler").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun characterWithoutNegativePromptHasNoEmptyNegativeSection() {
        showDetail()
        compose.onNodeWithText("独立角色 1").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("居中").assertIsDisplayed()
        compose.onNodeWithText("角色专属排除词").assertDoesNotExist()
        compose.onNodeWithText("未设置").assertDoesNotExist()
    }

    @Test
    fun characterNegativePromptRemainsSeparateAndReadable() {
        showDetail(negative = "hat, glasses")
        compose.onNodeWithText("hat, glasses").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("角色专属排除词").assertIsDisplayed()
    }
}
