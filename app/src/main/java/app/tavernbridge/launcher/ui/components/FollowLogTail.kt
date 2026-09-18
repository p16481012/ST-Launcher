package app.tavernbridge.launcher.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect

/** Keep following new output only while the user is reading the bottom of the log. */
@Composable
internal fun FollowLogTail(
    logText: String,
    scrollState: ScrollState,
    enabled: Boolean = true,
    resetKey: Any? = Unit,
) {
    var following by remember(scrollState, resetKey) { mutableStateOf(true) }
    LaunchedEffect(scrollState, resetKey) {
        snapshotFlow { scrollState.isScrollInProgress to (scrollState.value >= scrollState.maxValue) }
            .collect { (scrolling, atBottom) ->
                // Content growing is not a user scroll. Do not stop following just
                // because new output has increased maxValue before we reach it.
                if (scrolling) following = atBottom
            }
    }
    LaunchedEffect(logText, enabled, resetKey) {
        if (enabled && following) {
            delay(50)
            if (following && !scrollState.isScrollInProgress) scrollState.scrollTo(scrollState.maxValue)
        }
    }
}
