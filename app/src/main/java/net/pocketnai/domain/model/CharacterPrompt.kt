package net.pocketnai.domain.model

import kotlinx.serialization.Serializable

/**
 * 角色提示词与空间布局（规划书与官方 V4.5/V5 对齐）。
 *
 * 对应请求体中的 `v4_prompt.caption.char_captions` 与 `v4_negative_prompt.caption.char_captions`。
 * 坐标范围通常为 0.0 ~ 1.0，中心默认 (0.5, 0.5)。
 */
@Serializable
data class CharacterPrompt(
    val id: String = java.util.UUID.randomUUID().toString(),
    val prompt: String = "",
    val negativePrompt: String = "",
    val centerX: Double = 0.5,
    val centerY: Double = 0.5,
) {
    val isBlank: Boolean get() = prompt.isBlank() && negativePrompt.isBlank()

    constructor(
        id: String = java.util.UUID.randomUUID().toString(),
        prompt: String = "",
        negativePrompt: String = "",
        position: CharacterPosition,
    ) : this(id, prompt, negativePrompt, position.x, position.y)

    companion object {
        /**
         * 角色数量上限。
         *
         * 官方网页是 5×5 网格、上限更高；我们只做五档横排，5 个是界面与请求共同遵守的上限
         * （添加按钮、元数据导入截断都读这一个常量，别再各写一份）。
         */
        const val MAX_COUNT: Int = 5
    }
}

/** 5 档横向快捷站位选项 */
enum class CharacterPosition(val label: String, val x: Double, val y: Double) {
    FAR_LEFT("左", 0.15, 0.5),
    MID_LEFT("偏左", 0.32, 0.5),
    CENTER("居中", 0.5, 0.5),
    MID_RIGHT("偏右", 0.68, 0.5),
    FAR_RIGHT("右", 0.85, 0.5),
    ;

    companion object {
        fun fromCoords(x: Double, y: Double): CharacterPosition {
            return entries.minByOrNull {
                val dx = it.x - x
                val dy = it.y - y
                dx * dx + dy * dy
            } ?: CENTER
        }
    }
}
