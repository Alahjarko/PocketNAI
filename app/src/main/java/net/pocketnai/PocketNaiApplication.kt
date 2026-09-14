package net.pocketnai

import android.app.Application
import net.pocketnai.di.AppContainer

class PocketNaiApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
