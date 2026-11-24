package app.tavernbridge.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.tavernbridge.launcher.model.*
import app.tavernbridge.launcher.ui.theme.V2Mono
import app.tavernbridge.launcher.ui.theme.LocalV2ReducedMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

private enum class BackupPane { OVERVIEW, ARCHIVE, ALL, SPACE, MORE }
internal val LocalBackupScopeOpener = staticCompositionLocalOf<(() -> Unit)?> { null }

@OptIn(ExperimentalMaterial3Api::class)
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
    requestedArchive: String?,
    onArchiveOpened: () -> Unit,
    onArchiveShortcutBack: () -> Unit,
) {
    LaunchedEffect(state.environment.managerConnected, state.termuxSetup.verified) {
        if (state.environment.managerConnected && state.termuxSetup.verified) onRefresh()
    }
    var pane by rememberSaveable { mutableStateOf(if (requestedArchive == null) BackupPane.OVERVIEW else BackupPane.ARCHIVE) }
    var localScope by rememberSaveable { mutableStateOf(false) }
    val openScope = LocalBackupScopeOpener.current ?: { localScope = true }
    var fromAll by rememberSaveable { mutableStateOf(false) }
    var selectedName by rememberSaveable { mutableStateOf(requestedArchive) }
    var openedFromHome by rememberSaveable { mutableStateOf(requestedArchive != null) }
    LaunchedEffect(requestedArchive) {
        if (requestedArchive != null) {
            selectedName = requestedArchive
            fromAll = false
            openedFromHome = true
            pane = BackupPane.ARCHIVE
            onArchiveOpened()
        }
    }
    val busy = state.isWorking || state.environment.operationActive
    val connected = state.environment.managerConnected && state.termuxSetup.verified
    // Picker reads a user-granted document; staging probes actual Termux read access.
    // A separate "can Termux write a new backup ZIP?" check must not gate the picker.
    val canImport = connected && !busy && !state.environment.processRunning
    val archives = state.backups
    val listFailed = state.backupListError.isNotBlank()
    val firstLoad = !state.backupsLoaded && state.backupStorageReady != false && !listFailed && connected
    var showSkeleton by remember(firstLoad) { mutableStateOf(false) }
    LaunchedEffect(firstLoad) { if (firstLoad) { kotlinx.coroutines.delay(300); showSkeleton = true } }
    val reduced = LocalV2ReducedMotion.current
    val pull = rememberPullToRefreshState()
    val refreshing = state.backupListLoading && state.backupsLoaded
    val estimate by produceState<Long?>(null, archives, state.backupAnnotations, state.displayPreferences.keepBackups, state.backupsLoaded, state.backupStorageReady) {
        value = if (!state.backupsLoaded || state.backupStorageReady != true) null else withContext(Dispatchers.Default) {
            runCatching {
                BackupRetentionPolicy.afterCreation(archives, state.backupAnnotations, state.displayPreferences.keepBackups)
                    ?.reclaimableBytes ?: 0L
            }.getOrNull()
        }
    }
    val selectArchive: (BackupArchive, Boolean) -> Unit = { archive, all ->
        openedFromHome = false; selectedName = archive.fileName; fromAll = all; pane = BackupPane.ARCHIVE
    }
    val masterScroll = rememberLazyListState()
    val allScroll = rememberLazyListState()
    val goBack: () -> Unit = {
        if (pane == BackupPane.ARCHIVE && openedFromHome) onArchiveShortcutBack()
        else {
            pane = if (pane == BackupPane.ARCHIVE && fromAll) BackupPane.ALL else BackupPane.OVERVIEW
            if (pane == BackupPane.OVERVIEW) { selectedName = null; openedFromHome = false }
        }
    }
    val headline = if (listFailed) "백업 목록을\n불러오지 못했어요"
        else if (!connected && !state.backupsLoaded) "백업을 보려면\nTermux를 연결해 주세요"
        else backupOverviewHeading(archives.firstOrNull(), state.backupsLoaded, state.backupStorageReady, LocalDate.now())
    V2BackHandler(pane != BackupPane.OVERVIEW, onBack = goBack)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val dual = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 840 && maxWidth >= 720.dp
        val masterWidth = (maxWidth * .33f).coerceIn(280.dp, 382.dp)
        val showMaster = dual || pane == BackupPane.OVERVIEW
        val showDetails = dual || !showMaster
        val archive = selectedName?.let { name -> archives.find { it.fileName == name } } ?: if (dual && pane == BackupPane.OVERVIEW) archives.firstOrNull() else null
        Row(Modifier.fillMaxSize()) {
            if (showMaster) {
                val masterModifier = if (dual) Modifier.width(masterWidth) else Modifier.fillMaxWidth()
                val masterPadding = if (dual) 24.dp else 20.dp
                Column(masterModifier.fillMaxHeight()) {
                Box(Modifier.weight(1f).fillMaxWidth().pullToRefresh(isRefreshing = refreshing, state = pull,
                    enabled = connected && !busy && !state.backupListLoading, threshold = 64.dp, onRefresh = onRefresh)) {
                LazyColumn(Modifier.fillMaxSize(), state = masterScroll, contentPadding = PaddingValues(
                    horizontal = masterPadding, vertical = if (dual) 36.dp else 20.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    item {
                        if (!dual) Text("백업", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(if (dual) 4.dp else 8.dp))
                        Text(headline, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
                        Spacer(Modifier.height(8.dp))
                        val reminder = if (state.backupReminder.enabled) "${state.backupReminder.interval.label} 알림" else "알림 꺼짐"
                        Text(if (state.backupsLoaded && state.backupStorageReady == true)
                            "${archives.size}개 · ${formatBackupBytes(archives.sumOf { it.sizeBytes })} · $reminder"
                            else if (firstLoad && showSkeleton) "확인하고 있어요" else reminder,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium, fontFamily = V2Mono)
                        Spacer(Modifier.height(20.dp))
                        BackupGroupHeader(if (dual) "백업 목록" else "최근",
                            if (dual) "파일에서 가져오기" else if (state.backupsLoaded && !listFailed) "전체 ${archives.size}개" else "", if (dual) canImport else state.backupsLoaded,
                            if (dual) onImport else ({ pane = BackupPane.ALL }))
                    }
                    val recent = if (listFailed || state.backupStorageReady == false) emptyList() else if (dual) archives else archives.take(2)
                    if (recent.isEmpty()) item {
                        when {
                            listFailed -> Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
                                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                    val reason = state.backupListError.removePrefix("[${state.backupListErrorCode}]").trim().lineSequence().firstOrNull()
                                    Text(reason.orEmpty().ifBlank { "Termux 응답을 확인하지 못했어요. 잠시 뒤 다시 시도해 주세요." })
                                    Text(state.backupListErrorCode, fontFamily = V2Mono, style = MaterialTheme.typography.labelMedium)
                                    TextButton(onClick = onRefresh, enabled = connected && !busy) { Text("다시 시도") }
                                }
                            }
                            !connected -> BackupBlank("홈에서 Termux 연결을 확인한 뒤 백업을 다시 열어 주세요.")
                            state.backupStorageReady == false -> BackupStorageSetupCard(storageSetupCommand, onTermux, onRefresh, onCopied)
                            firstLoad && showSkeleton -> BackupSkeleton(reduced)
                            !firstLoad -> BackupBlank("채팅과 캐릭터를 Download 폴더에 저장해요.")
                            else -> Unit
                        }
                    }
                    items(recent, key = { "backup:${it.fileName}" }) { entry ->
                        val selected = dual && (pane == BackupPane.OVERVIEW || pane == BackupPane.ARCHIVE) && entry.fileName == archive?.fileName
                        Box(if (reduced) Modifier else Modifier.animateItem(fadeInSpec = tween(120), placementSpec = tween(200),
                            fadeOutSpec = tween(120))) {
                            BackupArchiveRow(entry, entry === recent.first(), entry === recent.last(), selected = selected) { selectArchive(entry, false) }
                        }
                    }
                    item {
                        Spacer(Modifier.height(20.dp))
                        BackupGroupHeader("관리")
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface)) {
                            if (!dual) {
                                BackupEntry(Icons.Outlined.Launch, "파일에서 가져오기", enabled = canImport, click = onImport)
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                            BackupEntry(Icons.Outlined.DeleteOutline, "공간 정리", estimate?.let { formatBackupBytes(it) }.orEmpty(), click = { pane = BackupPane.SPACE })
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            BackupEntry(Icons.Outlined.HealthAndSafety, "안전 사본", state.safetyCopyCount?.let { "${it}개" }.orEmpty(),
                                enabled = connected && !busy, click = onSafety)
                        }
                        if (state.environment.processRunning) Text("백업을 가져오려면 서버를 먼저 종료해 주세요.",
                            Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            if (dual) TextButton(onClick = { pane = BackupPane.ALL }) { Text("전체 관리", color = MaterialTheme.colorScheme.secondary) }
                            IconButton(onClick = onRefresh, enabled = connected && !busy && !state.backupListLoading) { Icon(Icons.Outlined.Refresh, "백업 목록 새로고침") }
                            IconButton(onClick = { pane = BackupPane.MORE }) { Icon(Icons.Outlined.MoreHoriz, "암호화·백업 추가 설정") }
                        }
                    }
                }
                if (pull.distanceFraction > 0f || refreshing) {
                    Surface(modifier = Modifier.align(Alignment.TopCenter).graphicsLayer {
                        translationY = ((if (refreshing) 1f else pull.distanceFraction.coerceIn(0f, 1.4f)) * 64.dp.toPx()) - 40.dp.toPx()
                    }.size(40.dp), shape = androidx.compose.foundation.shape.CircleShape,
                        color = MaterialTheme.colorScheme.surface, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        Box(contentAlignment = Alignment.Center) {
                            if (refreshing) VisibleBusyIndicator(Modifier.size(20.dp), false, 2.dp)
                            else Icon(Icons.Outlined.ArrowDownward, "당겨서 새로고침", Modifier.size(20.dp))
                        }
                    }
                }
                }
                if (!listFailed && state.backupStorageReady != false) V2BottomActions {
                    Button(onClick = openScope, enabled = connected && !busy && state.backupStorageReady == true, shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = masterPadding, vertical = 8.dp).heightIn(min = 52.dp)) {
                        Text("지금 백업")
                    }
                }
                }
            }
            if (dual) VerticalDivider()
            if (showDetails) {
                val detailModifier = Modifier.weight(1f).fillMaxHeight().then(if (dual) Modifier.background(MaterialTheme.colorScheme.surface) else Modifier)
                Column(detailModifier.padding(horizontal = if (dual) 40.dp else 20.dp, vertical = if (dual) 28.dp else 8.dp)) {
                    if (!dual || (pane == BackupPane.ARCHIVE && openedFromHome) || (pane != BackupPane.OVERVIEW && pane != BackupPane.ARCHIVE)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = goBack) { Icon(Icons.Outlined.ArrowBack, "뒤로") }
                            Text(when (pane) {
                                BackupPane.ARCHIVE -> "백업 상세"; BackupPane.ALL -> "전체 백업"
                                BackupPane.SPACE -> "공간 정리"; BackupPane.MORE -> "백업 추가 설정"; else -> "백업"
                            }, style = MaterialTheme.typography.titleMedium)
                        }
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
                                BackupPane.SPACE -> {
                                    estimate?.let { Text("일반 백업 정리 기준으로 확보할 수 있는 공간은 ${formatBackupBytes(it)}예요. 아래에서 다른 기준으로도 확인할 수 있어요.", style = MaterialTheme.typography.bodyMedium) }
                                    StorageManagementCard(state, onScan, onPreviewCleanup, initiallyExpanded = true)
                                }
                                BackupPane.MORE -> {
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
    if (localScope) V2BottomSheet({ localScope = false }) {
        BackupCreatorCard(state.environment.processRunning, state.displayPreferences.includeSecretsByDefault, state.backupFolders,
            !busy && state.backupStorageReady == true) { categories, secrets, folders ->
            localScope = false; onCreateBackup(categories, secrets, folders)
        }
    }
}

@Composable
private fun BackupSkeleton(reduced: Boolean) {
    val opacity = if (reduced) 1f else rememberInfiniteTransition(label = "백업 목록").animateFloat(1f, .6f,
        infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "자리 행").value
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface)
        .padding(horizontal = 16.dp, vertical = 12.dp).alpha(opacity), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        repeat(2) { Box(Modifier.fillMaxWidth(.7f).height(18.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(6.dp))) }
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
        val color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else app.tavernbridge.launcher.ui.theme.LocalLauncherColors.current.disabledInk
        Icon(icon, null, Modifier.size(20.dp), tint = color)
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface else color)
            if (stack && suffix.isNotBlank()) Text(suffix, fontFamily = V2Mono, style = MaterialTheme.typography.labelMedium, color = color)
        }
        if (!stack && suffix.isNotBlank()) Text(suffix, fontFamily = V2Mono, style = MaterialTheme.typography.labelMedium, color = color)
        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = color)
    }
}

