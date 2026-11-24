package app.tavernbridge.launcher.ui

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.tavernbridge.launcher.BuildConfig
import app.tavernbridge.launcher.LauncherViewModel
import app.tavernbridge.launcher.model.*
import app.tavernbridge.launcher.ui.theme.V2Mono

internal enum class SettingsPage(val title: String, val icon: ImageVector) {
    DISPLAY("화면", Icons.Outlined.TextFields), SERVER("서버 연결", Icons.Outlined.Language),
    BACKGROUND("백그라운드 실행", Icons.Outlined.VerifiedUser), BACKUP("백업 알림", Icons.Outlined.FileDownload),
    ABOUT("앱 정보", Icons.Outlined.Info),
}

@Composable
internal fun V2SettingsScreen(state: LauncherUiState, viewModel: LauncherViewModel) {
    V2SettingsLayout(state) { page, roomy ->
        when (page) {
            SettingsPage.DISPLAY -> V2DisplaySettings(state, viewModel::selectTheme, viewModel::setDisplayPreferences, roomy)
            SettingsPage.SERVER -> V2ServerSettings(state, viewModel)
            SettingsPage.BACKGROUND -> V2BackgroundSettings(state, viewModel)
            SettingsPage.BACKUP -> V2BackupPreferences(state, viewModel)
            SettingsPage.ABOUT -> V2AboutSettings(state, viewModel)
        }
    }
}

@Composable
internal fun V2SettingsLayout(state: LauncherUiState, detail: @Composable (SettingsPage, Boolean) -> Unit) {
    var open by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(Modifier.fillMaxSize().background(colors.background)) {
        val fontScale = LocalDensity.current.fontScale
        val wide = maxWidth >= 720.dp && fontScale < 1.6f
        val roomy = wide && maxWidth >= 890.dp && fontScale < 1.3f
        BackHandler(enabled = !wide && open != null) { open = null }
        val selected = open ?: SettingsPage.DISPLAY
        Row(Modifier.fillMaxSize()) {
            if (wide || open == null) {
                Column((if (wide) Modifier.width(304.dp) else Modifier.fillMaxWidth()).fillMaxHeight()
                    .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 22.dp)) {
                    Text("설정", style = MaterialTheme.typography.displaySmall, modifier = Modifier.semantics { heading() })
                    Spacer(Modifier.height(24.dp))
                    SettingsGroup {
                        SettingsPage.entries.forEachIndexed { index, page ->
                            val summary = when (page) {
                                SettingsPage.DISPLAY -> "${state.displayPreferences.textSize.label} · ${settingsThemeName(state.theme)}"
                                SettingsPage.SERVER -> if (state.environment.externalAccessEnabled) "외부 접속" else "이 기기만"
                                SettingsPage.BACKGROUND -> if (state.backgroundStatus.termuxBatteryUnrestricted) "제한 없음" else "확인 필요"
                                SettingsPage.BACKUP -> if (state.backupReminder.enabled) state.backupReminder.interval.label else "꺼짐"
                                SettingsPage.ABOUT -> BuildConfig.VERSION_NAME
                            }
                            val active = wide && selected == page
                            SettingsRow(page.title, value = summary, icon = page.icon, selected = active,
                                valueColor = if (page == SettingsPage.BACKGROUND && !state.backgroundStatus.termuxBatteryUnrestricted) colors.error else colors.onSurfaceVariant,
                                onClick = { open = page })
                            if (index != SettingsPage.entries.lastIndex) SettingsDivider(52)
                        }
                    }
                }
            }
            if (wide || open != null) key(selected) {
                Column(Modifier.weight(1f).fillMaxHeight().background(if (wide) colors.surface else colors.background)
                    .verticalScroll(rememberScrollState()).padding(horizontal = if (wide) 32.dp else 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!wide) IconButton(onClick = { open = null }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "설정 목록으로") }
                        Text(selected.title, style = if (wide) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(vertical = 12.dp).semantics { heading() })
                    }
                    detail(selected, roomy)
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

internal fun settingsThemeName(theme: AppTheme): String = when (theme) {
    AppTheme.SYSTEM -> "시스템"; AppTheme.LIGHT -> "밝게"; AppTheme.DARK -> "어둡게"; else -> theme.label
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
    enabled: Boolean = true, checked: Boolean? = null, onClick: (() -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    val click = when {
        onClick == null -> Modifier
        checked != null -> Modifier.toggleable(checked, enabled = enabled, role = Role.Switch) { onClick() }
        else -> Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    }
    val stack = LocalDensity.current.fontScale >= 1.3f
    Row(Modifier.fillMaxWidth().background(if (selected) colors.surfaceVariant else colors.surface)
        .then(click).heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) Icon(icon, null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) colors.onSurface else colors.onSurfaceVariant)
            if (caption.isNotBlank()) SettingsCaption(caption)
            if (stack && value.isNotBlank()) Text(value, color = valueColor, style = MaterialTheme.typography.bodyMedium,
                fontFamily = if (mono) V2Mono else null)
        }
        if (!stack && value.isNotBlank()) Text(value, modifier = Modifier.widthIn(max = 138.dp),
            textAlign = TextAlign.End, style = MaterialTheme.typography.bodyMedium, color = valueColor, fontFamily = if (mono) V2Mono else null)
        if (checked != null) Switch(checked = checked, onCheckedChange = null, enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = colors.primary, checkedThumbColor = colors.onPrimary,
                uncheckedTrackColor = colors.surfaceVariant, uncheckedBorderColor = colors.outlineVariant))
        else if (onClick != null) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
    }
}

@Composable
internal fun <T> SettingsSegments(options: List<T>, selected: T, label: (T) -> String, onChange: (T) -> Unit,
    enabled: Boolean = true) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.surfaceVariant)
        .padding(3.dp).selectableGroup()) {
        options.forEach { value ->
            val active = value == selected
            Box(Modifier.weight(1f).clip(RoundedCornerShape(9.dp)).background(if (active) colors.surface else Color.Transparent)
                .selectable(active, enabled = enabled, role = Role.RadioButton) { onChange(value) }
                .heightIn(min = 44.dp).padding(horizontal = 2.dp, vertical = 5.dp), contentAlignment = Alignment.Center) {
                Text(label(value), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) colors.onSurface else colors.onSurfaceVariant)
            }
        }
    }
}
