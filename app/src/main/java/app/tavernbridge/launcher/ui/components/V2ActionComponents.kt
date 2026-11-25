package app.tavernbridge.launcher.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import app.tavernbridge.launcher.ui.theme.LocalLauncherColors

/** Borderless labels on the snackbar's inverse surface; keep the normal touch target. */
@Composable internal fun V2NoticeTextAction(label: String, ink: Color, onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(label, color = ink) }
}

/** Supplemental navigation. The underline remains a cue even without hue. */
@Composable internal fun V2LinkAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null) {
    TextButton(onClick, modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 6.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = LocalLauncherColors.current.accent)) {
        V2ActionLabel(label, leadingIcon, underlined = true)
    }
}

/** Only for the trailing action of a screen header (save, select all, etc.). */
@Composable internal fun V2TopBarAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null) {
    TextButton(onClick, modifier.heightIn(min = 48.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = LocalLauncherColors.current.accent)) {
        V2ActionLabel(label, leadingIcon, underlined = true)
    }
}

@Composable internal fun V2OutlineAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null) {
    val c = LocalLauncherColors.current
    OutlinedButton(onClick, modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, c.ink2), colors = ButtonDefaults.outlinedButtonColors(contentColor = c.ink),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) { V2ActionLabel(label, leadingIcon) }
}

@Composable internal fun V2DangerAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null) {
    val c = LocalLauncherColors.current
    Button(onClick, modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = c.danger, contentColor = if (c.dark) Color.Black else Color.White),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) { V2ActionLabel(label, leadingIcon) }
}

@Composable private fun V2ActionLabel(label: String, leadingIcon: (@Composable () -> Unit)?, underlined: Boolean = false) {
    if (leadingIcon != null) {
        leadingIcon()
        Spacer(Modifier.width(6.dp))
    }
    Text(label, textDecoration = if (underlined) TextDecoration.Underline else null)
}

/** Navigation to a page, sheet or external app; describe the destination for an external launch. */
@Composable internal fun V2NavigationRow(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, description: String = "",
    leadingIcon: (@Composable () -> Unit)? = null) {
    val c = LocalLauncherColors.current
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = c.surface) {
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).heightIn(min = 52.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            leadingIcon?.invoke()
            Column(Modifier.weight(1f)) {
                Text(label, color = c.ink, style = MaterialTheme.typography.bodyLarge)
                if (description.isNotBlank()) Text(description, color = c.ink2, style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(20.dp), tint = c.ink2)
        }
    }
}