@Composable
private fun BackupArchiveRow(archive: BackupArchive, first: Boolean, last: Boolean, selected: Boolean, click: () -> Unit) {
    val top = if (first) 18.dp else 0.dp
    val bottom = if (last) 18.dp else 0.dp
    Column(Modifier.clip(RoundedCornerShape(top, top, bottom, bottom))
        .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)) {
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
        val singleRow = maxWidth >= 640.dp && LocalDensity.current.fontScale <= 1.3f
        val stack = maxWidth < 300.dp || LocalDensity.current.fontScale >= 1.5f
        Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(archiveHeading(archive, true), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(listOfNotNull(formatBackupBytes(archive.sizeBytes), archive.sillyTavernVersion.takeIf { it.isNotBlank() }?.let { "SillyTavern $it" },
                if (archive.includesSecrets) "API 키 포함 · 공유 주의" else "API 키 제외").joinToString(" · "),
                fontFamily = V2Mono, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Text("들어 있는 것", fontWeight = FontWeight.SemiBold)
            archive.characterFiles?.let { BackupDetailRow("캐릭터 파일", "${it}개") }
            archive.chatFiles?.let { BackupDetailRow("채팅 파일", "${it}개") }
            archive.extensionCount?.let { BackupDetailRow("확장 프로그램", "${it}개") }
            archive.categories.filterNot { it == BackupCategory.EXTENSIONS && archive.extensionCount != null }
                .forEach { BackupDetailRow(it.label, "포함") }
            archive.customFolders.forEach { BackupDetailRow(backupFolderLabel(it), "포함") }
            if (archive.categories.isEmpty() && archive.customFolders.isEmpty()) BackupBlank("포함 항목 정보 없음")
            if (archive.expandedBytes > 0) BackupDetailRow("해제 크기", formatBackupBytes(archive.expandedBytes))
            if (archive.entryCount > 0) BackupDetailRow("압축 항목", "${archive.entryCount}개")
            if (archive.integrity.isNotBlank()) BackupDetailRow("무결성", if (archive.integrity == "zip-crc32") "ZIP CRC" else archive.integrity)
            Text(when {
                !archive.restoreSpaceReady -> "복원 공간이 부족합니다. ${formatBackupBytes(archive.requiredRestoreBytes)}가 필요해요."
                state.environment.processRunning -> "복원하려면 서버를 먼저 종료해 주세요."
                state.environment.sillyTavernInstalled -> "복원하면 백업 이후의 대화가 지워질 수 있어요. 기본으로 복원 전에 지금 상태를 먼저 백업해요."
                else -> "이 백업으로 설치 데이터를 복원해요."
            }, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            BackupAnnotationControl(archive, annotation) { pin, memo -> onSave(archive.fileName, pin, memo) }
        }
        V2BottomActions {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
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
