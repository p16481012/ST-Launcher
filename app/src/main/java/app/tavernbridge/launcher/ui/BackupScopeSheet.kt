package app.tavernbridge.launcher.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.tavernbridge.launcher.R
import app.tavernbridge.launcher.model.BackupCategory
import app.tavernbridge.launcher.model.LauncherUiState
import app.tavernbridge.launcher.model.backupFolderInfo
import app.tavernbridge.launcher.ui.theme.LocalV2ReducedMotion

@Composable
internal fun BackupScopeSheet(
    state: LauncherUiState,
    choosing: Boolean,
    onCreate: (Set<BackupCategory>, Boolean, Set<String>) -> Unit,
    onClose: () -> Unit,
    progress: @Composable ColumnScope.() -> Unit,
) {
    var scope by rememberSaveable { mutableStateOf("data") }
    var keys by rememberSaveable { mutableStateOf(listOf(BackupCategory.USER_DATA.key)) }
    var folders by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var secrets by rememberSaveable { mutableStateOf(state.displayPreferences.includeSecretsByDefault) }
    val selected = keys.mapNotNull(BackupCategory::fromKey).toSet()
    val canChooseSecrets = BackupCategory.USER_DATA in selected || BackupCategory.FULL in selected
    val canStart = state.backupStorageReady == true && selected.isNotEmpty() &&
        (BackupCategory.CUSTOM !in selected || folders.isNotEmpty())
    val pickScope: (String) -> Unit = { key ->
        if (key != scope) {
            scope = key
            keys = listOf(if (key == "full") BackupCategory.FULL.key else BackupCategory.USER_DATA.key)
            folders = emptyList()
        }
    }
    val reduced = LocalV2ReducedMotion.current
    val c = MaterialTheme.colorScheme
    V2BottomSheet(onClose = onClose,
        handleLabel = if (choosing) "시트 닫기" else stringResource(R.string.ui_collapse_progress),
        animateSize = true,
        footer = if (!choosing) null else ({
            if (canStart) V2SheetPrimaryButton("백업 시작") {
                onCreate(selected, secrets && canChooseSecrets, folders.toSet())
            } else SettingsCaption(if (state.backupStorageReady != true) "저장 공간을 연결한 뒤 백업할 수 있어요."
                else "백업할 항목이나 사용자 폴더를 선택해 주세요.")
        }),
    ) {
        Crossfade(choosing, modifier = Modifier.fillMaxWidth(), animationSpec = tween(if (reduced) 0 else 120),
            label = "백업 범위와 진행") { selecting ->
            if (!selecting) Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = progress)
            else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("백업 범위", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    // The two common scopes stay visible; custom folders remain one tap away.
                    V2LinkAction(if (scope == "custom") "직접 선택 닫기" else "직접 선택",
                        { pickScope(if (scope == "custom") "data" else "custom") })
                }
                Column(Modifier.fillMaxWidth().selectableGroup()) {
                    ScopeRow("전체 백업", BackupCategory.FULL.description, scope == "full") { pickScope("full") }
                    HorizontalDivider(color = c.outlineVariant)
                    ScopeRow("사용자 데이터만", BackupCategory.USER_DATA.description, scope == "data") { pickScope("data") }
                }
                if (scope == "custom") {
                    Column {
                        BackupCategory.entries.filter { it != BackupCategory.FULL }.forEach { item ->
                            ScopeCheckRow(item.label, item.description, item in selected) { checked ->
                                keys = if (checked) (keys + item.key).distinct() else keys - item.key
                                if (item == BackupCategory.CUSTOM && !checked) folders = emptyList()
                            }
                        }
                    }
                    if (BackupCategory.CUSTOM in selected) {
                        Surface(color = c.surfaceVariant, shape = RoundedCornerShape(14.dp)) {
                            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                Text("~/SillyTavern/data/default-user", style = MaterialTheme.typography.titleSmall)
                                if (state.backupFolders.isEmpty()) Text("선택할 사용자 폴더가 없습니다.",
                                    style = MaterialTheme.typography.bodySmall)
                                state.backupFolders.forEach { folder ->
                                    ScopeCheckRow(folder.label, backupFolderInfo(folder.key).description, folder.key in folders) { checked ->
                                        folders = if (checked) (folders + folder.key).distinct() else folders - folder.key
                                    }
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().toggleable(secrets && canChooseSecrets, enabled = canChooseSecrets,
                    role = Role.Switch, onValueChange = { secrets = it }).heightIn(min = 52.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("API 키 등 비밀 설정 포함", style = MaterialTheme.typography.bodyLarge)
                        Text(if (secrets && canChooseSecrets) "API 키와 선택한 설정 파일의 외부 접속 로그인 정보도 들어갈 수 있어요. ZIP은 암호화되지 않으니 공유하지 마세요."
                            else if (selected.any { it == BackupCategory.CONFIG || it == BackupCategory.FULL })
                                "지금은 secrets.json과 외부 접속 로그인 정보를 넣지 않아요. 복원한 설정에서는 외부 접속이 꺼져요. ZIP은 암호화되지 않아요."
                            else "지금은 secrets.json을 넣지 않아요. ZIP은 암호화되지 않아요.",
                            style = MaterialTheme.typography.bodySmall, color = if (secrets && canChooseSecrets) c.error else c.onSurfaceVariant)
                    }
                    SettingsSwitch(secrets && canChooseSecrets, enabled = canChooseSecrets)
                }
                if (state.environment.processRunning) {
                    Surface(color = c.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
                        Text("백업 중엔 서버를 잠시 꺼요. 완료하면 다시 켜고, 중단하면 꺼진 상태로 남아요.",
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun ScopeRow(title: String, detail: String, selected: Boolean, choose: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = choose)
        .heightIn(min = 56.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        RadioButton(selected = selected, onClick = null)
    }
}

@Composable
private fun ScopeCheckRow(title: String, detail: String, selected: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(selected, role = Role.Checkbox, onValueChange = change)
        .heightIn(min = 48.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SettingsChoiceMark(selected, selection = true)
    }
}
