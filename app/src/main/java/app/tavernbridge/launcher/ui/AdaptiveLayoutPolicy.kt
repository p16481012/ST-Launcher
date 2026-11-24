package app.tavernbridge.launcher.ui

enum class LauncherNavigationMode { BOTTOM_BAR, RAIL }

/** Use the current window width, not the device's physical screen size. */
fun chooseLauncherNavigation(widthDp: Float): LauncherNavigationMode =
    if (widthDp.isFinite() && widthDp >= 840f) LauncherNavigationMode.RAIL
    else LauncherNavigationMode.BOTTOM_BAR

/** A label and a long value must never compete for the same small row. */
fun shouldStackInfoRow(availableWidthDp: Float, fontScale: Float): Boolean =
    !availableWidthDp.isFinite() || availableWidthDp < 360f ||
        !fontScale.isFinite() || fontScale <= 0f || fontScale >= 1.3f
