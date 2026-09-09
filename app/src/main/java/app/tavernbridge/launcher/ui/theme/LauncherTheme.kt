package app.tavernbridge.launcher.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import app.tavernbridge.launcher.model.AppTheme

private val LightColors = lightColorScheme(
    primary = Color(0xFF4E6073),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE3EA),
    onPrimaryContainer = Color(0xFF273646),
    secondary = Color(0xFF6D747C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE2E5E8),
    onSecondaryContainer = Color(0xFF393F45),
    tertiary = Color(0xFF64717D),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE0E5EA),
    onTertiaryContainer = Color(0xFF35414C),
    background = Color(0xFFF5F4F0),
    onBackground = Color(0xFF222524),
    surface = Color(0xFFFBFAF7),
    onSurface = Color(0xFF222524),
    surfaceDim = Color(0xFFE1E1DE),
    surfaceBright = Color(0xFFFCFBF8),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F6F3),
    surfaceContainer = Color(0xFFF1F0ED),
    surfaceContainerHigh = Color(0xFFEBEAE7),
    surfaceContainerHighest = Color(0xFFE5E4E1),
    surfaceVariant = Color(0xFFE9E8E3),
    onSurfaceVariant = Color(0xFF4A4E4C),
    outline = Color(0xFF858A87),
    outlineVariant = Color(0xFFC9CCCA),
    inverseSurface = Color(0xFF303331),
    inverseOnSurface = Color(0xFFF3F2EE),
    inversePrimary = Color(0xFFBCCBDD),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFD1D4D8),
    onPrimary = Color(0xFF25282C),
    primaryContainer = Color(0xFF373B40),
    onPrimaryContainer = Color(0xFFF0F1F3),
    secondary = Color(0xFFBEC2C7),
    onSecondary = Color(0xFF292C30),
    secondaryContainer = Color(0xFF393D42),
    onSecondaryContainer = Color(0xFFE2E4E7),
    tertiary = Color(0xFFAEB3B9),
    onTertiary = Color(0xFF282C30),
    tertiaryContainer = Color(0xFF3A3E43),
    onTertiaryContainer = Color(0xFFDEE1E5),
    background = Color(0xFF111315),
    onBackground = Color(0xFFE4E5E7),
    surface = Color(0xFF181A1D),
    onSurface = Color(0xFFE4E5E7),
    surfaceDim = Color(0xFF101214),
    surfaceBright = Color(0xFF34373B),
    surfaceContainerLowest = Color(0xFF0D0F11),
    surfaceContainerLow = Color(0xFF17191C),
    surfaceContainer = Color(0xFF1D1F22),
    surfaceContainerHigh = Color(0xFF26292C),
    surfaceContainerHighest = Color(0xFF303337),
    surfaceVariant = Color(0xFF292C30),
    onSurfaceVariant = Color(0xFFC5C8CC),
    outline = Color(0xFF898E94),
    outlineVariant = Color(0xFF44484D),
    inverseSurface = Color(0xFFE4E5E7),
    inverseOnSurface = Color(0xFF2B2E32),
    inversePrimary = Color(0xFF555D66),
)

private val PastelColors = lightColorScheme(
    primary = Color(0xFF76536F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEBD6E5),
    onPrimaryContainer = Color(0xFF452B40),
    secondary = Color(0xFF86606C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF0D9DF),
    onSecondaryContainer = Color(0xFF4D3038),
    tertiary = Color(0xFF6D617D),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE6DCEF),
    onTertiaryContainer = Color(0xFF3B3048),
    background = Color(0xFFF8F1F5),
    onBackground = Color(0xFF282326),
    surface = Color(0xFFFEF8FB),
    onSurface = Color(0xFF282326),
    surfaceDim = Color(0xFFE3DDE0),
    surfaceBright = Color(0xFFFFFBFC),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8F4F6),
    surfaceContainer = Color(0xFFF4EAF0),
    surfaceContainerHigh = Color(0xFFEEE3E9),
    surfaceContainerHighest = Color(0xFFE8DCE3),
    surfaceVariant = Color(0xFFEEDFE7),
    onSurfaceVariant = Color(0xFF584650),
    outline = Color(0xFF927B87),
    outlineVariant = Color(0xFFD5BEC9),
    inverseSurface = Color(0xFF342F32),
    inverseOnSurface = Color(0xFFF7F0F4),
    inversePrimary = Color(0xFFD8C5E0),
)

