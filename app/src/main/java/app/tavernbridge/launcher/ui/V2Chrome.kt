package app.tavernbridge.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.animation.core.*
import androidx.compose.animation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.launch
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

@Composable
internal fun V2BottomSheet(
    onClose: () -> Unit,
    handleLabel: String = "시트 닫기",
    footer: (@Composable () -> Unit)? = null,
    animateSize: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val reduced = LocalV2ReducedMotion.current
    val latestClose by rememberUpdatedState(onClose)
    val scope = rememberCoroutineScope()
    var height by remember { mutableIntStateOf(0) }
    var drag by remember { mutableFloatStateOf(0f) }
    var closing by remember { mutableStateOf(false) }
    val open = remember { Animatable(if (reduced) 1f else 0f) }
    val settle = remember { Animatable(0f) }
    var settling by remember { mutableStateOf(false) }
    var motionJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val density = LocalDensity.current
    val dismiss: () -> Unit = {
        if (!closing) {
            closing = true
            motionJob?.cancel()
            scope.launch {
                open.animateTo(0f, tween(if (reduced) 0 else 200, easing = FastOutLinearInEasing))
                latestClose()
            }
        }
    }
    val rest: (Float) -> Unit = { velocity ->
        if (height > 0 && (drag >= height / 3f || velocity >= with(density) { 125.dp.toPx() } && drag > 0f)) dismiss()
        else if (drag > 0f) {
            motionJob?.cancel()
            motionJob = scope.launch {
                settle.snapTo(drag); settling = true
                settle.animateTo(0f, tween(if (reduced) 0 else 200))
                drag = 0f; settling = false
            }
        }
    }
    val startDrag: () -> Unit = {
        motionJob?.cancel()
        if (settling) { drag = settle.value; settling = false }
    }
    val scroll = rememberScrollState()
    val scrollRest by rememberUpdatedState(rest)
    val nested = remember(scroll) { object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (closing || source != NestedScrollSource.UserInput || available.y == 0f || drag == 0f) return Offset.Zero
            startDrag()
            val before = drag
            drag = (drag + available.y).coerceIn(0f, height.toFloat())
            return Offset(0f, drag - before)
        }
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            // A scrollable child (for example an expanded log) gets to use the
            // downward drag first. Fold the sheet only after it reaches the top.
            if (closing || source != NestedScrollSource.UserInput || available.y <= 0f || scroll.value > 0) return Offset.Zero
            startDrag()
            val before = drag
            drag = (drag + available.y).coerceAtMost(height.toFloat())
            return Offset(0f, drag - before)
        }
        override suspend fun onPreFling(available: Velocity): Velocity {
            if (drag <= 0f) return Velocity.Zero
            scrollRest(available.y)
            return Velocity(0f, available.y)
        }
    } }
    LaunchedEffect(Unit) { if (!reduced) open.animateTo(1f, tween(250, easing = LinearOutSlowInEasing)) }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(window) {
            val original = window?.attributes?.dimAmount ?: 0f
            window?.setDimAmount(0f)
            onDispose { window?.setDimAmount(original) }
        }
        val held = if (settling) settle.value else drag
        val visible = open.value * (1f - held / height.coerceAtLeast(1)).coerceIn(0f, 1f)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Spacer(Modifier.matchParentSize().background(Color.Black.copy(alpha = .40f * visible))
                .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null, onClickLabel = handleLabel, onClick = dismiss))
            // Keep the sheet flush with the bottom; let it hug short content. Only a
            // very tall sheet should need scrolling, with room for tapping the scrim.
            BoxWithConstraints(Modifier.align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))
                .imePadding().widthIn(max = 640.dp).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                val topGap = if (maxHeight < 480.dp) 24.dp else 40.dp
                Surface(Modifier.fillMaxWidth().heightIn(max = (maxHeight - topGap).coerceAtLeast(1.dp))
                    .testTag("v2-sheet-surface")
                    .onSizeChanged { height = it.height }.graphicsLayer {
                        translationY = if (reduced) 0f else (1f - visible) * size.height
                    }, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.navigationBarsPadding().then(if (animateSize)
                        Modifier.animateContentSize(tween(if (reduced) 0 else 200)) else Modifier)) {
                        // The visual handle is 36×4, 12dp from both neighbours. Clickable
                        // extends small touch targets; the entire surface can also be dragged.
                        Box(Modifier.fillMaxWidth().height(28.dp)
                            .draggable(rememberDraggableState { delta -> if (!closing) drag = (drag + delta).coerceIn(0f, height.toFloat()) },
                                Orientation.Vertical, onDragStarted = { startDrag() }, onDragStopped = { rest(it) })
                            .clickable(role = Role.Button, onClick = dismiss)
                            .semantics { contentDescription = handleLabel }, contentAlignment = Alignment.Center) {
                            Spacer(Modifier.size(36.dp, 4.dp).background(MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp)))
                        }
                        CompositionLocalProvider(LocalV2SheetDismiss provides dismiss) {
                            Column(Modifier.weight(1f, fill = false).fillMaxWidth().testTag("v2-sheet-body")
                                .nestedScroll(nested).verticalScroll(scroll)
                                .padding(start = 24.dp, end = 24.dp, bottom = if (footer == null) 24.dp else 0.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
                            footer?.let {
                                Box(Modifier.fillMaxWidth().testTag("v2-sheet-footer")
                                    .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 24.dp)) { it() }
                            }
                        }
                    }
                }
            }
        }
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
                    val ink = if (active) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.outline
                    Column(Modifier.weight(1f).heightIn(min = 65.dp)
                        .selectable(active, role = Role.Tab, onClick = { onSelect(tab) }).padding(vertical = 6.dp),
                        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        val shape = RoundedCornerShape(14.dp)
                        Box(Modifier.size(48.dp, 28.dp).then(if (active) Modifier
                            .background(MaterialTheme.colorScheme.secondaryContainer, shape)
                            .border(1.5.dp, MaterialTheme.colorScheme.secondary, shape) else Modifier), contentAlignment = Alignment.Center) {
                            V2TabGlyph(tab, tab.icon(), ink, Modifier.size(21.dp))
                        }
                        Text(tab.label, style = MaterialTheme.typography.labelMedium, color = ink,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium)
                    }
                }
            }
        }
    }
}

