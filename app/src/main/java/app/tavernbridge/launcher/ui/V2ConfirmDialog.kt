package app.tavernbridge.launcher.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import app.tavernbridge.launcher.ui.theme.LocalV2ReducedMotion
import kotlinx.coroutines.launch

/** One dismissal path for cancel, outside tap, Back, and confirm; the action runs once, after exit. */
@Composable
internal fun V2ConfirmDialog(onDismissRequest: () -> Unit, title: @Composable () -> Unit,
    text: @Composable () -> Unit, confirmButton: @Composable ((() -> Unit) -> Unit) -> Unit,
    dismissButton: @Composable (() -> Unit) -> Unit) {
    val reduced = LocalV2ReducedMotion.current
    val progress = remember { Animatable(if (reduced) 1f else 0f) }
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    val finish: (() -> Unit) -> Unit = { then ->
        if (!closing) {
            closing = true
            scope.launch { progress.animateTo(0f, tween(if (reduced) 0 else 120)); then() }
        }
    }
    val cancel = { finish(onDismissRequest) }
    Dialog(onDismissRequest = cancel) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        LaunchedEffect(Unit) { progress.animateTo(1f, tween(if (reduced) 0 else 180, easing = LinearOutSlowInEasing)) }
        val dim = .4f * progress.value
        SideEffect { window?.setDimAmount(dim) }
        Surface(Modifier.widthIn(max = 440.dp).fillMaxWidth().graphicsLayer {
            alpha = progress.value
            scaleX = if (reduced) 1f else .96f + .04f * progress.value
            scaleY = scaleX
        }, shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ProvideTextStyle(MaterialTheme.typography.titleLarge, title)
                Box(Modifier.weight(1f, fill = false)) { text() }
                Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) { dismissButton(cancel); confirmButton(finish) }
            }
        }
    }
}
