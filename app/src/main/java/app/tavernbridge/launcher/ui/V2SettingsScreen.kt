package app.tavernbridge.launcher.ui

import android.os.Build
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.tavernbridge.launcher.BuildConfig
import app.tavernbridge.launcher.LauncherViewModel
import app.tavernbridge.launcher.model.*
import app.tavernbridge.launcher.ui.theme.V2Mono
import app.tavernbridge.launcher.ui.theme.LocalLauncherColors
import app.tavernbridge.launcher.ui.theme.LocalV2ReducedMotion
import kotlinx.coroutines.delay

internal enum class SettingsPage(val title: String, val icon: ImageVector) {
    DISPLAY("화면", Icons.Outlined.TextFields), SERVER("서버 연결", Icons.Outlined.Language),
    BACKGROUND("백그라운드 실행", Icons.Outlined.VerifiedUser), BACKUP("백업 알림", Icons.Outlined.FileDownload),
    ABOUT("앱 정보", Icons.Outlined.Info),
}

@Composable
internal fun V2SettingsScreen(state: LauncherUiState, viewModel: LauncherViewModel) {
    // Save only the destination, never the contents of a security form.
    var editor by rememberSaveable { mutableStateOf<String?>(null) }
    V2SettingsLayout(state, editor, { editor = null }, { mode ->
        when (mode) {
            ABOUT_MANAGEMENT -> V2AppManagementSettings(state, viewModel)
            ABOUT_UPDATE -> UpdateInformationCard(state, viewModel::checkUpdateInformation)
            else -> {
                val env = state.environment
                LanSecuritySettingsPage(LanSecuritySnapshot(env.externalAccessEnabled, env.whitelist, env.basicAuthEnabled, env.perUserBasicAuth),
                    state.isWorking || env.operationActive, { editor = null }, viewModel::saveLanSecurity,
                    requestedListen = mode == "toggle-listen" || env.externalAccessEnabled,
                    requestedBasicAuth = if (mode == "toggle-auth") !env.basicAuthEnabled else env.basicAuthEnabled,
                    running = env.processRunning, result = state.lastOperationResult)
            }
        }
    }) { page, roomy ->
        when (page) {
            SettingsPage.DISPLAY -> V2DisplaySettings(state, viewModel::selectTheme, viewModel::setDisplayPreferences, roomy)
            SettingsPage.SERVER -> V2ServerSettings(state, viewModel) { editor = it }
            SettingsPage.BACKGROUND -> V2BackgroundSettings(state, viewModel)
            SettingsPage.BACKUP -> V2BackupPreferences(state, viewModel)
            SettingsPage.ABOUT -> V2AboutSettings(state, viewModel) { editor = it }
        }
    }
}

internal const val ABOUT_MANAGEMENT = "about-management"
internal const val ABOUT_UPDATE = "about-update"

