package com.aurora.music.ui.overlay

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/** 悬浮窗权限（SYSTEM_ALERT_WINDOW）：跳系统设置页授权，无 launcher 回调，进设置页 onResume 重读即可。 */
fun canDrawOverlays(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= 23) Settings.canDrawOverlays(context) else true

fun openOverlaySettings(context: Context) {
    runCatching {
        val intent = if (Build.VERSION.SDK_INT >= 23) {
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + context.packageName))
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
