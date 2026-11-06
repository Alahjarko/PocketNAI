package net.pocketnai.data.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Movie
import android.graphics.drawable.BitmapDrawable
import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import coil.ImageLoader
import coil.decode.DecodeResult
import coil.decode.Decoder
import coil.fetch.SourceResult
import coil.request.Options
import coil.size.Dimension
import coil.size.Scale
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import kotlin.math.roundToInt

/** A bitmap-only GIF cover. Never create or start an animated drawable for a thumbnail. */
internal class GifFirstFrameDecoder(
    private val result: SourceResult,
    private val options: Options,
    private val permits: Semaphore,
) : Decoder {
    override suspend fun decode(): DecodeResult = permits.withPermit {
        runInterruptible {
            val file = result.source.file().toFile()
            require(file.length() in 1..(36L * 1024 * 1024))
            var originalWidth = 0
            var originalHeight = 0
            val bitmap = GifFirstFrame.decode(file) { width, height ->
                originalWidth = width
                originalHeight = height
                targetSize(width, height)
            }
            bitmap.density = options.context.resources.displayMetrics.densityDpi
            DecodeResult(BitmapDrawable(options.context.resources, bitmap),
                isSampled = bitmap.width < originalWidth || bitmap.height < originalHeight)
        }
    }

    private fun targetSize(width: Int, height: Int): Pair<Int, Int> {
        require(width in 1..3840 && height in 1..3840 && width.toLong() * height <= 8_294_400)
        val targetWidth = (options.size.width as? Dimension.Pixels)?.px ?: width
        val targetHeight = (options.size.height as? Dimension.Pixels)?.px ?: height
        val widthScale = targetWidth.toDouble() / width
        val heightScale = targetHeight.toDouble() / height
        val scale = (if (options.scale == Scale.FILL) maxOf(widthScale, heightScale)
            else minOf(widthScale, heightScale)).coerceAtMost(1.0)
        return (width * scale).roundToInt().coerceAtLeast(1) to (height * scale).roundToInt().coerceAtLeast(1)
    }

    class Factory : Decoder.Factory {
        private val permits = Semaphore(2)
        override fun create(result: SourceResult, options: Options, imageLoader: ImageLoader): Decoder =
            GifFirstFrameDecoder(result, options, permits)
    }
}

/** Shared by thumbnail decoding and GIF → PNG reference import. */
internal object GifFirstFrame {
    fun decode(file: File, target: (Int, Int) -> Pair<Int, Int>): Bitmap {
        require(file.length() in 1..(36L * 1024 * 1024))
        return if (Build.VERSION.SDK_INT >= 28) decodePlatform(ImageDecoder.createSource(file), target)
        else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            validateBounds(bounds.outWidth, bounds.outHeight)
            @Suppress("DEPRECATION")
            renderMovie(requireNotNull(Movie.decodeFile(file.path)), target)
        }
    }

    fun decode(resolver: ContentResolver, uri: Uri, target: (Int, Int) -> Pair<Int, Int>): Bitmap =
        if (Build.VERSION.SDK_INT >= 28) decodePlatform(ImageDecoder.createSource(resolver, uri), target)
        else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            requireNotNull(resolver.openInputStream(uri)).use { BitmapFactory.decodeStream(it, null, bounds) }
            validateBounds(bounds.outWidth, bounds.outHeight)
            @Suppress("DEPRECATION")
            requireNotNull(resolver.openInputStream(uri)).use { renderMovie(requireNotNull(Movie.decodeStream(it)), target) }
        }

    private fun validateBounds(width: Int, height: Int) {
        require(width in 1..3840 && height in 1..3840 && width.toLong() * height <= 8_294_400)
    }

    @RequiresApi(28)
    private fun decodePlatform(source: ImageDecoder.Source, target: (Int, Int) -> Pair<Int, Int>): Bitmap =
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            validateBounds(info.size.width, info.size.height)
            val (width, height) = target(info.size.width, info.size.height)
            decoder.setTargetSize(width.coerceIn(1, info.size.width), height.coerceIn(1, info.size.height))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }

    @Suppress("DEPRECATION")
    private fun renderMovie(movie: Movie, target: (Int, Int) -> Pair<Int, Int>): Bitmap {
        val (width, height) = target(movie.width(), movie.height())
        val cover = Bitmap.createBitmap(width.coerceIn(1, movie.width()), height.coerceIn(1, movie.height()), Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(cover)
            canvas.scale(cover.width.toFloat() / movie.width(), cover.height.toFloat() / movie.height())
            movie.setTime(0)
            movie.draw(canvas, 0f, 0f)
            return cover
        } catch (error: Throwable) { cover.recycle(); throw error }
    }
}
