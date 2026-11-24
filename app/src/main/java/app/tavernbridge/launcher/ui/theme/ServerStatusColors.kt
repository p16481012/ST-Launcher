package app.tavernbridge.launcher.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import app.tavernbridge.launcher.model.ServerReadiness
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

enum class ServerIndicatorTone { READY, WORKING, WARNING, ERROR, OFFLINE }

fun serverIndicatorTone(readiness: ServerReadiness): ServerIndicatorTone = when (readiness) {
    ServerReadiness.READY -> ServerIndicatorTone.READY
    ServerReadiness.STARTING -> ServerIndicatorTone.WORKING
    ServerReadiness.UNRESPONSIVE -> ServerIndicatorTone.WARNING
    ServerReadiness.PORT_CONFLICT -> ServerIndicatorTone.ERROR
    ServerReadiness.OFFLINE -> ServerIndicatorTone.OFFLINE
}

internal fun statusColorLuminance(argb: Long): Double {
    fun linear(shift: Int): Double {
        val channel = ((argb shr shift) and 255).toDouble() / 255.0
        return if (channel <= .04045) channel / 12.92 else ((channel + .055) / 1.055).pow(2.4)
    }
    return .2126 * linear(16) + .7152 * linear(8) + .0722 * linear(0)
}

internal fun statusColorContrast(first: Double, second: Double): Double =
    (max(first, second) + .05) / (min(first, second) + .05)

internal fun LauncherColors.indicator(readiness: ServerReadiness): Color = when (serverIndicatorTone(readiness)) {
    ServerIndicatorTone.READY -> live
    ServerIndicatorTone.WORKING, ServerIndicatorTone.WARNING -> accent
    ServerIndicatorTone.ERROR -> danger
    ServerIndicatorTone.OFFLINE -> ink3
}

/** Pair the dot with an explicit state label/icon; color is never the only state cue. */
@Composable
fun serverStatusIndicatorColor(
    readiness: ServerReadiness,
    containerColor: Color = MaterialTheme.colorScheme.surface,
): Color {
    val tokens = LocalLauncherColors.current
    val tone = tokens.indicator(readiness)
    return if (contrastRatio(tone, containerColor) >= 3.0) tone else
        if (contrastRatio(tokens.ink, containerColor) >= contrastRatio(tokens.onInk, containerColor)) tokens.ink else tokens.onInk
}
