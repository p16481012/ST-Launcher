package app.tavernbridge.launcher.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** A full-width action inside an explicitly opened item sheet, not a navigation or footer button. */
@Composable
internal fun V2SheetActionRow(label: String, danger: Boolean = false, leadingIcon: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().v2RowTap("sheet:$label", role = Role.Button, click = onClick)
        .heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (leadingIcon != null) { leadingIcon(); Spacer(Modifier.width(12.dp)) }
        Text(label, style = MaterialTheme.typography.bodyLarge,
            color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    }
}
