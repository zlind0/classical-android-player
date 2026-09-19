package com.aurora.music.ui.ios5

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aurora.music.R

/**
 * App typeface: TeX Gyre Heros (open Helvetica-metric clone, GUST license)
 * for Latin/digits, system Noto Sans CJK fallback for Chinese (Heiti-style).
 * This mirrors the real iOS5 split: Helvetica(Neue) + STHeiti SC.
 */
val Ios5Sans: FontFamily = FontFamily(
    Font(R.font.heros_regular, FontWeight.Normal),
    Font(R.font.heros_bold, FontWeight.Bold),
)

/**
 * iOS5 skeuomorphic tokens. Fixed light look, does not follow system dark mode.
 */
object Ios5Colors {
    // linen background base
    val LinenTop = Color(0xFFD9DCE1)
    val LinenBottom = Color(0xFFB9BDC6)
    // brushed-metal nav bar
    val MetalTop = Color(0xFF8E99AB)
    val MetalMid = Color(0xFF6B7689)
    val MetalBottom = Color(0xFF4E5869)
    val MetalHighlight = Color(0xFFFFFFFF)
    // tab bar blue gradient
    val TabTop = Color(0xFF3E4B5E)
    val TabBottom = Color(0xFF14181F)
    val TabSelected = Color(0xFF9AB8E8)
    val TabUnselected = Color(0xFF8A8F99)
    // grouped list
    val GroupBg = Color(0xFFF4F5F7)
    val CellBg = Color(0xFFFFFFFF)
    val CellDivider = Color(0xFFD9DCE1)
    val IosBlue = Color(0xFF0A60D6)
    val IosBlueDark = Color(0xFF083E9E)
    val TextPrimary = Color(0xFF1A1D22)
    val TextSecondary = Color(0xFF6B7280)
    // gloss button
    val GlossTop = Color(0xFF7FA8E8)
    val GlossMid = Color(0xFF2F6BDD)
    val GlossBottom = Color(0xFF0A3FA8)
    // leather accent for the player sheet
    val LeatherTop = Color(0xFF4A3B30)
    val LeatherBottom = Color(0xFF241A14)

    val linenBrush = Brush.verticalGradient(listOf(LinenTop, LinenBottom))
    // 简单两段：上浅下深
    val metalBrush = Brush.verticalGradient(
        0f to MetalTop,
        1f to MetalBottom,
    )
    val tabBrush = Brush.verticalGradient(listOf(TabTop, TabBottom))
    val glossBrush = Brush.verticalGradient(
        0f to GlossTop,
        0.48f to GlossMid,
        0.52f to GlossBottom,
        1f to GlossBottom,
    )
    /** Gloss highlight streak drawn over a button. */
    fun glossStreak(): Brush = Brush.linearGradient(
        colors = listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0.05f)),
        start = Offset.Zero,
        end = Offset(0f, Float.POSITIVE_INFINITY),
    )
    val cellBrush = Brush.verticalGradient(
        0f to Color.White,
        0.08f to CellBg,
        1f to GroupBg,
    )
}

object Ios5Dimens {
    val CornerGroup = 10.dp
    val CornerSheet = 18.dp
    val CellHeight = 48.dp
    val NavHeight = 52.dp
    val TabHeight = 56.dp
}
