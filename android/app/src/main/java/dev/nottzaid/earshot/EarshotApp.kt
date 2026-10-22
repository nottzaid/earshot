package dev.nottzaid.earshot

import android.app.Application
import dev.nottzaid.earshot.data.Settings

class EarshotApp : Application() {
    lateinit var settings: Settings
        private set

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
    }
}

val android.content.Context.settings: Settings get() = (applicationContext as EarshotApp).settings
