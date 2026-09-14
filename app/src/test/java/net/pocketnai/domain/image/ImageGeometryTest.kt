package net.pocketnai.domain.image

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 参考图的几何计算（《参考图功能规划书》5.3）。
 *
 * 这里测的是**算错会很难查**的那一类问题：尺寸不是 64 的倍数、图与请求里的
 * width/height 不一致、黑边补错位置、降采样倍数取反导致 OOM。
 * 这些都不会在本机崩溃，而是变成服务端一句笼统的"参数无效"。
 */
class ImageGeometryTest {

    // ---- 铺满画布（Image2Img） ----

    @Test
    fun `方图铺进竖画布时左右各裁掉一部分`() {
        val placement = ImageGeometry.centerCrop(PixelSize(1000, 1000), PixelSize(832, 1216))

        assertThat(placement.source).isEqualTo(PixelRegion(x = 158, y = 0, width = 684, height = 1000))
        assertThat(placement.destination).isEqualTo(PixelRegion(0, 0, 832, 1216))
        assertThat(placement.canvas).isEqualTo(PixelSize(832, 1216))
    }

    @Test
    fun `宽图铺进宽画布时裁掉左右两侧`() {
        val placement = ImageGeometry.centerCrop(PixelSize(2000, 1000), PixelSize(1216, 832))

        assertThat(placement.source).isEqualTo(PixelRegion(x = 269, y = 0, width = 1462, height = 1000))
    }

    @Test
    fun `比例完全一致时不裁任何内容`() {
        val placement = ImageGeometry.centerCrop(PixelSize(832, 1216), PixelSize(832, 1216))

        assertThat(placement.source).isEqualTo(PixelRegion(0, 0, 832, 1216))
        assertThat(placement.destination).isEqualTo(PixelRegion(0, 0, 832, 1216))
    }

    @Test
    fun `比画布小的图也要铺满`() {
        // 图生图的起点必须铺满画布，否则会留下未绘制的区域。
        val placement = ImageGeometry.centerCrop(PixelSize(200, 100), PixelSize(832, 1216))

        assertThat(placement.destination).isEqualTo(PixelRegion(0, 0, 832, 1216))
        // 源图按比例取到最窄的一条：832/12.16 ≈ 68，高度取满 100。
        assertThat(placement.source.width).isEqualTo(68)
        assertThat(placement.source.height).isEqualTo(100)
    }

    // ---- 完整放入（Precise Reference） ----

    @Test
    fun `小方图居中放进大方画布`() {
        val placement = ImageGeometry.letterbox(PixelSize(1000, 1000), PixelSize(1472, 1472))

        assertThat(placement.source).isEqualTo(PixelRegion(0, 0, 1000, 1000))
        assertThat(placement.destination).isEqualTo(PixelRegion(236, 236, 1000, 1000))
    }

    @Test
    fun `比例一致的大图缩到刚好占满画布`() {
        val placement = ImageGeometry.letterbox(PixelSize(3000, 2000), PixelSize(1536, 1024))

        assertThat(placement.destination).isEqualTo(PixelRegion(0, 0, 1536, 1024))
    }

    @Test
    fun `横幅长图放进竖画布时上下留黑`() {
        val placement = ImageGeometry.letterbox(PixelSize(3000, 1000), PixelSize(1024, 1536))

        assertThat(placement.destination.width).isEqualTo(1024)
        assertThat(placement.destination.height).isEqualTo(341)
        assertThat(placement.destination.x).isEqualTo(0)
        assertThat(placement.destination.y).isEqualTo(597)
    }

    @Test
    fun `完整放入不会放大小图`() {
        // 放大不会增加任何信息，只会让参考变糊。
        val placement = ImageGeometry.letterbox(PixelSize(300, 200), PixelSize(1472, 1472))

        assertThat(placement.destination.width).isEqualTo(300)
        assertThat(placement.destination.height).isEqualTo(200)
    }

    // ---- Precise Reference 的画布选择 ----

    @Test
    fun `按方向选出官方要求的三种画布`() {
        assertThat(ImageGeometry.directorCanvas(PixelSize(832, 1216)))
            .isEqualTo(PixelSize(1024, 1536))
        assertThat(ImageGeometry.directorCanvas(PixelSize(1216, 832)))
            .isEqualTo(PixelSize(1536, 1024))
        assertThat(ImageGeometry.directorCanvas(PixelSize(1024, 1024)))
            .isEqualTo(PixelSize(1472, 1472))
    }

