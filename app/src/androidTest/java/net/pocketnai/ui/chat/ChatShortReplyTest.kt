package net.pocketnai.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import net.pocketnai.domain.chat.ChatEntry
import net.pocketnai.domain.chat.wireMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ChatShortReplyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun formattedShortReplyShowsAllPoints() {
        val text = (1..12).joinToString("\n\n") { "$it. 角色、服装、场景与光线的要点" }
        compose.setContent {
            MaterialTheme { LazyColumn(Modifier.fillMaxSize()) { item { ChatMessageBubble(ChatEntry("short", wireMessage("assistant", text)), { File("") }, {}) } } }
        }
        compose.onNodeWithText(text).assertExists()
        compose.onAllNodesWithTag("chat-read-full").assertCountEquals(0)
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText(text).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertThat(layouts.single().lineCount).isGreaterThan(8)
        assertThat((0 until layouts.single().lineCount).any { layouts.single().isLineEllipsized(it) }).isFalse()
    }
}
