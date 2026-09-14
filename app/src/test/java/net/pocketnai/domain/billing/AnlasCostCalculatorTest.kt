package net.pocketnai.domain.billing

import com.google.common.truth.Truth.assertThat
import net.pocketnai.domain.model.GenerationParams
import net.pocketnai.domain.model.ImageModel
import net.pocketnai.domain.model.ImageSizePreset
import net.pocketnai.domain.model.ModelCatalog
import net.pocketnai.domain.model.ModelProfile
import net.pocketnai.domain.model.ResolutionTier
import org.junit.Test

/**
 * 费用计算器（余额规划 §12.4）。
 *
 * 这个类的价值不在"算得准"，而在**分得清四种状态**：
 * 免费、消耗 V5 额度、能算出 Anlas、算不出来。
 * 尤其是最后一种 —— 未经核对的组合必须落进"费用待确认"，不允许给猜测数字。
 */
class AnlasCostCalculatorTest {

    private val policyVersion = "uncalibrated"
    private val calculator = AnlasCostCalculator()

    private val v45 = ModelCatalog.profileOf(ImageModel.V4_5_CURATED)
    private val v45Full = ModelCatalog.profileOf(ImageModel.V4_5_FULL)
    private val v5 = ModelCatalog.profileOf(ImageModel.V5_CURATED)

    private fun context(
        profile: ModelProfile = v45,
        tier: SubscriptionTier = SubscriptionTier.Opus,
        usage: V5UsageLimit? = V5UsageLimit(87, isNegative = false, timeUntilNextPercentSeconds = 3600),
        size: ImageSizePreset = profile.defaultSize,
        resolutionTier: ResolutionTier? = ResolutionTier.NORMAL,
        kind: GenerationKind = GenerationKind.TEXT_TO_IMAGE,
        hasBaseImage: Boolean = false,
        referenceImageCount: Int = 0,
        steps: Int = 23,
        guidance: Double = 7.0,
        sampleCount: Int = 1,
        model: ImageModel? = null,
    ): AnlasPricingContext {
        val base = GenerationParams.defaultsFor(profile)
        return AnlasPricingContext(
            params = base.copy(
                model = model ?: profile.model,
                size = size,
                steps = steps,
                guidance = guidance,
                sampleCount = sampleCount,
            ),
            subscriptionTier = tier,
            v5UsageLimit = usage,
            resolutionTier = resolutionTier,
            generationKind = kind,
            hasBaseImage = hasBaseImage,
            referenceImageCount = referenceImageCount,
            pricingPolicyVersion = policyVersion,
        )
    }

    // ---- 免费 ----

    @Test
    fun `Opus 用户满足官方免费条件时判定为免费`() {
        val estimate = calculator.estimate(context())

        assertThat(estimate).isInstanceOf(GenerationCostEstimate.Free::class.java)
        assertThat((estimate as GenerationCostEstimate.Free).reason)
            .isEqualTo(FreeReason.OPUS_V45_ELIGIBLE)
    }

    @Test
    fun `等级字段读不出来但带 V5 额度的账号仍算有订阅权益`() {
        // 本机实测账号就是这样：tier 0 / active false，但既有 V5 使用额度、生成也不扣费。
        // 官方文档说明 V5 使用额度是 V5 Opus 的功能，因此它的存在是订阅的旁证。
        val estimate = calculator.estimate(context(tier = SubscriptionTier.None))

        assertThat(estimate).isInstanceOf(GenerationCostEstimate.Free::class.java)
        assertThat((estimate as GenerationCostEstimate.Free).reason)
            .isEqualTo(FreeReason.OPUS_V45_ELIGIBLE)
    }

    @Test
    fun `只买积分、没有订阅证据的账号不免费`() {
        // 免费额度是订阅权益：未订阅的账号会正常扣费，不能对它宣称免费。
        val estimate = calculator.estimate(
            context(
                tier = SubscriptionTier.None,
                usage = null,
            ),
        )

        assertThat(estimate).isNotInstanceOf(GenerationCostEstimate.Free::class.java)
    }

