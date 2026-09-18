package com.aurora.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aurora.music.R
import com.aurora.music.data.ThemeStyle
import com.aurora.music.ui.theme.LocalUiPrefs

// iOS 5 glossy blue navigation bar. Used directly by new shell screens;
// SettingsTopBar delegates here when the iOS theme is active.
@Composable
fun GlossyNavBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(
        Modifier.fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF9DB9D9),
                        Color(0xFF6E97C4),
                        Color(0xFF4A76B0),
                        Color(0xFF3A639C),
                    )
                )
            )
            // hairline instead of shadow: shadow() forces an offscreen layer per bar
            .padding(top = topInset),
    ) {
        // gloss highlight over the top half
        Box(
            Modifier.fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.35f),
                            Color.White.copy(alpha = 0.05f),
                        )
                    )
                )
                .padding(horizontal = 8.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) {
                    Row(
                        Modifier.clip(RoundedCornerShape(6.dp))
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color(0xFF6E97C4), Color(0xFF3A639C), Color(0xFF2E4F80))
                                )
                            )
                            .clickable(onClick = onBack)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back),
                            tint = Color.White, modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                } else {
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                actions()
                if (onBack != null) Spacer(Modifier.width(8.dp))
            }
        }
        // bottom hairline for separation (no shadow layer)
        Box(
            Modifier.fillMaxWidth().height(1.dp)
                .background(Color.Black.copy(alpha = 0.35f))
                .align(Alignment.BottomCenter),
        )
    }
}

@Composable
fun isIosTheme(): Boolean = LocalUiPrefs.current.themeStyle == ThemeStyle.IOS
