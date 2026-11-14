package net.pocketnai.domain.mcp

import kotlinx.serialization.json.*
import net.pocketnai.domain.chat.ChatFailure
import net.pocketnai.domain.chat.ImageChatTools
import net.pocketnai.domain.chat.ToolCall
import net.pocketnai.domain.model.GenerationParams

/** External structured characters use the same generation path as the drawing assistant. */
object McpNovelAiParams {
    fun parse(arguments: JsonObject, base: GenerationParams): GenerationParams {
        arguments["characters"]?.let { value ->
            val roles = value as? JsonArray ?: throw ChatFailure("characters 必须为数组")
            roles.forEach { value ->
                val role = value as? JsonObject ?: throw ChatFailure("每个角色必须为对象")
                if (role.keys.any { it !in setOf("prompt", "negative_prompt", "x", "y") })
                    throw ChatFailure("角色只支持 prompt、negative_prompt、x、y；权重请写入提示词，例如 1.2::blue hair::")
                for (key in listOf("prompt", "negative_prompt")) role[key]?.let {
                    val text = (it as? JsonPrimitive)?.takeIf { it.isString }?.content
                        ?: throw ChatFailure("角色提示词必须为字符串")
                    if (text.length > 8000) throw ChatFailure("角色提示词过长")
                }
                for (key in listOf("x", "y")) role[key]?.let {
                    if (it !is JsonPrimitive || it.isString || it.doubleOrNull?.let { n -> n.isFinite() && n in 0.0..1.0 } != true)
                        throw ChatFailure("角色位置必须是 0–1 之间的数字")
                }
            }
        }
        arguments["use_coords"]?.let {
            if (it !is JsonPrimitive || it.isString || it.booleanOrNull == null)
                throw ChatFailure("use_coords 必须为布尔值")
        }
        val payload = buildJsonObject {
            arguments.filterKeys { it in setOf("prompt", "negative_prompt", "model", "width", "height", "seed", "characters", "use_coords") }
                .forEach { (k, v) -> put(k, v) }
            if ("negative_prompt" !in arguments) put("negative_prompt", base.negativePrompt)
        }
        return ImageChatTools.parse(ToolCall("mcp", "generate_image", payload.toString()), base)
    }

    fun characterDetails(params: GenerationParams): String = if (params.characters.isEmpty()) "" else buildString {
        append(if (params.useCharacterCoordinates) "独立角色 · 手动站位（画布中心坐标 0–1）" else "独立角色 · AI 自动布局（不使用坐标）")
        params.characters.forEachIndexed { index, role ->
            append("\n\n角色 ${index + 1}")
            if (params.useCharacterCoordinates) append(" · x=${role.centerX}, y=${role.centerY}")
            append("\n${role.prompt}")
            if (role.negativePrompt.isNotBlank()) append("\n排除词：${role.negativePrompt}")
        }
    }
}
