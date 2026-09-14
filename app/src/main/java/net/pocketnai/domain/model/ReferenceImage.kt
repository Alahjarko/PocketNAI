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

    /**
     * Precise Reference（API 字段叫 `director_reference_*`）。
     *
     * 与图生图的语义完全不同：图生图把源图当作生成的起点，整张图要重新长出来；
     * Precise Reference 把参考图当作条件（角色或画风），生成仍从空白开始。
     * 因此它不是一个"带图的文生图"，而是独立的一种模式。
     */
    PRECISE_REFERENCE,

    /**
     * 局部重绘（官方界面叫 Inpaint，API 里叫 `infill`）。
     *
     * 属于 **Image2Img 家族**：底图 + 蒙版，只重画被涂抹的区域。
     * 因此它四个模型都能用（V4.5 与 V5），也不额外计费。
     */
    INPAINT,
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

    /**
     * 局部重绘的蒙版（`parameters.mask`）。
     *
     * 单独占一个角色的好处：**不需要改数据库 schema**，而且启动清理按"仍被引用的路径集合"
     * 回收文件时，`allReferencePaths()` 的 UNION 查询会自动把它算进去 ——
     * 这正是之前"草稿里的参考图被当孤儿删掉"那个坑的同一条防线。
     */
    INPAINT_MASK,
}

/**
 * Precise Reference 的取用方式，直接对应 `director_reference_descriptions[].caption.base_caption`
 * 的两个取值（来自官方 OpenAPI 字段说明）。
 */
enum class DirectorReferenceKind(val apiValue: String) {
    /** 只取角色。 */
    CHARACTER("character"),

    /**
     * 只取画风（官方文档里的 Style Reference）。
     *
     * ⚠️ `"style"` 这个取值是**推断**的：OpenAPI 的字段说明只给出了 `character` 与
     * `character&style` 两个可用值（那份说明是写给 Character Reference 的），
     * 而官方文档明确列出三种参考类型（Character / Style / Character & Style）。
     * 真机核对后如果服务端不接受，改这一个字符串即可。
     */
    STYLE("style"),

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
    /**
     * 标识符。**两种身份共用这一个字段，但语义随来源不同**：
     * - 由界面刚导入时：素材身份（同一次导入在多次生成间保持不变）；
     * - 从数据库读回时：行身份（哪条生成记录的第几个参考图）。
     *
     * 因此它只适合当"诊断用的标识"（例如 [ReferenceViolation.FileMissing] 里指认是哪一张），
     * **不要拿它做跨生成的去重或比较** —— 那种判断要用 [sha256]。
     */
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
) {
    companion object {
        /**
         * 把一张刚导入的本地图片变成 Image2Img 的起点。
         *
         * 顺序号固定为 0：起点图只有一张，`GenerationRequest.validate` 也会拒绝多于一张。
         */
        fun img2imgSource(
            prepared: net.pocketnai.domain.image.PreparedReference,
            strength: Double,
            id: String,
            createdAt: Long,
        ): ReferenceImage = ReferenceImage(
            id = id,
            role = ReferenceRole.IMG2IMG,
            ordinal = 0,
            relativePath = prepared.relativePath,
            width = prepared.width,
            height = prepared.height,
            byteSize = prepared.byteSize,
            sha256 = prepared.sha256,
            createdAt = createdAt,
            strength = strength,
        )
    }
}
