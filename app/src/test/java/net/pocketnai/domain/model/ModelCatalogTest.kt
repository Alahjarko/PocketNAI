package net.pocketnai.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 模型档案的合法性与归一化。
 *
 * 这些断言的作用不是“证明默认值正确”（那要靠协议探针核对官方网页版），
 * 而是保证**无论默认值怎么改，应用都不会向外发出已知无效的组合或越界参数**。
 */
class ModelCatalogTest {

    @Test
    fun `四个首版模型都已登记且顺序稳定`() {
        assertThat(ModelCatalog.models).containsExactly(
            ImageModel.V4_5_CURATED,
            ImageModel.V4_5_FULL,
            ImageModel.V5_CURATED,
            ImageModel.V5_FULL,
        ).inOrder()
    }

    @Test
    fun `模型 id 与规划书一致`() {
        assertThat(ModelCatalog.models.map { it.apiModelId }).containsExactly(
            "nai-diffusion-4-5-curated",
            "nai-diffusion-4-5-full",
            "nai-diffusion-5-curated",
            "nai-diffusion-5-full",
        ).inOrder()
    }

    @Test
    fun `每个模型的默认采样器与调度组合都是合法的`() {
        ModelCatalog.models.forEach { model ->
            val profile = ModelCatalog.profileOf(model)
            assertThat(profile.isCombinationSupported(profile.defaultSampler, profile.defaultNoiseSchedule))
                .isTrue()
            assertThat(profile.sizeOptions.map { it.size }).contains(profile.defaultSize)
        }
    }

    @Test
    fun `每个模型默认参数自身通过校验`() {
        ModelCatalog.models.forEach { model ->
            val profile = ModelCatalog.profileOf(model)
            val params = GenerationParams.defaultsFor(profile)
            // 默认提示词为空，因此不应出现任何阻断性违规。
            assertThat(profile.validate(params).filterNot { it is ParamViolation.PromptTooLong })
                .isEmpty()
        }
    }

    @Test
    fun `DDIM 只暴露 native 调度`() {
        val profile = ModelCatalog.defaultProfile()
        assertThat(profile.availableSchedulesFor(Sampler.DDIM))
            .containsExactly(NoiseSchedule.NATIVE)
    }

    @Test
    fun `DPM++ 2S Ancestral 不暴露 karras`() {
        val profile = ModelCatalog.defaultProfile()
        assertThat(profile.availableSchedulesFor(Sampler.DPM_PLUS_PLUS_2S_ANCESTRAL))
            .doesNotContain(NoiseSchedule.KARRAS)
    }

    @Test
    fun `归一化会修掉不支持的采样器调度组合`() {
        val profile = ModelCatalog.defaultProfile()
        val invalid = GenerationParams.defaultsFor(profile).copy(
            sampler = Sampler.DDIM,
            noiseSchedule = NoiseSchedule.KARRAS,
        )
        val normalized = profile.normalize(invalid)

        assertThat(profile.isCombinationSupported(normalized.sampler, normalized.noiseSchedule)).isTrue()
        assertThat(normalized.sampler).isEqualTo(Sampler.DDIM)
        assertThat(normalized.noiseSchedule).isEqualTo(NoiseSchedule.NATIVE)
    }

    @Test
    fun `归一化会夹取越界数值`() {
        val profile = ModelCatalog.defaultProfile()
        val wild = GenerationParams.defaultsFor(profile).copy(
            steps = 999,
            guidance = 99.0,
            cfgRescale = -3.0,
            sampleCount = 99,
        )
        val normalized = profile.normalize(wild)

        assertThat(normalized.steps).isEqualTo(profile.stepsRange.max.toInt())
        assertThat(normalized.guidance).isEqualTo(profile.guidanceRange.max)
        assertThat(normalized.cfgRescale).isEqualTo(profile.cfgRescaleRange.min)
        assertThat(normalized.sampleCount).isEqualTo(profile.maxSampleCount)
    }

    @Test
    fun `归一化会把非法尺寸换回默认尺寸`() {
        val profile = ModelCatalog.defaultProfile()
        val badSize = GenerationParams.defaultsFor(profile).copy(
            size = ImageSizePreset(width = 100, height = 100),
        )
        assertThat(profile.normalize(badSize).size).isEqualTo(profile.defaultSize)
    }

    @Test
    fun `尺寸必须是 64 的倍数且在总像素上限内`() {
        val constraints = ModelCatalog.defaultProfile().sizeConstraints
        assertThat(constraints.isValid(832, 1216)).isTrue()
        assertThat(constraints.isValid(833, 1216)).isFalse()
        assertThat(constraints.isValid(100, 100)).isFalse()
        assertThat(constraints.isValid(1536, 1536)).isTrue()
    }

    @Test
    fun `校验能报出越界与尺寸问题`() {
        val profile = ModelCatalog.defaultProfile()
        val bad = GenerationParams.defaultsFor(profile).copy(
            steps = 999,
            size = ImageSizePreset(width = 100, height = 100),
        )
        val violations = profile.validate(bad)

        assertThat(violations.any { it is ParamViolation.OutOfRange && it.field == "steps" }).isTrue()
        assertThat(violations.any { it is ParamViolation.InvalidSize }).isTrue()
    }

