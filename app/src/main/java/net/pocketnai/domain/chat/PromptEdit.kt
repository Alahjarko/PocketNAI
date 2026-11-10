package net.pocketnai.domain.chat

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class PromptEdit(
    val title: String = "提示词调整",
    val prompt: String? = null,
    val negative: String? = null,
    val beforePrompt: String = "",
    val beforeNegative: String? = "",
    val provider: String = "novelai",
) {
    companion object {
        fun parse(call: ToolCall, base: PromptEdit): PromptEdit {
            if (call.arguments.length > 96 * 1024) throw ChatFailure("改词建议过长")
            val obj = runCatching { Json.parseToJsonElement(call.arguments).jsonObject }.getOrNull() ?: throw ChatFailure("改词建议格式不正确")
            if (obj.keys.any { it !in setOf("title", "prompt", "negative_prompt") }) throw ChatFailure("改词建议包含不支持的参数")
            fun field(key: String): String? = obj[key]?.let {
                val value = (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw ChatFailure("提示词须为文字")
                if (value.length > 16000) throw ChatFailure("提示词过长")
                value
            }
            val positive = field("prompt"); val negative = field("negative_prompt")
            if (positive == null && negative == null) throw ChatFailure("改词建议没有修改内容")
            if (negative != null && base.beforeNegative == null) throw ChatFailure("当前图片服务不使用排除词")
            return base.copy(title = field("title")?.take(80)?.ifBlank { "提示词调整" } ?: "提示词调整", prompt = positive, negative = negative)
        }
    }
}
