package com.aurora.music

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.data.UiPrefs
import com.aurora.music.ui.AuroraApp
import com.aurora.music.ui.theme.AuroraTheme

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(com.aurora.music.util.AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as AuroraApplication).container
        setContent {
            val uiPrefs by container.settingsStore.uiPrefs.collectAsStateWithLifecycle(initialValue = UiPrefs())
            AuroraTheme(uiPrefs = uiPrefs) {
                AuroraApp()
            }
        }
    }
}