    @Test
    fun `提示词超长只作为提示不算阻断`() {
        val profile = ModelCatalog.profileOf(ImageModel.V4_5_CURATED)
        val long = GenerationParams.defaultsFor(profile).copy(
            prompt = "a".repeat(profile.promptSoftLimitChars + 1),
        )
        val violations = profile.validate(long)

        assertThat(violations.any { it is ParamViolation.PromptTooLong }).isTrue()
        assertThat(violations.filterNot { it is ParamViolation.PromptTooLong }).isEmpty()
    }

    @Test
    fun `只有 V5 声明支持多语言提示词`() {
        assertThat(ModelCatalog.profileOf(ImageModel.V5_CURATED).supportsMultilingualPrompt).isTrue()
        assertThat(ModelCatalog.profileOf(ImageModel.V5_FULL).supportsMultilingualPrompt).isTrue()
        assertThat(ModelCatalog.profileOf(ImageModel.V4_5_CURATED).supportsMultilingualPrompt).isFalse()
        assertThat(ModelCatalog.profileOf(ImageModel.V4_5_FULL).supportsMultilingualPrompt).isFalse()
    }

    @Test
    fun `切换到新模型时保留仍然合法的参数`() {
        val source = ModelCatalog.profileOf(ImageModel.V4_5_CURATED)
        val target = ModelCatalog.profileOf(ImageModel.V4_5_FULL)
        val params = GenerationParams.defaultsFor(source).copy(steps = 40, guidance = 7.0)

        val migrated = GenerationParams.migrateTo(target, params)

        assertThat(migrated.model).isEqualTo(ImageModel.V4_5_FULL)
        assertThat(migrated.steps).isEqualTo(40)
        assertThat(migrated.guidance).isWithin(1e-9).of(7.0)
    }

    // ---- 依据官方网页版固化的默认值 ----

    @Test
    fun `默认 Steps 与 Prompt Guidance 与官方一致`() {
        ModelCatalog.models.forEach { model ->
            val profile = ModelCatalog.profileOf(model)
            assertThat(profile.defaultSteps).isEqualTo(23)
            assertThat(profile.defaultGuidance).isWithin(1e-9).of(7.0)
        }
    }

    @Test
    fun `默认质量标签为 Standard`() {
        ModelCatalog.models.forEach { model ->
            assertThat(ModelCatalog.profileOf(model).defaultQualityTags)
                .isEqualTo(QualityTagsOption.STANDARD)
        }
    }

    // ---- Resolution 的档位 × 方向结构 ----

    @Test
    fun `Normal 档位的三个方向与官方显示一致`() {
        val profile = ModelCatalog.profileOf(ImageModel.V4_5_CURATED)

        assertThat(profile.sizeFor(ResolutionTier.NORMAL, ImageOrientation.LANDSCAPE))
            .isEqualTo(ImageSizePreset(width = 1216, height = 832))
        assertThat(profile.sizeFor(ResolutionTier.NORMAL, ImageOrientation.PORTRAIT))
            .isEqualTo(ImageSizePreset(width = 832, height = 1216))
        assertThat(profile.sizeFor(ResolutionTier.NORMAL, ImageOrientation.SQUARE))
            .isEqualTo(ImageSizePreset(width = 1024, height = 1024))
    }

    @Test
    fun `每个档位都提供三个方向`() {
        val profile = ModelCatalog.defaultProfile()
        profile.availableTiers().forEach { tier ->
            assertThat(profile.availableOrientations(tier)).containsExactly(
                ImageOrientation.LANDSCAPE,
                ImageOrientation.PORTRAIT,
                ImageOrientation.SQUARE,
            )
        }
    }

    @Test
    fun `切换档位时保持当前方向`() {
        val profile = ModelCatalog.defaultProfile()
        val landscape = profile.sizeFor(ResolutionTier.NORMAL, ImageOrientation.LANDSCAPE)!!

        val larger = profile.sizeInTier(ResolutionTier.LARGE, landscape)!!

        assertThat(larger.orientation).isEqualTo(ImageOrientation.LANDSCAPE)
        assertThat(larger.width).isGreaterThan(landscape.width)
    }

    @Test
    fun `能从尺寸反查档位与方向`() {
        val profile = ModelCatalog.defaultProfile()
        val square = profile.sizeFor(ResolutionTier.NORMAL, ImageOrientation.SQUARE)!!

        assertThat(profile.tierOf(square)).isEqualTo(ResolutionTier.NORMAL)
        assertThat(square.orientation).isEqualTo(ImageOrientation.SQUARE)
        assertThat(profile.sizeInTier(ResolutionTier.LARGE, square)!!.orientation)
            .isEqualTo(ImageOrientation.SQUARE)
    }

    @Test
    fun `历史记录里不属于任何预设的尺寸不会反查出档位`() {
        val profile = ModelCatalog.defaultProfile()
        // 旧版本保存过的自定义尺寸；界面此时应回退到默认档位而不是崩溃。
        assertThat(profile.tierOf(ImageSizePreset(width = 512, height = 512))).isNull()
    }

    @Test
    fun `所有预设尺寸都满足模型的尺寸约束`() {
        ModelCatalog.models.forEach { model ->
            val profile = ModelCatalog.profileOf(model)
            profile.sizeOptions.forEach { option ->
                assertThat(
                    profile.sizeConstraints.isValid(option.size.width, option.size.height),
                ).isTrue()
            }
        }
    }
}
