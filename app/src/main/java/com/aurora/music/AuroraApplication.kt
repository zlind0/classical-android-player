package com.aurora.music

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.aurora.music.data.AppContainer
import com.aurora.music.data.ebook.EbookDebugLog
import com.aurora.music.util.AppLocale
import java.io.File

class AuroraApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocale.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        // :llm 只承载大模型服务，不要在服务进程里初始化整个 AppContainer
        // （数据库、投屏、扫描器等都不需要，且会白白占内存）。
        if (currentProcessName() == "$packageName:llm") return
        EbookDebugLog.init(this)
        EbookDebugLog.log("APP onCreate pid=${android.os.Process.myPid()}")
        container = AppContainer(this)
        // 切后台弹悬浮窗、回前台藏（播放前台服务不影响这里，只看 Activity 是否都在后台）
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                container.floatingWindow.onAppForegrounded()
                // 回前台：取消空闲卸载计时；翻译层还开着就把模型重新加载回来。
                container.llmClient.onAppForeground()
            }

            override fun onStop(owner: LifecycleOwner) {
                container.floatingWindow.onAppBackgrounded()
                // 切出 App 不立刻卸载：30 秒内回来可以继续用同一个引擎，
                // 播放中的翻译也不会因为一次锁屏就丢掉 3-5 GB 的加载成本。
                container.llmClient.onAppBackground()
            }
        })
    }

    private fun currentProcessName(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return getProcessName()
        }
        return runCatching {
            File("/proc/self/cmdline").readText().trim('\u0000')
        }.getOrDefault("")
    }
}