@Composable
internal fun V2SheetPrimaryButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp)) { Text(label) }
}

internal val LocalV2NoticeHost = staticCompositionLocalOf<SnackbarHostState?> { null }
internal val LocalV2SheetDismiss = staticCompositionLocalOf<(() -> Unit)?> { null }

internal fun noticeTimeout(message: String, action: String?): Long = when {
    action != null -> 8_000L
    message == "되돌렸어요" -> 2_000L
    else -> 5_000L
}

@Composable
internal fun V2BottomActions(content: @Composable () -> Unit) {
    if (LocalV2NoticeHost.current?.currentSnackbarData == null) content()
}

internal suspend fun showV2Notice(host: SnackbarHostState, message: String, actionLabel: String? = null): SnackbarResult =
    host.showSnackbar(message, actionLabel, withDismissAction = true, duration = SnackbarDuration.Indefinite)

@Composable
internal fun V2NoticeHost(host: SnackbarHostState) {
    val reduced = LocalV2ReducedMotion.current
    val entranceOffset = with(LocalDensity.current) { 16.dp.roundToPx() }
    AnimatedContent(targetState = host.currentSnackbarData, label = "하단 알림", transitionSpec = {
        (fadeIn(tween(if (reduced) 0 else 180)) + slideInVertically(tween(if (reduced) 0 else 180)) { if (reduced) 0 else entranceOffset }) togetherWith
            fadeOut(tween(if (reduced) 0 else 180))
    }) { shown -> shown?.let { data ->
            val accessibility = LocalAccessibilityManager.current
            LaunchedEffect(data, accessibility) {
                val timeout = noticeTimeout(data.visuals.message, data.visuals.actionLabel)
                delay(accessibility?.calculateRecommendedTimeoutMillis(timeout, true, true, true) ?: timeout)
                data.dismiss()
            }
            val density = LocalDensity.current
            val threshold = with(density) { 64.dp.toPx() }
            var width by remember { mutableIntStateOf(1) }
            var offset by remember { mutableFloatStateOf(0f) }
            val scope = rememberCoroutineScope()
            var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
            Box(Modifier.onSizeChanged { width = it.width }.graphicsLayer {
                translationX = if (reduced) 0f else offset
                alpha = (1f - kotlin.math.abs(offset) / width.coerceAtLeast(1)).coerceIn(0f, 1f)
            }.draggable(rememberDraggableState { offset += it }, Orientation.Horizontal,
                onDragStarted = { job?.cancel() }, onDragStopped = { velocity ->
                    val fling = kotlin.math.abs(velocity) > with(density) { 400.dp.toPx() }
                    val dismiss = fling || kotlin.math.abs(offset) >= threshold
                    val sign = if ((if (fling) velocity else offset) < 0) -1 else 1
                    job = scope.launch {
                        animate(offset, if (dismiss) sign * width.toFloat() else 0f,
                            animationSpec = tween(if (reduced) 0 else if (dismiss) 120 else 200)) { value, _ -> offset = value }
                        if (dismiss) data.dismiss()
                    }
                })) {
                Snackbar(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite }, shape = RoundedCornerShape(20.dp),
                    action = data.visuals.actionLabel?.let { label -> { TextButton(onClick = data::performAction) {
                        Text(label, color = MaterialTheme.colorScheme.inversePrimary)
                    } } }, dismissAction = { TextButton(onClick = data::dismiss) {
                        Text("닫기", color = MaterialTheme.colorScheme.inverseOnSurface)
                    } }) { Text(data.visuals.message) }
            }
    } }
}
