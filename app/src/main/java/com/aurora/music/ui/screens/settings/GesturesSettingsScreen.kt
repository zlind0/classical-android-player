package com.aurora.music.ui.screens.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.R
import com.aurora.music.data.GesturePrefs
import com.aurora.music.data.PlaybackPrefs
import com.aurora.music.ui.ios5.Ios5CellDivider
import com.aurora.music.ui.ios5.Ios5SettingsPage
import com.aurora.music.ui.ios5.Ios5SwitchRow
import com.aurora.music.ui.ios5.ios5Section
import kotlinx.coroutines.launch

@Composable
fun GesturesSettingsScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val container = (LocalContext.current.applicationContext as AuroraApplication).container
    val store = container.settingsStore
    val gestures by store.gesturePrefs.collectAsStateWithLifecycle(initialValue = GesturePrefs())
    val haptics by store.haptics.collectAsStateWithLifecycle(initialValue = false)
    val playback by store.playbackPrefs.collectAsStateWithLifecycle(initialValue = PlaybackPrefs())
    var notifications by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    val strTitle = stringResource(R.string.gestures_title)
    val strPlayerSection = stringResource(R.string.gestures_section_player)
    val strFeedbackSection = stringResource(R.string.gestures_section_feedback)
    val strBehaviourSection = stringResource(R.string.gestures_section_behaviour)

    Ios5SettingsPage(strTitle, onBack) {
        ios5Section(strPlayerSection) {
            Ios5SwitchRow(stringResource(R.string.gestures_swipe_art), stringResource(R.string.gestures_swipe_art_sub), gestures.swipeArtwork) { v -> scope.launch { store.setGestureSwipeArtwork(v) } }
            Ios5CellDivider()
            Ios5SwitchRow(stringResource(R.string.gestures_swipe_down), stringResource(R.string.gestures_swipe_down_sub), gestures.swipeDownDismiss) { v -> scope.launch { store.setGestureSwipeDismiss(v) } }
            Ios5CellDivider()
            Ios5SwitchRow(stringResource(R.string.gestures_double_tap), stringResource(R.string.gestures_double_tap_sub), gestures.doubleTapPause) { v -> scope.launch { store.setGestureDoubleTap(v) } }
        }

        ios5Section(strFeedbackSection) {
            Ios5SwitchRow(stringResource(R.string.gestures_haptics), stringResource(R.string.gestures_haptics_sub), haptics) { v -> scope.launch { store.setHaptics(v) } }
        }

        ios5Section(strBehaviourSection) {
            Ios5SwitchRow(stringResource(R.string.gestures_notifications), stringResource(R.string.gestures_notifications_sub), notifications) { notifications = it }
            Ios5CellDivider()
            Ios5SwitchRow(stringResource(R.string.gestures_autoplay), stringResource(R.string.gestures_autoplay_sub), playback.autoplayRadio) { v -> scope.launch { store.setAutoplayRadio(v) } }
        }
    }
}
