package net.pocketnai.domain.model

/**
 * 生成模式（《参考图功能规划书》5.1）。
 *
 * 首版只有纯文生图与**整图**图生图。Inpaint 的请求形态与整图不同
 * （OpenAPI 为它单独准备了嵌套的 `parameters.img2img` 对象），因此不在这一维度里表达。
 */
enum class GenerationMode {
    TXT2IMG,
    IMG2IMG,
    ;

    companion object {
        /**
         * 从存储里的名字解析。
         *
         * 缺失或无法识别时按 [TXT2IMG] 处理：这个功能之前落库的记录全都是纯文生图，
         * 这是唯一正确且不改变既有历史解读的降级方向（与 `QualityTagsOption` 同款处理）。
         */
        fun fromNameOrDefault(name: String?): GenerationMode =
            entries.firstOrNull { it.name == name } ?: TXT2IMG
    }
}

/** 参考图在请求里的角色，决定它被放进哪个字段。 */
enum class ReferenceRole {
    /** 生成起点：`parameters.image`。 */
    IMG2IMG,

    /** 风格参考：`reference_image_multiple` 等三个数组。 */
    VIBE,

    /** 角色 / 画风参考：`director_reference_*` 五个数组。 */
    DIRECTOR,
}

/**
 * Precise Reference 的取用方式，直接对应 `director_reference_descriptions[].caption.base_caption`
 * 的两个取值（来自官方 OpenAPI 字段说明）。
 */
enum class DirectorReferenceKind(val apiValue: String) {
    /** 只取角色。 */
    CHARACTER("character"),

    /** 角色与画风一起取。 */
    CHARACTER_AND_STYLE("character&style"),
    ;

    companion object {
        fun fromApiValueOrDefault(value: String?): DirectorReferenceKind =
            entries.firstOrNull { it.apiValue == value } ?: CHARACTER
    }
}

/**
 * 一张参考图及其逐条参数（《参考图功能规划书》5.1）。
 *
 * [relativePath] 与生成图片使用同一套相对路径约定（以应用私有目录为根），
 * 且是**内容寻址**的：同名文件即同内容，多份历史可以安全地指向同一个文件。
 * 这样反复选同一张图不会在磁盘上堆副本，删除某条历史也不会误删别的历史还在用的文件
 * —— 文件回收统一在启动清理里按"仍被引用的路径集合"处理。
 *
 * 逐条参数按 [role] 取用其中一部分：
 * - [ReferenceRole.IMG2IMG]：只用 [strength]；
 * - [ReferenceRole.VIBE]：[strength]、[informationExtracted]、[vibeRelativePath]；
 * - [ReferenceRole.DIRECTOR]：[strength]、[secondaryStrength]、[informationExtracted]、[directorKind]。
 */
data class ReferenceImage(
    val id: String,
    val role: ReferenceRole,
    /** 同一次生成内的顺序号，从 0 开始，与请求数组的下标一一对应。 */
    val ordinal: Int,
    val relativePath: String,
    val width: Int,
    val height: Int,
    val byteSize: Long,
    val sha256: String,
    val createdAt: Long,
    /** Image2Img 的 Strength；Vibe / Director 的 Reference Strength。 */
    val strength: Double? = null,
    /** Vibe / Director 的 Information Extracted。 */
    val informationExtracted: Double? = null,
    /** Precise Reference 的 Fidelity（API 里的 `director_reference_secondary_strength_values`）。 */
    val secondaryStrength: Double? = null,
    /** 仅 [ReferenceRole.DIRECTOR] 使用。 */
    val directorKind: DirectorReferenceKind? = null,
    /**
     * `encode-vibe` 产物（`.vibe`）的相对路径，仅 [ReferenceRole.VIBE] 使用。
     *
     * 为空表示尚未编码。缓存键含模型，因此换模型后需要重新编码（见规划书 3.4 B5）。
     */
    val vibeRelativePath: String? = null,
)
