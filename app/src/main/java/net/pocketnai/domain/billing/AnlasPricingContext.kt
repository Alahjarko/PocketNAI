package net.pocketnai.domain.billing

import net.pocketnai.domain.model.GenerationParams

/** 这次生成属于哪一类（规划 §5.3）。只用于诊断与文案，费用判定不再按它分支。 */
enum class GenerationKind {
    TEXT_TO_IMAGE,
    IMAGE_TO_IMAGE,
    VIBE_TRANSFER,
    PRECISE_REFERENCE,
    /** 局部重绘：按 Image2Img 家族计费，没有额外附加费。 */
    INPAINT,
    OTHER,
}

/**
 * 费用计算的输入（规划 §5.3，2026-09-14 按官方算法改版）。
 *
 * 每一项都对应官方前端计价函数的一个真实入参，而不是我们自己发明的维度：
 * 尺寸/步数/张数来自参数，[strengthMultiplier] 对应 `cost × strength`，
 * [smeaMultiplier] 对应 SMEA 的三档倍率，三个"张数"分别对应参考图附加费的三条规则。
 */
data class AnlasPricingContext(
    val params: GenerationParams,
    val subscriptionTier: SubscriptionTier,
    /**
     * 是否具备订阅权益。
     *
     * **不给默认值**：免费单张与 V5 额度都建立在它之上，忘记传就等于悄悄多报价，
     * 这类 bug 在界面上只表现为"数字不对"，很难查。
     */
    val hasSubscription: Boolean,
    val v5UsageLimit: V5UsageLimit?,
    val generationKind: GenerationKind = GenerationKind.TEXT_TO_IMAGE,
    val hasBaseImage: Boolean = false,
    /**
     * Precise Reference 的参考图张数（`director_reference_*`）。
     *
     * 官方规则：**每张、每张输出图** 5 Anlas，即 `5 × 张数 × 张数`。
     * 图生图的起点图不计费。
     */
    val referenceImageCount: Int = 0,
    /** 已启用的 Vibe 张数。官方规则：**超过 4 张时每张 +2 Anlas**。 */
    val vibeCount: Int = 0,
    /**
     * 本次还需要付费编码的 Vibe 张数。
     *
     * 官方规则：把一张图编码成 `.vibe` 是一次性 2 Anlas；已经编码过（缓存命中）不再收费。
     * 因此这里只数"还没有 `.vibe` 产物"的那些。
     */
    val uncachedVibeCount: Int = 0,
    /**
     * 基础费用的乘数：图生图/重绘的 Strength，纯文生图为 1.0。
     *
     * 官方式子最后一步是 `max(ceil(cost × strength), 2)`，所以 Strength 低不但改图少，也**更便宜**。
     */
    val strengthMultiplier: Double = 1.0,
    /**
     * SMEA 的倍率：关 1.0、`sm` 1.2、`sm + sm_dyn` 1.4。
     *
     * 这四个模型的官方默认都是关闭（官方前端默认值里 `sm/sm_dyn` 都是 false），
     * 我们的请求也不发这两个字段，因此默认 1.0。
     */
    val smeaMultiplier: Double = 1.0,
    val pricingPolicyVersion: String,
)
