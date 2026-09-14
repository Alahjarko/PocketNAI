package net.pocketnai.domain.inpaint

/**
 * 蒙版图像里"涂抹区域"怎么表示。
 *
 * ## 结论：已实测确认（2026-09-14，技术决策记录第十八节）
 * **[PAINTED_IS_WHITE] 正确**：涂抹区域 = 白色、其余 = 黑色、不透明 PNG。
 * 证据有两层，互相独立：
 * 1. 官方前端 bundle 里的蒙版管线（同尺寸、白涂抹/黑背景，见第十七节）；
 * 2. 真机判别性探针：蒙版只涂 3.91% 像素，出图中蒙版内 98.9% 重画、
 *    蒙版外仅 0.74% 有变动 —— 若约定相反，变动的会是那 96%。
 *
 * 枚举仍然保留：它是"渲染输出怎么映射"的唯一开关，也是日后发现新证据时的翻转点。
 */
enum class MaskConvention {

    /** 涂抹区域涂成白色，其余为黑。**当前生效且已实测确认。** */
    PAINTED_IS_WHITE,

    /** 涂抹区域留成透明（alpha = 0），其余为不透明。未使用，留作翻转点。 */
    PAINTED_IS_TRANSPARENT,
    ;

    companion object {
        /** 当前采用的约定：已实测确认（见类注释）。 */
        val CURRENT: MaskConvention = PAINTED_IS_WHITE
    }
}