@Composable
internal fun V2SettingsLayout(state: LauncherUiState, nested: String? = null, closeNested: () -> Unit = {},
    nestedDetail: @Composable (String) -> Unit = {}, detail: @Composable (SettingsPage, Boolean) -> Unit) {
    var open by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    var returned by remember { mutableStateOf<SettingsPage?>(null) }
    val reduced = LocalV2ReducedMotion.current
    val holder = rememberSaveableStateHolder()
    LaunchedEffect(state.portEditorRequested, state.lanAddressRequested) {
        if (state.portEditorRequested || state.lanAddressRequested) { closeNested(); open = SettingsPage.SERVER }
    }
    LaunchedEffect(returned) { if (returned != null) { delay(if (reduced) 0 else 300); returned = null } }
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(Modifier.fillMaxSize().background(colors.background)) {
        val fontScale = LocalDensity.current.fontScale
        val wide = chooseLauncherNavigation(LocalConfiguration.current.screenWidthDp.toFloat()) == LauncherNavigationMode.RAIL
        val roomy = wide && maxWidth >= 890.dp && fontScale < 1.3f
        val listWidth = if (roomy) 350.dp else (maxWidth * .38f).coerceAtMost(300.dp)
        val back = { if (nested != null) closeNested() else { returned = open; open = null } }
        V2BackHandler(enabled = nested != null || !wide && open != null, onBack = back)
        val selected = open ?: SettingsPage.DISPLAY
        val index: @Composable (Modifier) -> Unit = { modifier ->
                Column(modifier.fillMaxHeight()
                    .verticalScroll(rememberScrollState()).padding(horizontal = if (wide) 24.dp else 20.dp, vertical = 22.dp)) {
                    Text("설정", style = MaterialTheme.typography.displaySmall, modifier = Modifier.semantics { heading() })
                    Spacer(Modifier.height(24.dp))
                    SettingsGroup {
                        SettingsPage.entries.forEachIndexed { index, page ->
                            val summary = when (page) {
                                SettingsPage.DISPLAY -> "${state.displayPreferences.textSize.label} · ${settingsThemeName(state.theme)}"
                                SettingsPage.SERVER -> if (state.environment.externalAccessEnabled) "같은 Wi-Fi" else "이 기기만"
                                SettingsPage.BACKGROUND -> if (needsBackgroundAttention(state, Build.VERSION.SDK_INT)) "확인 필요" else "정상"
                                SettingsPage.BACKUP -> if (state.backupReminder.enabled) state.backupReminder.interval.label else "꺼짐"
                                SettingsPage.ABOUT -> BuildConfig.VERSION_NAME
                            }
                            val active = wide && selected == page
                            SettingsRow(page.title, value = summary, icon = page.icon, selected = active, returned = !wide && returned == page,
                                mono = page == SettingsPage.ABOUT,
                                valueColor = if (page == SettingsPage.BACKGROUND && needsBackgroundAttention(state, Build.VERSION.SDK_INT))
                                    (if (active) colors.onSecondaryContainer else colors.secondary) else colors.onSurfaceVariant,
                                onClick = { if (selected != page || !wide || nested != null) {
                                    if (wide && selected != page) holder.removeState("detail-" + page.name)
                                    closeNested(); open = page
                                } })
                            if (index != SettingsPage.entries.lastIndex) SettingsDivider(52)
                        }
                    }
                }
            }
        Row(Modifier.fillMaxSize()) {
            if (wide) index(Modifier.width(listWidth))
            val route: Pair<SettingsPage?, String?> = (if (wide) selected else open) to nested
            val travel = with(LocalDensity.current) { 24.dp.roundToPx() }
            AnimatedContent(route, modifier = Modifier.weight(1f).fillMaxHeight(), transitionSpec = {
                val depth: (Pair<SettingsPage?, String?>) -> Int = { if (it.second != null) 2 else if (it.first != null) 1 else 0 }
                val t = if (reduced) 0 else if (depth(targetState) < depth(initialState)) 180 else 220
                (when {
                    reduced -> EnterTransition.None togetherWith ExitTransition.None
                    depth(targetState) > depth(initialState) -> (fadeIn(tween(t)) + slideInHorizontally(tween(t, easing = LinearOutSlowInEasing)) { travel }) togetherWith fadeOut(tween(t))
                    depth(targetState) < depth(initialState) -> fadeIn(tween(t)) togetherWith (fadeOut(tween(t)) + slideOutHorizontally(tween(t, easing = FastOutLinearInEasing)) { travel })
                    else -> fadeIn(tween(120)) togetherWith fadeOut(tween(120))
                }).using(null)
            }, label = "settings destination") { (page, child) ->
                if (page == null) holder.SaveableStateProvider("settings-index") { index(Modifier.fillMaxWidth()) }
                else holder.SaveableStateProvider(if (child == null) "detail-" + page.name else "child-" + child) {
                Column(Modifier.fillMaxSize().background(if (wide) colors.surface else colors.background)
                    .verticalScroll(rememberScrollState()).padding(horizontal = if (wide) 40.dp else 20.dp, vertical = if (wide) 24.dp else 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!wide || child != null) {
                            val requester = remember { FocusRequester() }
                            IconButton(onClick = back, modifier = Modifier.focusRequester(requester)) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "뒤로") }
                            LaunchedEffect(page, child) { requester.requestFocus() }
                        }
                        Text(when (child) {
                            null -> page.title
                            ABOUT_MANAGEMENT -> "바로가기 · 앱 관리"
                            ABOUT_UPDATE -> "업데이트 정보"
                            else -> "외부 접속·인증 설정"
                        }, style = if (wide) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(vertical = 12.dp).semantics { heading() })
                    }
                    if (child == null) detail(page, roomy) else nestedDetail(child)
                    Spacer(Modifier.height(12.dp))
                }
                }
            }
        }
    }
}

internal fun settingsThemeName(theme: AppTheme): String = when (theme) {
    AppTheme.SYSTEM -> "시스템"; AppTheme.LIGHT -> "밝게"; AppTheme.DARK -> "어둡게"
}

@Composable
internal fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surface), content = content)
}

@Composable
internal fun SettingsHeading(label: String) {
    Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp).semantics { heading() })
}

