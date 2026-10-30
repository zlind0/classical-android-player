package com.aurora.music.data

import com.aurora.music.model.Song

/**
 * 专辑内曲目标准序：本地 MEDIA/FILE 两套库与远程资料库共用这一份，
 * 避免 UI/扫描/远程三处各写一个比较器导致顺序不一致。
 *
 * pathFirst = true：FILE 栈的历史行为，按完整路径排序（文件名通常自带曲号）。
 * pathFirst = false：MEDIA 栈，碟号 → 曲号 → 标题 → 文件名；缺号沉底。
 */
fun albumTrackComparator(pathFirst: Boolean): Comparator<Song> =
    if (pathFirst) {
        compareBy<Song> { it.path.lowercase() }
    } else {
        compareBy<Song> { if (it.discNumber == 0) Int.MAX_VALUE else it.discNumber }
            .thenBy { if (it.trackNumber == 0) Int.MAX_VALUE else it.trackNumber }
            .thenBy { it.title.lowercase() }
            .thenBy { it.path.substringAfterLast('/').lowercase() }
    }
