package app.tavernbridge.launcher.ui.theme

import android.app.Activity
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import app.tavernbridge.launcher.model.AppTheme
import app.tavernbridge.launcher.model.DisplayPreferences
import app.tavernbridge.launcher.model.LauncherAccent

/** Source of every semantic role, including roles Material does not have slots for. */
@Immutable
data class LauncherColors internal constructor(
    val dark: Boolean, val highContrast: Boolean,
    val canvas: Color, val surface: Color, val sunken: Color,
    val ink: Color, val onInk: Color, val ink2: Color, val ink3: Color, val hairline: Color,
    val accent: Color, val accentWash: Color, val onAccentWash: Color,
    val live: Color, val liveWash: Color, val danger: Color, val dangerWash: Color,
    val disabledInk: Color,
)

internal fun launcherColors(dark: Boolean, accent: LauncherAccent, highContrast: Boolean): LauncherColors {
    fun pair(light: Long, night: Long) = Color(if (dark) night else light)
    val ink = pair(0xFF17191C, 0xFFF1F2F4)
    val ink2 = if (highContrast) ink else pair(0xFF59606A, 0xFFA9AFB7)
    val ink3 = if (highContrast) pair(0xFF414750, 0xFFCFD4DB) else pair(0xFF6A717B, 0xFF8E949C)
    val accentColor = pair(accent.lightArgb, accent.darkArgb)
    val wash = pair(accent.lightWash, accent.darkWash)
    // Use ink when the exact swatch fails 4.5:1 on its own wash (notably green).
    val onWash = if (contrastRatio(accentColor, wash) >= 4.5) accentColor else ink
    return LauncherColors(dark, highContrast,
        canvas = pair(0xFFF4F5F7, 0xFF111214), surface = pair(0xFFFFFFFF, 0xFF1A1C1F),
        sunken = pair(0xFFECEEF1, 0xFF24272B), ink = ink, onInk = pair(0xFFFFFFFF, 0xFF17191C),
        ink2 = ink2, ink3 = ink3,
        hairline = if (highContrast) pair(0xFF68707A, 0xFF858D98) else pair(0xFFE4E7EB, 0xFF2D3035),
        accent = accentColor, accentWash = wash, onAccentWash = onWash,
        live = pair(0xFF157A4C, 0xFF4CC98C), liveWash = pair(0xFFE6F4EC, 0xFF163328),
        danger = pair(0xFFC9372C, 0xFFFF7468), dangerWash = pair(0xFFFDECEF, 0xFF41242A),
        disabledInk = if (highContrast) ink else ink2)
}

internal fun contrastRatio(a: Color, b: Color): Double =
    statusColorContrast(a.luminance().toDouble(), b.luminance().toDouble())

val LocalLauncherColors = staticCompositionLocalOf { launcherColors(false, LauncherAccent.AMBER, false) }
val LocalV2ReducedMotion = staticCompositionLocalOf { false }

private fun LauncherColors.materialScheme() = (if (dark) darkColorScheme() else lightColorScheme()).copy(
    primary = ink, onPrimary = onInk, primaryContainer = sunken, onPrimaryContainer = ink,
    secondary = accent, onSecondary = onInk, secondaryContainer = accentWash, onSecondaryContainer = onAccentWash,
    tertiary = accent, onTertiary = onInk, tertiaryContainer = accentWash, onTertiaryContainer = onAccentWash,
    background = canvas, onBackground = ink, surface = surface, onSurface = ink,
    surfaceTint = surface, surfaceBright = surface, surfaceDim = sunken, surfaceVariant = sunken,
    onSurfaceVariant = ink2, surfaceContainerLowest = surface, surfaceContainerLow = canvas,
    surfaceContainer = sunken, surfaceContainerHigh = sunken, surfaceContainerHighest = sunken,
    outline = ink3, outlineVariant = hairline, error = danger, onError = onInk,
    errorContainer = dangerWash, onErrorContainer = danger,
    inverseSurface = ink, inverseOnSurface = onInk, inversePrimary = onInk, scrim = Color(0xFF0B0C0E),
    primaryFixed = sunken, primaryFixedDim = hairline, onPrimaryFixed = ink, onPrimaryFixedVariant = ink2,
    secondaryFixed = accentWash, secondaryFixedDim = accentWash, onSecondaryFixed = onAccentWash, onSecondaryFixedVariant = onAccentWash,
    tertiaryFixed = accentWash, tertiaryFixedDim = accentWash, onTertiaryFixed = onAccentWash, onTertiaryFixedVariant = onAccentWash,
)

