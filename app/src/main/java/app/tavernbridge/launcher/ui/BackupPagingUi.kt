package app.tavernbridge.launcher.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import kotlinx.coroutines.flow.first

internal const val BackupPageEnd = "backup-page-end"

/** The actual viewport, not a prefetched/composed row, triggers the next bounded page. */
@Composable
internal fun LoadBackupPageAtEnd(list: LazyListState, cursor: Int?, enabled: Boolean, onMore: () -> Unit) {
    val load by rememberUpdatedState(onMore)
    LaunchedEffect(list, cursor, enabled) {
        if (enabled && cursor != null) {
            snapshotFlow { list.layoutInfo.visibleItemsInfo.any { it.key == BackupPageEnd } }.first { it }
            load()
        }
    }
}