    @Test
    fun `批量不误判为免费`() {
        val estimate = calculator.estimate(context(sampleCount = 4))

        assertThat(estimate).isNotInstanceOf(GenerationCostEstimate.Free::class.java)
    }

    @Test
    fun `Large 档位不误判为免费`() {
        val estimate = calculator.estimate(
            context(
                size = ImageSizePreset(1536, 1024),
                resolutionTier = ResolutionTier.LARGE,
            ),
        )

        assertThat(estimate).isNotInstanceOf(GenerationCostEstimate.Free::class.java)
    }

    @Test
    fun `认不出的尺寸档位不误判为免费`() {
        val estimate = calculator.estimate(context(resolutionTier = null))

        assertThat(estimate).isNotInstanceOf(GenerationCostEstimate.Free::class.java)
    }

    @Test
    fun `Steps 28 仍属免费区间而 29 不是`() {
        assertThat(calculator.estimate(context(steps = 28)))
            .isInstanceOf(GenerationCostEstimate.Free::class.java)
        assertThat(calculator.estimate(context(steps = 29)))
            .isNotInstanceOf(GenerationCostEstimate.Free::class.java)
    }

    @Test
    fun `V4_5 Full 在 Opus 下也免费`() {
        assertThat(calculator.estimate(context(profile = v45Full)))
            .isInstanceOf(GenerationCostEstimate.Free::class.java)
    }

    @Test
    fun `Guidance 不影响免费判定`() {
        // 官方免费条件里没有 Guidance 这一项，因此不再把它当成门槛。
        val estimate = calculator.estimate(context(guidance = 5.0))

        assertThat(estimate).isInstanceOf(GenerationCostEstimate.Free::class.java)
    }

    // ---- V5 额度 ----

    @Test
    fun `V5 在 Opus 且额度可用时走额度分支`() {
        val estimate = calculator.estimate(context(profile = v5))

        assertThat(estimate).isInstanceOf(GenerationCostEstimate.UsesV5Allowance::class.java)
        assertThat((estimate as GenerationCostEstimate.UsesV5Allowance).estimatedPercentCost).isNull()
    }

    @Test
    fun `V5 没有 usage 状态时返回 Unknown 而不是免费`() {
        val estimate = calculator.estimate(context(profile = v5, usage = null))

        assertThat((estimate as GenerationCostEstimate.Unknown).reason)
            .isEqualTo(UnknownCostReason.V5_ALLOWANCE_STATE_UNKNOWN)
    }

    @Test
    fun `V5 额度不可用时进入 Anlas 计价`() {
        // 额度用尽（percent = 0）后不再免费，但付费公式未校准 → 待确认。
        val estimate = calculator.estimate(
            context(profile = v5, usage = V5UsageLimit(0, isNegative = false, timeUntilNextPercentSeconds = null)),
        )

        assertThat((estimate as GenerationCostEstimate.Unknown).reason)
            .isEqualTo(UnknownCostReason.PRICING_NOT_CALIBRATED)
    }

    @Test
    fun `V5 非 Opus 不走额度分支`() {
        val estimate = calculator.estimate(context(profile = v5, tier = SubscriptionTier.None))

        assertThat(estimate).isNotInstanceOf(GenerationCostEstimate.UsesV5Allowance::class.java)
    }

    // ---- 参考图附加费：只有 Precise Reference 收费 ----

    @Test
    fun `图生图不额外收费`() {
        // 账号所有者更正：Image2Img 在 V4.5 与 V5 上都是免费的。
        val estimate = calculator.estimate(
            context(
                kind = GenerationKind.IMAGE_TO_IMAGE,
                hasBaseImage = true,
                referenceImageCount = 1,
            ),
        )

        assertThat(estimate).isInstanceOf(GenerationCostEstimate.Free::class.java)
    }

