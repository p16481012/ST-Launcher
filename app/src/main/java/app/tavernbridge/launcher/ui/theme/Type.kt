package app.tavernbridge.launcher.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.tavernbridge.launcher.R

private val PlexMono = FontFamily(Font(R.font.plex_mono_regular))
internal val LocalSystemFont = androidx.compose.runtime.staticCompositionLocalOf { false }
val V2Mono: FontFamily
    @androidx.compose.runtime.Composable get() = if (LocalSystemFont.current) FontFamily.Monospace else PlexMono
private val Plex = FontFamily(Font(R.font.plex_kr_regular), Font(R.font.plex_kr_medium, FontWeight.Medium),
    Font(R.font.plex_kr_semibold, FontWeight.SemiBold))

private fun typographyFor(family: FontFamily): Typography {
    fun style(size: Int, height: Int, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = family, fontWeight = weight, fontSize = size.sp, lineHeight = height.sp,
        letterSpacing = (-.01f * size).sp)

    return Typography(
    displayLarge = style(48, 56, FontWeight.SemiBold), displayMedium = style(36, 44, FontWeight.SemiBold),
    displaySmall = style(30, 38, FontWeight.SemiBold), headlineLarge = style(28, 36, FontWeight.SemiBold),
    headlineMedium = style(24, 32, FontWeight.SemiBold), headlineSmall = style(22, 30, FontWeight.SemiBold),
    titleLarge = style(20, 28, FontWeight.SemiBold), titleMedium = style(16, 24, FontWeight.SemiBold),
    titleSmall = style(14, 20, FontWeight.SemiBold), bodyLarge = style(16, 24),
    bodyMedium = style(14, 21), bodySmall = style(13, 18, FontWeight.Medium),
    labelLarge = style(14, 20, FontWeight.SemiBold), labelMedium = style(13, 18, FontWeight.Medium),
    labelSmall = style(12, 16, FontWeight.Medium),
    )
}

val LauncherTypography = typographyFor(Plex)
internal val SystemTypography = typographyFor(FontFamily.Default)
