package app.tavernbridge.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.tavernbridge.launcher.model.*
import java.time.LocalDate

private enum class BackupPane { OVERVIEW, ARCHIVE, ALL, CREATE, SPACE, MORE }

@Composable
internal fun V2BackupScreen(
    state: LauncherUiState,
    onCreateBackup: (Set<BackupCategory>, Boolean, Set<String>) -> Unit,
    onRefresh: () -> Unit,
    onReminderChange: (Boolean) -> Unit,
    onTransfer: (BackupArchive, BackupTransferAction) -> Unit,
    onOpenFolder: () -> Unit,
    onSafety: () -> Unit,
    onScan: () -> Unit,
    onPreviewCleanup: (Int, Int) -> Unit,
    onSaveAnnotation: (String, Boolean, String) -> Unit,
    onEncrypted: (EncryptedBackupMode) -> Unit,
    onSelective: (BackupArchive) -> Unit,
    onImport: () -> Unit,
    storageSetupCommand: String,
    onTermux: () -> Unit,
    onCopied: () -> Unit,
    onRestore: (BackupArchive) -> Unit,
    onDelete: (List<BackupArchive>) -> Unit,
) {
    LaunchedEffect(state.environment.managerConnected, state.termuxSetup.verified) {
        if (state.environment.managerConnected && state.termuxSetup.verified) onRefresh()
    }
    var pane by rememberSaveable { mutableStateOf(BackupPane.OVERVIEW) }
    var fromAll by rememberSaveable { mutableStateOf(false) }
    var selectedName by rememberSaveable { mutableStateOf<String?>(null) }
    val busy = state.isWorking || state.environment.operationActive
    // Picker reads a user-granted document; staging probes actual Termux read access.
    // A separate "can Termux write a new backup ZIP?" check must not gate the picker.
    val canImport = !busy && !state.environment.processRunning
    val archives = state.backups
    val selectArchive: (BackupArchive, Boolean) -> Unit = { archive, all ->
        selectedName = archive.fileName; fromAll = all; pane = BackupPane.ARCHIVE
    }
    val masterScroll = rememberLazyListState()
    val allScroll = rememberLazyListState()
    val goBack: () -> Unit = {
        pane = if (pane == BackupPane.ARCHIVE && fromAll) BackupPane.ALL else BackupPane.OVERVIEW
        if (pane == BackupPane.OVERVIEW) selectedName = null
    }
    val headline = backupOverviewHeading(archives.firstOrNull(), state.backupsLoaded, state.backupStorageReady, LocalDate.now())
    BackHandler(pane != BackupPane.OVERVIEW, onBack = goBack)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val dual = maxWidth >= 720.dp && LocalDensity.current.fontScale < 1.6f
        val masterWidth = (maxWidth * .33f).coerceIn(280.dp, 382.dp)
        val showMaster = dual || pane == BackupPane.OVERVIEW
        val showDetails = dual || !showMaster
        val archive = selectedName?.let { name -> archives.find { it.fileName == name } } ?: if (dual && pane == BackupPane.OVERVIEW) archives.firstOrNull() else null
        Row(Modifier.fillMaxSize()) {
            if (showMaster) {
                val masterModifier = if (dual) Modifier.width(masterWidth) else Modifier.fillMaxWidth()
                LazyColumn(masterModifier.fillMaxHeight(), state = masterScroll, contentPadding = PaddingValues(
                    horizontal = if (dual) 28.dp else 20.dp, vertical = if (dual) 36.dp else 20.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    item {
                        if (!dual) Text("백업", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(if (dual) 4.dp else 8.dp))
                        Text(headline, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
                        Spacer(Modifier.height(8.dp))
                        val reminder = if (state.backupReminder.enabled) "${state.backupReminder.interval.label} 알림" else "알림 꺼짐"
                        Text(if (state.backupsLoaded && state.backupStorageReady == true)
                            "${archives.size}개 · ${formatBackupBytes(archives.sumOf { it.sizeBytes })} · $reminder" else reminder,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.height(24.dp))
                        Button(onClick = { pane = BackupPane.CREATE }, enabled = !busy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp)) {
                            Text(if (state.backupStorageReady == false) "저장공간 연결" else "지금 백업")
                        }
                        Spacer(Modifier.height(20.dp))
                        BackupGroupHeader(if (dual) "백업 목록" else "최근",
                            if (dual) "파일에서 가져오기" else "전체 ${archives.size}개", if (dual) canImport else state.backupsLoaded,
                            if (dual) onImport else ({ pane = BackupPane.ALL }))
                    }
                    val recent = if (dual) archives else archives.take(2)
                    if (recent.isEmpty()) item {
                        BackupBlank(when {
                            state.backupStorageReady == false -> "저장공간을 연결한 뒤 다운로드 폴더의 백업을 확인할 수 있어요."
                            !state.backupsLoaded -> "다운로드 폴더를 확인하고 있어요."
                            else -> "아직 다운로드 폴더에 런처 백업이 없어요."
                        })
                    }
                    items(recent, key = { "backup:${it.fileName}" }) { entry ->
                        val selected = dual && (pane == BackupPane.OVERVIEW || pane == BackupPane.ARCHIVE) && entry.fileName == archive?.fileName
                        BackupArchiveRow(entry, entry === recent.first(), entry === recent.last(), selected = selected) { selectArchive(entry, false) }
                    }
                    item {
                        Spacer(Modifier.height(20.dp))
                        BackupGroupHeader("관리")
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface)) {
                            if (!dual) BackupEntry(Icons.Outlined.Launch, "파일에서 가져오기", enabled = canImport, click = onImport)
                            BackupEntry(Icons.Outlined.DeleteOutline, "공간 정리", click = { pane = BackupPane.SPACE })
                            BackupEntry(Icons.Outlined.HealthAndSafety, "안전 사본", click = onSafety)
                        }
                        if (state.environment.processRunning) Text("백업을 가져오려면 서버를 먼저 종료해 주세요.",
                            Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            if (dual) TextButton(onClick = { pane = BackupPane.ALL }) { Text("전체 관리") }
                            IconButton(onClick = onRefresh, enabled = !busy) { Icon(Icons.Outlined.Refresh, "백업 목록 새로고침") }
                            IconButton(onClick = { pane = BackupPane.MORE }) { Icon(Icons.Outlined.MoreHoriz, "암호화·백업 추가 설정") }
                        }
                    }
                }
            }
            if (dual) VerticalDivider()
            if (showDetails) {
                val detailModifier = Modifier.weight(1f).fillMaxHeight().then(if (dual) Modifier.background(MaterialTheme.colorScheme.surface) else Modifier)
                Column(detailModifier.padding(horizontal = if (dual) 32.dp else 20.dp, vertical = if (dual) 28.dp else 8.dp)) {
                    if (!dual || (pane != BackupPane.OVERVIEW && pane != BackupPane.ARCHIVE)) {
                        TextButton(onClick = goBack) { Icon(Icons.Outlined.ArrowBack, null); Spacer(Modifier.width(6.dp)); Text("백업") }
                    }
                    when (pane) {
                        BackupPane.ARCHIVE, BackupPane.OVERVIEW -> if (archive == null) {
                            BackupBlank(if (pane == BackupPane.ARCHIVE) "이 백업이 현재 목록에 없어요. 목록을 새로고침해 주세요." else "확인할 백업을 선택하세요.")
                        } else key(archive.fileName) {
                            BackupDetail(archive, state, busy, onSelective, onRestore, onTransfer, onSaveAnnotation, onDelete)
                        }
                        BackupPane.ALL -> BackupAll(state, busy, allScroll, { selectArchive(it, true) }, onDelete)
                        else -> Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            when (pane) {
                                BackupPane.CREATE -> when (state.backupStorageReady) {
                                    false -> BackupStorageSetupCard(storageSetupCommand, onTermux, onRefresh, onCopied)
                                    true -> BackupCreatorCard(state.environment.processRunning,
                                        state.displayPreferences.includeSecretsByDefault, state.backupFolders, !busy) { categories, secrets, folders ->
                                        if (!busy) { pane = BackupPane.OVERVIEW; onCreateBackup(categories, secrets, folders) }
                                    }
                                    null -> { BackupBlank("저장공간 연결 상태를 확인해 주세요."); TextButton(onClick = onRefresh) { Text("다시 확인") } }
                                }
                                BackupPane.SPACE -> StorageManagementCard(state, onScan, onPreviewCleanup, initiallyExpanded = true)
                                BackupPane.MORE -> {
                                    Text("백업 추가 설정", style = MaterialTheme.typography.headlineSmall)
                                    EncryptedBackupCard(!busy, onEncrypted, initiallyExpanded = true)
                                    BackupReminderSetting(state.backupReminder.enabled, onReminderChange)
                                    OutlinedButton(onClick = onOpenFolder, modifier = Modifier.fillMaxWidth()) { Text("Download 폴더 열기") }
                                }
                                else -> Unit
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BackupBlank(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(vertical = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun BackupGroupHeader(title: String, action: String = "", enabled: Boolean = true, onClick: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f).semantics { heading() }, fontWeight = FontWeight.SemiBold)
        if (action.isNotEmpty()) TextButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(0.dp)) {
            Text(action, style = MaterialTheme.typography.labelLarge, color = if (enabled) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BackupEntry(icon: ImageVector, label: String, suffix: String = "", enabled: Boolean = true, click: () -> Unit) {
    val stack = LocalDensity.current.fontScale >= 1.3f
    Row(Modifier.fillMaxWidth().clickable(enabled, role = Role.Button, onClick = click).heightIn(min = 56.dp).padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else .48f)
        Icon(icon, null, Modifier.size(20.dp), tint = color)
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface else color)
            if (stack && suffix.isNotBlank()) Text(suffix, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium, color = color)
        }
        if (!stack && suffix.isNotBlank()) Text(suffix, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium, color = color)
        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = color)
    }
}

@Composable
private fun BackupArchiveRow(archive: BackupArchive, first: Boolean, last: Boolean, selected: Boolean, click: () -> Unit) {
    val top = if (first) 18.dp else 0.dp
    val bottom = if (last) 18.dp else 0.dp
    Column(Modifier.clip(RoundedCornerShape(top, top, bottom, bottom))
        .background(if (selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)) {
        BackupEntry(Icons.Outlined.FileDownload, archiveHeading(archive), formatBackupBytes(archive.sizeBytes), click = click)
        if (!last) HorizontalDivider(Modifier.padding(start = 52.dp, end = 18.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun BackupDetail(
    archive: BackupArchive, state: LauncherUiState, busy: Boolean, onSelective: (BackupArchive) -> Unit,
    onRestore: (BackupArchive) -> Unit, onTransfer: (BackupArchive, BackupTransferAction) -> Unit,
    onSave: (String, Boolean, String) -> Unit, onDelete: (List<BackupArchive>) -> Unit,
) {
    val annotation = state.backupAnnotations[archive.fileName] ?: BackupAnnotation()
    val canRestore = !busy && !state.environment.processRunning && archive.restoreSpaceReady
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val minHeight = maxHeight
        val singleRow = maxWidth >= 640.dp && LocalDensity.current.fontScale <= 1.3f
        val stack = maxWidth < 300.dp || LocalDensity.current.fontScale >= 1.5f
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = minHeight).padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(archiveHeading(archive, true), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(listOfNotNull(formatBackupBytes(archive.sizeBytes), archive.sillyTavernVersion.takeIf { it.isNotBlank() }?.let { "SillyTavern $it" },
                if (archive.includesSecrets) "API 키 포함 · 공유 주의" else "API 키 제외").joinToString(" · "),
                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Text("들어 있는 것", fontWeight = FontWeight.SemiBold)
            archive.characterFiles?.let { BackupDetailRow("캐릭터 파일", "${it}개") }
            archive.chatFiles?.let { BackupDetailRow("채팅 파일", "${it}개") }
            archive.categories.forEach { BackupDetailRow(it.label, "포함") }
            archive.customFolders.forEach { BackupDetailRow(backupFolderLabel(it), "포함") }
            if (archive.categories.isEmpty() && archive.customFolders.isEmpty()) BackupBlank("포함 항목 정보 없음")
            if (archive.expandedBytes > 0) BackupDetailRow("해제 크기", formatBackupBytes(archive.expandedBytes))
            if (archive.entryCount > 0) BackupDetailRow("압축 항목", "${archive.entryCount}개")
            if (archive.integrity.isNotBlank()) BackupDetailRow("무결성", if (archive.integrity == "zip-crc32") "ZIP CRC" else archive.integrity)
            Text(when {
                !archive.restoreSpaceReady -> "복원 공간이 부족합니다. ${formatBackupBytes(archive.requiredRestoreBytes)}가 필요해요."
                state.environment.processRunning -> "복원하려면 서버를 먼저 종료해 주세요."
                else -> "복원하면 백업 이후의 대화가 지워질 수 있어요. 다음 화면에서 교체 전 백업을 선택할 수 있어요."
            }, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            BackupAnnotationControl(archive, annotation) { pin, memo -> onSave(archive.fileName, pin, memo) }
            Spacer(Modifier.weight(1f))
            val restoreButtons: @Composable RowScope.() -> Unit = {
                Button(onClick = { onRestore(archive) }, enabled = canRestore, shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("이 백업으로 복원") }
                OutlinedButton(onClick = { onSelective(archive) }, enabled = !busy && !state.environment.processRunning,
                    shape = RoundedCornerShape(14.dp), modifier = Modifier.heightIn(min = 52.dp)) { Text("일부만 복원") }
            }
            val otherButtons: @Composable RowScope.() -> Unit = {
                IconButton(onClick = { onTransfer(archive, BackupTransferAction.EXPORT) }, enabled = !busy) {
                    Icon(Icons.Outlined.SaveAlt, "다른 위치에 저장", Modifier.size(20.dp))
                }
                TextButton(onClick = { onTransfer(archive, BackupTransferAction.SHARE) }, enabled = !busy) { Text("공유") }
                TextButton(onClick = { onDelete(listOf(archive)) }, enabled = !busy && BackupDeleteSelection.identifiable(archive) && annotation.trusted && !annotation.pinned,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("삭제") }
            }
            if (stack) {
                Button(onClick = { onRestore(archive) }, enabled = canRestore, shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("이 백업으로 복원") }
                OutlinedButton(onClick = { onSelective(archive) }, enabled = !busy && !state.environment.processRunning,
                    shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("일부만 복원") }
            } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                restoreButtons()
                if (singleRow) otherButtons()
            }
            if (!singleRow) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically, content = otherButtons)
        }
    }
}

@Composable
private fun BackupDetailRow(label: String, value: String) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    AdaptiveInfoRow(label, value)
}

@Composable
private fun BackupAll(state: LauncherUiState, busy: Boolean, scroll: LazyListState, onOpen: (BackupArchive) -> Unit, onDelete: (List<BackupArchive>) -> Unit) {
    var selection by rememberSaveable { mutableStateOf(false) }
    var keys by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val deletable = remember(state.backups, state.backupAnnotations) {
        state.backups.filter { archive -> BackupDeleteSelection.identifiable(archive) && state.backupAnnotations[archive.fileName]?.let { it.trusted && !it.pinned } != false }
    }
    val allowed = remember(deletable) { deletable.mapTo(mutableSetOf(), BackupDeleteSelection::key) }
    val selectedKeys = remember(allowed, keys) { keys.toSet().intersect(allowed) }
    val chosen = remember(deletable, selectedKeys) { deletable.filter { BackupDeleteSelection.key(it) in selectedKeys } }
    BackHandler(selection) { selection = false; keys = emptyList() }
    LazyColumn(state = scroll) {
        item {
            BackupGroupHeader("전체 백업 ${state.backups.size}개", if (selection) "취소" else "선택 삭제", enabled = !busy) {
                selection = !selection; keys = emptyList()
            }
            if (selection) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { keys = if (chosen.isEmpty()) deletable.take(200).map(BackupDeleteSelection::key) else emptyList() }) { Text(if (chosen.isEmpty()) "전체 선택" else "선택 해제") }
                TextButton(enabled = chosen.isNotEmpty() && !busy, onClick = { onDelete(chosen) }) { Text("선택 항목 ${chosen.size}개 삭제", color = MaterialTheme.colorScheme.error) }
            }
        }
        if (state.backups.isEmpty()) item { BackupBlank("표시할 백업이 없어요.") }
        items(state.backups, key = { it.fileName }) { archive ->
            if (!selection) BackupArchiveRow(archive, archive === state.backups.first(), archive === state.backups.last(), false) { onOpen(archive) }
            else Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                val id = BackupDeleteSelection.key(archive)
                Checkbox(checked = id in selectedKeys, enabled = id in allowed && !busy && (id in selectedKeys || selectedKeys.size < 200),
                    onCheckedChange = { checked -> keys = if (checked) (selectedKeys + id).toList() else (selectedKeys - id).toList() })
                Text(archiveHeading(archive), Modifier.weight(1f))
                Text(if (id !in allowed) "삭제 보호" else formatBackupBytes(archive.sizeBytes), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
