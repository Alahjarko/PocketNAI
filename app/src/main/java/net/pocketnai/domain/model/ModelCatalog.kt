package net.pocketnai.domain.model

/**
 * 四个模型的配置档案。
 *
 * ## 关于数值来源
 *
 * 规划书 3.2 要求默认参数必须从 NovelAI 当前网页版记录后再固化，不得凭经验猜。
 * 这里已经落实了用户从官方网页版核对的几项：
 *
 * - `defaultSteps = 23`、`defaultGuidance = 7.0`（官方默认值）；
 * - Resolution 采用官方的“档位 × 横竖方”二维结构，`Normal` 档位的实际像素
 *   （横 1216×832 / 竖 832×1216 / 方 1024×1024）与官方显示一致；
 * - 质量标签是追加到提示词末尾的文本，见 [QualityTagsOption]。
 *
 * **仍未核对**的部分集中在一处，便于后续修正：
 * - `Large` 档位的具体像素组合；
 * - Sampler × Noise Schedule 组合表；
 * - V5 的默认尺寸档位与方向；
 * - Prompt Guidance / CFG Rescale 的合法区间。
 *
 * 上述项都带上 [CONFIG_VERSION]，出现差异时整体升版本。
 * [ModelProfile.normalize] 与 [ModelProfile.validate] 是参数合法性的唯一出口，
 * 修正数值不需要改动网络层与界面层。
 */
object ModelCatalog {

    /** 待与官方网页版继续核对的配置版本号。 */
    const val CONFIG_VERSION: String = "2026-09-14-qtag-resolution"

    /** NovelAI 生成接口的 `params_version`，V4.5 / V5 使用 3。 */
    private const val PARAMS_VERSION_V4_V5 = 3

    /** 尺寸边长必须是 64 的倍数。 */
    private const val DIMENSION_STEP = 64

    /** 官方默认的 Steps 与 Prompt Guidance（用户核对）。 */
    private const val DEFAULT_STEPS = 23
    private const val DEFAULT_GUIDANCE = 7.0

    /** DPM++ 2S Ancestral 不支持 Karras 调度。 */
    private val NO_KARRAS = linkedSetOf(
        NoiseSchedule.NATIVE,
        NoiseSchedule.EXPONENTIAL,
        NoiseSchedule.POLYEXPONENTIAL,
    )

    /** 支持全部四种调度的采样器。 */
    private val ALL_SCHEDULES = linkedSetOf(
        NoiseSchedule.NATIVE,
        NoiseSchedule.KARRAS,
        NoiseSchedule.EXPONENTIAL,
        NoiseSchedule.POLYEXPONENTIAL,
    )

    /** DDIM 只支持 Native。 */
    private val NATIVE_ONLY = linkedSetOf(NoiseSchedule.NATIVE)

    private val SAMPLER_SCHEDULES: Map<Sampler, Set<NoiseSchedule>> = linkedMapOf(
        Sampler.EULER to ALL_SCHEDULES,
        Sampler.EULER_ANCESTRAL to ALL_SCHEDULES,
        Sampler.DPM_PLUS_PLUS_2S_ANCESTRAL to NO_KARRAS,
        Sampler.DPM_PLUS_PLUS_2M to ALL_SCHEDULES,
        Sampler.DPM_PLUS_PLUS_SDE to ALL_SCHEDULES,
        Sampler.DPM_PLUS_PLUS_2M_SDE to ALL_SCHEDULES,
        Sampler.DDIM to NATIVE_ONLY,
    )

    /** ucPreset 的固定含义，四个模型一致。 */
    private val UNDESIRED_CONTENT_PRESETS: List<UndesiredContentPreset> = listOf(
        UndesiredContentPreset(index = 0, displayName = "Heavy"),
        UndesiredContentPreset(index = 1, displayName = "Light"),
        UndesiredContentPreset(index = 2, displayName = "Human Focus"),
        UndesiredContentPreset(index = 3, displayName = "None"),
    )

    private const val DEFAULT_UC_PRESET_INDEX = 0

