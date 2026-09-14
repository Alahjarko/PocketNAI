package net.pocketnai.domain.model

/** 数值参数的合法区间与步长。 */
data class NumericRange(val min: Double, val max: Double, val step: Double) {
    fun contains(value: Double): Boolean = value in min..max
    fun clamp(value: Double): Double = value.coerceIn(min, max)
}

/** 尺寸合法性约束。NovelAI 的边长必须是 64 的倍数，且总像素有上限。 */
data class SizeConstraints(
    val minDimension: Int,
    val maxDimension: Int,
    val dimensionStep: Int,
    val maxTotalPixels: Int,
) {
    fun isValid(width: Int, height: Int): Boolean =
        width in minDimension..maxDimension &&
            height in minDimension..maxDimension &&
            width % dimensionStep == 0 &&
            height % dimensionStep == 0 &&
            width.toLong() * height.toLong() <= maxTotalPixels
}

/** Undesired Content 预设，对应 API 的 `ucPreset` 整数。 */
data class UndesiredContentPreset(val index: Int, val displayName: String)

/**
 * 参数校验问题，用于在界面上标出具体是哪一项不合法（规划书 9.1）。
 */
sealed interface ParamViolation {
    data class UnsupportedCombination(
        val sampler: Sampler,
        val noiseSchedule: NoiseSchedule,
    ) : ParamViolation

    data class OutOfRange(
        val field: String,
        val min: Double,
        val max: Double,
        val actual: Double,
    ) : ParamViolation

    data class InvalidSize(val width: Int, val height: Int) : ParamViolation

    data class PromptTooLong(val length: Int, val limit: Int) : ParamViolation
}

/**
 * 单个模型的完整配置档案（规划书 3.1）。
 *
 * 界面只允许展示 [samplerSchedules] 中真实存在的组合，避免用户构造已知无效请求。
 * 具体数值由 [ModelCatalog] 提供，并带 [configVersion] 版本号。
 */
