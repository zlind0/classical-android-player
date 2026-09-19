package com.aurora.music.ui.screens.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.music.R
import com.aurora.music.data.DuplicateGroup
import com.aurora.music.model.Song
import com.aurora.music.ui.components.Artwork
import com.aurora.music.ui.ios5.Ios5CellDivider
import com.aurora.music.ui.ios5.Ios5Colors
import com.aurora.music.ui.ios5.Ios5Empty
import com.aurora.music.ui.ios5.Ios5Loading
import com.aurora.music.ui.ios5.Ios5SettingsPage
import com.aurora.music.ui.ios5.Ios5StaticText
import com.aurora.music.ui.ios5.ios5FootNote
import com.aurora.music.ui.ios5.ios5Section

/** Result list of the duplicate scan: one card per suspected-duplicate group. */
@Composable
fun DuplicatesScreen(
    contentPadding: PaddingValues,
    loading: Boolean,
    scanned: Int,
    groups: List<DuplicateGroup>,
    currentSongId: String,
    onBack: () -> Unit,
    onPlay: (Song) -> Unit,
) {
    val strTitle = stringResource(R.string.dup_title)
    val strNone = stringResource(R.string.dup_none, scanned)
    val bottomPad = contentPadding.calculateBottomPadding()
    val dupCount = groups.sumOf { it.songs.size }
    val strStats = stringResource(R.string.dup_stats_fmt, groups.size, dupCount, scanned)

    when {
        loading -> {
            Ios5SettingsPage(title = strTitle, onBack = onBack) {
                item { Ios5Loading() }
                item { Spacer(Modifier.height(bottomPad)) }
            }
        }
        groups.isEmpty() -> {
            Ios5SettingsPage(title = strTitle, onBack = onBack) {
                item { Ios5Empty(strNone) }
                item { Spacer(Modifier.height(bottomPad)) }
            }
        }
        else -> {
            Ios5SettingsPage(title = strTitle, onBack = onBack) {
                ios5Section(strTitle) {
                    Ios5StaticText(strStats)
                }
                groups.forEach { group ->
                    ios5Section(group.title) {
                        GroupRows(group, currentSongId, onPlay)
                    }
                }
                ios5FootNote(strStats)
                item { Spacer(Modifier.height(bottomPad)) }
            }
        }
    }
}

@Composable
private fun GroupRows(group: DuplicateGroup, currentSongId: String, onPlay: (Song) -> Unit) {
    val copiesLine = stringResource(R.string.dup_copies_fmt, group.artist, group.songs.size)
    val unknownAlbum = stringResource(R.string.dup_unknown_album)
    val unknownFormat = stringResource(R.string.dup_unknown_format)
    val playingDesc = stringResource(R.string.dup_playing)
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(copiesLine, color = Ios5Colors.TextSecondary, fontSize = 13.sp)
    }
    Ios5CellDivider()
    group.songs.forEachIndexed { i, s ->
        Row(
            Modifier.fillMaxWidth().clickable { onPlay(s) }.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(s.artworkUrl, s.accent, Modifier.size(40.dp), corner = 6.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(s.album.ifBlank { unknownAlbum }, color = Ios5Colors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(specLine(s, unknownFormat), color = Ios5Colors.TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (s.id == currentSongId) {
                Text("♪", color = Ios5Colors.IosBlue, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp))
            } else {
                Text(playingDesc, color = Ios5Colors.TextSecondary, fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(start = 6.dp))
            }
        }
        if (i < group.songs.size - 1) Ios5CellDivider()
    }
}

private fun specLine(s: Song, unknownFormat: String): String {
    val parts = mutableListOf<String>()
    if (s.suffix.isNotBlank()) parts += s.suffix.uppercase()
    if (s.bitrateKbps > 0) parts += "${s.bitrateKbps} kbps"
    if (s.durationSec > 0) parts += "%d:%02d".format(s.durationSec / 60, s.durationSec % 60)
    return parts.joinToString(" • ").ifBlank { unknownFormat }
}
