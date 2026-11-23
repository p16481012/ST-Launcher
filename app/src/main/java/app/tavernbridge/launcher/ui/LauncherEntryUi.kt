package app.tavernbridge.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.tavernbridge.launcher.R
import app.tavernbridge.launcher.entry.LauncherEntryAction
import app.tavernbridge.launcher.entry.LauncherShortcutInstaller
import app.tavernbridge.launcher.entry.PinShortcutResult

@Composable
fun LauncherEntrySettingsCard() {
    val context = LocalContext.current
    val installer = remember(context) { LauncherShortcutInstaller(context) }
    var result by remember { mutableStateOf<PinShortcutResult?>(null) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.entry_settings_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.entry_pin_notice), style = MaterialTheme.typography.bodyMedium)
            LauncherEntryAction.entries.forEach { action ->
                val label = stringResource(when (action) {
                    LauncherEntryAction.START -> R.string.entry_start
                    LauncherEntryAction.STOP -> R.string.entry_stop
                    LauncherEntryAction.OPEN -> R.string.entry_open
                })
                OutlinedButton(
                    onClick = { result = installer.requestPin(action) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.entry_pin_action, label)) }
            }
            result?.let {
                Text(stringResource(when (it) {
                    PinShortcutResult.REQUESTED -> R.string.entry_pin_requested
                    PinShortcutResult.UNSUPPORTED -> R.string.entry_pin_unsupported
                    PinShortcutResult.FAILED -> R.string.entry_pin_failed
                }), style = MaterialTheme.typography.bodyMedium)
            }
            Text(stringResource(R.string.entry_tile_widget_help), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
