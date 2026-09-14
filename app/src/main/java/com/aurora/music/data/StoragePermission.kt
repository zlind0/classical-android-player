package com.aurora.music.data

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat

// Classical fork v0.3: storage access gating (plan §64-65).
//
// The fork reads user-picked directories with the plain File API (plan §3.1).
// That needs:
// - API ≤ 29: READ_EXTERNAL_STORAGE (runtime).
// - API 33+: READ_MEDIA_AUDIO (runtime) for media, but directory browsing and
//   non-media files additionally need MANAGE_EXTERNAL_STORAGE on API 30+.
// - API 30+: MANAGE_EXTERNAL_STORAGE ("All files access", via system settings)
//   for unrestricted File listing/reading of shared storage (scoped storage).
fun storageReadPermission(): String =
    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
    else Manifest.permission.READ_EXTERNAL_STORAGE

fun hasStorageRead(ctx: Context): Boolean =
    ContextCompat.checkSelfPermission(ctx, storageReadPermission()) ==
        PackageManager.PERMISSION_GRANTED

fun hasAllFilesAccess(ctx: Context): Boolean =
    if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else hasStorageRead(ctx)

/** True when the scanner is allowed to walk user-picked directories. */
fun canScanStorage(ctx: Context): Boolean = hasAllFilesAccess(ctx)

fun needsAllFilesRow(): Boolean = Build.VERSION.SDK_INT >= 30

/** Opens the system "All files access" page for this app. Returns false if unavailable. */
fun openAllFilesSettings(ctx: Context): Boolean = runCatching {
    val intent = if (Build.VERSION.SDK_INT >= 30) {
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + ctx.packageName))
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))
    }
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    ctx.startActivity(intent)
    true
}.getOrDefault(false)
