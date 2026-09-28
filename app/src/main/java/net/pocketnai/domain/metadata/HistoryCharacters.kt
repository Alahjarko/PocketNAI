package net.pocketnai.domain.metadata

import java.io.InputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.pocketnai.domain.model.CharacterPrompt

/** 历史角色快照；旧记录仅从自身 PNG 恢复，不混入当前草稿。 */
object HistoryCharacters {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(characters: List<CharacterPrompt>): String = json.encodeToString(characters)

    fun decode(raw: String?): List<CharacterPrompt> =
        raw?.let { runCatching { json.decodeFromString<List<CharacterPrompt>>(it) }.getOrNull() }
            ?: emptyList()

    fun readLegacy(input: InputStream): List<CharacterPrompt> {
        val chunks = (PngTextChunks.read(input) as? PngTextChunks.Result.Read)?.chunks
            ?: return emptyList()
        val metadata = NovelAiMetadataParser.parse(chunks, json) ?: return emptyList()
        // 历史展示保留原坐标与顺序，不套用导入编辑器的五档吸附。
        return metadata.characters.mapIndexed { index, character ->
            CharacterPrompt(
                id = "legacy-character-$index",
                prompt = character.prompt,
                negativePrompt = character.negativePrompt.orEmpty(),
                centerX = character.centerX ?: 0.5,
                centerY = character.centerY ?: 0.5,
            )
        }
    }
}
