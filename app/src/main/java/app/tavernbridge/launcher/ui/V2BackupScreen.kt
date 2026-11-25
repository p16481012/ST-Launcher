package app.tavernbridge.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.tavernbridge.launcher.R
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
    archiveReturnToOrigin: Boolean = true,
    requestSpace: Boolean = false,
    onSpaceOpened: () -> Unit = {},
    spaceShortcutFromBackup: Boolean = false,
) {
    LaunchedEffect(state.environment.managerConnected, state.termuxSetup.verified) {
        if (state.environment.managerConnected && state.termuxSetup.verified) onRefresh()
    }
    var pane by rememberSaveable { mutableStateOf(if (requestedArchive == null) BackupPane.OVERVIEW else BackupPane.ARCHIVE) }
    // Null means the shortcut came from a different navigation entry (e.g. Home or Safety).
    var spaceReturnPane by rememberSaveable { mutableStateOf<BackupPane?>(BackupPane.OVERVIEW) }
    LaunchedEffect(requestSpace) {
        if (requestSpace) {
            if (!spaceShortcutFromBackup) spaceReturnPane = null
            else if (pane != BackupPane.SPACE) spaceReturnPane = pane
            pane = BackupPane.SPACE
            onSpaceOpened()
        }
    }
    var localScope by rememberSaveable { mutableStateOf(false) }
    val openScope = LocalBackupScopeOpener.current ?: { localScope = true }
    var fromAll by rememberSaveable { mutableStateOf(false) }
    var selectedName by rememberSaveable { mutableStateOf(requestedArchive) }
    var openedFromHome by rememberSaveable { mutableStateOf(requestedArchive != null && archiveReturnToOrigin) }
    LaunchedEffect(requestedArchive) {
        if (requestedArchive != null) {
            selectedName = requestedArchive
            fromAll = false
            openedFromHome = archiveReturnToOrigin
            pane = BackupPane.ARCHIVE
            onArchiveOpened()
        }
    }
    val busy = state.isWorking || state.environment.operationActive
    val connected = state.environment.managerConnected && state.termuxSetup.verified
    // Picker reads a user-granted document; staging probes actual Termux read access.
    // A separate "can Termux write a new backup ZIP?" check must not gate the picker.
    val running = state.environment.processRunning
    val stopToImport = rememberStopToContinue(state, pane to selectedName)
    val canImport = connected && !busy && !state.environment.recoveryPending
    val pickImport = onImport
    val auto = rememberBackupJobs()
    val archives = state.backups
    val listFailed = state.backupListError.isNotBlank()
    var retryingError by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.backupListLoading, listFailed) {
        if (!state.backupListLoading) retryingError = false
    }
    val firstLoad = (!state.backupsLoaded || retryingError) && state.backupStorageReady != false && !listFailed && connected
    var showSkeleton by remember(firstLoad) { mutableStateOf(false) }
    LaunchedEffect(firstLoad) { if (firstLoad) { kotlinx.coroutines.delay(300); showSkeleton = true } }
    val reduced = LocalV2ReducedMotion.current
    var displayedReceipt by rememberSaveable { mutableStateOf(state.lastCreatedBackupName) }
    val arrivingFile = state.lastCreatedBackupName?.takeIf { name ->
        displayedReceipt != name && archives.firstOrNull()?.fileName == name
    }
    SideEffect { if (arrivingFile != null) displayedReceipt = arrivingFile }
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
        if (pane == BackupPane.SPACE) {
            val previous = spaceReturnPane
            spaceReturnPane = BackupPane.OVERVIEW
            pane = previous ?: BackupPane.OVERVIEW
            if (previous == null) onArchiveShortcutBack()
        } else if (pane == BackupPane.ARCHIVE && openedFromHome) onArchiveShortcutBack()
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
                LazyColumn(Modifier.fillMaxSize().testTag("backup-overview-list"), state = masterScroll, contentPadding = PaddingValues(
                    horizontal = masterPadding, vertical = if (dual) 36.dp else 20.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    item {
                        if (!dual) Text("백업", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(if (dual) 4.dp else 8.dp))
                        Text(headline, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
                        Spacer(Modifier.height(8.dp))
                        val reminder = (if (state.backupReminder.enabled) "${state.backupReminder.interval.label} 알림" else "알림 꺼짐") + (if (auto.enabled) " · 자동 백업 켜짐" else "")
                        Text(if (state.backupsLoaded && state.backupStorageReady == true)
                            "${archives.size}개 · ${formatBackupBytes(archives.sumOf { it.sizeBytes })} · $reminder"
                            else if (firstLoad && showSkeleton) "확인하고 있어요" else reminder,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium, fontFamily = V2Mono)
                        Spacer(Modifier.height(20.dp))
                        AutoBackupNotice(auto, openScope, { pane = BackupPane.SPACE })
                        BackupGroupHeader(if (dual) "백업 목록" else "최근",
                            if (dual) "파일에서 가져오기" else if (state.backupsLoaded && !listFailed) "전체 ${archives.size}개" else "", if (dual) canImport else state.backupsLoaded,
                            if (dual) pickImport else ({ pane = BackupPane.ALL }),
                            onRefresh = if (connected && !busy && !state.backupListLoading) onRefresh else null)
                    }
                    val recent = if (listFailed || firstLoad || state.backupStorageReady == false) emptyList() else if (dual) archives else archives.take(2)
                    if (recent.isEmpty()) item {
                        when {
                            listFailed -> Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
                                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                    val reason = state.backupListError.removePrefix("[${state.backupListErrorCode}]").trim().lineSequence().firstOrNull()
                                    Text(reason.orEmpty().ifBlank { "Termux 응답을 확인하지 못했어요. 잠시 뒤 다시 시도해 주세요." })
                                    Text(state.backupListErrorCode, fontFamily = V2Mono, style = MaterialTheme.typography.labelMedium)
                                    if (connected && !busy) V2OutlineAction("다시 시도", { retryingError = true; onRefresh() })
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
                        val reveal = remember(entry.fileName) {
                            MutableTransitionState(reduced || arrivingFile != entry.fileName).apply { targetState = true }
                        }
                        androidx.compose.animation.AnimatedVisibility(visibleState = reveal,
                            modifier = if (reduced) Modifier else Modifier.animateItem(fadeInSpec = tween(120), placementSpec = tween(200),
                                fadeOutSpec = tween(120)),
                            enter = expandVertically(animationSpec = tween(if (reduced) 0 else 200), expandFrom = Alignment.Top) +
                                fadeIn(tween(if (reduced) 0 else 120))) {
                            BackupArchiveRow(entry, entry === recent.first(), entry === recent.last(), selected = selected) { selectArchive(entry, false) }
                        }
                    }
                    item {
                        Spacer(Modifier.height(20.dp))
                        BackupGroupHeader("관리")
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface)) {
                            if (!dual && canImport) {
                                BackupEntry(Icons.Outlined.Launch, "파일에서 가져오기", description = "ZIP을 선택할 파일 앱이 열려요.", click = pickImport)
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                            BackupEntry(Icons.Outlined.DeleteOutline, "공간 정리", estimate?.let { formatBackupBytes(it) }.orEmpty(), click = {
                                if (pane != BackupPane.SPACE) { spaceReturnPane = BackupPane.OVERVIEW; pane = BackupPane.SPACE }
                            })
                            if (connected && !busy) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                BackupEntry(Icons.Outlined.HealthAndSafety, "안전 사본·휴지통", state.safetyCopyCount?.let { copies -> state.fileTrashCount?.let { trash -> "${copies + trash}개" } }.orEmpty(),
                                    click = onSafety)
                            }
                        }
                        if (busy) SettingsCaption("진행 중인 작업이 끝난 뒤 백업을 만들거나 가져올 수 있어요.")
                        else if (state.environment.recoveryPending) SettingsCaption("점검·복구를 마친 뒤 백업 ZIP을 가져올 수 있어요.")
                        else if (state.environment.processRunning) Text(if (canImport) "가져오기를 누르면 서버를 끈 뒤 백업 ZIP을 선택해요." else "백업을 가져오려면 서버를 먼저 종료해 주세요.",
                            Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (dual) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            V2LinkAction("전체 관리", { pane = BackupPane.ALL })
                        }
                    }
                    item(key = "backup-storage-actions") {
                        Spacer(Modifier.height(16.dp))
                        BackupGroupHeader("저장·알림")
                        SettingsGroup {
                            SettingsRow("암호화 백업", caption = "ZIP 암호화 저장 · 암호화 파일 복원",
                                icon = Icons.Outlined.Lock, onClick = { pane = BackupPane.MORE })
                            SettingsDivider(52)
                            SettingsRow("Download 폴더 열기", caption = "파일 앱이 열려요.", icon = Icons.Outlined.FolderOpen, onClick = onOpenFolder)
                            SettingsDivider(52)
                            SettingsRow("백업 알림", caption = "백업할 때가 되면 알려줘요. 충전 중 자동 백업은 설정에서 따로 켜요.",
                                icon = Icons.Outlined.Notifications, checked = state.backupReminder.enabled,
                                onClick = { onReminderChange(!state.backupReminder.enabled) })
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
                if (!listFailed && connected && !busy && state.backupStorageReady == true) V2BottomActions {
                    Button(onClick = openScope, shape = RoundedCornerShape(14.dp),
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
                    if (pane == BackupPane.MORE || pane == BackupPane.SPACE || (pane == BackupPane.ARCHIVE && archive == null)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = goBack) { Icon(Icons.Outlined.ArrowBack, "뒤로") }
                            Text(when (pane) {
                                BackupPane.ARCHIVE -> "백업 상세"; BackupPane.ALL -> "전체 백업"
                                BackupPane.SPACE -> "공간 정리"; BackupPane.MORE -> "암호화 백업"; else -> "백업"
                            }, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    when (pane) {
                        BackupPane.ARCHIVE, BackupPane.OVERVIEW -> if (archive == null) {
                            BackupBlank(if (pane == BackupPane.ARCHIVE) "이 백업이 현재 목록에 없어요. 목록을 새로고침해 주세요." else "확인할 백업을 선택하세요.")
                        } else key(archive.fileName) {
                            BackupDetail(archive, state, busy, dual, !dual || openedFromHome, goBack, onSelective, onRestore, onTransfer, onSaveAnnotation, onDelete)
                        }
                        BackupPane.ALL -> BackupAll(state, busy, allScroll, goBack, { selectArchive(it, true) }, onDelete)
                        BackupPane.SPACE -> StorageManagementScreen(state, estimate, onScan, onPreviewCleanup)
                        else -> Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            when (pane) {
                                BackupPane.MORE -> {
                                    if (busy) SettingsCaption("진행 중인 작업을 마친 뒤 암호화 백업을 관리할 수 있어요.")
                                    else SettingsGroup {
                                        SettingsRow("ZIP 암호화 저장", caption = "원본 ZIP을 고를 파일 앱이 열려요. 원본은 그대로 남아요.",
                                            icon = Icons.Outlined.Lock,
                                            onClick = { onEncrypted(EncryptedBackupMode.EXPORT) })
                                        if (canImport) {
                                            SettingsDivider(52)
                                            SettingsRow("암호화 백업 열기·복원", caption = "암호화 파일을 고를 파일 앱이 열려요.",
                                                icon = Icons.Outlined.Restore,
                                                onClick = { enterStoppedAction(running, stopToImport, "암호화 백업 선택") { onEncrypted(EncryptedBackupMode.RESTORE) } })
                                        } else SettingsCaption("Termux 연결과 이전 작업 복구를 확인하고, 서버가 켜져 있으면 먼저 종료해 주세요.")
                                    }
                                    SettingsCaption("원본 ZIP 8 GiB까지 지원해요. 원본 ZIP은 암호화되지 않으며, 암호를 잊으면 암호화한 사본을 복구할 수 없어요.")
                                }
                                else -> Unit
                            }
                        }
                    }
                }
            }
        }
    }
    if (localScope && !busy) V2BackupFlowSheet(state, true,
        onCreate = { categories, secrets, folders -> localScope = false; onCreateBackup(categories, secrets, folders) },
        onCancel = {}, onClose = { localScope = false })
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
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
        Text(text, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BackupGroupHeader(title: String, action: String = "", enabled: Boolean = true,
    onClick: () -> Unit = {}, onRefresh: (() -> Unit)? = null) {
    // Keep refresh with the list it reads. The optional trailing link can wrap in a narrow tablet pane.
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Row(Modifier.heightIn(min = if (action.isNotEmpty() || onRefresh != null) 48.dp else 44.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.semantics { heading() }, fontWeight = FontWeight.SemiBold)
            if (onRefresh != null) IconButton(onClick = onRefresh, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.Refresh, "백업 목록 새로고침")
            }
        }
        if (action.isNotEmpty() && enabled) V2LinkAction(action, onClick)
    }
}

@Composable
private fun BackupEntry(icon: ImageVector, label: String, suffix: String = "", description: String = "", click: () -> Unit) {
    val stack = LocalDensity.current.fontScale >= 1.3f
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = click).heightIn(min = 56.dp).padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val color = MaterialTheme.colorScheme.onSurfaceVariant
        Icon(icon, null, Modifier.size(20.dp), tint = color)
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (description.isNotEmpty()) SettingsCaption(description)
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
        BackupEntry(Icons.Outlined.FileDownload, autoArchiveHeading(archive), formatBackupBytes(archive.sizeBytes), click = click)
        if (!last) HorizontalDivider(Modifier.padding(start = 52.dp, end = 18.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BackupDetail(
    archive: BackupArchive, state: LauncherUiState, busy: Boolean, dual: Boolean, showBack: Boolean,
    onBack: () -> Unit, onSelective: (BackupArchive) -> Unit,
    onRestore: (BackupArchive) -> Unit, onTransfer: (BackupArchive, BackupTransferAction) -> Unit,
    onSave: (String, Boolean, String) -> Unit, onDelete: (List<BackupArchive>) -> Unit,
) {
    val annotation = state.backupAnnotations[archive.fileName] ?: BackupAnnotation()
    val running = state.environment.processRunning
    val stop = rememberStopToContinue(state, Triple(BackupDeleteSelection.key(archive), archive.sizeBytes, archive.restoreSpaceReady))
    val canSelect = !busy && !state.environment.recoveryPending && (!running || stop != null)
    val canRestore = canSelect && archive.restoreSpaceReady
    val openRestore = { enterStoppedAction(running, stop, "선택한 백업 복원 확인") { onRestore(archive) } }
    val selectParts = { enterStoppedAction(running, stop, "복원할 항목 선택") { onSelective(archive) } }
    val deletable = BackupDeleteSelection.identifiable(archive) && annotation.trusted && !annotation.pinned
    var showActions by rememberSaveable(archive.fileName, archive.revision) { mutableStateOf(false) }
    LaunchedEffect(busy, dual) { if (busy || dual) showActions = false }
    if (showActions && !busy && !dual) V2BottomSheet(onClose = { showActions = false }) {
        Text("백업 파일 동작", style = MaterialTheme.typography.titleLarge)
        V2SheetActionRow("공유") { showActions = false; onTransfer(archive, BackupTransferAction.SHARE) }
        V2SheetActionRow("다른 위치에 저장", leadingIcon = { Icon(Icons.Outlined.SaveAlt, null, Modifier.size(20.dp)) }) {
            showActions = false; onTransfer(archive, BackupTransferAction.EXPORT)
        }
        if (deletable) V2SheetActionRow("삭제", danger = true) { showActions = false; onDelete(listOf(archive)) }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val stack = maxWidth < 300.dp || LocalDensity.current.fontScale >= 1.5f
        Column(Modifier.fillMaxSize()) {
        if (showBack) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "뒤로") }
            Text("백업 상세", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            if (!busy && !dual) IconButton(onClick = { showActions = true }) {
                ReviewGlyph(R.drawable.rv_more, description = "백업 파일 동작")
            }
        }
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
            if (archive.createdAt.isNotBlank()) BackupDetailRow("백업 파일", archive.fileName)
            Text(when {
                state.environment.recoveryPending -> "복구 중인 자료가 있어요. 점검·복구를 마친 뒤 백업을 복원해 주세요."
                !archive.restoreSpaceReady -> "복원 공간이 부족합니다. ${formatBackupBytes(archive.requiredRestoreBytes)}가 필요해요."
                archive.availableRestoreBytes == null && archive.requiredRestoreBytes > 0 -> "현재 여유 공간은 미확인이에요. 복원 전에 다시 검사해요."
                running -> if (stop != null) "복원을 누르면 서버를 끈 뒤 복원 내용을 확인해요." else "복원하려면 서버를 먼저 종료해 주세요."
                state.environment.sillyTavernInstalled -> "복원하면 백업 이후의 대화가 지워질 수 있어요. 기본으로 복원 전에 지금 상태를 먼저 백업해요."
                else -> "이 백업으로 설치 데이터를 복원해요."
            }, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            BackupAnnotationControl(archive, annotation) { pin, memo -> onSave(archive.fileName, pin, memo) }
        }
        V2BottomActions {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            if (stack && canSelect) {
                if (canRestore) V2SheetPrimaryButton("이 백업으로 복원", onClick = openRestore)
                V2OutlineAction("일부만 복원", selectParts, Modifier.fillMaxWidth(), footer = true)
            } else if (canSelect) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (canRestore) Button(onClick = openRestore, shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("이 백업으로 복원") }
                V2OutlineAction("일부만 복원", selectParts, footer = true)
            }
            if (busy) SettingsCaption("진행 중인 작업이 끝난 뒤 백업 파일을 관리할 수 있어요.")
            else if (dual) FlowRow(Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (deletable) V2QuietAction("삭제", { onDelete(listOf(archive)) })
                V2QuietAction("다른 위치에 저장", { onTransfer(archive, BackupTransferAction.EXPORT) }) {
                    Icon(Icons.Outlined.SaveAlt, null, Modifier.size(20.dp))
                }
                V2QuietAction("공유", { onTransfer(archive, BackupTransferAction.SHARE) })
            }
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
private fun BackupAll(state: LauncherUiState, busy: Boolean, scroll: LazyListState, onBack: () -> Unit, onOpen: (BackupArchive) -> Unit, onDelete: (List<BackupArchive>) -> Unit) {
    var selection by rememberSaveable { mutableStateOf(false) }
    var keys by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val deletable = remember(state.backups, state.backupAnnotations) {
        state.backups.filter { archive -> BackupDeleteSelection.identifiable(archive) && state.backupAnnotations[archive.fileName]?.let { it.trusted && !it.pinned } != false }
    }
    val allowed = remember(deletable) { deletable.mapTo(mutableSetOf(), BackupDeleteSelection::key) }
    val selectedKeys = remember(allowed, keys) { keys.toSet().intersect(allowed) }
    val chosen = remember(deletable, selectedKeys) { deletable.filter { BackupDeleteSelection.key(it) in selectedKeys } }
    val leaveSelection = { selection = false; keys = emptyList<String>() }
    BackHandler(selection, onBack = leaveSelection)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = if (selection) leaveSelection else onBack) {
            ReviewGlyph(if (selection) R.drawable.rv_close else R.drawable.rv_arrow_left,
                description = if (selection) "선택 종료" else "뒤로")
        }
        Text(if (selection) "${chosen.size}개 선택" else "전체 백업 ${state.backups.size}개",
            Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        if (!busy && deletable.isNotEmpty()) {
            if (selection) {
                val atLimit = chosen.size == minOf(deletable.size, 200)
                V2TopBarAction(if (atLimit) "선택 해제" else "모두 선택", {
                    keys = if (atLimit) emptyList() else deletable.take(200).map(BackupDeleteSelection::key)
                })
            } else IconButton(onClick = { selection = true }) {
                ReviewGlyph(R.drawable.rv_select, description = "항목 선택")
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
    LazyColumn(modifier = Modifier.weight(1f), state = scroll) {
        if (selection && chosen.isEmpty()) item { SettingsCaption("삭제할 백업을 선택해 주세요. 최대 200개까지 선택할 수 있어요.") }
        if (state.backups.isEmpty()) item { BackupBlank("표시할 백업이 없어요.") }
        items(state.backups, key = { it.fileName }) { archive ->
            if (!selection) BackupArchiveRow(archive, archive === state.backups.first(), archive === state.backups.last(), false) { onOpen(archive) }
            else Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                val id = BackupDeleteSelection.key(archive)
                Checkbox(checked = id in selectedKeys, enabled = id in allowed && !busy && (id in selectedKeys || selectedKeys.size < 200),
                    onCheckedChange = { checked -> keys = if (checked) (selectedKeys + id).toList() else (selectedKeys - id).toList() })
                Text(autoArchiveHeading(archive), Modifier.weight(1f))
                Text(if (id !in allowed) "삭제 보호" else formatBackupBytes(archive.sizeBytes), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
    if (selection && !busy && chosen.isNotEmpty()) V2BottomActions {
        V2DangerAction("선택 항목 ${chosen.size}개 삭제", { onDelete(chosen) }, Modifier.fillMaxWidth().padding(vertical = 8.dp))
    }
    }
}