    @Test
    fun `Precise Reference 一张就是 5 Anlas`() {
        val estimate = calculator.estimate(
            context(
                kind = GenerationKind.PRECISE_REFERENCE,
                referenceImageCount = 1,
            ),
        )

        assertThat(estimate).isInstanceOf(GenerationCostEstimate.EstimatedAnlas::class.java)
        assertThat((estimate as GenerationCostEstimate.EstimatedAnlas).batchTotal)
            .isEqualTo(AnlasCostCalculator.PRECISE_REFERENCE_SURCHARGE_ANLAS)
    }

    @Test
    fun `Vibe Transfer 的价格未确认因此待确认`() {
        // 只确认了 Precise Reference 的附加费，不能顺手把 Vibe 说成免费或 5。
        val estimate = calculator.estimate(
            context(
                kind = GenerationKind.VIBE_TRANSFER,
                hasBaseImage = true,
                referenceImageCount = 1,
            ),
        )

        assertThat((estimate as GenerationCostEstimate.Unknown).reason)
            .isEqualTo(UnknownCostReason.PRICING_NOT_CALIBRATED)
    }

    @Test
    fun `Precise Reference 附加费按张数累加`() {
        // 累加这件事与"基础费用是否免费"无关，因此用测试用的已校准公式把基础固定成 20。
        // 同时必须避开免费规则（这里用 Large 档位），否则基础会被判成 0，
        // 测到的就只是附加费本身而不是"基础 + 附加费"。
        val calibrated = AnlasCostCalculator(FixedPriceFormula(base = 20L))
        val estimate = calibrated.estimate(
            context(
                kind = GenerationKind.PRECISE_REFERENCE,
                referenceImageCount = 3,
                resolutionTier = ResolutionTier.LARGE,
                size = ImageSizePreset(1536, 1024),
            ),
        ) as GenerationCostEstimate.EstimatedAnlas

        assertThat(estimate.batchTotal)
            .isEqualTo(20L + 3 * AnlasCostCalculator.PRECISE_REFERENCE_SURCHARGE_ANLAS)
    }

    @Test
    fun `Precise Reference 的基础费用未知时不给总数`() {
        // 非实测组合（这里是 Large 档位）：
        // 只报 5 点会让用户以为总共只要 5，因此宁可整单待确认。
        val estimate = calculator.estimate(
            context(
                kind = GenerationKind.PRECISE_REFERENCE,
                referenceImageCount = 1,
                resolutionTier = ResolutionTier.LARGE,
                size = ImageSizePreset(1536, 1024),
            ),
        )

        assertThat((estimate as GenerationCostEstimate.Unknown).reason)
            .isEqualTo(UnknownCostReason.PRICING_NOT_CALIBRATED)
    }

    @Test
    fun `Vibe Transfer 不套用 T2I 的免费规则`() {
        val estimate = calculator.estimate(
            context(
                kind = GenerationKind.VIBE_TRANSFER,
                hasBaseImage = true,
                referenceImageCount = 1,
            ),
        )

        assertThat(estimate).isNotInstanceOf(GenerationCostEstimate.Free::class.java)
    }

    // ---- 未知与非法 ----

    @Test
    fun `等级未知又没有订阅证据时不冒充 Opus 也不冒充免费`() {
        // 拿掉 V5 额度这一订阅旁证后，等级读不出来就必须落到"待确认"。
        val estimate = calculator.estimate(
            context(
                profile = v45Full,
                tier = SubscriptionTier.Unknown(9),
                usage = null,
            ),
        )

        assertThat((estimate as GenerationCostEstimate.Unknown).reason)
            .isEqualTo(UnknownCostReason.UNKNOWN_SUBSCRIPTION_TIER)
    }

    @Test
    fun `等级未知但有订阅旁证时按免费处理`() {
        // tier 字段不可信（本机账号就是 tier 0/active false 却免费），
        // 因此"带 V5 使用额度"这条旁证足以支撑免费判定。
        val estimate = calculator.estimate(
            context(profile = v45Full, tier = SubscriptionTier.Unknown(9)),
        )

        assertThat(estimate).isInstanceOf(GenerationCostEstimate.Free::class.java)
    }

