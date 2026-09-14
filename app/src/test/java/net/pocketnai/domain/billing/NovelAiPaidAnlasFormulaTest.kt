package net.pocketnai.domain.billing

import com.google.common.truth.Truth.assertThat
import net.pocketnai.domain.model.GenerationFamily
import net.pocketnai.domain.model.GenerationParams
import net.pocketnai.domain.model.ImageModel
import net.pocketnai.domain.model.ImageSizePreset
import net.pocketnai.domain.model.ModelCatalog
import org.junit.Test

/**
 * 官方计价公式（2026-09-14 从官方网页前端 bundle 反解）。
 *
 * ## 这些数字是怎么来的
 * 式子原样抄自官方前端的计价函数：
 * ```text
 * raw = ceil(2.951823174884865e-6 × area + 5.753298233447344e-7 × area × steps)
 * raw *= smea 倍率            // 1.0 / 1.2 / 1.4
 * if (V5) raw *= 1.5
 * perImage = max(ceil(raw × strength), 2)
 * ```
 * 下面的样本值是**独立算一遍**得到的（不是把实现跑一遍再抄结果），
 * 其中 1024×1024 @28 → 20 与 832×1216 @23 → 17 两条可以人工复核，
 * 后者也正好解释了社区长期流传的"默认一张约 17 Anlas"。
 */
class NovelAiPaidAnlasFormulaTest {

    private val formula = NovelAiPaidAnlasFormula()

    private fun context(
        model: ImageModel,
        size: ImageSizePreset,
        steps: Int,
        strength: Double = 1.0,
        smea: Double = 1.0,
        samples: Int = 1,
    ): AnlasPricingContext {
        val profile = ModelCatalog.profileOf(model)
        return AnlasPricingContext(
            params = GenerationParams.defaultsFor(profile).copy(
                model = model,
                size = size,
                steps = steps,
                sampleCount = samples,
            ),
            subscriptionTier = SubscriptionTier.Opus,
            hasSubscription = true,
            v5UsageLimit = null,
            strengthMultiplier = strength,
            smeaMultiplier = smea,
            pricingPolicyVersion = formula.version,
        )
    }

    private fun perImage(
        model: ImageModel,
        width: Int,
        height: Int,
        steps: Int,
        strength: Double = 1.0,
        smea: Double = 1.0,
    ): Long = formula.perImageCost(
        context(model, ImageSizePreset(width, height), steps, strength, smea),
    )!!

    @Test
    fun `V4_5 单张价格与官方样本一致`() {
        val v45 = ImageModel.V4_5_CURATED

        assertThat(perImage(v45, 1024, 1024, 28)).isEqualTo(20L)
        assertThat(perImage(v45, 832, 1216, 23)).isEqualTo(17L)
        assertThat(perImage(v45, 1216, 832, 23)).isEqualTo(17L)
        assertThat(perImage(v45, 1024, 1536, 28)).isEqualTo(30L)
        assertThat(perImage(v45, 1536, 1024, 23)).isEqualTo(26L)
        assertThat(perImage(v45, 1536, 1536, 23)).isEqualTo(39L)
        assertThat(perImage(v45, 832, 1216, 29)).isEqualTo(20L)
        assertThat(perImage(v45, 832, 1216, 40)).isEqualTo(27L)
    }

    @Test
    fun `V5 单价是 V4_5 的 1_5 倍`() {
        val v45 = ImageModel.V4_5_CURATED
        val v5 = ImageModel.V5_CURATED

        // 1011712 面积 @23 步：V4.5 = 17，V5 = ceil(17 × 1.5) = 26。
        assertThat(perImage(v5, 832, 1216, 23)).isEqualTo(26L)
        assertThat(perImage(v45, 832, 1216, 23)).isEqualTo(17L)
        // 1048576 @28：20 → 30。
        assertThat(perImage(v5, 1024, 1024, 28)).isEqualTo(30L)
        // 1572864 @28：30 → 45。
        assertThat(perImage(v5, 1024, 1536, 28)).isEqualTo(45L)
    }

