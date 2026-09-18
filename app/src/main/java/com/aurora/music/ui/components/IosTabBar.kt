package com.aurora.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.music.R
import com.aurora.music.navigation.Routes
import com.aurora.music.navigation.topLevelDestinations

// iOS 5 style metal tab bar: black brushed gradient, blue glow on selection,
// full-bleed into the system gesture area (no inset gap).
@Composable
fun IosTabBar(selectedRoute: String?, onNavigate: (String) -> Unit) {
    // hoisted: these were rebuilt on every bottom-bar recomposition (5x/s ticker)
    val barBrush = remember {
        Brush.verticalGradient(
            listOf(
                Color(0xFF5A5E63),
                Color(0xFF2E3134),
                Color(0xFF17181A),
                Color(0xFF0B0C0D),
            )
        )
    }
    val glowBrush = remember {
        Brush.radialGradient(
            listOf(Color(0xFF5EB9F5), Color(0xFF1C7FE0).copy(alpha = 0.55f), Color.Transparent),
            radius = 90f,
        )
    }
    val idleBrush = remember {
        Brush.radialGradient(listOf(Color.Transparent, Color.Transparent))
    }
    val hairline = remember { Color.White.copy(alpha = 0.22f) }
    Column(
        Modifier.fillMaxWidth()
            .background(barBrush)
            .navigationBarsPadding()
            .padding(top = 5.dp, bottom = 4.dp),
    ) {
        // top hairline highlight
        Box(Modifier.fillMaxWidth().height(1.dp).background(hairline))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            topLevelDestinations.forEach { dest ->
                val selected = dest.route == selectedRoute
                Column(
                    Modifier.weight(1f).clickable { onNavigate(dest.route) }.padding(vertical = 3.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .background(if (selected) glowBrush else idleBrush)
                            .padding(horizontal = 14.dp, vertical = 2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (selected) dest.selectedIcon else dest.unselectedIcon,
                            contentDescription = null,
                            tint = if (selected) Color(0xFFBFE3FF) else Color(0xFF9AA0A6),
                            modifier = Modifier.size(27.dp),
                        )
                    }
                    Text(
                        tabLabel(dest.route),
                        fontSize = 10.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) Color(0xFFBFE3FF) else Color(0xFF9AA0A6),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun tabLabel(route: String): String = stringResource(
    when (route) {
        Routes.PLAYLISTS -> R.string.tab_playlists
        Routes.ARTISTS -> R.string.tab_artists
        Routes.MORE -> R.string.tab_more
        Routes.SETTINGS -> R.string.tab_settings
        else -> R.string.tab_home
    }
)
