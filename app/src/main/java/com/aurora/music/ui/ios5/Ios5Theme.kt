package com.aurora.music.ui.ios5

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * iOS5 skeuomorphic tokens, sampled pixel-by-pixel from
 * "iOS 5 GUI - iPhone4S.psd" (Teehan+Lax kit, @2x, pt = px / 2).
 *
 * PSD gradients use angle 90 with stop loc 0 = bottom, 4096 = top; Compose
 * `Brush.verticalGradient` uses 0f = top, so the PSD "top" stop is 0f here.
 *
 * Fixed light look, does not follow system dark mode.
 */
object Ios5Colors {
    // ---- linen page backdrop (PSD "ugb, Linen" flat avg; top edge lighter) ----
    val Linen = Color(0xFFC5CCD4)
    val LinenTop = Color(0xFFCBD2D8)

    // ---- brushed-metal navigation / tool bar (PSD "bar top", 644x91 @2x) ----
    val BarTop = Color(0xFFB4BFCD)
    val BarUpper = Color(0xFF899BB2)
    val BarLower = Color(0xFF7F94B0)
    val BarBottom = Color(0xFF6E84A1)

    // ---- grey gradient toolbars (search bar 644x91, segmented bar 644x91) ----
    val GreyBarTop = Color(0xFFE0E4E6)
    val GreyBarBottom = Color(0xFFB4BEC5)
    val SegBarTop = Color(0xFFD6DDE2)
    val SegBarBottom = Color(0xFFB4BFC6)

    // ---- tab bar (PSD "tab bar", 644x101 @2x) ----
    val TabTop = Color(0xFF2F2F2F)
    val TabMid = Color(0xFF151515)
    val TabBottom = Color(0xFF000000)
    val TabIconOffTop = Color(0xFF9D9D9D)
    val TabIconOffBottom = Color(0xFF595959)
    val TabIconOnTop = Color(0xFFD9E6F9)
    val TabIconOnBottom = Color(0xFF10C0F7)
    val TabLabelOff = Color(0xFF9D9D9D)

    // ---- grouped tables (PSD "Tables") ----
    val CellBg = Color(0xFFF7F7F7)
    val CellDivider = Color(0xFFABABAB)
    val Chevron = Color(0xFF7F7F7F)
    val TableHeaderInk = Color(0xFF4C566C)
    val IosBlue = Color(0xFF385487)
    val IosBlueDark = Color(0xFF083E9E)
    val IosBlueBright = Color(0xFF0A60D6)
    /** Flat field / inner-card surface used by inline editors. */
    val GroupBg = Color(0xFFF2F3F5)

    // ---- gloss bar buttons (PSD Button Grey-Blue / Blue / Red / Black) ----
    val BarBtnTop = Color(0xFF89A0BE)
    val BarBtnMid = Color(0xFF5877A2)
    val BarBtnBottom = Color(0xFF4A6C9B)
    val BtnBlueTop = Color(0xFF7398E9)
    val BtnBlueMid = Color(0xFF3970E0)
    val BtnBlueBottom = Color(0xFF2463DE)
    val BtnRedTop = Color(0xFFF08189)
    val BtnRedBottom = Color(0xFFEC1113)
    val BtnBlackTop = Color(0xFF787878)
    val BtnBlackBottom = Color(0xFF000000)

    // ---- large buttons (PSD 558x93 @2x, outer edge #464951 + inner gradient) ----
    val LargeEdge = Color(0xFF464951)
    val LargeLightTop = Color(0xFFF6F6F6)
    val LargeLightBottom = Color(0xFFC0C1C4)
    val LargeDarkTop = Color(0xFF6D7278)
    val LargeDarkBottom = Color(0xFF454B54)
    val LargeGreenTop = Color(0xFFA1C69B)
    val LargeGreenBottom = Color(0xFF0D9C23)
    val LargeRedTop = Color(0xFFF0838B)
    val LargeRedBottom = Color(0xFFED1414)

    // ---- switch (PSD 156x56 @2x) ----
    val SwitchOffTop = Color(0xFFEEEEEE)
    val SwitchOffBottom = Color(0xFFFCFCFC)
    val SwitchOnTop = Color(0xFF007FEA)
    val SwitchOnBottom = Color(0xFF52A8F1)
    val SwitchHandleTop = Color(0xFFFCFCFC)
    val SwitchHandleBottom = Color(0xFFD0D0D0)

