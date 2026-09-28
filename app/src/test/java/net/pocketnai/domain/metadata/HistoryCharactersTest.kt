package net.pocketnai.domain.metadata

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HistoryCharactersTest {
    @Test
    fun `旧图恢复保持角色顺序独立负向词和原始坐标`() {
        val png = PngFixture.novelAi(commentJson = """
            {"v4_prompt":{"caption":{"char_captions":[
                {"char_caption":"blue hair","centers":[{"x":0.2,"y":0.3}]},
                {"char_caption":"red hair","centers":[{"x":0.8,"y":0.7}]}
            ]}},"v4_negative_prompt":{"caption":{"char_captions":[
                {"char_caption":"hat"},{"char_caption":"glasses"}
            ]}}}
        """.trimIndent())
        val characters = HistoryCharacters.readLegacy(png.inputStream())
        assertThat(characters.map { it.prompt }).containsExactly("blue hair", "red hair").inOrder()
        assertThat(characters.map { it.negativePrompt }).containsExactly("hat", "glasses").inOrder()
        assertThat(characters[0].centerX).isEqualTo(0.2)
        assertThat(characters[0].centerY).isEqualTo(0.3)
        assertThat(characters[1].centerX).isEqualTo(0.8)
        assertThat(characters[1].centerY).isEqualTo(0.7)
    }

    @Test
    fun `普通图片和无效文件没有角色`() {
        assertThat(HistoryCharacters.readLegacy(PngFixture.png(texts = emptyList()).inputStream())).isEmpty()
        assertThat(HistoryCharacters.readLegacy(byteArrayOf(1, 2, 3).inputStream())).isEmpty()
    }
}
