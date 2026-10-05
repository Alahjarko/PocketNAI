package net.pocketnai.ui.chat

import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import net.pocketnai.PocketNaiApplication
import net.pocketnai.ui.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 实际导航、输入法和已有 SFW 图片，只操作本地界面，不发送消息或生成图片。 */
@RunWith(AndroidJUnit4::class)
class ChatComposerInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun keyboardCompositionAndGalleryAttachment() = runBlocking {
        val app = compose.activity.application as PocketNaiApplication
        val oldKeyboard = shell("settings get secure show_ime_with_hard_keyboard").trim()
        val oldIme = shell("settings get secure default_input_method").trim()
        val testIme = "net.pocketnai.test/net.pocketnai.ui.chat.ChatTestImeService"
        shell("ime enable $testIme"); shell("ime set $testIme")
        shell("settings put secure show_ime_with_hard_keyboard 1")
        try {
        compose.waitForIdle()
        compose.runOnIdle { app.container.settingsStore.setChatEnabled(true) }
        compose.onNodeWithText("对话").performClick()
        compose.onNodeWithTag("chat-input").performClick()
        compose.waitUntil(10_000) { (compose.activity.window.decorView.rootWindowInsets?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0) > 200 }
        compose.runOnIdle {
            val view = composeView(compose.activity.window.decorView)!!
            val connection = view.onCreateInputConnection(EditorInfo())!!
            connection.setComposingText("xiang", 1)
            connection.setComposingText("xiangyao", 1)
            connection.commitText("想要", 1)
            connection.setComposingText("shengcheng", 1)
            connection.commitText("生成", 1)
            connection.setSelection(0, 0)
            connection.commitText("再", 1)
            connection.setSelection(5, 5)
            connection.commitText("\n雨后的咖啡店", 1)
            connection.deleteSurroundingText(1, 0)
            connection.commitText("店", 1)
        }
        compose.onNodeWithTag("chat-input").assertTextEquals("再想要生成\n雨后的咖啡店")
        compose.onNodeWithText("画廊").assertDoesNotExist()
        var keyboardTop = 0
        compose.runOnIdle {
            val decor = compose.activity.window.decorView
            keyboardTop = decor.height - decor.rootWindowInsets.getInsets(WindowInsets.Type.ime()).bottom
        }
        val composerBottom = compose.onNodeWithTag("chat-composer").fetchSemanticsNode().boundsInWindow.bottom
        assertThat(keyboardTop - composerBottom).isAtLeast(0f)
        assertThat(keyboardTop - composerBottom).isAtMost(48f)
        screenshot(app.cacheDir, "chat-fixed-keyboard.png")
        compose.onNodeWithTag("chat-attach-button").performClick()
        compose.onNodeWithText("从相册选择").assertExists()
        compose.onNodeWithText("从软件画廊选择").performClick()
        val safe = app.container.generationRepository.observeGallery().first().first { it.imageId == "e18aff2f-275d-4d7e-9723-f2db61d1784f" }
        compose.onNodeWithTag("history-image-picker").performScrollToNode(hasTestTag("history-image-${safe.imageId}"))
        compose.onNodeWithTag("history-image-${safe.imageId}").performClick()
        var vm: ChatViewModel? = null
        compose.runOnIdle { vm = ViewModelProvider(compose.activity)[ChatViewModel::class.java] }
        compose.waitUntil(10_000) { !vm!!.state.value.importingAttachment && vm!!.state.value.draftAttachments.isNotEmpty() }
        compose.onNodeWithTag("chat-attachment-strip").assertExists()
        val attachment = vm!!.state.value.draftAttachments.single()
        assertThat(app.container.fileStore.resolve(attachment.relativePath).isFile).isTrue()
        assertThat(app.container.fileStore.resolve(safe.relativePath).isFile).isTrue()
        screenshot(app.cacheDir, "chat-fixed-gallery-attachment.png")
        compose.onNodeWithTag("chat-remove-attachment").performClick()
        compose.waitUntil(10_000) { !app.container.fileStore.resolve(attachment.relativePath).exists() }
        assertThat(app.container.fileStore.resolve(safe.relativePath).isFile).isTrue()
        compose.onNodeWithTag("chat-input").performTextClearance()
        } catch (failure: Throwable) {
            screenshot(app.cacheDir, "chat-ime-failure.png")
            File(app.cacheDir, "chat-ime-failure.txt").writeText("height=${compose.activity.window.decorView.height};ime=${compose.activity.window.decorView.rootWindowInsets?.getInsets(WindowInsets.Type.ime())?.bottom};selected=${shell("settings get secure default_input_method").trim()}")
            throw failure
        } finally {
            shell("ime set $oldIme"); shell("ime disable $testIme")
            if (oldKeyboard == "null") shell("settings delete secure show_ime_with_hard_keyboard")
            else shell("settings put secure show_ime_with_hard_keyboard $oldKeyboard")
        }
    }

    private fun composeView(view: View): View? {
        if (view.javaClass.simpleName == "AndroidComposeView") return view
        if (view is ViewGroup) for (index in 0 until view.childCount) composeView(view.getChildAt(index))?.let { return it }
        return null
    }
    private fun screenshot(root: File, name: String) {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(root, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun shell(command: String): String = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use { descriptor ->
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes().toString(Charsets.UTF_8) }
    }
}
