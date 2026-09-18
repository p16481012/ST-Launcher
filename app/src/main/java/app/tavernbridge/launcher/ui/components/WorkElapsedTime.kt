package app.tavernbridge.launcher.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.tavernbridge.launcher.model.workingElapsedLabel
import kotlinx.coroutines.delay

/** The start belongs to the whole operation; phase and panel changes must not reset it. */
@Composable
internal fun WorkElapsedTime(startedAtMillis: Long, modifier: Modifier = Modifier) {
    var nowMillis by remember(startedAtMillis) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAtMillis) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    Text(
        workingElapsedLabel(startedAtMillis, nowMillis),
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
