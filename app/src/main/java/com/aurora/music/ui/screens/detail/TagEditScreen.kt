package com.aurora.music.ui.screens.detail

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.music.R
import com.aurora.music.AuroraApplication
import com.aurora.music.data.AudioTags
import com.aurora.music.data.remote.MetadataMatch
import com.aurora.music.ui.components.Artwork
import com.aurora.music.ui.ios5.Ios5CellDivider
import com.aurora.music.ui.ios5.Ios5Colors
import com.aurora.music.ui.ios5.Ios5GlossButton
import com.aurora.music.ui.ios5.Ios5Loading
import com.aurora.music.ui.ios5.Ios5SettingsPage
import com.aurora.music.ui.ios5.Ios5StaticText
import com.aurora.music.ui.ios5.Ios5TextRow
import com.aurora.music.ui.ios5.ios5FootNote
import com.aurora.music.ui.ios5.ios5Section
import com.aurora.music.viewmodel.TagEditState
import kotlinx.coroutines.launch

// saving a local file goes through the mediastore write-consent dialog on android 11+
@Composable
fun TagEditScreen(
    contentPadding: PaddingValues,
    state: TagEditState,
    onEdit: ((AudioTags) -> AudioTags) -> Unit,
    onMatch: () -> Unit,
    onApplyMatch: (MetadataMatch) -> Unit,
    onIdentify: (() -> Unit)?,        // null when acoustid identify unavailable
    identifying: Boolean = false,
    onBack: () -> Unit,
    confirm: (String) -> Unit,
) {
    val container = (LocalContext.current.applicationContext as AuroraApplication).container
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    // hoisted: stringResource() is @Composable and can't be called inside the plain lambdas below
    val msgNoWrite = stringResource(R.string.tags_no_write)
    val msgSaved = stringResource(R.string.tags_saved)
    val msgSaveFailed = stringResource(R.string.tags_save_failed)
    val msgNoPermission = stringResource(R.string.tags_no_permission)
    val msgUpdated = stringResource(R.string.tags_updated)
    val msgUpdateFailed = stringResource(R.string.tags_update_failed)

    val doWrite: () -> Unit = {
        scope.launch {
            val uri = container.tagEditor.contentUriFor(state.songId)
            if (uri == null) { confirm(msgNoWrite); saving = false } else {
                val art = if (state.pickedCoverUrl.isNotBlank()) container.musicBrainz.fetchImage(state.pickedCoverUrl) else null
                val ok = container.tagEditor.write(uri, state.path, state.tags, art)
                saving = false
                if (ok) {
                    confirm(msgSaved)
                    runCatching { container.localLibrary.refresh() }
                    onBack()
                } else confirm(msgSaveFailed)
            }
        }
    }
    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) doWrite() else { saving = false; confirm(msgNoPermission) }
    }
    val onSave: () -> Unit = {
        saving = true
        if (!state.localFile) {
            // server item updates via backend metadata api no file write or consent
            scope.launch {
                val ok = container.repository.updateMetadata(state.songId, state.tags)
                saving = false
                if (ok) { confirm(msgUpdated); onBack() } else confirm(msgUpdateFailed)
            }
        } else {
            val uri = container.tagEditor.contentUriFor(state.songId)
            if (uri == null) { confirm(msgNoWrite); saving = false } else {
                val consent = container.tagEditor.writeConsentIntent(uri)
                if (consent != null) consentLauncher.launch(IntentSenderRequest.Builder(consent).build()) else doWrite()
            }
        }
    }

    val strTitle = stringResource(R.string.common_edit_tags)
    val strUntitled = stringResource(R.string.tag_untitled)
    val strCoverStaged = stringResource(R.string.tags_cover_staged)
    val strMatch = stringResource(R.string.tags_match)
    val strAutoId = stringResource(R.string.tag_autoidentify)
    val strIdentifying = stringResource(R.string.tag_identifying)
    val strFTtitle = stringResource(R.string.tag_f_title)
    val strFArtist = stringResource(R.string.tag_f_artist)
    val strFAlbum = stringResource(R.string.tag_f_album)
    val strFAlbumArtist = stringResource(R.string.tag_f_albumartist)
    val strFGenre = stringResource(R.string.tag_f_genre)
    val strFYear = stringResource(R.string.tag_f_year)
    val strFTrack = stringResource(R.string.tag_f_track)
    val strSaving = stringResource(R.string.tag_saving)
    val strSaveTags = stringResource(R.string.tag_save_tags)
    val strFootnote = if (state.localFile) stringResource(R.string.tag_footnote_file) else stringResource(R.string.tag_footnote_server)
    val strMatchSection = stringResource(R.string.tags_match)
    val strFieldsSection = stringResource(R.string.common_edit_tags)
    val bottomPad = contentPadding.calculateBottomPadding()
    val isSaving = saving
    val fileName = state.path.substringAfterLast('/')

    if (state.loading) {
        Ios5SettingsPage(title = strTitle, onBack = onBack) {
            item { Ios5Loading() }
            item { Spacer(Modifier.height(bottomPad)) }
        }
        return
    }
    Ios5SettingsPage(title = strTitle, onBack = onBack) {
        ios5Section(strTitle) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(state.pickedCoverUrl.ifBlank { state.artUrl }, Ios5Colors.IosBlue, Modifier.size(56.dp), corner = 8.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(state.tags.title.ifBlank { strUntitled }, color = Ios5Colors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(fileName, color = Ios5Colors.TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (state.pickedCoverUrl.isNotBlank()) Text(strCoverStaged, color = Ios5Colors.IosBlue, fontSize = 12.sp)
                }
            }
        }
        ios5Section(strMatchSection) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Ios5GlossButton(text = strMatch, onClick = onMatch, modifier = Modifier.weight(1f))
                if (onIdentify != null) {
                    Ios5GlossButton(
                        text = if (identifying) strIdentifying else strAutoId,
                        onClick = onIdentify,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (state.matching) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Ios5StaticText(strMatch)
                }
                Ios5CellDivider()
            }
            if (identifying) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Ios5StaticText(strIdentifying)
                }
                Ios5CellDivider()
            }
            state.matchError?.let { Ios5StaticText(it) }
            state.matches.forEachIndexed { i, m ->
                MatchRow(m) { onApplyMatch(m) }
                if (i < state.matches.size - 1) Ios5CellDivider()
            }
        }
        ios5Section(strFieldsSection) {
            Ios5TextRow(title = strFTtitle, value = state.tags.title, placeholder = strFTtitle) { v -> onEdit { it.copy(title = v) } }
            Ios5CellDivider()
            Ios5TextRow(title = strFArtist, value = state.tags.artist, placeholder = strFArtist) { v -> onEdit { it.copy(artist = v) } }
            Ios5CellDivider()
            Ios5TextRow(title = strFAlbum, value = state.tags.album, placeholder = strFAlbum) { v -> onEdit { it.copy(album = v) } }
            Ios5CellDivider()
            Ios5TextRow(title = strFAlbumArtist, value = state.tags.albumArtist, placeholder = strFAlbumArtist) { v -> onEdit { it.copy(albumArtist = v) } }
            Ios5CellDivider()
            Ios5TextRow(title = strFGenre, value = state.tags.genre, placeholder = strFGenre) { v -> onEdit { it.copy(genre = v) } }
            Ios5CellDivider()
            Ios5TextRow(title = strFYear, value = state.tags.year, placeholder = strFYear) { v -> onEdit { it.copy(year = v.filter { c -> c.isDigit() }) } }
            Ios5CellDivider()
            Ios5TextRow(title = strFTrack, value = state.tags.trackNumber, placeholder = strFTrack) { v -> onEdit { it.copy(trackNumber = v.filter { c -> c.isDigit() }) } }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).padding(bottom = bottomPad)) {
                if (isSaving) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(strSaving, color = Ios5Colors.TextSecondary, fontSize = 15.sp)
                    }
                } else {
                    Ios5GlossButton(text = strSaveTags, onClick = onSave, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        ios5FootNote(strFootnote)
        item { Spacer(Modifier.height(bottomPad)) }
    }
}

@Composable
private fun MatchRow(m: MetadataMatch, onApply: () -> Unit) {
    val strApply = stringResource(R.string.tags_apply)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (m.coverUrl.isNotBlank()) {
            Artwork(m.coverUrl, Ios5Colors.IosBlue, Modifier.size(40.dp), corner = 8.dp)
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(m.title, color = Ios5Colors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(m.artist.ifBlank { null }, m.album.ifBlank { null }, m.year.ifBlank { null }).joinToString(" • "),
                color = Ios5Colors.TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            strApply, fontSize = 15.sp, color = Ios5Colors.IosBlue,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onApply).padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
}
