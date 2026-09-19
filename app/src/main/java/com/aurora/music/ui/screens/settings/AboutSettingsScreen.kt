package com.aurora.music.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.R
import com.aurora.music.ui.ios5.Ios5CellDivider
import com.aurora.music.ui.ios5.Ios5Colors
import com.aurora.music.ui.ios5.Ios5SettingsPage
import com.aurora.music.ui.ios5.Ios5StaticText
import com.aurora.music.ui.ios5.ios5FootNote
import com.aurora.music.ui.ios5.ios5Section

@Composable
fun AboutSettingsScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val container = (LocalContext.current.applicationContext as AuroraApplication).container
    val session by container.settingsStore.session.collectAsStateWithLifecycle(initialValue = null)

    val strTitle = stringResource(R.string.about_title)
    val strInfoSection = stringResource(R.string.about_app)
    val strFooter = stringResource(R.string.about_footer)
    val strLibraryLabel = stringResource(R.string.about_library)
    val strLibraryValue = stringResource(R.string.about_library_value)
    val strSignedInLabel = stringResource(R.string.about_signed_in)
    val strClientLabel = stringResource(R.string.about_client)
    val strClientValue = stringResource(R.string.about_app)
    val strEngineLabel = stringResource(R.string.about_engine)
    val strEngineValue = stringResource(R.string.about_engine_value)

    Ios5SettingsPage(strTitle, onBack) {
        item {
            Box(Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(88.dp).clip(CircleShape)
                        .background(Ios5Colors.glossBrush),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("♪", color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Black)
                }
            }
        }
        item {
            Text(
                stringResource(R.string.about_app),
                color = Ios5Colors.TextPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            )
        }
        item {
            Text(
                stringResource(R.string.about_version),
                color = Ios5Colors.TextSecondary,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 12.dp),
            )
        }
        ios5Section(strInfoSection) {
            InfoRow(strLibraryLabel, strLibraryValue)
            Ios5CellDivider()
            InfoRow(strSignedInLabel, session?.username ?: "—")
            Ios5CellDivider()
            InfoRow(strClientLabel, strClientValue)
            Ios5CellDivider()
            InfoRow(strEngineLabel, strEngineValue)
        }
        ios5FootNote(strFooter)
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Ios5StaticText("$label：$value")
}
