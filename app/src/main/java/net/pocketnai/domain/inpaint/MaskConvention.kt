package net.pocketnai.domain.inpaint

/**
 * 蒙版图像里"涂抹区域"怎么表示。
 *
 * ## 为什么这是一个可翻转的枚举
 * 公开资料里查不到 NovelAI 的蒙版约定：OpenAPI 只写了"base64 encoded mask"，
 * 官方文档是产品说明（讲的是界面上涂蓝色区域），搜索引擎又被反爬拦住。
 * 因此把不确定性关在这一个枚举里：真机探针判定后改**一行**即可，渲染与请求构造都不用动。
 *
 * ## 探针方法（一次生成即可判定）
 * 蒙版只涂左半边、提示词换成与底图完全不同的内容：
 * - 左半边变了 → [PAINTED_IS_WHITE]（白色是重画区域）；
 * - 右半边变了 → [PAINTED_IS_TRANSPARENT]（透明是重画区域，约定相反）。
 *
 * 见《局部重绘功能规划书》§3 的 B1。
 */
enum class MaskConvention {

    /** 涂抹区域涂成白色，其余为黑。这是 SD 系 img2img 的常见约定。 */
    PAINTED_IS_WHITE,

    /** 涂抹区域留成透明（alpha = 0），其余为不透明。 */
    PAINTED_IS_TRANSPARENT,
    ;

    companion object {
        /**
         * 当前采用的约定。
         *
         * ⚠️ 真机探针确认前，这里按更常见的 [PAINTED_IS_WHITE] 实现。
         * 改动只影响渲染函数的输出映射。
         */
        val CURRENT: MaskConvention = PAINTED_IS_WHITE
    }
}
