package net.pocketnai

import android.app.Application
import net.pocketnai.di.AppContainer

class PocketNaiApplication : Application(), coil.ImageLoaderFactory {

    override fun newImageLoader(): coil.ImageLoader = coil.ImageLoader.Builder(this)
        .memoryCache { net.pocketnai.ui.safety.SfwImageMemoryCache(this) }
        .build()

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