    // ---- slider (PSD 442x44 @2x) ----
    val SliderFillTop = Color(0xFF0D48A8)
    val SliderFillBottom = Color(0xFF77ADF6)
    val SliderTrackTop = Color(0xFF909090)
    val SliderTrackBottom = Color(0xFFDEDEDE)
    val SliderThumbTop = Color(0xFFFAFAFA)
    val SliderThumbBottom = Color(0xFFA6A6A6)

    // ---- segmented control (PSD "grey tabs bar") ----
    val SegOnTop = Color(0xFF8D9BAF)
    val SegOnBottom = Color(0xFF647792)
    val SegOffTop = Color(0xFFD5DCE0)
    val SegOffMid = Color(0xFFB4BEC6)
    val SegOffBottom = Color(0xFF778593)
    val SegDividerTop = Color(0xFF7B8BA2)
    val SegDividerBottom = Color(0xFF465473)
    val SegInkActive = Color.White
    val SegInkIdle = Color(0xFF5B6A7E)

    // ---- grey search bar (PSD 644x91 @2x) ----
    val SearchIcon = Color(0xFF93989D)
    val SearchPlaceholder = Color(0xFF93989D)

    // ---- alert dialog (PSD 554x301 @2x) ----
    val AlertBg = Color(0xFF131F40)
    val AlertGlossTop = Color(0xFF6C7593)
    val AlertCancelTop = Color(0xFF858585)
    val AlertOkTop = Color(0xFFD6D6D6)

    // ---- progress / loading bar (PSD "loading bar (bottom)") ----
    val ProgressTrackTop = Color(0xFF41638F)
    val ProgressTrackBottom = Color(0xFF274369)
    val ProgressFillTop = Color(0xFFFCFCFC)
    val ProgressFillBottom = Color(0xFFAAAAAA)

    // ---- text ----
    val TextPrimary = Color(0xFF000000)
    val TextSecondary = Color(0xFF6D7278)
    val TextDisabled = Color(0xFFB0B0B0)

    // =========================================================
    // Brushes
    // =========================================================

    val linenBrush = Brush.verticalGradient(
        0f to LinenTop,
        0.16f to Linen,
        1f to Linen,
    )

    val barBrush = Brush.verticalGradient(
        0f to BarTop,
        0.49f to BarUpper,
        0.515f to BarLower,
        1f to BarBottom,
    )

    val greyBarBrush = Brush.verticalGradient(0f to GreyBarTop, 1f to GreyBarBottom)
    val segBarBrush = Brush.verticalGradient(0f to SegBarTop, 1f to SegBarBottom)

    val tabBrush = Brush.verticalGradient(
        0f to TabTop,
        0.5f to TabMid,
        0.53f to TabBottom,
        1f to TabBottom,
    )

    val barButtonBrush = Brush.verticalGradient(
        0f to BarBtnTop,
        0.505f to BarBtnMid,
        0.515f to Color(0xFF486A9A),
        1f to BarBtnBottom,
    )
    val buttonBlueBrush = Brush.verticalGradient(
        0f to BtnBlueTop,
        0.5f to BtnBlueMid,
        0.515f to Color(0xFF2260DD),
        1f to BtnBlueBottom,
    )
    val buttonRedBrush = Brush.verticalGradient(
        0f to BtnRedTop,
        0.5f to Color(0xFFE73542),
        0.515f to Color(0xFFE10111),
        1f to BtnRedBottom,
    )
    val buttonBlackBrush = Brush.verticalGradient(
        0f to BtnBlackTop,
        0.5f to Color(0xFF1C1C1C),
        0.515f to Color(0xFF000000),
        1f to BtnBlackBottom,
    )
    val buttonGreyBrush = barButtonBrush
    /** Alias kept for existing call sites: the primary glossy blue fill. */
    val glossBrush: Brush = buttonBlueBrush