    /**
     * 官方 Resolution 的二维矩阵。
     *
     * `Normal` 档位的数值来自官方网页版截图（横 1216×832、竖 832×1216、方 1024×1024）。
     * `Large` 档位沿用同一组比例放大，**待与官方核对**。
     */
    private val STANDARD_SIZE_OPTIONS: List<ImageSizeOption> = listOf(
        // Normal
        ImageSizeOption(ResolutionTier.NORMAL, ImageSizePreset(width = 832, height = 1216)),
        ImageSizeOption(ResolutionTier.NORMAL, ImageSizePreset(width = 1216, height = 832)),
        ImageSizeOption(ResolutionTier.NORMAL, ImageSizePreset(width = 1024, height = 1024)),
        // Large
        ImageSizeOption(ResolutionTier.LARGE, ImageSizePreset(width = 1024, height = 1536)),
        ImageSizeOption(ResolutionTier.LARGE, ImageSizePreset(width = 1536, height = 1024)),
        ImageSizeOption(ResolutionTier.LARGE, ImageSizePreset(width = 1536, height = 1536)),
    )

    private val NORMAL_PORTRAIT = ImageSizePreset(width = 832, height = 1216)
    private val NORMAL_SQUARE = ImageSizePreset(width = 1024, height = 1024)

    private fun profileFor(
        model: ImageModel,
        defaultSize: ImageSizePreset,
        supportsMultilingualPrompt: Boolean,
        promptSoftLimitChars: Int,
    ): ModelProfile = ModelProfile(
        model = model,
        defaultSize = defaultSize,
        sizeOptions = STANDARD_SIZE_OPTIONS,
        sizeConstraints = SizeConstraints(
            minDimension = 512,
            maxDimension = 1536,
            dimensionStep = DIMENSION_STEP,
            maxTotalPixels = 1536 * 1536,
        ),
        defaultSampleCount = 1,
        maxSampleCount = 4,
        stepsRange = NumericRange(min = 1.0, max = 50.0, step = 1.0),
        defaultSteps = DEFAULT_STEPS,
        guidanceRange = NumericRange(min = 0.0, max = 10.0, step = 0.1),
        defaultGuidance = DEFAULT_GUIDANCE,
        cfgRescaleRange = NumericRange(min = 0.0, max = 1.0, step = 0.05),
        defaultCfgRescale = 0.0,
        defaultSampler = Sampler.EULER_ANCESTRAL,
        defaultNoiseSchedule = NoiseSchedule.KARRAS,
        samplerSchedules = SAMPLER_SCHEDULES,
        defaultQualityTags = QualityTagsOption.DEFAULT,
        undesiredContentPresets = UNDESIRED_CONTENT_PRESETS,
        defaultUndesiredContentPresetIndex = DEFAULT_UC_PRESET_INDEX,
        promptSoftLimitChars = promptSoftLimitChars,
        supportsMultilingualPrompt = supportsMultilingualPrompt,
        paramsVersion = PARAMS_VERSION_V4_V5,
        configVersion = CONFIG_VERSION,
    )

    private val PROFILES: Map<ImageModel, ModelProfile> = mapOf(
        ImageModel.V4_5_CURATED to profileFor(
            model = ImageModel.V4_5_CURATED,
            defaultSize = NORMAL_PORTRAIT,
            // V4.5 对多语言提示词的理解弱于 V5，界面只做非阻断提示（规划书 3.4）。
            supportsMultilingualPrompt = false,
            promptSoftLimitChars = 1000,
        ),
        ImageModel.V4_5_FULL to profileFor(
            model = ImageModel.V4_5_FULL,
            defaultSize = NORMAL_PORTRAIT,
            supportsMultilingualPrompt = false,
            promptSoftLimitChars = 1000,
        ),
        ImageModel.V5_CURATED to profileFor(
            model = ImageModel.V5_CURATED,
            defaultSize = NORMAL_SQUARE,
            supportsMultilingualPrompt = true,
            promptSoftLimitChars = 2000,
        ),
        ImageModel.V5_FULL to profileFor(
            model = ImageModel.V5_FULL,
            defaultSize = NORMAL_SQUARE,
            supportsMultilingualPrompt = true,
            promptSoftLimitChars = 2000,
        ),
    )

    /** 界面下拉里展示的模型顺序，与规划书 2.1 的列举顺序一致。 */
    val models: List<ImageModel> = listOf(
        ImageModel.V4_5_CURATED,
        ImageModel.V4_5_FULL,
        ImageModel.V5_CURATED,
        ImageModel.V5_FULL,
    )

    fun profileOf(model: ImageModel): ModelProfile =
        PROFILES.getValue(model)

    fun profileOfApiModelId(apiModelId: String): ModelProfile? =
        ImageModel.fromApiModelId(apiModelId)?.let(::profileOf)

    fun defaultProfile(): ModelProfile = profileOf(ImageModel.DEFAULT)
}
