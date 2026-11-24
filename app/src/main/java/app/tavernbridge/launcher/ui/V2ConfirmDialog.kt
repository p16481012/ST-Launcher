package app.tavernbridge.launcher.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import app.tavernbridge.launcher.ui.theme.LocalV2ReducedMotion
import kotlinx.coroutines.launch

@Composable
internal fun V2AlertDialog(onDismissRequest: () -> Unit, title: @Composable () -> Unit,
    text: @Composable () -> Unit, confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null, properties: DialogProperties = DialogProperties(),
    scrollBody: Boolean = true) {
    V2DialogFrame(onDismissRequest, title, text, properties, scrollBody) { _, _ ->
        dismissButton?.invoke()
        confirmButton()
    }
}

/** The danger action is dispatched once, after the exit, not from outside/back/dismiss. */
@Composable
internal fun V2ConfirmDialog(onDismissRequest: () -> Unit, title: @Composable () -> Unit,
    text: @Composable () -> Unit, confirmButton: @Composable ((() -> Unit) -> Unit) -> Unit,
    dismissButton: @Composable (() -> Unit) -> Unit) {
    V2DialogFrame(onDismissRequest, title, text, DialogProperties(), true) { cancel, finish ->
        dismissButton(cancel)
        confirmButton(finish)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun V2DialogFrame(onDismissRequest: () -> Unit, title: @Composable () -> Unit,
    text: @Composable () -> Unit, properties: DialogProperties, scrollBody: Boolean,
    actions: @Composable (() -> Unit, (() -> Unit) -> Unit) -> Unit) {
    val reduced = LocalV2ReducedMotion.current
    val progress = remember { Animatable(if (reduced) 1f else 0f) }
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    val updatedDismiss by rememberUpdatedState(onDismissRequest)
    val finish: (() -> Unit) -> Unit = { then ->
        if (!closing) {
            closing = true
            scope.launch { progress.animateTo(0f, tween(if (reduced) 0 else 120)); then() }
        }
    }
    val cancel = { finish(updatedDismiss) }
    Dialog(onDismissRequest = cancel, properties = DialogProperties(
        dismissOnBackPress = properties.dismissOnBackPress, dismissOnClickOutside = properties.dismissOnClickOutside,
        securePolicy = properties.securePolicy, usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(window) {
            val previous = window?.attributes?.dimAmount
            window?.setDimAmount(0f)
            onDispose { previous?.let { window?.setDimAmount(it) } }
        }
        LaunchedEffect(reduced) {
            if (!closing) progress.animateTo(1f, tween(if (reduced) 0 else 180, easing = LinearOutSlowInEasing))
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val outside = if (properties.dismissOnClickOutside) Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null,
                onClickLabel = "닫기", onClick = cancel) else Modifier
            Spacer(Modifier.matchParentSize().background(Color.Black.copy(alpha = .4f * progress.value)).then(outside))
            // Take system insets once, and size against the actual window, not the platform's
            // already-narrow dialog. The surface retains 24dp side margins at phone widths.
            Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = 24.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center) {
                Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth().testTag("v2-dialog-surface").graphicsLayer {
                    alpha = progress.value
                    scaleX = if (reduced) 1f else .96f + .04f * progress.value
                    scaleY = scaleX
                }, shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        // With a keyboard or large text the body can move without pushing actions
                        // below the viewport. Lazy lists retain their own bounded scrolling.
                        val body = Modifier.fillMaxWidth().weight(1f, fill = false).testTag("v2-dialog-body")
                        Column(if (scrollBody) body.verticalScroll(rememberScrollState()) else body,
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.semantics { heading() }) { ProvideTextStyle(MaterialTheme.typography.titleLarge, title) }
                            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                                ProvideTextStyle(MaterialTheme.typography.bodyLarge, text)
                            }
                        }
                        FlowRow(Modifier.fillMaxWidth().testTag("v2-dialog-actions").padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) { actions(cancel, finish) }
                    }
                }
            }
        }
    }
}
