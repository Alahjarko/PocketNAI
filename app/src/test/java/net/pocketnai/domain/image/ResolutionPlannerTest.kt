package net.pocketnai.domain.image

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 分辨率规划。
 *
 * 这里断言的是自定义分辨率最容易出错的几个点：**对齐方向**（向上而不是"最近"）、
 * **裁切原点**（居中、奇数固定多给右下）、以及**什么时候根本不该放行**。
 */
class ResolutionPlannerTest {

    private fun plan(width: Int, height: Int, exact: Boolean = true) =
        ResolutionPlanner.plan(PixelSize(width, height), exactOutput = exact)

    private fun success(width: Int, height: Int, exact: Boolean = true): ResolutionPlanner.Plan =
        (plan(width, height, exact) as ResolutionPlanner.Result.Success).plan

    private fun failure(width: Int, height: Int, exact: Boolean = true): ResolutionPlanner.Reason =
        (plan(width, height, exact) as ResolutionPlanner.Result.Failure).reason

    // ---- 需求里那个例子 ----

    @Test
    fun `1920x1080 以 1920x1088 生成并上下各裁 4 px`() {
        val plan = success(1920, 1080)

        assertThat(plan.generationSize).isEqualTo(PixelSize(1920, 1088))
        assertThat(plan.targetSize).isEqualTo(PixelSize(1920, 1080))
        assertThat(plan.crop).isEqualTo(PixelRegion(x = 0, y = 4, width = 1920, height = 1080))
        assertThat(plan.croppedHeight).isEqualTo(8)
        assertThat(plan.croppedWidth).isEqualTo(0)
        assertThat(plan.needsCrop).isTrue()
    }

    @Test
    fun `1080x1920 只补宽度并左右各裁 4 px`() {
        val plan = success(1080, 1920)

        assertThat(plan.generationSize).isEqualTo(PixelSize(1088, 1920))
        assertThat(plan.crop).isEqualTo(PixelRegion(x = 4, y = 0, width = 1080, height = 1920))
    }

    @Test
    fun `已经 64 对齐的尺寸不产生裁切`() {
        val plan = success(1920, 1088)

        assertThat(plan.generationSize).isEqualTo(PixelSize(1920, 1088))
        assertThat(plan.crop).isNull()
        assertThat(plan.needsCrop).isFalse()
    }

    // ---- 对齐方向 ----

    @Test
    fun `精确模式向上取整而不是取最近`() {
        // 1050 的最近倍数是 1024（差 26），但 1024 比目标小，根本没有像素可裁。
        assertThat(ResolutionPlanner.nearestStep(1050)).isEqualTo(1024)
        assertThat(ResolutionPlanner.ceilToStep(1050)).isEqualTo(1088)

        val plan = success(1050, 1050)
        assertThat(plan.generationSize).isEqualTo(PixelSize(1088, 1088))
        assertThat(plan.crop).isEqualTo(PixelRegion(x = 19, y = 19, width = 1050, height = 1050))
    }

    @Test
    fun `仿官方模式取最近的 64 倍数且不裁切`() {
        val plan = success(1920, 1080, exact = false)

        assertThat(plan.generationSize).isEqualTo(PixelSize(1920, 1088))
        assertThat(plan.crop).isNull()
        // 官方前端在距离相等时取较大值（`e-a < n-e ? a : n`）。
        assertThat(ResolutionPlanner.nearestStep(1056)).isEqualTo(1088)
        assertThat(ResolutionPlanner.nearestStep(1024)).isEqualTo(1024)
        assertThat(ResolutionPlanner.nearestStep(1080)).isEqualTo(1088)
    }

    // ---- 裁切原点 ----

    @Test
    fun `奇数差值固定多给右下 1 px`() {
        // 1088 - 1081 = 7 → 上 3 下 4；规则确定，重现时才不会左右漂移。
        val plan = success(1920, 1081)

        assertThat(plan.crop).isEqualTo(PixelRegion(x = 0, y = 3, width = 1920, height = 1081))
    }

    // ---- 拒绝的情形 ----

    @Test
    fun `边长太小直接拒绝`() {
        assertThat(failure(32, 1024)).isEqualTo(ResolutionPlanner.Reason.SIDE_TOO_SMALL)
        assertThat(failure(1024, 32)).isEqualTo(ResolutionPlanner.Reason.SIDE_TOO_SMALL)
    }

    @Test
    fun `面积超过官方上限时拒绝并说明`() {
        // 2048×2048 = 4,194,304 > 3,145,728。
        assertThat(failure(2048, 2048)).isEqualTo(ResolutionPlanner.Reason.AREA_TOO_LARGE)
    }

    @Test
    fun `边长超过本地安全上限时拒绝`() {
        // 本地护栏比官方更保守（官方只看面积）：2048 是上限，2112 就不放行。
        assertThat(failure(2112, 512)).isEqualTo(ResolutionPlanner.Reason.SIDE_TOO_LARGE)
    }

    @Test
    fun `面积上限内的极端长条仍然放行`() {
        // 2048×1536 = 3,145,728，正好等于官方上限：边界值必须放行。
        val plan = success(2048, 1536)

        assertThat(plan.generationSize).isEqualTo(PixelSize(2048, 1536))
        assertThat(plan.generationSize.totalPixels)
            .isEqualTo(SizeConstraintsOfficialLimit)
    }

    // ---- 预设 ----

    @Test
    fun `预设尺寸不产生裁切也不参与校验`() {
        val plan = ResolutionPlanner.planForPreset(PixelSize(832, 1216))

        assertThat(plan.generationSize).isEqualTo(PixelSize(832, 1216))
        assertThat(plan.targetSize).isEqualTo(PixelSize(832, 1216))
        assertThat(plan.crop).isNull()
    }

    private companion object {
        const val SizeConstraintsOfficialLimit = net.pocketnai.domain.model.SizeConstraints.OFFICIAL_MAX_TOTAL_PIXELS
    }
}