data class ModelProfile(
    val model: ImageModel,
    val defaultSize: ImageSizePreset,
    /**
     * 官方 Resolution 选择器暴露的全部组合（档位 × 横竖方）。
     * 界面按 [ResolutionTier] 与 [ImageOrientation] 两个维度渲染，而不是平铺一个像素列表。
     */
    val sizeOptions: List<ImageSizeOption>,
    val sizeConstraints: SizeConstraints,
    val defaultSampleCount: Int,
    val maxSampleCount: Int,
    val stepsRange: NumericRange,
    val defaultSteps: Int,
    val guidanceRange: NumericRange,
    val defaultGuidance: Double,
    val cfgRescaleRange: NumericRange,
    val defaultCfgRescale: Double,
    val defaultSampler: Sampler,
    val defaultNoiseSchedule: NoiseSchedule,
    val samplerSchedules: Map<Sampler, Set<NoiseSchedule>>,
    val defaultQualityTags: QualityTagsOption,
    val undesiredContentPresets: List<UndesiredContentPreset>,
    val defaultUndesiredContentPresetIndex: Int,
    /** 提示词长度提示的软上限（字符数），不阻断提交，只在界面提示。 */
    val promptSoftLimitChars: Int,
    val supportsMultilingualPrompt: Boolean,
    /** 请求体 `params_version`。 */
    val paramsVersion: Int,
    /**
     * 是否支持整图图生图。四个模型目前都支持；保留成字段而不是写死 `true`，
     * 是为了将来出现不支持的模型时不必回头改调用点。
     */
    val supportsImg2Img: Boolean,
    /** Vibe Transfer 的参考图张数上限。数值集中在 [ModelCatalog]，见那里的待核对说明。 */
    val maxVibeReferences: Int,
    /** Precise Reference 的参考图张数上限。数值集中在 [ModelCatalog]，见那里的待核对说明。 */
    val maxDirectorReferences: Int,
    val configVersion: String,
) {
    val displayName: String get() = model.displayName

    /** Resolution 档位下拉的选项，按 [ResolutionTier] 声明顺序。 */
    fun availableTiers(): List<ResolutionTier> =
        sizeOptions.map { it.tier }.distinct().sortedBy { it.ordinal }

    /** 某个档位下可用的方向，决定三个方向按钮是否可点。 */
    fun availableOrientations(tier: ResolutionTier): List<ImageOrientation> =
        sizeOptions.filter { it.tier == tier }.map { it.orientation }

    fun sizeFor(tier: ResolutionTier, orientation: ImageOrientation): ImageSizePreset? =
        sizeOptions.firstOrNull { it.tier == tier && it.orientation == orientation }?.size

    /** 反查当前尺寸属于哪个档位；不匹配任何预设时返回 null（例如来自旧版本的历史参数）。 */
    fun tierOf(size: ImageSizePreset): ResolutionTier? =
        sizeOptions.firstOrNull { it.size == size }?.tier

    /**
     * 找到与给定尺寸同方向、但属于指定档位的尺寸。
     * 切换档位时用它保持方向不变 —— 这正是官方 Resolution 选择器的行为。
     */
    fun sizeInTier(tier: ResolutionTier, like: ImageSizePreset): ImageSizePreset? =
        sizeFor(tier, like.orientation) ?: sizeOptions.firstOrNull { it.tier == tier }?.size

    fun availableSamplers(): List<Sampler> = samplerSchedules.keys.toList()

    fun availableSchedulesFor(sampler: Sampler): Set<NoiseSchedule> =
        samplerSchedules[sampler].orEmpty()

    fun defaultScheduleFor(sampler: Sampler): NoiseSchedule? {
        val schedules = availableSchedulesFor(sampler)
        return when {
            schedules.isEmpty() -> null
            defaultNoiseSchedule in schedules -> defaultNoiseSchedule
            else -> schedules.first()
        }
    }

    fun isCombinationSupported(sampler: Sampler, noiseSchedule: NoiseSchedule): Boolean =
        noiseSchedule in availableSchedulesFor(sampler)

    /**
     * 把参数修正到该模型可用的取值：
     * - 采样器/调度组合不受支持时换回默认组合；
     * - 数值越界时夹取到合法区间；
     * - 尺寸不合法时换回默认尺寸。
     */
    fun normalize(params: GenerationParams): GenerationParams {
        val sampler = if (params.sampler in samplerSchedules) params.sampler else defaultSampler
        val schedules = availableSchedulesFor(sampler)
        val schedule = when {
            params.noiseSchedule in schedules -> params.noiseSchedule
            defaultNoiseSchedule in schedules -> defaultNoiseSchedule
            else -> schedules.firstOrNull() ?: defaultNoiseSchedule
        }
        return params.copy(
            model = model,
            steps = stepsRange.clamp(params.steps.toDouble()).toInt(),
            guidance = guidanceRange.clamp(params.guidance),
            cfgRescale = cfgRescaleRange.clamp(params.cfgRescale),
            sampleCount = params.sampleCount.coerceIn(1, maxSampleCount),
            sampler = sampler,
            noiseSchedule = schedule,
            size = if (sizeConstraints.isValid(params.size.width, params.size.height)) {
                params.size
            } else {
                defaultSize
            },
            baseSeed = params.baseSeed.coerceIn(0L, GenerationParams.MAX_SEED),
        )
    }

    /** 返回所有不合法项，空列表表示可以安全提交。 */
    fun validate(params: GenerationParams): List<ParamViolation> = buildList {
        if (!isCombinationSupported(params.sampler, params.noiseSchedule)) {
            add(ParamViolation.UnsupportedCombination(params.sampler, params.noiseSchedule))
        }
        if (!stepsRange.contains(params.steps.toDouble())) {
            add(
                ParamViolation.OutOfRange(
                    field = "steps",
                    min = stepsRange.min,
                    max = stepsRange.max,
                    actual = params.steps.toDouble(),
                ),
            )
        }
        if (!guidanceRange.contains(params.guidance)) {
            add(
                ParamViolation.OutOfRange(
                    field = "guidance",
                    min = guidanceRange.min,
                    max = guidanceRange.max,
                    actual = params.guidance,
                ),
            )
        }
        if (!cfgRescaleRange.contains(params.cfgRescale)) {
            add(
                ParamViolation.OutOfRange(
                    field = "cfgRescale",
                    min = cfgRescaleRange.min,
                    max = cfgRescaleRange.max,
                    actual = params.cfgRescale,
                ),
            )
        }
        if (params.sampleCount !in 1..maxSampleCount) {
            add(
                ParamViolation.OutOfRange(
                    field = "sampleCount",
                    min = 1.0,
                    max = maxSampleCount.toDouble(),
                    actual = params.sampleCount.toDouble(),
                ),
            )
        }
        if (!sizeConstraints.isValid(params.size.width, params.size.height)) {
            add(ParamViolation.InvalidSize(params.size.width, params.size.height))
        }
        if (params.prompt.length > promptSoftLimitChars) {
            add(
                ParamViolation.PromptTooLong(
                    length = params.prompt.length,
                    limit = promptSoftLimitChars,
                ),
            )
        }
    }
}