private val AvocadoColors = lightColorScheme(
    primary = Color(0xFF4E674E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD2E2C8),
    onPrimaryContainer = Color(0xFF203722),
    secondary = Color(0xFF617159),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDE6D2),
    onSecondaryContainer = Color(0xFF2E3D2A),
    tertiary = Color(0xFF746548),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEEE2BD),
    onTertiaryContainer = Color(0xFF3B321E),
    background = Color(0xFFF5F0DE),
    onBackground = Color(0xFF23271F),
    surface = Color(0xFFFFF9E9),
    onSurface = Color(0xFF23271F),
    surfaceDim = Color(0xFFDEDACB),
    surfaceBright = Color(0xFFFFFCF2),
    surfaceContainerLowest = Color(0xFFFFFFF8),
    surfaceContainerLow = Color(0xFFFAF5E5),
    surfaceContainer = Color(0xFFF0F0E1),
    surfaceContainerHigh = Color(0xFFE6EADB),
    surfaceContainerHighest = Color(0xFFDDE4D4),
    surfaceVariant = Color(0xFFE3E9D9),
    onSurfaceVariant = Color(0xFF465143),
    outline = Color(0xFF7A8575),
    outlineVariant = Color(0xFFBDC7B5),
    inverseSurface = Color(0xFF2D332A),
    inverseOnSurface = Color(0xFFF2F5EB),
    inversePrimary = Color(0xFFAFC7A6),
)

private val BananaColors = lightColorScheme(
    primary = Color(0xFF806221), onPrimary = Color.White,
    primaryContainer = Color(0xFFF2DB91), onPrimaryContainer = Color(0xFF43330C),
    secondary = Color(0xFF826E3B), onSecondary = Color.White,
    secondaryContainer = Color(0xFFF0E1B7), onSecondaryContainer = Color(0xFF443A1D),
    tertiary = Color(0xFF775D42), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEAD8C5), onTertiaryContainer = Color(0xFF402F20),
    background = Color(0xFFFAF4E2), onBackground = Color(0xFF29251A),
    surface = Color(0xFFFFFAEC), onSurface = Color(0xFF29251A),
    surfaceDim = Color(0xFFE2DDCF), surfaceBright = Color(0xFFFFFCF2),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF8F4E8),
    surfaceContainer = Color(0xFFF5ECD5), surfaceContainerHigh = Color(0xFFEFE5CC),
    surfaceContainerHighest = Color(0xFFE9DEC4), surfaceVariant = Color(0xFFF0E2BC),
    onSurfaceVariant = Color(0xFF554B34), outline = Color(0xFF8D8163),
    outlineVariant = Color(0xFFD2C29C), inverseSurface = Color(0xFF363126),
    inverseOnSurface = Color(0xFFFAF1DC), inversePrimary = Color(0xFFE6C45F),
)

private val BlueberryColors = lightColorScheme(
    primary = Color(0xFF475982), onPrimary = Color.White,
    primaryContainer = Color(0xFFCFD9F1), onPrimaryContainer = Color(0xFF243250),
    secondary = Color(0xFF625C82), onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDD8EF), onSecondaryContainer = Color(0xFF35304F),
    tertiary = Color(0xFF765970), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE9D5E4), onTertiaryContainer = Color(0xFF432E40),
    background = Color(0xFFF1F2F8), onBackground = Color(0xFF22242E),
    surface = Color(0xFFF8F8FC), onSurface = Color(0xFF22242E),
    surfaceDim = Color(0xFFDDE0E6), surfaceBright = Color(0xFFFCFCFE),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF5F6F9),
    surfaceContainer = Color(0xFFEDEEF6), surfaceContainerHigh = Color(0xFFE6E7F1),
    surfaceContainerHighest = Color(0xFFDEE0EC), surfaceVariant = Color(0xFFE1E3F0),
    onSurfaceVariant = Color(0xFF464A5D), outline = Color(0xFF7C8296),
    outlineVariant = Color(0xFFBEC3D5), inverseSurface = Color(0xFF2D303E),
    inverseOnSurface = Color(0xFFF0F1F8), inversePrimary = Color(0xFFAFC0EB),
)

@Composable
fun SillyTavernLauncherTheme(
    appTheme: AppTheme,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val colors = when (appTheme) {
        AppTheme.SYSTEM -> if (systemDark) DarkColors else LightColors
        AppTheme.LIGHT -> LightColors
        AppTheme.DARK -> DarkColors
        AppTheme.PASTEL -> PastelColors
        AppTheme.AVOCADO -> AvocadoColors
        AppTheme.BANANA -> BananaColors
        AppTheme.BLUEBERRY -> BlueberryColors
    }
    val useDarkIcons = when (appTheme) {
        AppTheme.DARK -> false
        AppTheme.SYSTEM -> !systemDark
        else -> true
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = useDarkIcons
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = useDarkIcons
        }
    }

    MaterialTheme(
        colorScheme = colors,
        typography = LauncherTypography,
        content = content,
    )
}
