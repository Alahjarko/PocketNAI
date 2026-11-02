package net.pocketnai.domain.safety

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.roundToInt

/** 小尺寸工作图的两次可分离高斯卷积；只处理显示副本，不触碰原图。 */
object GaussianBlur {
    fun apply(pixels: IntArray, width: Int, height: Int, sigma: Double = 8.0): IntArray {
        require(width > 0 && height > 0 && pixels.size == width * height && sigma > 0 && sigma.isFinite())
        val radius = ceil(sigma * 3).toInt()
        val kernel = DoubleArray(radius * 2 + 1) { offset -> exp(-((offset - radius).toDouble() * (offset - radius)) / (2 * sigma * sigma)) }
        val total = kernel.sum()
        for (i in kernel.indices) kernel[i] /= total
        fun pass(input: IntArray, horizontal: Boolean): IntArray = IntArray(input.size) { index ->
            val x = index % width; val y = index / width
            var a = 0.0; var r = 0.0; var g = 0.0; var b = 0.0
            for (offset in -radius..radius) {
                val px = if (horizontal) (x + offset).coerceIn(0, width - 1) else x
                val py = if (horizontal) y else (y + offset).coerceIn(0, height - 1)
                val pixel = input[py * width + px]; val weight = kernel[offset + radius]
                a += (pixel ushr 24) * weight; r += ((pixel ushr 16) and 255) * weight
                g += ((pixel ushr 8) and 255) * weight; b += (pixel and 255) * weight
            }
            (a.roundToInt().coerceIn(0,255) shl 24) or (r.roundToInt().coerceIn(0,255) shl 16) or
                (g.roundToInt().coerceIn(0,255) shl 8) or b.roundToInt().coerceIn(0,255)
        }
        return pass(pass(pixels, true), false)
    }
}
