package net.pocketnai

import android.app.Application
import net.pocketnai.di.AppContainer

class PocketNaiApplication : Application(), coil.ImageLoaderFactory {

    override fun newImageLoader(): coil.ImageLoader = coil.ImageLoader.Builder(this)
        .memoryCache { net.pocketnai.ui.safety.SfwImageMemoryCache(this) }
        .components {
            if (android.os.Build.VERSION.SDK_INT >= 28) add(coil.decode.ImageDecoderDecoder.Factory())
            else add(coil.decode.GifDecoder.Factory())
            add(coil.decode.SvgDecoder.Factory(useViewBoundsAsIntrinsicSize = false))
        }
        .build()

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
