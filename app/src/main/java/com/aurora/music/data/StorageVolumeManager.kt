package com.aurora.music.data

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File

// Classical fork v0.3 (plan §5): discovers the storage volumes the folder picker
// and scanner may use. Business layers never touch raw vendor paths directly.
//
// Primary discovery is StorageManager.getStorageVolumes() (API 24+ = minSdk):
// it lists every mounted volume the system knows, including USB-OTG on pads
// that never shows up in Context.getExternalFilesDirs(). The getExternalFilesDirs
// mapping is kept as a fallback for odd vendor ROMs.
data class StorageVolume(
    val id: String,          // stable key: "internal" or the volume root path
    val label: String,       // display label
    val rootPath: String,    // public root, e.g. /storage/emulated/0 or /storage/XXXX-XXXX
    val type: StorageType,
    val available: Boolean,  // false when the directory is currently unreachable
)

class StorageVolumeManager(private val context: Context) {

    fun volumes(): List<StorageVolume> {
        val out = LinkedHashMap<String, StorageVolume>()
        fun add(v: StorageVolume) {
            if (v.rootPath.isBlank()) return
            if (out.values.any { it.rootPath == v.rootPath }) return
            out[v.id] = v
        }
        // Primary shared storage. Deprecated upstream but still
        // functional on the API 24+ range this fork targets.
        @Suppress("DEPRECATION")
        val primary = Environment.getExternalStorageDirectory()?.absolutePath
        if (!primary.isNullOrBlank()) {
            add(
                StorageVolume(
                    id = "internal",
                    label = "Internal Storage",
                    rootPath = primary,
                    type = StorageType.INTERNAL,
                    available = File(primary).isDirectory,
                )
            )
        }
        // All system-known volumes, including USB-OTG. Unmounted volumes are
        // useless for scanning and only add clutter, so they are skipped.
        runCatching {
            val sm = context.getSystemService(StorageManager::class.java) ?: return@runCatching
            for (vol in sm.storageVolumes) {
                val state = runCatching { vol.state }.getOrNull() ?: continue
                if (state != Environment.MEDIA_MOUNTED) continue
                val isPrimary = runCatching { vol.isPrimary }.getOrDefault(false)
                if (isPrimary) continue // already added above
                val root = volumeRoot(vol) ?: continue
                val removable = runCatching { vol.isRemovable }.getOrDefault(true)
                // USB-OTG typically mounts under /mnt/media_rw or contains "usb";
                // anything else removable is treated as an SD card. Advisory only.
                val looksUsb = root.contains("usb", ignoreCase = true) || root.startsWith("/mnt/media_rw")
                val type = if (!removable) StorageType.INTERNAL
                else if (looksUsb) StorageType.USB else StorageType.SD_CARD
                val label = runCatching { vol.getDescription(context) }.getOrNull()?.takeIf { it.isNotBlank() }
                    ?: when (type) {
                        StorageType.SD_CARD -> "SD Card"
                        StorageType.USB -> "USB Storage"
                        StorageType.INTERNAL -> "Internal Storage"
                    }
                add(
                    StorageVolume(
                        id = root, label = label, rootPath = root,
                        type = type, available = File(root).isDirectory,
                    )
                )
            }
        }
        // Fallback: app-specific dirs (no permission needed to *list* these paths).
        // Each entry looks like /storage/XXXX-XXXX/Android/data/<package>/files,
        // so walking up past the Android/data segment yields the volume root.
        val pkg = context.packageName
        val dirs = runCatching { context.getExternalFilesDirs(null) }.getOrNull()
        dirs?.forEach { dir ->
            val abs = dir?.absolutePath ?: return@forEach
            val marker = "/Android/data/$pkg/files"
            val root = if (abs.endsWith(marker)) abs.dropLast(marker.length) else return@forEach
            if (root.isBlank() || out.values.any { it.rootPath == root }) return@forEach
            val removable = runCatching {
                if (Build.VERSION.SDK_INT >= 21) Environment.isExternalStorageRemovable(File(root)) else true
            }.getOrDefault(true)
            val looksUsb = root.contains("usb", ignoreCase = true) || root.startsWith("/mnt/media_rw")
            val type = if (!removable) StorageType.INTERNAL
            else if (looksUsb) StorageType.USB else StorageType.SD_CARD
            val label = when (type) {
                StorageType.SD_CARD -> "SD Card"
                StorageType.USB -> "USB Storage"
                StorageType.INTERNAL -> "Internal Storage"
            }
            add(
                StorageVolume(
                    id = root, label = label, rootPath = root,
                    type = type, available = File(root).isDirectory,
                )
            )
        }
        return out.values.toList()
    }

    /** Public root of a StorageManager volume across API levels. */
    private fun volumeRoot(vol: android.os.storage.StorageVolume): String? = runCatching {
        if (Build.VERSION.SDK_INT >= 30) {
            vol.directory?.absolutePath?.takeIf { it.isNotBlank() }
        } else {
            // Pre-30 has no public directory accessor; removable volumes
            // mount at /storage/<uuid> on the phones/pads this fork targets.
            vol.uuid?.takeIf { it.isNotBlank() }?.let { "/storage/$it" }
        }
    }.getOrNull()

    /** Lists immediate subdirectories of [dirPath] for the folder picker. Null on error. */
    fun listDirs(dirPath: String): List<File>? = runCatching {
        val dir = File(dirPath)
        if (!dir.isDirectory || !dir.canRead()) return null
        dir.listFiles { f -> runCatching { f.isDirectory && !f.isHidden }.getOrDefault(false) }
            ?.sortedBy { it.name.lowercase() }
    }.getOrNull()
}
