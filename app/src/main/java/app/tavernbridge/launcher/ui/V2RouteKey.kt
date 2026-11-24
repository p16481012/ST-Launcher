package app.tavernbridge.launcher.ui

import app.tavernbridge.launcher.model.MainSection
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.runtime.*
import kotlinx.coroutines.flow.collect

internal data class V2RouteKey(val entry: Long, val section: MainSection, val detail: Boolean)

internal val LocalV2BackProgress = staticCompositionLocalOf<((Float) -> Unit)?> { null }

@Composable
internal fun V2BackHandler(enabled: Boolean, onBack: () -> Unit) {
    val update = LocalV2BackProgress.current
    val latest by rememberUpdatedState(onBack)
    PredictiveBackHandler(enabled) { events ->
        try {
            events.collect { update?.invoke(it.progress.coerceIn(0f, 1f)) }
            latest()
        } finally {
            update?.invoke(0f)
        }
    }
}