    val largeLightBrush = Brush.verticalGradient(
        0f to LargeLightTop,
        0.505f to Color(0xFFD2D3D5),
        0.515f to LargeLightBottom,
        1f to LargeLightBottom,
    )
    val largeDarkBrush = Brush.verticalGradient(
        0f to LargeDarkTop,
        0.505f to Color(0xFF363E46),
        0.515f to LargeDarkBottom,
        1f to Color(0xFF2B323B),
    )
    val largeGreenBrush = Brush.verticalGradient(
        0f to LargeGreenTop,
        0.505f to Color(0xFF41BF4A),
        0.515f to Color(0xFF0EB81D),
        1f to LargeGreenBottom,
    )
    val largeRedBrush = Brush.verticalGradient(
        0f to LargeRedTop,
        0.505f to Color(0xFFE73340),
        0.515f to Color(0xFFE10111),
        1f to LargeRedBottom,
    )

    val switchOnBrush = Brush.verticalGradient(0f to SwitchOnTop, 1f to SwitchOnBottom)
    val switchOffBrush = Brush.verticalGradient(0f to SwitchOffTop, 1f to SwitchOffBottom)
    val switchHandleBrush = Brush.verticalGradient(0f to SwitchHandleTop, 1f to SwitchHandleBottom)

    val sliderFillBrush = Brush.verticalGradient(0f to SliderFillTop, 1f to SliderFillBottom)
    val sliderTrackBrush = Brush.verticalGradient(
        0f to SliderTrackTop,
        0.5f to SliderTrackBottom,
        1f to SliderTrackBottom,
    )
    val sliderThumbBrush = Brush.verticalGradient(0f to SliderThumbTop, 1f to SliderThumbBottom)

    val segOnBrush = Brush.verticalGradient(0f to SegOnTop, 1f to SegOnBottom)
    val segOffBrush = Brush.verticalGradient(
        0f to SegOffTop,
        0.06f to SegOffMid,
        1f to SegOffBottom,
    )
    val segDividerBrush = Brush.verticalGradient(0f to SegDividerTop, 1f to SegDividerBottom)

    val alertBrush = Brush.verticalGradient(
        0f to Color(0xFF54607C),
        0.16f to Color(0xFF2A3556),
        0.22f to AlertBg,
        1f to AlertBg,
    )
    val alertCancelBrush = Brush.verticalGradient(
        0f to AlertCancelTop,
        0.505f to Color(0xFF262727),
        0.515f to Color(0xFF000000),
        1f to Color(0xFF121212),
    )
    val alertOkBrush = Brush.verticalGradient(
        0f to AlertOkTop,
        0.505f to Color(0xFF717377),
        0.515f to Color(0xFF4A4B4E),
        1f to Color(0xFF66676A),
    )

    val progressTrackBrush = Brush.verticalGradient(0f to ProgressTrackTop, 1f to ProgressTrackBottom)
    val progressFillBrush = Brush.verticalGradient(0f to ProgressFillTop, 1f to ProgressFillBottom)
}

/** iOS5 corner radii and fixed control metrics, all in pt (dp). */
object Ios5Dimens {
    // corners
    val CornerButton = 5.dp
    val CornerLarge = 10.dp
    val CornerGroup = 10.dp
    val CornerDialog = 11.dp
    val CornerSegmented = 5.dp
    val CornerSearchField = 15.dp

    // bars
    val NavHeight = 44.dp
    val TabHeight = 49.dp
    val BarButtonHeight = 30.dp

    // rows
    val CellHeight = 44.dp
    val GroupMargin = 10.dp

    // switch (PSD 156x56 @2x)
    val SwitchWidth = 78.dp
    val SwitchHeight = 28.dp

    // slider (PSD 442x44 @2x)
    val SliderThumb = 22.dp
    val SliderTrack = 11.dp

    // segmented (PSD 211x61 @2x)
    val SegmentHeight = 31.dp
}

/** iOS5 type ramp (Helvetica-equivalent), sizes in sp. */
object Ios5Type {
    val NavTitle = 20.sp
    val BarButton = 12.sp
    val TabLabel = 10.sp
    val CellTitle = 17.sp
    val CellDetail = 14.sp
    val SectionTitle = 17.sp
    val Segment = 13.sp
    val Search = 14.sp
    val AlertTitle = 17.sp
    val AlertBody = 14.sp
    val AlertButton = 15.sp
    val Button = 15.sp
    val LargeButton = 17.sp
    val SwitchLabel = 11.sp
}
