package net.pocketnai.ui.update

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import net.pocketnai.BuildConfig
import net.pocketnai.core.ErrorCode
import net.pocketnai.domain.model.ThemeMode
import net.pocketnai.domain.update.UpdateRelease
import net.pocketnai.ui.theme.PocketNaiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpdateDialogsTest {
    @get:Rule val compose = createComposeRule()
    private val opened = mutableListOf<String>()
    // 仅截获 URI，不打开浏览器、不联网。
    private val uriHandler = object : UriHandler {
        override fun openUri(uri: String) { opened += uri }
    }
    private val latestUrl = "https://github.com/${BuildConfig.UPDATE_REPO}/releases/latest"

    @Test fun `检查中最新与失败状态均可打开最新Release主页`() {
        var state by mutableStateOf(UpdateViewModel.UiState())
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                PocketNaiTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
                    UpdateCheckDialog("0.1.9", 9, state, {}, {})
                }
            }
        }
        val states = listOf(
            UpdateViewModel.UiState(), UpdateViewModel.UiState(checking = true),
            UpdateViewModel.UiState(upToDateNotice = true),
            UpdateViewModel.UiState(error = ErrorCode.UPDATE_CHECK_FAILED),
        )
        states.forEach { next ->
            compose.runOnIdle { state = next }
            compose.onNodeWithText("在 GitHub 查看最新版").assertIsDisplayed().performClick()
        }
        compose.runOnIdle { assertThat(opened).containsExactlyElementsIn(List(states.size) { latestUrl }) }
    }

    @Test fun `发现更新且下载失败时也可通过网页更新`() {
        var downloads = 0
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                PocketNaiTheme(themeMode = ThemeMode.DARK, dynamicColor = false) {
                    UpdateAvailableDialog(
                        release = UpdateRelease(10, "0.1.10", "build-10", "https://example.invalid/app.apk", 10L, "测试版本"),
                        currentVersionName = "0.1.9", download = null, errorCode = ErrorCode.UPDATE_DOWNLOAD_FAILED,
                        onDownload = { downloads++ }, onLater = {}, onDismissError = {},
                    )
                }
            }
        }
        compose.onNodeWithText("在 GitHub 查看最新版").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertThat(opened).containsExactly(latestUrl)
            assertThat(downloads).isEqualTo(0)
        }
    }
}
