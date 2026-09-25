package com.aurora.music

import android.app.Application
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
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
        // 切后台弹悬浮窗、回前台藏（播放前台服务不影响这里，只看 Activity 是否都在后台）
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                container.floatingWindow.onAppForegrounded()
            }

            override fun onStop(owner: LifecycleOwner) {
                container.floatingWindow.onAppBackgrounded()
            }
        })
    }
}