private val shapes = Shapes(extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(14.dp), large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(24.dp))

@Composable
private fun systemMotionReduced(): Boolean {
    val resolver = LocalContext.current.contentResolver
    fun read() = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    var reduced by remember(resolver) { mutableStateOf(read()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { reduced = read() }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        reduced = read()
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return reduced
}

@Composable
fun SillyTavernLauncherTheme(appTheme: AppTheme, preferences: DisplayPreferences = DisplayPreferences(), content: @Composable () -> Unit) {
    val dark = when (appTheme) { AppTheme.DARK -> true; AppTheme.LIGHT -> false; AppTheme.SYSTEM -> isSystemInDarkTheme() }
    val reducedMotion = preferences.reducedMotion ?: systemMotionReduced()
    val target = remember(dark, preferences.accent, preferences.highContrast) { launcherColors(dark, preferences.accent, preferences.highContrast) }
    // Crossfade semantic colors in place. Never compose a second running launcher (it owns effects and dialogs).
    val transition = updateTransition(target, label = "launcher palette")
    @Composable fun shade(role: (LauncherColors) -> Color): Color {
        val animated by transition.animateColor(transitionSpec = {
            tween(if (reducedMotion) 0 else if (initialState.dark != targetState.dark) 250 else 200)
        }, label = "semantic role") { role(it) }
        return if (reducedMotion) role(target) else animated
    }
    val tokens = target.copy(canvas = shade { it.canvas }, surface = shade { it.surface }, sunken = shade { it.sunken },
        ink = shade { it.ink }, onInk = shade { it.onInk }, ink2 = shade { it.ink2 }, ink3 = shade { it.ink3 },
        hairline = shade { it.hairline }, accent = shade { it.accent }, accentWash = shade { it.accentWash },
        onAccentWash = shade { it.onAccentWash }, live = shade { it.live }, liveWash = shade { it.liveWash },
        danger = shade { it.danger }, dangerWash = shade { it.dangerWash }, disabledInk = shade { it.disabledInk })
    val colors = remember(tokens) { tokens.materialScheme() }
    val density = LocalDensity.current
    val targetScale = preferences.textSize.applyTo(density.fontScale)
    val animatedScale by animateFloatAsState(targetScale, tween(if (reducedMotion) 0 else 200), label = "launcher text size")
    val scale = if (reducedMotion) targetScale else animatedScale
    val scaled = remember(density, scale) { Density(density.density, scale) }
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        (view.context as? Activity)?.let {
            WindowCompat.getInsetsController(it.window, view).apply {
                isAppearanceLightStatusBars = tokens.canvas.luminance() > .179f
                isAppearanceLightNavigationBars = tokens.canvas.luminance() > .179f
            }
        }
    }
    CompositionLocalProvider(LocalDensity provides scaled, LocalLauncherColors provides tokens, LocalV2ReducedMotion provides reducedMotion) {
        MaterialTheme(colorScheme = colors, typography = LauncherTypography, shapes = shapes) {
            // A Box/Column with a canvas background is not a Material Surface. Supply ink for
            // unstyled headings/icons there as well; nested buttons/surfaces provide their own color.
            CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides tokens.ink, content = content)
        }
    }
}
