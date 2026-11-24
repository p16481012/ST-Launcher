package app.tavernbridge.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.delay
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.tavernbridge.launcher.model.MainSection
import app.tavernbridge.launcher.model.launcherTabs
import app.tavernbridge.launcher.ui.theme.LocalV2ReducedMotion

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun V2BottomSheet(onClose: () -> Unit, handleLabel: String = "시트 닫기", content: @Composable ColumnScope.() -> Unit) {
    val reduced = LocalV2ReducedMotion.current
    val latestClose by rememberUpdatedState(onClose)
    val handle: @Composable () -> Unit = {
        // In the animated sheet Material owns the drag gesture; only the static dialog needs a recognizer.
        val drag = if (!reduced) Modifier else Modifier.pointerInput(Unit) {
            var dy = 0f
            detectVerticalDragGestures(onDragStart = { dy = 0f }, onDragEnd = {
                if (dy > 20.dp.toPx()) latestClose()
            }) { _, distance -> dy += distance }
        }
        Box(Modifier.fillMaxWidth().height(48.dp).then(drag)
            .clickable(role = Role.Button) { latestClose() }
            .semantics { contentDescription = handleLabel }, contentAlignment = Alignment.Center) {
            Spacer(Modifier.size(36.dp, 4.dp).background(MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp)))
        }
    }
    val body: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
    if (reduced) {
        Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                Spacer(Modifier.matchParentSize().clickable(interactionSource = interaction, indication = null, onClick = onClose))
                Surface(Modifier.widthIn(max = 640.dp).fillMaxWidth(),
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp), color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.navigationBarsPadding()) {
                        handle()
                        body()
                    }
                }
            }
        }
    } else ModalBottomSheet(onDismissRequest = onClose, containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp), tonalElevation = 0.dp,
        dragHandle = null, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        handle()
        body()
    }
}

@Composable
internal fun V2TabBar(current: MainSection, onSelect: (MainSection) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.navigationBarsPadding()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().selectableGroup()) {
                launcherTabs.forEach { tab ->
                    val active = tab == current
                    val ink = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
                    Column(Modifier.weight(1f).heightIn(min = 65.dp)
                        .selectable(active, role = Role.Tab, onClick = { onSelect(tab) }).padding(vertical = 6.dp),
                        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(tab.icon(), null, Modifier.size(21.dp), tint = ink)
                        Text(tab.label, style = MaterialTheme.typography.labelMedium, color = ink,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium)
                    }
                }
            }
        }
    }
}

internal val LocalV2NoticeHost = staticCompositionLocalOf<SnackbarHostState?> { null }

@Composable
internal fun V2BottomActions(content: @Composable () -> Unit) {
    if (LocalV2NoticeHost.current?.currentSnackbarData == null) content()
}

internal suspend fun showV2Notice(host: SnackbarHostState, message: String, actionLabel: String? = null): SnackbarResult =
    host.showSnackbar(message, actionLabel, withDismissAction = true, duration = SnackbarDuration.Indefinite)

@Composable
internal fun V2NoticeHost(host: SnackbarHostState) {
    host.currentSnackbarData?.let { data ->
        key(data) {
            val accessibility = LocalAccessibilityManager.current
            LaunchedEffect(data, accessibility) {
                delay(accessibility?.calculateRecommendedTimeoutMillis(5_000L, true, true, true) ?: 5_000L)
                data.dismiss()
            }
            val swipe = rememberSwipeToDismissBoxState()
            LaunchedEffect(swipe.currentValue) {
                if (swipe.currentValue != SwipeToDismissBoxValue.Settled) data.dismiss()
            }
            SwipeToDismissBox(state = swipe, backgroundContent = {}) {
                Snackbar(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite }, shape = RoundedCornerShape(14.dp),
                    action = data.visuals.actionLabel?.let { label -> { TextButton(onClick = data::performAction) {
                        Text(label, color = MaterialTheme.colorScheme.inversePrimary)
                    } } }, dismissAction = { TextButton(onClick = data::dismiss) {
                        Text("닫기", color = MaterialTheme.colorScheme.inverseOnSurface)
                    } }) { Text(data.visuals.message) }
            }
        }
    }
}
