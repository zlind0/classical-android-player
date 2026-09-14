package com.aurora.music.data

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File

// Classical fork v0.3 (plan §5): discovers the storage volumes the folder picker
// and scanner may use. Business layers never touch raw vendor paths directly.
//
// - Primary shared storage via Environment.getExternalStorageDirectory() (plan §5.1).
// - Extra volumes (SD card / USB mass storage) derived from
//   Context.getExternalFilesDirs(null): each entry looks like
//   /storage/XXXX-XXXX/Android/data/<package>/files, so walking up past the
//   Android/data segment yields the volume's public root (plan §5.2).
// Removable vs non-removable comes from Environment.isExternalStorageRemovable();
// SD vs USB is indistinguishable from the path alone, so removable volumes that
// are not the adopted primary are reported as USB when they look like USB
// (checked via isExternalStorageRemovable + path heuristics) — the label is
// advisory only; scanning treats both identically.
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
        // Primary shared storage (plan §5.1). Deprecated upstream but still
        // functional on the API 24+ range this fork targets.
        @Suppress("DEPRECATION")
        val primary = Environment.getExternalStorageDirectory()?.absolutePath
        if (!primary.isNullOrBlank()) {
            out["internal"] = StorageVolume(
                id = "internal",
                label = "Internal Storage",
                rootPath = primary,
                type = StorageType.INTERNAL,
                available = File(primary).isDirectory,
            )
        }
        // Extra volumes via app-specific dirs (plan §5.2). No storage permission
        // needed to *list* these paths; reading them still requires the user to
        // grant READ_EXTERNAL_STORAGE / READ_MEDIA_AUDIO first.
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
            // USB-OTG typically mounts under /mnt/media_rw or contains "usb";
            // anything else removable is treated as an SD card. Advisory only.
            val looksUsb = root.contains("usb", ignoreCase = true) || root.startsWith("/mnt/media_rw")
            val type = if (!removable) StorageType.INTERNAL
            else if (looksUsb) StorageType.USB else StorageType.SD_CARD
            val label = when (type) {
                StorageType.SD_CARD -> "SD Card"
                StorageType.USB -> "USB Storage"
                StorageType.INTERNAL -> "Internal Storage"
            }
            out[root] = StorageVolume(
                id = root, label = label, rootPath = root,
                type = type, available = File(root).isDirectory,
            )
        }
        return out.values.toList()
    }

    /** Lists immediate subdirectories of [dirPath] for the folder picker. Null on error. */
    fun listDirs(dirPath: String): List<File>? = runCatching {
        val dir = File(dirPath)
        if (!dir.isDirectory || !dir.canRead()) return null
        dir.listFiles { f -> runCatching { f.isDirectory && !f.isHidden }.getOrDefault(false) }
            ?.sortedBy { it.name.lowercase() }
    }.getOrNull()
}
