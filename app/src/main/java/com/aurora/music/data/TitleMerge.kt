package com.aurora.music.data

import com.aurora.music.model.Song
import com.aurora.titlemerge.MergeInput
import com.aurora.titlemerge.MergedRow
import com.aurora.titlemerge.mergeTracks
import com.aurora.titlemerge.mergedTitleOf as libLookup
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 标题合并的 app 侧薄适配。真正的分组计算在 :lib-titlemerge，
 * 由 LocalLibrary 扫描时预计算好存在 albumMerges 里；
 * UI 只做查表 + 映射，不跑任何分词。
 */

// UI 行：带 Song（封面/高亮用），index 永远是扁平原序号
data class MergedItem(val song: Song, val index: Int, val minor: String)

sealed interface AlbumRow {
    data class Single(val song: Song, val index: Int) : AlbumRow
    data class Group(val major: String, val items: List<MergedItem>) : AlbumRow
}

/** 库预计算行 + 曲目表 → UI 行。纯映射，无字符串计算。 */
fun mapMergeRows(lib: List<MergedRow>?, songs: List<Song>): List<AlbumRow> {
    if (lib == null) return songs.mapIndexed { i, s -> AlbumRow.Single(s, i) }
    return lib.map { row ->
        when (row) {
            is MergedRow.Single -> AlbumRow.Single(songs[row.index], row.index)
            is MergedRow.Group -> AlbumRow.Group(
                row.major,
                row.items.map { MergedItem(songs[it.index], it.index, it.minor) },
            )
        }
    }
}

/** 预计算表里查某首歌的（大标题，小标题），没被合并返回 null。 */
fun lookupMergedTitle(
    lib: List<MergedRow>?,
    songs: List<Song>,
    songId: String,
): Pair<String, String>? {
    if (lib == null) return null
    return libLookup(lib, songs.map { it.id }, songId)
}

/** 按曲库开关判定这批曲目允不允许合并：全部落在开了开关的库里才并。 */
fun mergeEnabledFor(roots: List<MusicRoot>, tracks: List<Song>): Boolean {
    if (roots.isEmpty()) return true
    val enabled = roots.filter { it.mergeTitles }
        .map { it.rootPath.trimEnd('/') }
        .filter { it.isNotBlank() }
    return tracks.all { t ->
        t.path.isNotBlank() && enabled.any { r -> t.path == r || t.path.startsWith("$r/") }
    }
}

// ---- 合并表落盘：MergedRow 是 sealed interface，Gson 直存多态反序列化不可靠，
// 用自有 DTO 中转，深扫时算好存 JSON，启动只读表不再分词。 ----

private data class StoredMergeItem(val index: Int, val minor: String)
private data class StoredMergeRow(
    val type: String, // "s" | "g"
    val index: Int = -1,
    val major: String = "",
    val items: List<StoredMergeItem> = emptyList(),
)

private val mergeGson = Gson()
private val storedRowsType = object : TypeToken<List<StoredMergeRow>>() {}.type

/** 深扫时：同一专辑标准序 → JSON（与 albumTracksSorted 同一份顺序，序号对齐）。 */
fun buildMergeJson(songsInAlbumOrder: List<Song>): String {
    val rows = mergeTracks(songsInAlbumOrder.map { MergeInput(it.id, it.title) })
    return mergeGson.toJson(rows.map { r ->
        when (r) {
            is MergedRow.Single -> StoredMergeRow("s", index = r.index)
            is MergedRow.Group -> StoredMergeRow(
                "g", major = r.major,
                items = r.items.map { StoredMergeItem(it.index, it.minor) },
            )
        }
    })
}

/** 启动时：JSON → MergedRow，坏行直接丢弃（回落为不合并）。 */
fun parseMergeJson(json: String): List<MergedRow> = runCatching {
    if (json.isBlank()) return emptyList()
    val stored: List<StoredMergeRow> = mergeGson.fromJson(json, storedRowsType) ?: return emptyList()
    stored.mapNotNull { r ->
        when (r.type) {
            "s" -> if (r.index >= 0) MergedRow.Single(r.index) else null
            "g" -> MergedRow.Group(r.major, r.items.filter { it.index >= 0 }
                .map { com.aurora.titlemerge.MergedItem(it.index, it.minor) })
            else -> null
        }
    }
}.getOrDefault(emptyList())
