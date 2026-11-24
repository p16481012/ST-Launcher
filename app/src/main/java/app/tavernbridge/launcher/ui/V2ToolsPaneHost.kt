package app.tavernbridge.launcher.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable internal fun ToolPaneHost(embedded: Boolean, onClose: () -> Unit, content: @Composable () -> Unit) {
    if (embedded) content() else Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) { content() }
    }
}
