package com.aurora.music.ui

import androidx.compose.runtime.Composable
import com.aurora.music.ui.ios5.Ios5App

/**
 * iOS5 rewrite entry. The legacy drawer + floating-nav shell was removed;
 * [Ios5App] owns the five-tab scaffold, browse stack and player sheet.
 */
@Composable
fun AuroraApp() {
    Ios5App()
}
