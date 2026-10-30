package com.aurora.music.playback

/**
 * 最近一次写入 UI / 音乐快照的进度样本。
 *
 * [zeroSinceMs] 记录本次连续 0 位置首次被观察到的时刻（-1 = 当前不是 0）。
 */
data class PositionState(
    val mediaId: String = "",
    val positionMs: Long = 0L,
    val zeroSinceMs: Long = -1L,
)

/**
 * 进度防抖：在播且仍是同一曲时，单次 [positionMs] == 0 不落 UI，
 * 只有连续 0 持续 [windowMs] 才接受。用户真正把进度拖到 0 时仍然生效，
 * 而 seek/缓冲、双播切换会话 player 造成的瞬时 0 不会让进度闪回 0:00。
 *
 * @return 新的进度样本；null 表示保持上一次显示位置不动。
 */
fun settlePosition(
    mediaId: String,
    positionMs: Long,
    playing: Boolean,
    previous: PositionState?,
    nowMs: Long,
    windowMs: Long = 300L,
): PositionState? {
    if (mediaId.isBlank()) return null
    val pos = positionMs.coerceAtLeast(0L)
    // 暂停/换曲/本来就在 0：直接接受（换曲时的 0 是正确的“从头开始”）
    if (!playing || pos > 0L || previous == null ||
        previous.mediaId != mediaId || previous.positionMs <= 0L
    ) {
        return PositionState(mediaId, pos, -1L)
    }
    val since = if (previous.zeroSinceMs >= 0L) previous.zeroSinceMs else nowMs
    if (nowMs - since >= windowMs) return PositionState(mediaId, 0L, -1L)
    // 第一次看到 0：先保持旧位置并记下起始时刻，连续 0 够久才真正落 0
    return previous.copy(zeroSinceMs = since)
}

/**
 * 选择进度来源并做防抖：
 * 1. 音乐快照 mediaId 非空 → 快照优先（双播时会话 player 是 UnifiedPlayer，位置恒 0）；
 * 2. 快照为空 → 只有会话当前曲是已知歌曲时才用会话位置；
 * 3. 否则（会话是 ebook_tts 等占位）→ null，保持 UI 不动，不再把书的占位 0 写进音乐气泡。
 */
fun resolvePosition(
    snapshotMediaId: String,
    snapshotPositionMs: Long,
    snapshotPlaying: Boolean,
    sessionMediaId: String?,
    sessionPositionMs: Long,
    sessionPlaying: Boolean,
    knownMediaIds: Set<String>,
    previous: PositionState?,
    nowMs: Long,
    windowMs: Long = 300L,
): PositionState? {
    val useSnapshot = snapshotMediaId.isNotBlank()
    if (!useSnapshot && (sessionMediaId == null || sessionMediaId !in knownMediaIds)) return null
    val mediaId = if (useSnapshot) snapshotMediaId else sessionMediaId.orEmpty()
    val positionMs = if (useSnapshot) snapshotPositionMs else sessionPositionMs
    val playing = if (useSnapshot) snapshotPlaying else sessionPlaying
    return settlePosition(mediaId, positionMs, playing, previous, nowMs, windowMs)
}

/**
 * 会话 player 换曲时是否应该把进度清 0：
 * 只有新曲是已知歌曲、且不是当前展示的这首歌时才清；
 * ebook_tts（朗读占位）和同一曲的 seek/缓冲一律不清。
 */
fun shouldResetPositionOnTransition(
    newMediaId: String?,
    shownMediaId: String,
    knownMediaIds: Set<String>,
): Boolean {
    if (newMediaId.isNullOrBlank()) return false
    if (newMediaId == shownMediaId) return false
    return newMediaId in knownMediaIds
}
