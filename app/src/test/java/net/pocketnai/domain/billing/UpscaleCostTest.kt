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
    fun `三档面积分界按官方表取上界`() {
        assertThat(UpscaleCost.anlas(1, 1_048_576)).isEqualTo(1L)
        assertThat(UpscaleCost.anlas(1, 1_048_577)).isEqualTo(2L)
        assertThat(UpscaleCost.anlas(1, 1_747_627)).isEqualTo(2L)
        assertThat(UpscaleCost.anlas(1, 1_747_628)).isEqualTo(3L)
        assertThat(UpscaleCost.anlas(1, 2_446_678)).isEqualTo(3L)
        assertThat(UpscaleCost.anlas(1, 2_446_679)).isEqualTo(4L)
    }
}