    @Test
    fun `余额未知时实测免费组合仍然显示免费`() {
        // 实测规则只依赖参数，不依赖订阅等级；因此余额还没读回来时按钮上也是稳定的"免费"。
        val estimate = calculator.estimate(
            context(
                tier = SubscriptionTier.Unknown(DefaultSubscriptionTierResolver.RAW_MISSING),
                // 等级读不出来，但账户带着 V5 使用额度 → 有订阅权益。
                usage = V5UsageLimit(1, isNegative = false, timeUntilNextPercentSeconds = 3600),
            ),
        )

        assertThat(estimate).isInstanceOf(GenerationCostEstimate.Free::class.java)
    }

    @Test
    fun `非法参数返回 Unknown`() {
        val estimate = calculator.estimate(context(size = ImageSizePreset(0, 0)))

        assertThat((estimate as GenerationCostEstimate.Unknown).reason)
            .isEqualTo(UnknownCostReason.INVALID_PARAMETERS)
    }

    @Test
    fun `未校准的付费组合一律返回待确认而不是数字`() {
        val estimate = calculator.estimate(context(tier = SubscriptionTier.None, steps = 40))

        assertThat((estimate as GenerationCostEstimate.Unknown).reason)
            .isEqualTo(UnknownCostReason.PRICING_NOT_CALIBRATED)
    }

    @Test
    fun `四种状态互不混淆`() {
        // 同一份参数只改一个维度，就应该落到不同的状态上。
        assertThat(calculator.estimate(context()))
            .isInstanceOf(GenerationCostEstimate.Free::class.java)
        assertThat(calculator.estimate(context(profile = v5)))
            .isInstanceOf(GenerationCostEstimate.UsesV5Allowance::class.java)
        assertThat(calculator.estimate(context(kind = GenerationKind.IMAGE_TO_IMAGE, hasBaseImage = true, referenceImageCount = 1)))
            .isInstanceOf(GenerationCostEstimate.Free::class.java)
        assertThat(
            calculator.estimate(
                context(kind = GenerationKind.PRECISE_REFERENCE, referenceImageCount = 1),
            ),
        ).isInstanceOf(GenerationCostEstimate.EstimatedAnlas::class.java)
        assertThat(calculator.estimate(context(steps = 40)))
            .isInstanceOf(GenerationCostEstimate.Unknown::class.java)
    }

    // ---- 订阅等级解析 ----

    @Test
    fun `订阅等级按整数映射且未知值不猜`() {
        val resolver = DefaultSubscriptionTierResolver

        assertThat(resolver.resolve(0, true)).isEqualTo(SubscriptionTier.None)
        assertThat(resolver.resolve(1, true)).isEqualTo(SubscriptionTier.Tablet)
        assertThat(resolver.resolve(2, true)).isEqualTo(SubscriptionTier.Scroll)
        assertThat(resolver.resolve(3, true)).isEqualTo(SubscriptionTier.Opus)
        assertThat(resolver.resolve(99, true)).isEqualTo(SubscriptionTier.Unknown(99))
        assertThat(resolver.resolve(null, true))
            .isEqualTo(SubscriptionTier.Unknown(DefaultSubscriptionTierResolver.RAW_MISSING))
    }

    @Test
    fun `active 为 false 时按无订阅处理`() {
        // 但**不能**据此判断"不能生成"：按量购买 Anlas 的账户也能生成。
        assertThat(DefaultSubscriptionTierResolver.resolve(3, false)).isEqualTo(SubscriptionTier.None)
    }

    /** 测试用的已校准公式：基础费用固定，用来单独验证附加费的累加与上限处理。 */
    private class FixedPriceFormula(private val base: Long) : PaidAnlasFormula {
        override val version: String = "test-fixed"

        override fun supports(context: AnlasPricingContext): Boolean = true

        override fun calculateBatchTotal(context: AnlasPricingContext): Long = base
    }
}
