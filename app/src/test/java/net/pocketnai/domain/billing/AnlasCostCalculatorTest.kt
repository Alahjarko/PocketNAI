package net.pocketnai.domain.billing

import com.google.common.truth.Truth.assertThat
import net.pocketnai.domain.model.CharacterPrompt
import net.pocketnai.domain.model.GenerationParams
import net.pocketnai.domain.model.ImageModel
import net.pocketnai.domain.model.ImageSizePreset
import net.pocketnai.domain.model.ModelCatalog
import net.pocketnai.domain.model.ModelProfile
import org.junit.Test

/**
 * 费用计算器（余额规划 §12.4）。
 *
 * 2026-09-14 改版后，这里断言的**不再只是"分得清四种状态"，还有具体数字** ——
 * 计价式子与免费规则都从官方网页前端 bundle 反解出来了，样本值与官方客户端的
 * 中间结果逐条对得上（见 [NovelAiPaidAnlasFormulaTest]）。
 */
class AnlasCostCalculatorTest {

    private val policyVersion = NovelAiPaidAnlasFormula.VERSION
    private val calculator = AnlasCostCalculator()

    private val v45 = ModelCatalog.profileOf(ImageModel.V4_5_CURATED)
    private val v45Full = ModelCatalog.profileOf(ImageModel.V4_5_FULL)
    private val v5 = ModelCatalog.profileOf(ImageModel.V5_CURATED)

    private fun context(
        profile: ModelProfile = v45,
        tier: SubscriptionTier = SubscriptionTier.Opus,
        subscribed: Boolean = true,
        usage: V5UsageLimit? = V5UsageLimit(87, isNegative = false, timeUntilNextPercentSeconds = 3600),
        size: ImageSizePreset = profile.defaultSize,
        kind: GenerationKind = GenerationKind.TEXT_TO_IMAGE,
        hasBaseImage: Boolean = false,
        referenceImageCount: Int = 0,
        vibeCount: Int = 0,
        uncachedVibeCount: Int = 0,
        strengthMultiplier: Double = 1.0,
        steps: Int = 23,
        sampleCount: Int = 1,
        model: ImageModel? = null,
        characters: List<CharacterPrompt> = emptyList(),
    ): AnlasPricingContext {
        val base = GenerationParams.defaultsFor(profile)
        return AnlasPricingContext(
            params = base.copy(
                model = model ?: profile.model,
                size = size,
                steps = steps,
                sampleCount = sampleCount,
                characters = characters,
            ),
            subscriptionTier = tier,
            hasSubscription = subscribed,
            v5UsageLimit = usage,
            generationKind = kind,
            hasBaseImage = hasBaseImage,
            referenceImageCount = referenceImageCount,
            vibeCount = vibeCount,
            uncachedVibeCount = uncachedVibeCount,
            strengthMultiplier = strengthMultiplier,
            pricingPolicyVersion = policyVersion,
        )
    }

    private fun GenerationCostEstimate.total(): Long =
        (this as GenerationCostEstimate.EstimatedAnlas).batchTotal

    @Test
    fun `批量只免一张而不是整单免费`() {
        val estimate = calculator.estimate(context(sampleCount = 4)) as GenerationCostEstimate.EstimatedAnlas

        assertThat(estimate.freeImageCount).isEqualTo(1)
        // 4 张里免 1 张 → 3 × 17。
        assertThat(estimate.batchTotal).isEqualTo(3 * 17L)
    }

    @Test
    fun `V5 额度为负时不再免费而是按 Anlas 计`() {
        // 官方前端：usage.isNegative 时连那一张免费也取消。V5 单价是 V4.5 的 1.5 倍。
        val estimate = calculator.estimate(
            context(
                profile = v5,
                usage = V5UsageLimit(0, isNegative = true, timeUntilNextPercentSeconds = null),
            ),
        ) as GenerationCostEstimate.EstimatedAnlas

        assertThat(estimate.batchTotal).isEqualTo(26L)
    }

    @Test
    fun `Precise Reference 附加费按张数与张数相乘`() {
        // 官方前端：`5 × 张数 × n_samples`。
        val estimate = calculator.estimate(
            context(
                kind = GenerationKind.PRECISE_REFERENCE,
                referenceImageCount = 3,
                sampleCount = 2,
                // 两张一律超出免费范围，方便把"基础 + 附加费"一起断言。
                size = ImageSizePreset(1536, 1024),
            ),
        ) as GenerationCostEstimate.EstimatedAnlas

        // 基础：1536×1024 @23 步单张 26，两张 52；附加费 5×3×2 = 30。
        assertThat(estimate.batchTotal).isEqualTo(52L + 30L)
        assertThat(estimate.freeImageCount).isEqualTo(0)
    }

    @Test
    fun `未校准公式仍然返回待确认`() {
        val uncalibrated = AnlasCostCalculator(UncalibratedPaidAnlasFormula)
        val estimate = uncalibrated.estimate(context(tier = SubscriptionTier.None, subscribed = false))

        assertThat((estimate as GenerationCostEstimate.Unknown).reason)
            .isEqualTo(UnknownCostReason.PRICING_NOT_CALIBRATED)
    }

    private val now = 1_800_000_000L

    @Test
    fun `已取消但仍在付费周期内仍算有订阅`() {
        // 官方说明：取消后权益保留到付费周期结束；expiresAt 还在未来就说明这点。
        val status = SubscriptionStatusResolver.resolve(
            rawTier = 2,
            accountType = 0,
            expiresAtEpochSeconds = now + 1,
            nowEpochSeconds = now,
        )

        assertThat(status.subscribed).isTrue()
    }
}