    @Test
    fun `接近正方形时用方画布`() {
        // 略宽/略高的图若强行用 3:2 画布，会留下两条很宽的黑边。
        assertThat(ImageGeometry.directorCanvas(PixelSize(1080, 1000)))
            .isEqualTo(ImageGeometry.DIRECTOR_SQUARE)
        assertThat(ImageGeometry.directorCanvas(PixelSize(1000, 1080)))
            .isEqualTo(ImageGeometry.DIRECTOR_SQUARE)
    }

    @Test
    fun `明显不成比例时用对应的画布`() {
        assertThat(ImageGeometry.directorCanvas(PixelSize(1800, 1000)))
            .isEqualTo(ImageGeometry.DIRECTOR_LANDSCAPE)
        assertThat(ImageGeometry.directorCanvas(PixelSize(1000, 1800)))
            .isEqualTo(ImageGeometry.DIRECTOR_PORTRAIT)
    }

    // ---- 解码降采样 ----

    @Test
    fun `不超过上限时不降采样`() {
        assertThat(ImageGeometry.downsampleFactor(PixelSize(4096, 2000), maxDimension = 4096))
            .isEqualTo(1)
    }

    @Test
    fun `长边超限时取最小的 2 的幂倍数`() {
        // 8000 必须降到 4096 以内。写成"降完仍不小于上限"的判据会算出 1，解码直接 OOM。
        assertThat(ImageGeometry.downsampleFactor(PixelSize(8000, 6000), maxDimension = 4096))
            .isEqualTo(2)
        assertThat(ImageGeometry.downsampleFactor(PixelSize(10000, 1000), maxDimension = 4096))
            .isEqualTo(4)
        assertThat(ImageGeometry.downsampleFactor(PixelSize(40000, 40000), maxDimension = 4096))
            .isEqualTo(16)
    }

    @Test
    fun `降采样后一定落在上限以内且不会缩过头`() {
        val max = 4096
        listOf(4097, 8192, 20000, 100000).forEach { side ->
            val factor = ImageGeometry.downsampleFactor(PixelSize(side, side), maxDimension = max)
            val result = side / factor
            assertThat(result).isAtMost(max)
            // 只是一个下界而不是"严格大于一半"：边长刚好略超上限时（4097），
            // 整除会把结果落在恰好 max/2 上。
            assertThat(result).isAtLeast(max / 2)
        }
    }

    // ---- 体积预算 ----

    @Test
    fun `base64 长度按三字节一组向上取整`() {
        assertThat(ImageGeometry.base64Length(0)).isEqualTo(0)
        assertThat(ImageGeometry.base64Length(1)).isEqualTo(4)
        assertThat(ImageGeometry.base64Length(3)).isEqualTo(4)
        assertThat(ImageGeometry.base64Length(4)).isEqualTo(8)
    }

    @Test
    fun `体积预算上限包含边界本身`() {
        assertThat(ImageGeometry.isWithinReferenceBudget(ImageGeometry.MAX_REFERENCE_FILE_BYTES))
            .isTrue()
        assertThat(ImageGeometry.isWithinReferenceBudget(ImageGeometry.MAX_REFERENCE_FILE_BYTES + 1))
            .isFalse()
    }

    // ---- 放置方式的派发 ----

    @Test
    fun `变换类型派发到对应的放置方式`() {
        val cover = ImageGeometry.placementFor(
            ImageTransform.Cover(PixelSize(832, 1216)),
            PixelSize(1000, 1000),
        )
        assertThat(cover).isEqualTo(ImageGeometry.centerCrop(PixelSize(1000, 1000), PixelSize(832, 1216)))

        val letterbox = ImageGeometry.placementFor(
            ImageTransform.Letterbox(PixelSize(1472, 1472)),
            PixelSize(1000, 1000),
        )
        assertThat(letterbox)
            .isEqualTo(ImageGeometry.letterbox(PixelSize(1000, 1000), PixelSize(1472, 1472)))
    }
}
