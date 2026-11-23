package app.tavernbridge.launcher.ui.theme

import app.tavernbridge.launcher.model.AppTheme

/** Explicitly named palettes remain fixed, regardless of Android wallpaper or dark-mode settings. */
fun shouldUseDynamicColors(theme: AppTheme, androidSdk: Int): Boolean =
    theme == AppTheme.SYSTEM && androidSdk >= 31
