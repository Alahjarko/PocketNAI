package net.pocketnai.data.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import kotlin.math.roundToInt

/** 当次请求的底图和硬边蒙版；只在结果处理期间使用，不持久化 base64。 */
data class InpaintSource(val imageBase64: String, val maskBase64: String)

/**
 * 网页端在收到 infill 图片后还会与原图融合，服务器图片不是最终成图。
 * 2026-10-10 核对：8px 蒙版网格、4 格方形扩张、半径 20px 的两轮方框模糊。
 * 这里只生成本地混合用的 alpha，绝不把柔化蒙版提交给生成接口。
 */
internal object InpaintCompositor {
    fun composite(generated: Bitmap, source: InpaintSource): Boolean {
        var base: Bitmap? = null
        return try {
            val alpha = blendAlpha(source.maskBase64, generated.width, generated.height) ?: return false
            base = decode(source.imageBase64) ?: return false
            if (base.width != generated.width || base.height != generated.height) return false
            val originalRow = IntArray(generated.width)
            val generatedRow = IntArray(generated.width)
            for (y in 0 until generated.height) {
                base.getPixels(originalRow, 0, generated.width, 0, y, generated.width, 1)
                generated.getPixels(generatedRow, 0, generated.width, 0, y, generated.width, 1)
                for (x in generatedRow.indices) {
                    generatedRow[x] = mix(originalRow[x], generatedRow[x], alpha[y * generated.width + x].toInt() and 255)
                }
                generated.setPixels(generatedRow, 0, generated.width, 0, y, generated.width, 1)
            }
            true
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: OutOfMemoryError) {
            false
        } finally {
            base?.recycle()
        }
    }

    private fun blendAlpha(encoded: String, width: Int, height: Int): ByteArray? {
        if (width % CELL != 0 || height % CELL != 0) return null
        val mask = decode(encoded) ?: return null
        val cellWidth = width / CELL
        val cellHeight = height / CELL
        val expanded = ByteArray(cellWidth * cellHeight)
        try {
            if (mask.width != width || mask.height != height) return null
            // 最近邻抽取与请求一致的格子中心。扩张只用于成图融合，不扩大生成范围。
            val row = IntArray(width)
            for (y in 0 until cellHeight) {
                mask.getPixels(row, 0, width, 0, y * CELL + CELL / 2, width, 1)
                for (x in 0 until cellWidth) {
                    val pixel = row[x * CELL + CELL / 2]
                    if (Color.alpha(pixel) == 0 || Color.red(pixel) < 128) continue
                    for (dy in (y - DILATION).coerceAtLeast(0)..(y + DILATION).coerceAtMost(cellHeight - 1)) {
                        java.util.Arrays.fill(expanded, dy * cellWidth + (x - DILATION).coerceAtLeast(0),
                            dy * cellWidth + (x + DILATION + 1).coerceAtMost(cellWidth), 255.toByte())
                    }
                }
            }
        } finally {
            mask.recycle()
        }
        val alpha = ByteArray(width * height) { index -> expanded[(index / width / CELL) * cellWidth + index % width / CELL] }
        val horizontal = IntArray(width * height)
        val blurred = ByteArray(width * height)
        boxBlur(alpha, blurred, horizontal, width, height)
        boxBlur(blurred, alpha, horizontal, width, height)
        return alpha
    }

    /** 两个方向滑动求和，边缘重复最外侧像素；每轮只在最后量化一次。 */
    private fun boxBlur(input: ByteArray, output: ByteArray, horizontal: IntArray, width: Int, height: Int) {
        for (y in 0 until height) {
            val offset = y * width
            var sum = 0
            for (dx in -BLUR..BLUR) sum += input[offset + dx.coerceIn(0, width - 1)].toInt() and 255
            for (x in 0 until width) {
                horizontal[offset + x] = sum
                sum += (input[offset + (x + BLUR + 1).coerceAtMost(width - 1)].toInt() and 255) -
                    (input[offset + (x - BLUR).coerceAtLeast(0)].toInt() and 255)
            }
        }
        for (x in 0 until width) {
            var sum = 0
            for (dy in -BLUR..BLUR) sum += horizontal[dy.coerceIn(0, height - 1) * width + x]
            for (y in 0 until height) {
                // 网页端 radius=20 的整数倒数：39 / 2^16，匹配其向下量化。
                output[y * width + x] = ((sum * 39) ushr 16).coerceIn(0, 255).toByte()
                sum += horizontal[(y + BLUR + 1).coerceAtMost(height - 1) * width + x] -
                    horizontal[(y - BLUR).coerceAtLeast(0) * width + x]
            }
        }
    }

    private fun mix(original: Int, generated: Int, weight: Int): Int {
        if (weight == 0) return original
        if (weight == 255) return generated
        val oldAlpha = (Color.alpha(original) * (255 - weight) / 255.0).roundToInt()
        val newAlpha = (Color.alpha(generated) * weight / 255.0).roundToInt()
        val total = oldAlpha + newAlpha
        if (total == 0) return Color.TRANSPARENT
        fun channel(old: Int, new: Int) = ((old * oldAlpha + new * newAlpha) / total.toDouble()).roundToInt()
        return Color.argb(total.coerceAtMost(255), channel(Color.red(original), Color.red(generated)),
            channel(Color.green(original), Color.green(generated)), channel(Color.blue(original), Color.blue(generated)))
    }

    private fun decode(encoded: String): Bitmap? {
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private const val CELL = 8
    private const val DILATION = 4
    private const val BLUR = 20
}
