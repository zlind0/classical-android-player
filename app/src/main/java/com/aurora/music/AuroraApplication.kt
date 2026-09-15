package com.aurora.music

import android.app.Application
import android.content.Context
import com.aurora.music.data.AppContainer
import com.aurora.music.util.AppLocale

class AuroraApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocale.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
