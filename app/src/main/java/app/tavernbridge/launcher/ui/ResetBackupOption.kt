package app.tavernbridge.launcher.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
internal fun ResetBackupOption(checked: Boolean, change: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().toggleable(checked, role = Role.Checkbox, onValueChange = change)) {
        Row(Modifier.heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Checkbox(checked, onCheckedChange = null)
            Text("초기화 전에 전체 백업하기", style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface)
        }
        Text(if (checked) "Download에 저장 · API 키 암호화 안 됨" else "백업 없이 진행해요.",
            style = MaterialTheme.typography.bodySmall,
            color = if (checked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
    }
}
