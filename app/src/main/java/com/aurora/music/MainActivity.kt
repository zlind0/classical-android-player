package com.aurora.music

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.data.UiPrefs
import com.aurora.music.data.ebook.EbookDebugLog
import com.aurora.music.data.ebook.isEbookFile
import com.aurora.music.ui.AuroraApp
import com.aurora.music.ui.theme.AuroraTheme

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(com.aurora.music.util.AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        EbookDebugLog.log(
            "ACT onCreate hasState=${savedInstanceState != null} " +
                "task=${taskId} instance=${System.identityHashCode(this)}",
        )
        // 导航条永远透明：iOS5 底部 chrome（tab 渐变）直接铺到屏幕最底边。
        // 不显式指定时 activity 1.9.x 默认浅色外观是 90% 白遮罩，会盖住深色 tab 条，
        // 让底部三个键下面整条发白、和上面的渐变断开。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        super.onCreate(savedInstanceState)
        val container = (application as AuroraApplication).container
        handleRelayIntent(intent)
        handleEbookIntent(intent)
        setContent {
            val uiPrefs by container.settingsStore.uiPrefs.collectAsStateWithLifecycle(initialValue = UiPrefs())
            AuroraTheme(uiPrefs = uiPrefs) {
                AuroraApp()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleRelayIntent(intent)
        handleEbookIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        EbookDebugLog.log("ACT onStart instance=${System.identityHashCode(this)}")
    }

    override fun onResume() {
        super.onResume()
        EbookDebugLog.log("ACT onResume instance=${System.identityHashCode(this)}")
    }

    override fun onPause() {
        EbookDebugLog.log(
            "ACT onPause instance=${System.identityHashCode(this)} " +
                "changingConfig=${isChangingConfigurations} finishing=${isFinishing}",
        )
        super.onPause()
    }

    override fun onStop() {
        EbookDebugLog.log(
            "ACT onStop instance=${System.identityHashCode(this)} " +
                "changingConfig=${isChangingConfigurations} finishing=${isFinishing}",
        )
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        EbookDebugLog.log("ACT onSaveInstanceState instance=${System.identityHashCode(this)}")
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        EbookDebugLog.log(
            "ACT onDestroy instance=${System.identityHashCode(this)} " +
                "changingConfig=${isChangingConfigurations} finishing=${isFinishing} destroyed=${isDestroyed}",
        )
        super.onDestroy()
    }

    /** 公网中继配对码：podrelay://pair?...（系统相机扫码后跳进来）。 */
    private fun handleRelayIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val data = intent.dataString ?: return
        if (!data.startsWith("podrelay://pair", ignoreCase = true)) return
        (application as AuroraApplication).container.relayService.acceptPairUri(data)
    }

    /** 文件关联打开 epub/mobi/azw/azw3：拷入默认书架（md5+大小去重）后在阅读页打开。 */
    private fun handleEbookIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        val name = queryDisplayName(uri) ?: uri.lastPathSegment.orEmpty()
        if (!isEbookFile(name)) {
            // 部分文件管理器不带扩展名/类型：仍尝试按内容导入，失败会在 UI 提示
        }
        val container = (application as AuroraApplication).container
        container.ebookStore.handleOpenUri(uri, name.ifBlank { null })
    }

    private fun queryDisplayName(uri: android.net.Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
}
