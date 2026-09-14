package net.pocketnai.domain.model

/**
 * 一次生成的完整输入：参数 + 参考图（《参考图功能规划书》5.1）。
 *
 * 参考图**不进入** [GenerationParams]，原因是后者要被 `Mappers` 展平进 `generations`
 * 表的一行，而参考图是数量可变、可选、带逐条参数的列表，塞进去会破坏那个映射。
 * 因此请求构造的入口是 [GenerationRequest] 而不是 `GenerationParams`：
 * 只有"参数"没有"图"的 T2I 请求是它的一个空参考图特例。
 */
data class GenerationRequest(
    val params: GenerationParams,
    val mode: GenerationMode = GenerationMode.TXT2IMG,
    val references: List<ReferenceImage> = emptyList(),
) {

    fun referencesOf(role: ReferenceRole): List<ReferenceImage> =
        references.filter { it.role == role }.sortedBy { it.ordinal }

    /** Image2Img 的起点；未选择时为 null。 */
    val img2imgSource: ReferenceImage? get() = referencesOf(ReferenceRole.IMG2IMG).firstOrNull()

    /**
     * 参考图相关的阻塞问题。空列表表示可以安全提交。
     *
     * 这是参考图校验的**唯一出口**：数量上限、起点是否存在、文件是否还在本机，
     * 都在这里判断，避免界面层与网络层各自发明一套规则。
     * （`GenerationParams` 自身的合法性仍由 [ModelProfile.validate] 负责，两者互不重叠。）
     */
    fun validate(
        profile: ModelProfile,
        referenceExists: (ReferenceImage) -> Boolean = { true },
    ): List<ReferenceViolation> = buildList {
        if (mode == GenerationMode.IMG2IMG && img2imgSource == null) {
            add(ReferenceViolation.MissingImg2ImgSource)
        }
        if (profile.supportsImg2Img.not() && mode == GenerationMode.IMG2IMG) {
            add(ReferenceViolation.ModeUnsupported(GenerationMode.IMG2IMG))
        }
        if (!profile.supportsInpaint && mode == GenerationMode.INPAINT) {
            add(ReferenceViolation.ModeUnsupported(GenerationMode.INPAINT))
        }
        if (mode == GenerationMode.INPAINT) {
            // 没有蒙版（或蒙版是空的）时提交等于"整图重画"，语义不明，直接拦下。
            if (referencesOf(ReferenceRole.INPAINT_MASK).isEmpty()) {
                add(ReferenceViolation.MissingInpaintMask)
            }
            // 底图 + 蒙版是重绘的全部输入，再挂参考条件只会被服务端拒绝。
            if (referencesOf(ReferenceRole.DIRECTOR).isNotEmpty() ||
                referencesOf(ReferenceRole.VIBE).isNotEmpty()
            ) {
                add(ReferenceViolation.ConflictingWithMode(GenerationMode.INPAINT))
            }
        }
        // Precise Reference 与 Vibe 目前只有 V4.5 能用（账号所有者确认），
        // V5 上提交这类请求只会得到服务端拒绝，因此在本地就拦下并说明原因。
        if (mode == GenerationMode.PRECISE_REFERENCE && !profile.supportsDirectorReference) {
            add(ReferenceViolation.ModeUnsupported(GenerationMode.PRECISE_REFERENCE))
        }
        if (referencesOf(ReferenceRole.VIBE).isNotEmpty() && !profile.supportsVibeTransfer) {
            add(ReferenceViolation.FeatureUnsupported(ReferenceRole.VIBE))
        }
        if (referencesOf(ReferenceRole.DIRECTOR).isNotEmpty() && !profile.supportsDirectorReference) {
            add(ReferenceViolation.FeatureUnsupported(ReferenceRole.DIRECTOR))
        }

        val vibes = referencesOf(ReferenceRole.VIBE).size
        if (vibes > profile.maxVibeReferences) {
            add(ReferenceViolation.TooMany(ReferenceRole.VIBE, vibes, profile.maxVibeReferences))
        }
        val directors = referencesOf(ReferenceRole.DIRECTOR).size
        if (directors > profile.maxDirectorReferences) {
            add(
                ReferenceViolation.TooMany(
                    ReferenceRole.DIRECTOR,
                    directors,
                    profile.maxDirectorReferences,
                ),
            )
        }
        if (referencesOf(ReferenceRole.IMG2IMG).size > 1) {
            add(ReferenceViolation.TooMany(ReferenceRole.IMG2IMG, 1, 1))
        }

        // 文件可能被系统清理或用户手工删除。带着一个不存在的路径提交，
        // 结果会是一张空白图或一句笼统的参数错误，都不如当场说清楚。
        references.filterNot(referenceExists).forEach {
            add(ReferenceViolation.FileMissing(it.id))
        }
    }
}

/** 参考图相关的阻塞问题，用于在界面上标出具体是哪一条不合法。 */
sealed interface ReferenceViolation {

    /** 选了图生图模式却没有起点图。 */
    data object MissingImg2ImgSource : ReferenceViolation

    data class ModeUnsupported(val mode: GenerationMode) : ReferenceViolation

    /** 该功能在当前模型上不可用（例如 V5 上的 Vibe Transfer / Precise Reference）。 */
    data class FeatureUnsupported(val role: ReferenceRole) : ReferenceViolation

    /** 局部重绘缺少蒙版（或蒙版为空）。 */
    data object MissingInpaintMask : ReferenceViolation

    /**
     * 与该模式冲突的参考图。
     *
     * 服务端对互斥关系有硬约束（实测：`cannot mix reference and director_reference`），
     * 因此在本地就把明显冲突的组合拦下，而不是发出去等一句笼统的参数错误。
     */
    data class ConflictingWithMode(val mode: GenerationMode) : ReferenceViolation

    data class TooMany(
        val role: ReferenceRole,
        val count: Int,
        val limit: Int,
    ) : ReferenceViolation

    data class FileMissing(val referenceId: String) : ReferenceViolation
}
