package com.aurora.music.ui.screens.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.R
import com.aurora.music.AuroraApplication
import com.aurora.music.data.RankedItem
import com.aurora.music.ui.components.Artwork
import com.aurora.music.ui.ios5.Ios5Colors
import com.aurora.music.ui.ios5.Ios5SectionTitle
import com.aurora.music.ui.ios5.Ios5SegmentRow
import com.aurora.music.ui.ios5.Ios5SettingsPage
import com.aurora.music.ui.ios5.Ios5StaticText
import com.aurora.music.ui.ios5.ios5FootNote
import com.aurora.music.ui.ios5.ios5Rows
import com.aurora.music.ui.ios5.ios5Section
import com.aurora.music.util.accentFor

@Composable
fun ListeningStatsScreen(contentPadding: PaddingValues, onBack: () -> Unit, onPlay: (String) -> Unit, onOpenDetail: (String, String) -> Unit) {
    val store = (LocalContext.current.applicationContext as AuroraApplication).container.playHistory
    val history by store.history.collectAsStateWithLifecycle()
    var range by remember { mutableIntStateOf(0) } // 0 week 1 month 2 all

    val now = System.currentTimeMillis()
    val since = when (range) {
        0 -> now - 7L * 24 * 3600 * 1000
        1 -> now - 30L * 24 * 3600 * 1000
        else -> 0L
    }
    val events = remember(history, range) { history.filter { it.timestamp >= since } }
    val artists = remember(events) { store.topArtists(events) }
    val songs = remember(events) { store.topSongs(events) }
    val albums = remember(events) { store.topAlbums(events) }
    val minutes = remember(events) { events.sumOf { it.durationSec.toLong() } / 60 }
    val byHour = remember(events) { store.playsByHour(events) }
    val streak = remember(history) { store.streak() }

    val strTitle = stringResource(R.string.stats_title)
    val strWeek = stringResource(R.string.stats_week)
    val strMonth = stringResource(R.string.stats_month)
    val strAll = stringResource(R.string.stats_alltime)
    val strRange = stringResource(R.string.stats_title)
    val strOverview = stringResource(R.string.stats_title)
    val strPlays = stringResource(R.string.stats_plays)
    val strMinutes = stringResource(R.string.stats_minutes)
    val strArtists = stringResource(R.string.stats_artists)
    val strEmptyPeriod = stringResource(R.string.stats_empty_period)
    val strStreak = stringResource(R.string.stats_title)
    val strClock = stringResource(R.string.stats_clock)
    val strTopArtists = stringResource(R.string.stats_top_artists)
    val strTopSongs = stringResource(R.string.stats_top_songs)
    val strTopAlbums = stringResource(R.string.stats_top_albums)
    val bottomPad = contentPadding.calculateBottomPadding()
    val streakCurrent = streak.first
    val streakLongest = streak.second
    val strStreakNow = if (streakCurrent > 0) stringResource(R.string.stats_streak_fmt, streakCurrent) else stringResource(R.string.stats_no_streak)
    val strStreakLong = stringResource(R.string.stats_longest, streakLongest, if (streakLongest == 1) "" else "s")
    val strUnknown = stringResource(R.string.stats_unknown)

    Ios5SettingsPage(title = strTitle, onBack = onBack) {
        ios5Section(strRange) {
            Ios5SegmentRow(
                title = strRange,
                options = listOf(strWeek, strMonth, strAll),
                selected = range,
                onSelect = { range = it },
            )
        }
        ios5Section(strOverview) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCell("${events.size}", strPlays, Modifier.weight(1f))
                StatCell("$minutes", strMinutes, Modifier.weight(1f))
                StatCell("${artists.size}", strArtists, Modifier.weight(1f))
            }
        }
        if (events.isEmpty()) {
            item { Ios5StaticText(strEmptyPeriod) }
        }
        if (streakLongest > 0) {
            ios5Section(strStreak) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Text(strStreakNow, color = Ios5Colors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(strStreakLong, color = Ios5Colors.TextSecondary, fontSize = 13.sp)
                }
            }
        }
        if (events.isNotEmpty()) {
            ios5Section(strClock) {
                ListeningClockIos5(byHour)
            }
        }
        if (artists.isNotEmpty()) {
            item { Ios5SectionTitle(strTopArtists) }
            ios5Rows(artists) { i, a ->
                RankRowIos5(i + 1, a, circle = true, unknown = strUnknown) { if (a.id.isNotBlank()) onOpenDetail("artist", a.id) }
            }
        }
        if (songs.isNotEmpty()) {
            item { Ios5SectionTitle(strTopSongs) }
            ios5Rows(songs) { i, s ->
                RankRowIos5(i + 1, s, circle = false, unknown = strUnknown) { onPlay(s.id) }
            }
        }
        if (albums.isNotEmpty()) {
            item { Ios5SectionTitle(strTopAlbums) }
            ios5Rows(albums) { i, a ->
                RankRowIos5(i + 1, a, circle = false, unknown = strUnknown) { if (a.id.isNotBlank()) onOpenDetail("album", a.id) }
            }
        }
        if (events.isEmpty()) {
            ios5FootNote(strEmptyPeriod)
        }
        item { Spacer(Modifier.height(bottomPad)) }
    }
}

@Composable
private fun ListeningClockIos5(byHour: IntArray) {
    val max = (byHour.maxOrNull() ?: 0).coerceAtLeast(1)
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth().height(80.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (h in 0 until 24) {
                val frac = (byHour[h].toFloat() / max).coerceIn(0.03f, 1f)
                Box(
                    Modifier.weight(1f).fillMaxHeight(frac).clip(RoundedCornerShape(3.dp))
                        .background(if (byHour[h] > 0) Ios5Colors.IosBlue else Color.Black.copy(alpha = 0.12f)),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("12a", "6a", "12p", "6p", "11p").forEach {
                Text(it, color = Ios5Colors.TextSecondary, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun StatCell(value: String, label: String, modifier: Modifier) {
    Column(modifier.padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Ios5Colors.IosBlue, fontSize = 22.sp, fontWeight = FontWeight.Black)
        Text(label, color = Ios5Colors.TextSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun RankRowIos5(rank: Int, item: RankedItem, circle: Boolean, unknown: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$rank", color = Ios5Colors.TextSecondary, fontSize = 16.sp, fontWeight = FontWeight.Black, modifier = Modifier.width(28.dp))
        Artwork(item.artworkUrl, accentFor(item.id.ifBlank { item.name }), Modifier.size(44.dp), corner = if (circle) 44.dp else 6.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name.ifBlank { unknown }, color = Ios5Colors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.subtitle, color = Ios5Colors.TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.clip(RoundedCornerShape(50)).background(Ios5Colors.IosBlue.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 4.dp)) {
            Text("${item.count}×", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Ios5Colors.IosBlue)
        }
    }
}
