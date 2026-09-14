package net.pocketnai.domain.billing

import net.pocketnai.domain.model.GenerationParams
import net.pocketnai.domain.model.ResolutionTier

/** 这次生成属于哪一类（规划 §5.3）。 */
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
 * 费用计算的输入（规划 §5.3）。
 *
 * 第一版只支持 T2I，但仍然显式传入 [generationKind] 与 [hasBaseImage]：
 * 以后加入参考图时，旧计算器不会把"带起点图的生成"误判成普通免费 T2I。
 */
data class AnlasPricingContext(
    val params: GenerationParams,
    val subscriptionTier: SubscriptionTier,
    val v5UsageLimit: V5UsageLimit?,
    /**
     * 参数对应哪个尺寸档位。
     *
     * 允许为空：尺寸不是官方预设之一时（例如来自旧版本历史）就不该被判定为免费。
     * 规划里写的是非空类型，这里放宽一格是为了让"认不出来的尺寸"有一个安全表达，
     * 而不是硬塞一个 NORMAL。
     */
    val resolutionTier: ResolutionTier?,
    val generationKind: GenerationKind = GenerationKind.TEXT_TO_IMAGE,
    val hasBaseImage: Boolean = false,
    /**
     * 本次挂了几张参考图（Image2Img 的起点图、Vibe、Precise Reference 都算）。
     *
     * 参考图有**独立的附加费**（每张固定 Anlas，与模型无关），
     * 因此费用计算必须知道张数，而不是只知道"有没有"。
     */
    val referenceImageCount: Int = 0,
    val pricingPolicyVersion: String,
)
