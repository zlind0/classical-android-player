package com.aurora.music

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.data.UiPrefs
import com.aurora.music.data.ebook.isEbookFile
import com.aurora.music.ui.AuroraApp
import com.aurora.music.ui.theme.AuroraTheme

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(com.aurora.music.util.AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as AuroraApplication).container
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
        handleEbookIntent(intent)
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
