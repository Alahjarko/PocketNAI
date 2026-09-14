package net.pocketnai.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.pocketnai.domain.image.ResolutionPlanner
import net.pocketnai.domain.model.CustomResolution
import net.pocketnai.domain.model.ImageSizePreset
import net.pocketnai.domain.model.ModelCatalog
import net.pocketnai.domain.model.ResolutionTier
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 分辨率控件的界面测试。
 *
 * 自定义分辨率的关键不是"能输入两个数"，而是**不静默调整**：用户输入 `1920×1080`
 * 时界面必须同时说清"最终输出多少、NovelAI 实际生成多大、裁掉多少、按哪个尺寸计费"。
 * 这四行文案是功能的一部分，所以在这里被断言，而不是只靠肉眼看一次。
 */
@RunWith(AndroidJUnit4::class)
class ResolutionSelectorTest {

    @get:Rule
    val compose = createComposeRule()

    private val profile = ModelCatalog.profileOf(ModelCatalog.defaultProfile().model)

    private fun setContent(
        custom: CustomResolution?,
        onWidthChange: (Int) -> Unit = {},
        onCustomEnabled: () -> Unit = {},
        onExactOutputChange: (Boolean) -> Unit = {},
    ) {
        val plan = custom?.let { value ->
            (ResolutionPlanner.plan(
                net.pocketnai.domain.image.PixelSize(value.width, value.height),
                exactOutput = value.exactOutput,
            ) as? ResolutionPlanner.Result.Success)?.plan
        }
        compose.setContent {
            MaterialTheme {
                ResolutionSelector(
                    tier = ResolutionTier.NORMAL,
                    size = ImageSizePreset(832, 1216),
                    availableTiers = profile.availableTiers(),
                    availableOrientations = profile.availableOrientations(ResolutionTier.NORMAL),
                    onTierChange = {},
                    onOrientationChange = {},
                    custom = custom,
                    plan = plan,
                    customError = null,
                    constraints = profile.sizeConstraints,
                    onCustomEnabled = onCustomEnabled,
                    onCustomDisabled = {},
                    onCustomWidthChange = onWidthChange,
                    onCustomHeightChange = {},
                    onCustomSwap = {},
                    onExactOutputChange = onExactOutputChange,
                )
            }
        }
    }

    @Test
    fun customModeExplainsFinalAndGeneratedSize() {
        setContent(CustomResolution(width = 1920, height = 1080, exactOutput = true))

        compose.onNodeWithText("最终输出：1920 × 1080").assertIsDisplayed()
        compose.onNodeWithText("NovelAI 生成：1920 × 1088，本地居中裁切（上下各裁 4 px）")
            .assertIsDisplayed()
        compose.onNodeWithText("计费依据：1920 × 1088").assertIsDisplayed()
    }

    @Test
    fun nativeModeSaysTheFileIsTheRoundedCanvas() {
        // 仿官方模式：不裁切，所以"你输入的 1080 不是 64 的倍数"必须写出来，
        // 否则用户会以为最终文件是 1080 高。
        setContent(CustomResolution(width = 1920, height = 1080, exactOutput = false))

        compose.onNodeWithText("NovelAI 生成：1920 × 1088（不裁切，最终文件就是这个尺寸）")
            .assertIsDisplayed()
        compose.onNodeWithText("你输入的边长不是 64 的倍数，已按官方规则取最近的合法值。")
            .assertIsDisplayed()
    }

    @Test
    fun verticalCustomSizeReportsHorizontalCrop() {
        setContent(CustomResolution(width = 1080, height = 1920, exactOutput = true))

        compose.onNodeWithText("最终输出：1080 × 1920").assertIsDisplayed()
        compose.onNodeWithText("NovelAI 生成：1088 × 1920，本地居中裁切（左右各裁 4 px）")
            .assertIsDisplayed()
    }

    @Test
    fun presetModeHasNoCustomPanelUntilTheChipIsTapped() {
        var enabled = false
        setContent(custom = null, onCustomEnabled = { enabled = true })

        compose.onNodeWithText("自定义").performClick()

        org.junit.Assert.assertTrue(enabled)
        // 预设模式下不显示尺寸对照：没有裁切就没有需要解释的东西。
        compose.onNodeWithText("最终输出：832 × 1216").assertDoesNotExist()
    }
}
