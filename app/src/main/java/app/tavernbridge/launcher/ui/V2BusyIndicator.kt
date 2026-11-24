package app.tavernbridge.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tavernbridge.launcher.ui.theme.LocalV2ReducedMotion
import kotlinx.coroutines.delay

internal const val PROGRESS_REVEAL_DELAY_MS = 400L

/** Debounce presentation only. Never delay starting work, locking buttons, cancellation or errors. */
@Composable
internal fun rememberProgressVisible(active: Boolean, request: Any? = Unit): Boolean {
    if (!active) return false
    var visible by remember(request) { mutableStateOf(false) }
    LaunchedEffect(request) {
        delay(PROGRESS_REVEAL_DELAY_MS)
        visible = true
    }
    return visible
}

/** No invented percentage or endless animation when the motion preference is on. */
@Composable
internal fun V2BusyIndicator(modifier: Modifier = Modifier, linear: Boolean = true, strokeWidth: Dp = 2.dp) {
    if (!rememberProgressVisible(active = true)) return
    VisibleBusyIndicator(modifier, linear, strokeWidth)
}

@Composable
internal fun VisibleBusyIndicator(modifier: Modifier, linear: Boolean, strokeWidth: Dp) {
    val ink = MaterialTheme.colorScheme.tertiary
    if (LocalV2ReducedMotion.current) {
        val state = modifier.semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate; contentDescription = "처리 중" }
        if (linear) Row(state.height(6.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(3) { Spacer(Modifier.size(5.dp).background(ink, CircleShape)) }
        } else Icon(Icons.Outlined.HourglassEmpty, null, state.size(24.dp), tint = ink)
    } else if (linear) LinearProgressIndicator(modifier = modifier, color = ink)
    else CircularProgressIndicator(modifier = modifier, color = ink, strokeWidth = strokeWidth)
}
