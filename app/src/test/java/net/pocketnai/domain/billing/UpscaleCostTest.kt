package net.pocketnai.domain.billing

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * `/ai/upscale` 的价格表（2026-09-18 从官方网页前端 bundle 反解，技术决策记录 §30）。
 *
 * 官方前端的原样查表是 `[[1048576,1],[1747627,2],[2446678,3],[3145728,4]]`，
 * 命中不了（面积 0 或超过上限）时返回哨兵 `-3` 并**禁用按钮** —— 我们同样不给数字。
 */
class UpscaleCostTest {

    @Test
    fun `面积不超过 1024 平方时收 1 Anlas`() {
        assertThat(UpscaleCost.anlas(1024, 1024)).isEqualTo(1L)
        assertThat(UpscaleCost.anlas(832, 1216)).isEqualTo(1L)
        assertThat(UpscaleCost.anlas(1216, 832)).isEqualTo(1L)
    }

    @Test
    fun `三档面积分界按官方表取上界`() {
        assertThat(UpscaleCost.anlas(1, 1_048_576)).isEqualTo(1L)
        assertThat(UpscaleCost.anlas(1, 1_048_577)).isEqualTo(2L)
        assertThat(UpscaleCost.anlas(1, 1_747_627)).isEqualTo(2L)
        assertThat(UpscaleCost.anlas(1, 1_747_628)).isEqualTo(3L)
        assertThat(UpscaleCost.anlas(1, 2_446_678)).isEqualTo(3L)
        assertThat(UpscaleCost.anlas(1, 2_446_679)).isEqualTo(4L)
    }

    @Test
    fun `常见大尺寸档位与官方一致`() {
        // Large 竖图 1024×1536 = 1572864 → 2
        assertThat(UpscaleCost.anlas(1024, 1536)).isEqualTo(2L)
        // Large 方图 1472×1472 = 2166784 → 3
        assertThat(UpscaleCost.anlas(1472, 1472)).isEqualTo(3L)
        // 壁纸 1920×1088 = 2088960 → 3
        assertThat(UpscaleCost.anlas(1920, 1088)).isEqualTo(3L)
        // 官方超分入口的上限尺寸 1536×2048 = 3145728 → 4
        assertThat(UpscaleCost.anlas(1536, 2048)).isEqualTo(4L)
    }

    @Test
    fun `超过官方上限或面积非法时不给数字`() {
        assertThat(UpscaleCost.anlas(2048, 2048)).isNull()
        assertThat(UpscaleCost.anlas(0, 1024)).isNull()
        assertThat(UpscaleCost.anlas(-832, 1216)).isNull()

        assertThat(UpscaleCost.isSupported(1536, 2048)).isTrue()
        assertThat(UpscaleCost.isSupported(2048, 2048)).isFalse()
    }

    @Test
    fun `超分倍数固定为 4 倍且与请求无关`() {
        // 官方请求体里没有倍数字段，文档也写明 "increase its resolution by four times"。
        assertThat(UpscaleCost.SCALE_FACTOR).isEqualTo(4)
        assertThat(UpscaleCost.MAX_SOURCE_AREA).isEqualTo(3_145_728L)
    }
}
