package app.tavernbridge.launcher.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
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

/** Pick the more legible semantic shade against the actual card, including dynamic/custom palettes. */
fun serverIndicatorArgb(readiness: ServerReadiness, backgroundLuminance: Double): Long {
    val (dark, light) = when (serverIndicatorTone(readiness)) {
        ServerIndicatorTone.READY -> 0xFF0C3C22 to 0xFFB7F5CA
        ServerIndicatorTone.WORKING -> 0xFF0D2F50 to 0xFFBEDDFF
        ServerIndicatorTone.WARNING -> 0xFF453000 to 0xFFFFE3A1
        ServerIndicatorTone.ERROR -> 0xFF690D0D to 0xFFFFDAD6
        ServerIndicatorTone.OFFLINE -> 0xFF293029 to 0xFFD7E1D6
    }
    val background = if (backgroundLuminance.isFinite()) backgroundLuminance.coerceIn(0.0, 1.0) else 1.0
    return if (statusColorContrast(statusColorLuminance(dark), background) >=
        statusColorContrast(statusColorLuminance(light), background)) dark else light
}

/** Pair the dot with an explicit state label/icon; color is never the only state cue. */
@Composable
fun serverStatusIndicatorColor(
    readiness: ServerReadiness,
    containerColor: Color = MaterialTheme.colorScheme.surface,
): Color = Color(serverIndicatorArgb(readiness, containerColor.luminance().toDouble()))
