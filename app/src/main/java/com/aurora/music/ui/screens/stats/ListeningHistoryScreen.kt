package com.aurora.music.ui.screens.stats

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.R
import com.aurora.music.AuroraApplication
import com.aurora.music.ui.components.Artwork
import com.aurora.music.ui.ios5.Ios5CellDivider
import com.aurora.music.ui.ios5.Ios5Colors
import com.aurora.music.ui.ios5.Ios5Empty
import com.aurora.music.ui.ios5.Ios5SettingsPage
import com.aurora.music.ui.ios5.ios5Section
import com.aurora.music.util.accentFor
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun ListeningHistoryScreen(contentPadding: PaddingValues, onBack: () -> Unit, onPlay: (String) -> Unit) {
    val container = (LocalContext.current.applicationContext as AuroraApplication).container
    val context = LocalContext.current
    val history by container.playHistory.history.collectAsStateWithLifecycle()
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    val strTitle = stringResource(R.string.history_title)
    val strEmpty = stringResource(R.string.history_empty)
    val strToday = context.getString(R.string.hist_today)
    val strYesterday = context.getString(R.string.hist_yesterday)
    val bottomPad = contentPadding.calculateBottomPadding()

    if (history.isEmpty()) {
        Ios5SettingsPage(title = strTitle, onBack = onBack) {
            item { Ios5Empty(strEmpty) }
            item { Spacer(Modifier.height(bottomPad)) }
        }
        return
    }
    val grouped = history.groupBy { dayLabel(it.timestamp, strToday, strYesterday) }
    Ios5SettingsPage(title = strTitle, onBack = onBack) {
        grouped.forEach { (day, events) ->
            ios5Section(day) {
                events.forEachIndexed { i, e ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPlay(e.songId) }.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Artwork(e.artworkUrl, accentFor(e.songId), Modifier.size(44.dp), corner = 6.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.title, color = Ios5Colors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(e.artist, color = Ios5Colors.TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(timeFmt.format(Date(e.timestamp)), color = Ios5Colors.TextSecondary, fontSize = 13.sp)
                    }
                    if (i < events.size - 1) Ios5CellDivider()
                }
            }
        }
        item { Spacer(Modifier.height(bottomPad)) }
    }
}

private fun dayLabel(ts: Long, today: String, yesterdayLabel: String): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = ts }
    val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    now.add(Calendar.DAY_OF_YEAR, -1)
    val yesterday = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    return when {
        sameDay -> today
        yesterday -> yesterdayLabel
        else -> SimpleDateFormat("EEEE, MMM d", Locale.getDefault()).format(Date(ts))
    }
}
