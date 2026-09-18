package com.aurora.music.ui.screens.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aurora.music.R
import com.aurora.music.model.LibraryFilter

// iOS-style More tab: browse the library by category.
@Composable
fun MoreScreen(
    contentPadding: PaddingValues,
    onBrowse: (LibraryFilter) -> Unit,
    onOpenFolders: () -> Unit,
) {
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val rows = moreRows()
    Column(Modifier.fillMaxSize()) {
        if (com.aurora.music.ui.components.isIosTheme()) {
            com.aurora.music.ui.components.GlossyNavBar(title = stringResource(R.string.more_title))
        } else {
            Text(
                stringResource(R.string.more_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp + topInset, bottom = 8.dp),
            )
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
        ) {
            items(rows) { row ->
                Row(
                    Modifier.fillMaxWidth().clickable {
                        if (row.filter == null) onOpenFolders() else onBrowse(row.filter)
                    }.padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(row.icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.width(14.dp))
                    Text(
                        row.label(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private data class MoreRow(val icon: ImageVector, val label: @Composable () -> String, val filter: LibraryFilter?)

@Composable
private fun moreRows(): List<MoreRow> = listOf(
    MoreRow(Icons.Filled.MusicNote, { stringResource(R.string.more_songs) }, LibraryFilter.SONGS),
    MoreRow(Icons.Filled.Album, { stringResource(R.string.more_albums) }, LibraryFilter.ALBUMS),
    MoreRow(Icons.Filled.Category, { stringResource(R.string.more_genres) }, LibraryFilter.GENRES),
    MoreRow(Icons.Filled.Mic, { stringResource(R.string.more_composers) }, LibraryFilter.COMPOSERS),
    MoreRow(Icons.Filled.Folder, { stringResource(R.string.more_folders) }, null),
)
