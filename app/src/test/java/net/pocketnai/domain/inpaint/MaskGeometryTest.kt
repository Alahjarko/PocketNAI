package net.pocketnai.domain.inpaint

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 蒙版编辑器的几何换算（局部重绘规划书 §5.2）。
 *
 * 这里守的是"涂的位置与提交的位置严格对应"：换算错一点，用户涂的是脸、重画的是背景，
 * 而程序不会报任何错 —— 这类问题只能在单元测试里被抓住。
 */
class MaskGeometryTest {

    // ---- Fit 摆放 ----

    @Test
    fun `横图放进竖视图时上下留边`() {
        val transform = MaskGeometry.fit(
            bitmapWidth = 1216,
            bitmapHeight = 832,
            viewWidth = 1080f,
            viewHeight = 1920f,
        )

        // 缩放比受宽度限制：1080 / 1216
        assertThat(transform.scale).isWithin(0.0001f).of(1080f / 1216f)
        assertThat(transform.offsetX).isWithin(0.001f).of(0f)
        assertThat(transform.offsetY).isWithin(0.001f).of((1920f - 832f * transform.scale) / 2f)
    }

    @Test
    fun `竖图在更瘦的视图里由宽度决定缩放比`() {
        // 位图 832×1216（比例 0.684）比视图 1080×1920（比例 0.5625）更"胖"，
        // 因此宽度先顶满，上下留边。这里钉住"到底哪一边受限"这个判断。
        val transform = MaskGeometry.fit(832, 1216, 1080f, 1920f)

        assertThat(transform.scale).isWithin(0.0001f).of(1080f / 832f)
        assertThat(transform.offsetX).isWithin(0.001f).of(0f)
        assertThat(transform.offsetY).isWithin(0.01f).of((1920f - 1216f * transform.scale) / 2f)

        // 上下留边意味着纵向可映射范围小于视图高度。
        assertThat(1216f * transform.scale).isLessThan(1920f)
    }

    @Test
    fun `视图尺寸为零时退化为恒等变换而不是崩掉`() {
        val transform = MaskGeometry.fit(1216, 832, 0f, 0f)

        assertThat(transform.scale).isEqualTo(1f)
        assertThat(transform.offsetX).isEqualTo(0f)
    }

    // ---- 坐标换算 ----

    @Test
    fun `视图中心对应位图中心`() {
        val transform = MaskGeometry.fit(1216, 832, 1080f, 1920f)

        val center = transform.toBitmap(
            x = 1080f / 2f,
            y = 1920f / 2f,
            bitmapWidth = 1216,
            bitmapHeight = 832,
        )

        assertThat(center.x).isWithin(0.5f).of(1216f / 2f)
        assertThat(center.y).isWithin(0.5f).of(832f / 2f)
    }

    @Test
    fun `留边区域内的触摸被裁剪到边界`() {
        // 手指可以滑到图片外的黑边上：不能产生负坐标，否则画蒙版时会越界。
        val transform = MaskGeometry.fit(1216, 832, 1080f, 1920f)

        val top = transform.toBitmap(540f, 0f, 1216, 832)
        val bottom = transform.toBitmap(540f, 1919f, 1216, 832)

        assertThat(top.y).isEqualTo(0f)
        assertThat(bottom.y).isEqualTo(832f)
    }

    @Test
    fun `来回换算回到原点`() {
        val transform = MaskGeometry.fit(1024, 1024, 1080f, 1500f)

        val original = MaskPoint(300f, 700f)
        val screen = transform.toView(original)
        val back = transform.toBitmap(screen.x, screen.y, 1024, 1024)

        assertThat(back.x).isWithin(0.01f).of(original.x)
        assertThat(back.y).isWithin(0.01f).of(original.y)
    }

    @Test
    fun `笔刷半径按缩放比换算成位图像素`() {
        val transform = MaskGeometry.fit(1216, 832, 608f, 416f)

        // 视图缩放比为 0.5，屏幕上 10px 的笔刷在位图上是 20px。
        assertThat(transform.toBitmapLength(10f)).isWithin(0.001f).of(20f)
    }

    @Test
    fun `极小的视图半径也会得到至少一像素`() {
        val transform = MaskGeometry.fit(4096, 4096, 200f, 200f)

        assertThat(MaskGeometry.bitmapRadius(1f, transform)).isAtLeast(1f)
    }

    // ---- 扩张 ----

    @Test
    fun `扩张只加大画笔的半径`() {
        val draw = MaskStroke(isErase = false, radius = 10f, points = listOf(MaskPoint(0f, 0f)))
        val erase = MaskStroke(isErase = true, radius = 10f, points = listOf(MaskPoint(0f, 0f)))

        assertThat(MaskGeometry.effectiveRadius(draw, 6f)).isEqualTo(16f)
        // 橡皮不参与：不然"扩张"会把涂抹区域缩小，与字面意思相反。
        assertThat(MaskGeometry.effectiveRadius(erase, 6f)).isEqualTo(10f)
    }

    @Test
    fun `零或负的扩张不改变半径`() {
        val stroke = MaskStroke(isErase = false, radius = 10f, points = listOf(MaskPoint(0f, 0f)))

        assertThat(MaskGeometry.effectiveRadius(stroke, 0f)).isEqualTo(10f)
        assertThat(MaskGeometry.effectiveRadius(stroke, -5f)).isEqualTo(10f)
    }

    // ---- 空蒙版判定 ----

    @Test
    fun `没有笔画或只有橡皮时视为空蒙版`() {
        assertThat(MaskGeometry.isEmpty(emptyList())).isTrue()
        assertThat(
            MaskGeometry.isEmpty(
                listOf(MaskStroke(isErase = true, radius = 8f, points = listOf(MaskPoint(1f, 1f)))),
            ),
        ).isTrue()
        assertThat(
            MaskGeometry.isEmpty(
                listOf(MaskStroke(isErase = false, radius = 0f, points = listOf(MaskPoint(1f, 1f)))),
            ),
        ).isTrue()
    }

    @Test
    fun `有画笔笔画时不是空蒙版`() {
        assertThat(
            MaskGeometry.isEmpty(
                listOf(MaskStroke(isErase = false, radius = 8f, points = listOf(MaskPoint(1f, 1f)))),
            ),
        ).isFalse()
    }

    @Test
    fun `笔画长度为各段距离之和`() {
        val stroke = MaskStroke(
            isErase = false,
            radius = 4f,
            points = listOf(MaskPoint(0f, 0f), MaskPoint(3f, 4f), MaskPoint(3f, 14f)),
        )

        assertThat(MaskGeometry.strokeLength(stroke)).isWithin(0.001f).of(15f)
    }
}
