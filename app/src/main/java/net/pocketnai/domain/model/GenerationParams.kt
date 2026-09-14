package net.pocketnai.domain.model

/** 随机方向。首版只区分随机与固定 Seed（规划书 2.1）。 */
enum class SeedMode { RANDOM, FIXED }

enum class ImageOrientation { PORTRAIT, LANDSCAPE, SQUARE }

/** 一个可选的输出尺寸。宽高都必须是 [ModelProfile.sizeConstraints] 允许的步长倍数。 */
data class ImageSizePreset(val width: Int, val height: Int) {

    val label: String get() = "$width × $height"

    val totalPixels: Int get() = width * height

    val orientation: ImageOrientation
        get() = when {
            width > height -> ImageOrientation.LANDSCAPE
            width < height -> ImageOrientation.PORTRAIT
            else -> ImageOrientation.SQUARE
        }
}

/**
 * 尺寸档位，对应官方网页版 Resolution 左侧的 `Normal` 下拉。
 *
 * 官方把尺寸拆成“档位 × 横竖方”两维，而不是一个长长的像素列表：
 * 用户选档位决定画面大小，再用三个图标选方向，右上角显示实际像素值。
 */
enum class ResolutionTier(val displayName: String) {
    NORMAL("Normal"),
    LARGE("Large"),
}

/** 档位 × 方向的组合，等价于官方 Resolution 里的一个可选状态。 */
data class ImageSizeOption(
    val tier: ResolutionTier,
    val size: ImageSizePreset,
) {
    val orientation: ImageOrientation get() = size.orientation
}

/**
 * 一次生成的完整参数快照。
 *
 * [prompt] 保存的是**本次实际提交**的提示词。如果用户输入了 Randomizer 模板，
 * 模板原文保存在 `Generation.promptTemplate`，两者分开存储（规划书 8.3）。
 *
 * 该类型是请求构造的唯一输入，因此可以在纯 JVM 单元测试里直接断言产出的请求体。
 */
data class GenerationParams(
    val model: ImageModel,
    val prompt: String,
    val negativePrompt: String,
    val size: ImageSizePreset,
    val sampleCount: Int,
    val steps: Int,
    val guidance: Double,
    val cfgRescale: Double,
    val sampler: Sampler,
    val noiseSchedule: NoiseSchedule,
    val seedMode: SeedMode,
    val baseSeed: Long,
    val qualityTags: QualityTagsOption,
    val undesiredContentPresetIndex: Int,
) {
    companion object {
        /** NovelAI 的 seed 是 uint32。 */
        const val MAX_SEED: Long = 0xFFFFFFFFL

        /** 按模型档案生成一份“新建任务”参数（规划书 3.2：默认值只影响新建任务）。 */
        fun defaultsFor(profile: ModelProfile): GenerationParams = GenerationParams(
            model = profile.model,
            prompt = "",
            negativePrompt = "",
            size = profile.defaultSize,
            sampleCount = profile.defaultSampleCount,
            steps = profile.defaultSteps,
            guidance = profile.defaultGuidance,
            cfgRescale = profile.defaultCfgRescale,
            sampler = profile.defaultSampler,
            noiseSchedule = profile.defaultNoiseSchedule,
            seedMode = SeedMode.RANDOM,
            baseSeed = 0L,
            qualityTags = profile.defaultQualityTags,
            undesiredContentPresetIndex = profile.defaultUndesiredContentPresetIndex,
        )

        /**
         * 把参数迁移到另一个模型：保留用户可见的输入，但把目标模型不支持的
         * 采样器 / 调度组合换回目标模型默认值（规划书 8.4：不静默替换后立即生成，
         * 这里只做迁移，由界面提示用户确认）。
         */
        fun migrateTo(target: ModelProfile, from: GenerationParams): GenerationParams {
            val supported = target.isCombinationSupported(from.sampler, from.noiseSchedule)
            return from.copy(
                model = target.model,
                sampler = if (supported) from.sampler else target.defaultSampler,
                noiseSchedule = if (supported) from.noiseSchedule else target.defaultNoiseSchedule,
                size = if (from.size.width <= target.sizeConstraints.maxDimension &&
                    from.size.height <= target.sizeConstraints.maxDimension &&
                    from.size.totalPixels <= target.sizeConstraints.maxTotalPixels
                ) {
                    from.size
                } else {
                    target.defaultSize
                },
            )
        }
    }
}
