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

    // ---- 免费单张（官方规则） ----

    @Test
    fun `Opus 满足面积与步数条件时单张免费`() {
        val estimate = calculator.estimate(context())

        assertThat(estimate).isInstanceOf(GenerationCostEstimate.Free::class.java)
        assertThat((estimate as GenerationCostEstimate.Free).reason)
            .isEqualTo(FreeReason.OPUS_FREE_IMAGE)
    }

    @Test
    fun `多角色提示词不破免费也没有附加费`() {
        // 官方计价组装里只有 Precise Reference（5/张/输出）与 Vibe 两项附加费，
        // 没有 char_captions 项；免费判定读的 `characterRef` 是个从不被赋值的死字段
        // （技术决策记录 §30.2）。因此免费组合下的多角色生成同样免费。
        val estimate = calculator.estimate(
            context(
                characters = List(5) { index -> CharacterPrompt(prompt = "1girl, character $index") },
            ),
        )

        assertThat(estimate).isInstanceOf(GenerationCostEstimate.Free::class.java)
    }

    @Test
    fun `只买 Anlas 没有订阅的账号不免费`() {
        // 免费单张是订阅权益：未订阅账号按官方公式正常扣费。
        val estimate = calculator.estimate(context(tier = SubscriptionTier.None, subscribed = false))

        assertThat(estimate).isInstanceOf(GenerationCostEstimate.EstimatedAnlas::class.java)
        assertThat(estimate.total()).isEqualTo(17L)
    }

    @Test
    fun `订阅权益在但等级不是 Opus 时不免费`() {
        val estimate = calculator.estimate(context(tier = SubscriptionTier.Scroll))

        assertThat(estimate).isInstanceOf(GenerationCostEstimate.EstimatedAnlas::class.java)
    }

    @Test
    fun `面积上界是 1024x1024`() {
        assertThat(calculator.estimate(context(size = ImageSizePreset(1024, 1024))))
            .isInstanceOf(GenerationCostEstimate.Free::class.java)
        // 1216×832 与 832×1216 都在 1024² 以内，同属免费区间。
        assertThat(calculator.estimate(context(size = ImageSizePreset(1216, 832))))
            .isInstanceOf(GenerationCostEstimate.Free::class.java)
        // Large 方形 1536×1536 超出上界。
        assertThat(calculator.estimate(context(size = ImageSizePreset(1536, 1536))))
            .isInstanceOf(GenerationCostEstimate.EstimatedAnlas::class.java)
    }

    @Test
    fun `Steps 28 仍免费而 29 不免费`() {
        assertThat(calculator.estimate(context(steps = 28)))
            .isInstanceOf(GenerationCostEstimate.Free::class.java)
        assertThat(calculator.estimate(context(steps = 29)))
            .isInstanceOf(GenerationCostEstimate.EstimatedAnlas::class.java)
    }

    @Test
    fun `图生图同样享受免费单张`() {
        // 官方文档说"不带底图"，但官方前端的判据里没有这一条，本机实测也不扣费。
        val estimate = calculator.estimate(
            context(
                kind = GenerationKind.IMAGE_TO_IMAGE,
                hasBaseImage = true,
                strengthMultiplier = 0.7,
            ),
        )

        assertThat(estimate).isInstanceOf(GenerationCostEstimate.Free::class.java)
    }

    @Test
    fun `批量只免一张而不是整单免费`() {
        val estimate = calculator.estimate(context(sampleCount = 4)) as GenerationCostEstimate.EstimatedAnlas

        assertThat(estimate.freeImageCount).isEqualTo(1)
        // 4 张里免 1 张 → 3 × 17。
        assertThat(estimate.batchTotal).isEqualTo(3 * 17L)
    }

    @Test
    fun `V4_5 Full 在 Opus 下同样免费`() {
        assertThat(calculator.estimate(context(profile = v45Full)))
            .isInstanceOf(GenerationCostEstimate.Free::class.java)
    }

    // ---- V5 额度 ----

    @Test
    fun `V5 在 Opus 且额度可用时走额度分支`() {
        val estimate = calculator.estimate(context(profile = v5))

        assertThat(estimate).isInstanceOf(GenerationCostEstimate.UsesV5Allowance::class.java)
        assertThat((estimate as GenerationCostEstimate.UsesV5Allowance).estimatedPercentCost).isNull()
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
    fun `V5 非 Opus 不走额度分支`() {
        val estimate = calculator.estimate(
            context(profile = v5, tier = SubscriptionTier.None, subscribed = false),
        )

        assertThat(estimate).isNotInstanceOf(GenerationCostEstimate.UsesV5Allowance::class.java)
    }

    // ---- 参考图附加费 ----

    @Test
    fun `Precise Reference 一张在免费基础上只收 5 Anlas`() {
        // 实测：余额 407 → 402。基础生成被 Opus 权益免掉，附加费照收。
        val estimate = calculator.estimate(
            context(kind = GenerationKind.PRECISE_REFERENCE, referenceImageCount = 1),
        ) as GenerationCostEstimate.EstimatedAnlas

        assertThat(estimate.batchTotal)
            .isEqualTo(NovelAiPaidAnlasFormula.PRECISE_REFERENCE_ANLAS_PER_IMAGE)
        assertThat(estimate.freeImageCount).isEqualTo(1)
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
    fun `Vibe 超过四张的部分每张加 2 Anlas`() {
        val estimate = calculator.estimate(
            context(vibeCount = 6, model = ImageModel.V4_5_CURATED),
        ) as GenerationCostEstimate.EstimatedAnlas

        // 基础被免费覆盖，只剩超出的 2 张。
        assertThat(estimate.batchTotal).isEqualTo(4L)
    }

    @Test
    fun `未编码的 Vibe 每张加 2 Anlas`() {
        val estimate = calculator.estimate(
            context(vibeCount = 2, uncachedVibeCount = 2),
        ) as GenerationCostEstimate.EstimatedAnlas

        assertThat(estimate.batchTotal).isEqualTo(4L)
    }

    @Test
    fun `已经编码过的 Vibe 不再收费`() {
        val estimate = calculator.estimate(
            context(vibeCount = 2, uncachedVibeCount = 0),
        )

        assertThat(estimate).isInstanceOf(GenerationCostEstimate.Free::class.java)
    }

    // ---- 未知与非法 ----

    @Test
    fun `非法参数返回 Unknown`() {
        val estimate = calculator.estimate(context(size = ImageSizePreset(0, 0)))

        assertThat((estimate as GenerationCostEstimate.Unknown).reason)
            .isEqualTo(UnknownCostReason.INVALID_PARAMETERS)
    }

    @Test
    fun `超出官方报价范围的步数不给数字`() {
        // 官方前端认为 steps > 50 的参数不合法，因此不报价。
        val estimate = calculator.estimate(context(steps = 60, size = ImageSizePreset(1536, 1024)))

        assertThat((estimate as GenerationCostEstimate.Unknown).reason)
            .isEqualTo(UnknownCostReason.PRICING_NOT_CALIBRATED)
    }

    @Test
    fun `未校准公式仍然返回待确认`() {
        val uncalibrated = AnlasCostCalculator(UncalibratedPaidAnlasFormula)
        val estimate = uncalibrated.estimate(context(tier = SubscriptionTier.None, subscribed = false))

        assertThat((estimate as GenerationCostEstimate.Unknown).reason)
            .isEqualTo(UnknownCostReason.PRICING_NOT_CALIBRATED)
    }

    @Test
    fun `四种状态互不混淆`() {
        assertThat(calculator.estimate(context()))
            .isInstanceOf(GenerationCostEstimate.Free::class.java)
        assertThat(calculator.estimate(context(profile = v5)))
            .isInstanceOf(GenerationCostEstimate.UsesV5Allowance::class.java)
        assertThat(calculator.estimate(context(tier = SubscriptionTier.None, subscribed = false)))
            .isInstanceOf(GenerationCostEstimate.EstimatedAnlas::class.java)
        assertThat(calculator.estimate(context(size = ImageSizePreset(0, 0))))
            .isInstanceOf(GenerationCostEstimate.Unknown::class.java)
    }

    // ---- 订阅状态解析 ----

    private val now = 1_800_000_000L

    @Test
    fun `服务端 tier 3 且在有效期内即有订阅`() {
        val status = SubscriptionStatusResolver.resolve(
            rawTier = 3,
            accountType = 0,
            expiresAtEpochSeconds = now + 86_400,
            nowEpochSeconds = now,
        )

        assertThat(status.tier).isEqualTo(SubscriptionTier.Opus)
        assertThat(status.subscribed).isTrue()
        assertThat(status.source).isEqualTo(SubscriptionSource.REMOTE)
    }

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

    @Test
    fun `本机账号的实测读数判为无订阅`() {
        // tier 0 / active false / accountType RETAIL / expiresAt 0 —— 2026-09-14 实测值。
        val status = SubscriptionStatusResolver.resolve(
            rawTier = 0,
            accountType = 0,
            expiresAtEpochSeconds = 0,
            nowEpochSeconds = now,
        )

        assertThat(status.tier).isEqualTo(SubscriptionTier.None)
        assertThat(status.subscribed).isFalse()
    }

    @Test
    fun `内部账号类型直接算有订阅`() {
        val status = SubscriptionStatusResolver.resolve(
            rawTier = 0,
            accountType = AccountType.SERVICE.rawValue,
            expiresAtEpochSeconds = 0,
            nowEpochSeconds = now,
        )

        assertThat(status.subscribed).isTrue()
    }

    @Test
    fun `手动指定直接覆盖服务端读数`() {
        val manual = SubscriptionStatusResolver.resolve(
            rawTier = 0,
            accountType = 0,
            expiresAtEpochSeconds = 0,
            nowEpochSeconds = now,
            override = SubscriptionOverride.OPUS,
        )

        assertThat(manual.tier).isEqualTo(SubscriptionTier.Opus)
        assertThat(manual.subscribed).isTrue()
        assertThat(manual.source).isEqualTo(SubscriptionSource.MANUAL)

        val forcedNone = SubscriptionStatusResolver.resolve(
            rawTier = 3,
            accountType = 0,
            expiresAtEpochSeconds = now + 86_400,
            nowEpochSeconds = now,
            override = SubscriptionOverride.NONE,
        )

        assertThat(forcedNone.subscribed).isFalse()
        assertThat(forcedNone.isOpus).isFalse()
    }

    @Test
    fun `未知 tier 不会被当成 Opus`() {
        val status = SubscriptionStatusResolver.resolve(
            rawTier = 99,
            accountType = 0,
            expiresAtEpochSeconds = now + 86_400,
            nowEpochSeconds = now,
        )

        assertThat(status.tier).isEqualTo(SubscriptionTier.Unknown(99))
        assertThat(status.isOpus).isFalse()
    }
}