    @Test
    fun `代号档位不影响价格`() {
        // Curated 与 Full 同价：官方前端只用家族（v4/v5）分支，不看档位。
        assertThat(perImage(ImageModel.V4_5_FULL, 832, 1216, 23)).isEqualTo(17L)
        assertThat(perImage(ImageModel.V5_FULL, 832, 1216, 23)).isEqualTo(26L)
    }

    @Test
    fun `Strength 是基础费用的乘数且下限为 2`() {
        // 官方前端：max(ceil(raw × strength), 2)，图生图 Strength 0.7 更便宜。
        assertThat(perImage(ImageModel.V4_5_CURATED, 832, 1216, 23, strength = 0.7))
            .isEqualTo(12L)
        // 极小图 × 极低 Strength 也至少 2。
        assertThat(perImage(ImageModel.V4_5_CURATED, 64, 64, 1, strength = 0.01))
            .isEqualTo(2L)
    }

    @Test
    fun `SMEA 倍率按官方三档`() {
        assertThat(perImage(ImageModel.V4_5_CURATED, 1024, 1024, 28, smea = 1.2))
            .isEqualTo(24L)
        assertThat(perImage(ImageModel.V4_5_CURATED, 1024, 1024, 28, smea = 1.4))
            .isEqualTo(28L)
    }

    @Test
    fun `面积低于 65536 时按 65536 计算`() {
        // 官方前端的面积下限；64×64 = 4096 会被抬到 65536。
        assertThat(perImage(ImageModel.V4_5_CURATED, 64, 64, 1))
            .isEqualTo(perImage(ImageModel.V4_5_CURATED, 256, 256, 1))
    }

    @Test
    fun `批量总价等于单张乘张数`() {
        val context = context(
            ImageModel.V4_5_CURATED,
            ImageSizePreset(1536, 1024),
            steps = 23,
            samples = 3,
        )

        assertThat(formula.calculateBatchTotal(context, billableImageCount = 3))
            .isEqualTo(3 * 26L)
        // 免费单张扣掉一张后，只按剩下的算。
        assertThat(formula.calculateBatchTotal(context, billableImageCount = 2))
            .isEqualTo(2 * 26L)
    }

    @Test
    fun `附加费与免费单张无关`() {
        val context = context(
            ImageModel.V4_5_CURATED,
            ImageSizePreset(832, 1216),
            steps = 23,
            samples = 2,
        ).copy(
            referenceImageCount = 3,
            vibeCount = 6,
            uncachedVibeCount = 2,
        )

        // Precise Reference 5×3×2 = 30；Vibe 超 4 张的部分 2×2 = 4；未编码 2×2 = 4。
        assertThat(formula.calculateSurcharge(context)).isEqualTo(30L + 4L + 4L)
    }

    @Test
    fun `超出官方报价范围时不给数字`() {
        // steps > 50 是官方前端的参数不合法边界。
        assertThat(
            formula.perImageCost(
                context(ImageModel.V4_5_CURATED, ImageSizePreset(832, 1216), steps = 60),
            ),
        ).isNull()

        // 单张超过 140 Anlas 的报价上限（官方前端此时不给数字）。
        // 2,048×1,536 恰好是官方参数校验允许的最大面积 3,145,728。
        assertThat(
            formula.perImageCost(
                context(
                    ImageModel.V5_FULL,
                    ImageSizePreset(2048, 1536),
                    steps = 50,
                ),
            ),
        ).isNull()
    }

    @Test
    fun `只声明支持 V4_5 与 V5 两个家族`() {
        val context = context(ImageModel.V4_5_CURATED, ImageSizePreset(832, 1216), steps = 23)

        assertThat(formula.supports(context)).isTrue()
        assertThat(context.params.model.family).isEqualTo(GenerationFamily.V4_5)
    }
}
