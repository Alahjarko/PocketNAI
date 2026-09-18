package net.pocketnai.domain.billing

/**
 * `/ai/upscale` 的价格与形态（2026-09-18 从官方网页前端 bundle 反解，见技术决策记录 §30）。
 *
 * 与生成计价不同，超分是**按源图面积查表的固定价**，不看步数、不看模型、
 * Opus 的免费单张也不覆盖它 —— 官方前端把这张表直接画在按钮上，服务端按同一套收。
 */
object UpscaleCost {

    /** 官方超分是固定 4 倍，请求里没有倍数参数（文档也写明 "four times"）。 */
    const val SCALE_FACTOR: Int = 4

    /** 源图面积上限（1536 × 2048）。超过它官方前端直接禁用按钮。 */
    const val MAX_SOURCE_AREA: Long = 3_145_728L

    /**
     * 这次超分预计消耗多少 Anlas；`null` 表示**超出官方价格表**（面积为 0 或大于
     * [MAX_SOURCE_AREA]），界面据此禁用按钮而不是报一个假的数字。
     */
    fun anlas(sourceWidth: Int, sourceHeight: Int): Long? {
        val area = sourceWidth.toLong() * sourceHeight.toLong()
        if (area <= 0L) return null
        return PRICE_TIERS.firstOrNull { area <= it.first }?.second
    }

    /** 源图面积是否还能超分（等价于 [anlas] 非空）。 */
    fun isSupported(sourceWidth: Int, sourceHeight: Int): Boolean =
        anlas(sourceWidth, sourceHeight) != null

    /** 官方前端的原样查表：`[[1048576,1],[1747627,2],[2446678,3],[3145728,4]]`。 */
    private val PRICE_TIERS: List<Pair<Long, Long>> = listOf(
        1_048_576L to 1L,
        1_747_627L to 2L,
        2_446_678L to 3L,
        MAX_SOURCE_AREA to 4L,
    )
}
