package net.pocketnai.domain.prompt

import com.google.common.truth.Truth.assertThat
import kotlin.random.Random
import net.pocketnai.domain.model.*
import org.junit.Test

class PromptTemplatesTest {
    @Test fun `全局和角色宏展开冻结本地随机而保留官方随机语法`() {
        fun saved(id: String, name: String, content: String) = PromptFavorite(id, PromptFavoriteKind.TAG,
            name, content, "", PromptTarget.POSITIVE, 0, 0, 0)
        val favorites = listOf(saved("style", "风格", "!macro:光线!, <oil painting|watercolor>"),
            saved("light", "光线", "soft lighting"))
        val original = GenerationParams.defaultsFor(ModelCatalog.profileOf(ImageModel.V5_FULL)).copy(
            prompt = "!macro:风格!, ||red hair|blue hair||",
            negativePrompt = "⌜macro:light⌟",
            characters = listOf(CharacterPrompt(prompt = "!macro:光线!, <hat|ribbon>", negativePrompt = "!macro:光线!")),
        )
        val resolved = PromptTemplates.resolve(original, favorites, Random(123))
        assertThat(resolved.prompt).startsWith("soft lighting, ")
        assertThat(resolved.prompt).endsWith(", ||red hair|blue hair||")
        assertThat(resolved.prompt).doesNotContain("<")
        assertThat(resolved.negativePrompt).isEqualTo("soft lighting")
        assertThat(resolved.characters.single().prompt).startsWith("soft lighting, ")
        assertThat(resolved.characters.single().negativePrompt).isEqualTo("soft lighting")
        assertThat(original.prompt).isEqualTo("!macro:风格!, ||red hair|blue hair||")
        // A cycle must fail before a billable request, rather than repeatedly expand or truncate it.
        val cycle = listOf(saved("cycle", "循环", "!macro:循环!"))
        assertThat(runCatching { PromptTemplates.resolve(original.copy(prompt = "!macro:循环!"), cycle, Random(1)) }.isFailure).isTrue()
    }
}
