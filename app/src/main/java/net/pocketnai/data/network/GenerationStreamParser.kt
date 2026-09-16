package net.pocketnai.data.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * 把 SSE 帧解析成流式生成事件。
 *
 * ## 结构从哪来
 * OpenAPI 对 `/ai/generate-image-stream` 只写了"流里会包含 Intermediate / Final / Error
 * 三种事件"，**没有给 payload 的字段定义**。因此这里的字段名是按服务端的一贯命名
 * 推断的（`image` 放 base64、`step`/`total_steps` 表进度），并保持宽容：
 * - 事件类型先看 SSE 的 `event` 字段，缺失时退回 data JSON 的 `type` 字段；
 * - 认不出结构的帧归入 [Event.Unknown]，只计数、不打断流 —— 首次真机验证时
 *   由诊断输出确认真实结构，再收紧解析。
 *
 * 图片载荷按 base64 字符串读取，解码与 PNG 校验由调用方完成（本对象不碰字节）。
 */
object GenerationStreamParser {

    sealed interface Event {
        /**
         * 中间预览：只用于界面显示，不写入历史。
         *
         * [step] 来自服务端的 `step_ix`（真实结构见技术决策记录 §24）；
         * 服务端**不告诉总步数**，百分比由调用方结合请求里的 steps 自己算。
         */
        data class Intermediate(val imageBase64: String, val step: Int?) : Event

        /** 最终图：与普通 ZIP 响应里的图片等价，逐张到达。 */
        data class Final(val imageBase64: String) : Event

        /** 服务端在流内报告的一次错误。 */
        data class StreamError(val message: String) : Event

        /** 认不出的帧：计入诊断，不参与业务。 */
        data class Unknown(val label: String) : Event
    }

    fun parse(frame: SseFrameReader.Frame, json: Json): Event {
        val obj = frame.jsonObject(json)
        val type = (frame.event ?: obj?.typeField()).orEmpty().lowercase()

        return when (type) {
            "intermediate" -> {
                val image = obj?.imageField()
                if (image.isNullOrEmpty()) {
                    Event.Unknown(label(frame, json))
                } else {
                    Event.Intermediate(imageBase64 = image, step = obj.stepField())
                }
            }

            "final" -> {
                val image = obj?.imageField()
                if (image.isNullOrEmpty()) {
                    Event.Unknown(label(frame, json))
                } else {
                    Event.Final(imageBase64 = image)
                }
            }

            "error" -> Event.StreamError(
                message = obj?.stringField("message", "error", "detail")
                    ?: "流式响应报告了错误",
            )

            else -> Event.Unknown(label(frame, json))
        }
    }

    /**
     * 帧的结构摘要，供诊断输出使用。
     *
     * **只包含结构信息**：事件名、data 长度、JSON 顶层键名、是否像 base64 ——
     * 绝不含字段值（图片、提示词都可能是敏感内容）。
     */
    fun label(frame: SseFrameReader.Frame, json: Json): String {
        val obj = frame.jsonObject(json)
        val structure = when {
            obj != null -> "keys=${obj.keys.sorted()}"
            frame.data.startsWith("{") -> "keys=(JSON 解析失败)"
            looksLikeBase64(frame.data) -> "base64(len=${frame.data.length})"
            frame.data.isEmpty() -> "empty"
            else -> "text(len=${frame.data.length})"
        }
        return "event=${frame.event ?: "-"} $structure"
    }

    private fun SseFrameReader.Frame.jsonObject(json: Json): JsonObject? = runCatching {
        json.parseToJsonElement(data) as? JsonObject
    }.getOrNull()

    private fun JsonObject.typeField(): String? =
        stringField("type", "event", "event_type", "eventType")

    private fun JsonObject.imageField(): String? =
        stringField("image", "image_data", "data")

    /** 采样步号：服务端发的是 `step_ix`（首帧即出现，配合请求里的 steps 即得进度）。 */
    private fun JsonObject.stepField(): Int? =
        read("step_ix")?.intOrNull ?: read("step")?.intOrNull

    private fun JsonObject.stringField(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key ->
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }
        }

    private fun JsonObject.read(key: String): JsonPrimitive? =
        this[key] as? JsonPrimitive

    /** base64 字母表粗检：只看字符集，不看内容。 */
    private fun looksLikeBase64(text: String): Boolean {
        if (text.length < 64) return false
        val sample = text.take(256)
        return sample.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' || it == '\n' }
    }
}