@Composable
internal fun SettingsCaption(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun SettingsDivider(indent: Int = 20) {
    HorizontalDivider(Modifier.padding(start = indent.dp, end = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
internal fun SettingsRow(title: String, value: String = "", caption: String = "", icon: ImageVector? = null,
    mono: Boolean = false, valueColor: Color = MaterialTheme.colorScheme.onSurfaceVariant, selected: Boolean = false,
    enabled: Boolean = true, checked: Boolean? = null, returned: Boolean = false, onClick: (() -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val click = when {
        onClick == null -> Modifier
        checked != null -> Modifier.toggleable(checked, interactionSource = source, indication = null, enabled = enabled, role = Role.Switch) { onClick() }
        else -> Modifier.clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
    }
    val stack = LocalDensity.current.fontScale >= 1.3f
    val tokens = LocalLauncherColors.current
    val base = if (selected) tokens.sunken else colors.surface
    Row(Modifier.fillMaxWidth().background(tokens.ink.copy(alpha = if (pressed) .08f else if (returned) .04f else 0f).compositeOver(base))
        .then(click).heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) Icon(icon, null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) colors.onSurface else tokens.disabledInk)
            if (caption.isNotBlank()) SettingsCaption(caption)
            if (stack && value.isNotBlank()) Text(value, color = valueColor, style = MaterialTheme.typography.bodyMedium,
                fontFamily = if (mono) V2Mono else null)
        }
        if (!stack && value.isNotBlank()) Text(value, modifier = Modifier.widthIn(max = 138.dp),
            textAlign = TextAlign.End, style = MaterialTheme.typography.bodyMedium, color = valueColor, fontFamily = if (mono) V2Mono else null)
        if (checked != null) SettingsSwitch(checked, enabled, pressed)
        else if (onClick != null) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(18.dp), tint = tokens.ink3)
    }
}

/** Semantics and the 48dp+ touch target belong to the full row, not the tiny visual. */
@Composable
internal fun SettingsSwitch(checked: Boolean, enabled: Boolean = true, pressed: Boolean = false) {
    val c = LocalLauncherColors.current
    val duration = if (LocalV2ReducedMotion.current) 0 else 150
    val position by animateDpAsState(if (checked) 18.dp else 0.dp, tween(duration, easing = LinearOutSlowInEasing), label = "switch knob")
    val track by animateColorAsState(if (checked) c.accent else c.sunken, tween(duration), label = "switch track")
    Box(Modifier.size(44.dp, 26.dp).graphicsLayer { alpha = if (enabled) 1f else .38f }
        .background(track, CircleShape).border(1.dp, if (checked) track else c.hairline, CircleShape), contentAlignment = Alignment.CenterStart) {
        Box(Modifier.offset(x = position + 3.dp).size(20.dp), contentAlignment = Alignment.Center) {
            if (pressed) Box(Modifier.requiredSize(36.dp).background(c.ink.copy(alpha = .12f), CircleShape))
            Box(Modifier.size(20.dp).background(if (checked) c.onInk else c.ink3, CircleShape))
        }
    }
}

@Composable
internal fun SettingsChoiceMark(checked: Boolean, selection: Boolean = false, enabled: Boolean = true) {
    val c = LocalLauncherColors.current
    val progress by animateFloatAsState(if (checked) 1f else 0f, tween(if (LocalV2ReducedMotion.current) 0 else 120), label = "check mark")
    val shape = RoundedCornerShape(if (selection) 6.dp else 5.dp)
    Box(Modifier.size(if (selection) 22.dp else 20.dp).graphicsLayer { alpha = if (enabled) 1f else .38f }
        .background(if (checked) c.ink else c.surface, shape).border(1.dp, if (checked) c.ink else c.ink3, shape), contentAlignment = Alignment.Center) {
        Icon(Icons.Outlined.Check, null, Modifier.size(16.dp).graphicsLayer {
            scaleX = .8f + .2f * progress; scaleY = scaleX; alpha = progress
        }, tint = c.onInk)
    }
}

@Composable
internal fun <T> SettingsSegments(options: List<T>, selected: T, label: (T) -> String, onChange: (T) -> Unit,
    enabled: Boolean = true) {
    val colors = MaterialTheme.colorScheme
    if (options.isEmpty()) return
    BoxWithConstraints(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.surfaceVariant)
        .heightIn(min = 48.dp).padding(3.dp)) {
        val cell = maxWidth / options.size
        val position by animateDpAsState(cell * options.indexOf(selected).coerceAtLeast(0),
            tween(if (LocalV2ReducedMotion.current) 0 else 200, easing = LinearOutSlowInEasing), label = "selected segment")
        Box(Modifier.matchParentSize()) {
            Box(Modifier.offset(x = position).width(cell).fillMaxHeight().background(colors.surface, RoundedCornerShape(9.dp)))
        }
        Row(Modifier.fillMaxWidth().selectableGroup()) {
        options.forEach { value ->
            val active = value == selected
            val source = remember { MutableInteractionSource() }
            val pressed by source.collectIsPressedAsState()
            Box(Modifier.weight(1f).clip(RoundedCornerShape(9.dp)).background(colors.onSurface.copy(alpha = if (pressed) .08f else 0f))
                .selectable(active, interactionSource = source, indication = null, enabled = enabled, role = Role.RadioButton) { onChange(value) }
                .heightIn(min = 42.dp).padding(horizontal = 2.dp, vertical = 5.dp), contentAlignment = Alignment.Center) {
                Text(label(value), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    color = when { !enabled -> LocalLauncherColors.current.disabledInk; active -> colors.onSurface; else -> colors.onSurfaceVariant })
            }
        }
        }
    }
}
