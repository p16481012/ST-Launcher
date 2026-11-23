package app.tavernbridge.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.tavernbridge.launcher.R
import app.tavernbridge.launcher.model.SillyBranch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> SectionTabs(options: List<T>, selected: T, onSelect: (T) -> Unit, label: (T) -> String) {
    ScrollableTabRow(selectedTabIndex = options.indexOf(selected).coerceAtLeast(0), edgePadding = 0.dp,
        containerColor = MaterialTheme.colorScheme.background) {
        options.forEach { option ->
            Tab(selected = selected == option, onClick = { onSelect(option) },
                modifier = Modifier.heightIn(min = 48.dp), text = { Text(label(option)) })
        }
    }
}

@Composable
internal fun BranchChangeButtons(current: SillyBranch?, onSwitch: (SillyBranch) -> Unit, enabled: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.ui_branch_change), style = MaterialTheme.typography.titleMedium)
        current?.let { Text(stringResource(R.string.ui_current_branch, it.label), style = MaterialTheme.typography.bodySmall) }
        SillyBranch.entries.filter { it != current }.forEach { target ->
            OutlinedButton(onClick = { onSwitch(target) }, enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.ui_branch_change_to, target.label))
            }
        }
    }
}

@Composable
internal fun DangerousInstallationActions(serverRunning: Boolean, enabled: Boolean, onReset: () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.ui_danger_area), modifier = Modifier.weight(1f).semantics { heading() },
                    color = MaterialTheme.colorScheme.error)
                Icon(if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown, null)
            }
            if (expanded) {
                Text(stringResource(R.string.ui_reset_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.ui_reset_description), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = onReset, enabled = enabled && !serverRunning,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text(stringResource(if (serverRunning) R.string.ui_stop_before_reset else R.string.ui_reset_action))
                }
            }
        }
    }
}
