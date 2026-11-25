package app.tavernbridge.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tavernbridge.launcher.LauncherViewModel
import app.tavernbridge.launcher.model.*
import app.tavernbridge.launcher.ui.theme.V2Mono
import app.tavernbridge.launcher.ui.theme.LocalV2ReducedMotion

@Composable
internal fun V2ToolsScreen(state: LauncherUiState, viewModel: LauncherViewModel,
    onUpdate: () -> Unit, onRepair: () -> Unit, onReset: (Boolean) -> Unit, onOpenInstallation: () -> Unit,
    onPickInstallation: () -> Unit, onSwitchBranch: (SillyBranch) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 840 && maxWidth >= 720.dp
        val masterWidth = (maxWidth * .32f).coerceIn(238.dp, 360.dp)
        val twoFileColumns = wide && (maxWidth - masterWidth).value / LocalDensity.current.fontScale.coerceAtLeast(1f) >= 560f
        val selected = state.toolPage
        val displayed = if (wide && selected == ToolPage.INDEX) ToolPage.UPDATE else selected
        val goBack = viewModel::navigateBack
        var allChanges by rememberSaveable(displayed) { mutableStateOf(false) }
        val reduced = LocalV2ReducedMotion.current
        V2BackHandler(selected != ToolPage.INDEX, onBack = goBack)
        V2BackHandler(allChanges && displayed == ToolPage.UPDATE) { allChanges = false }
        Row(Modifier.fillMaxSize()) {
            if (wide || selected == ToolPage.INDEX) {
                Column((if (wide) Modifier.width(masterWidth) else Modifier.weight(1f))
                    .fillMaxHeight().testTag("tools-index").verticalScroll(rememberScrollState()).padding(horizontal = if (wide) 24.dp else 20.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    Text("도구", style = MaterialTheme.typography.displaySmall, modifier = Modifier.semantics { heading() })
                    val groups = listOf("관리" to listOf(ToolPage.UPDATE, ToolPage.INSTALLATION, ToolPage.EXTENSIONS, ToolPage.FILES),
                        "문제 해결" to listOf(ToolPage.DIAGNOSTICS, ToolPage.LOGS, ToolPage.REPAIR), "주의" to listOf(ToolPage.RESET))
                    groups.forEach { (label, pages) ->
                        ToolGroup(label) { pages.forEachIndexed { index, page ->
                            val hint = when (page) {
                                ToolPage.UPDATE -> state.updatePreflight?.let { if (it.updateAvailable) "새 버전 ${it.targetVersion}".trim() else "최신" }
                                ToolPage.EXTENSIONS -> state.extensionInventory?.let { "${it.items.size}개${if (it.nextCursor != null) "+" else ""}" }
                                ToolPage.DIAGNOSTICS -> state.diagnosticReport?.indexLabel
                                else -> null
                            }
                            ToolRow(page.label, icon = toolIcon(page), trailing = hint.orEmpty(),
                                danger = page == ToolPage.RESET, current = wide && displayed == page,
                                accent = page == ToolPage.UPDATE && state.updatePreflight?.updateAvailable == true,
                                onClick = { viewModel.selectTool(page, replaceToolsPane = wide) })
                            if (index < pages.lastIndex) ToolDivider(52)
                        } }
                    }
                }
            }
            if (wide) VerticalDivider()
            if (displayed != ToolPage.INDEX) Crossfade(displayed, modifier = Modifier.weight(1f).fillMaxHeight(),
                animationSpec = tween(if (wide && !reduced) 120 else 0), label = "도구 상세 전환") { page ->
                Column(Modifier.fillMaxSize().then(if (wide) Modifier.background(if (page == ToolPage.DIAGNOSTICS)
                    app.tavernbridge.launcher.ui.theme.LocalLauncherColors.current.canvas else MaterialTheme.colorScheme.surface) else Modifier)) {
                    val hasDetailOrigin = selected != ToolPage.INDEX && state.navigation.splitToolsRoot == null &&
                        state.navigation.previous.lastOrNull()?.let { it.section != MainSection.LOGS || it.tool != ToolPage.INDEX } == true
                    val nested = page == ToolPage.UPDATE && allChanges
                    if (page != ToolPage.FILES && (!wide || hasDetailOrigin || nested)) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { if (nested) allChanges = false else goBack() }) { ReviewGlyph(app.tavernbridge.launcher.R.drawable.rv_arrow_left, 24.dp, description = "뒤로") }
                        if (page == ToolPage.DIAGNOSTICS) Spacer(Modifier.width(12.dp))
                        Text(if (nested) "바뀐 내용 전체" else page.label, style = if (page == ToolPage.DIAGNOSTICS)
                            MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    }
                    val detailMod = Modifier.fillMaxSize().padding(horizontal = if (wide) 40.dp else 20.dp, vertical = if (wide) 24.dp else 8.dp)
                    when (page) {
                        ToolPage.UPDATE -> {
                            val holder = rememberSaveableStateHolder()
                            val dx = with(LocalDensity.current) { 24.dp.roundToPx() }
                            AnimatedContent(allChanges, transitionSpec = {
                                when {
                                    reduced -> EnterTransition.None togetherWith ExitTransition.None
                                    targetState -> (fadeIn(tween(220)) + slideInHorizontally(tween(220, easing = LinearOutSlowInEasing)) { dx }) togetherWith fadeOut(tween(220))
                                    else -> fadeIn(tween(180)) togetherWith (fadeOut(tween(180)) + slideOutHorizontally(tween(180, easing = FastOutLinearInEasing)) { dx })
                                }.using(null)
                            }, label = "바뀐 내용 전체 전환") { full ->
                                holder.SaveableStateProvider(full) {
                                    if (full) Column(detailMod.verticalScroll(rememberScrollState())) {
                                        ToolGroup("바뀐 내용") {
                                            state.updatePreflight?.changes.orEmpty().forEachIndexed { index, c ->
                                                if (index > 0) ToolDivider()
                                                ToolRow(c.subject, c.commit.take(7), dataNote = true)
                                            }
                                            if (state.updatePreflight?.changesTruncated == true) ToolRow("일부 변경 사항만 표시합니다.")
                                        }
                                    } else UpdateDetail(state, viewModel, onUpdate, onOpenInstallation, detailMod) { allChanges = true }
                                }
                            }
                        }
                        ToolPage.INSTALLATION -> V2InstallationDetail(state, detailMod, onPickInstallation,
                            viewModel::inspectExistingInstallation, onSwitchBranch,
                            { viewModel.selectTool(ToolPage.REPAIR) }, { viewModel.selectTool(ToolPage.RESET) })
                        ToolPage.DIAGNOSTICS -> DiagnosticDetail(state, viewModel, detailMod)
                        ToolPage.LOGS -> ToolLogs(state, viewModel, detailMod)
                        ToolPage.REPAIR, ToolPage.RESET -> MaintenanceDetail(page, state, viewModel, onRepair, onReset, detailMod)
                        ToolPage.EXTENSIONS -> ExtensionManagerDialog(
                            state.extensionInventory, state.extensionLoading, state.isWorking, state.environment.managerConnected &&
                                !state.environment.processRunning && !state.environment.operationActive && !state.environment.recoveryPending, state.extensionError,
                            { viewModel.loadExtensions() }, { viewModel.loadExtensions(true) }, viewModel::recoverExtension,
                            viewModel::changeExtension, goBack, embedded = true, onCheckUpdates = viewModel::checkExtensionVersions,
                            onCheckDetail = viewModel::loadExtensionDetailVersion,
                            onUpdateAll = viewModel::updateCheckedExtensions, stopState = state)
                        ToolPage.FILES -> ToolFolder(state, viewModel, goBack, twoFileColumns)
                        ToolPage.INDEX -> Unit
                    }
                }
            }
        }
    }
}

private fun toolIcon(page: ToolPage): ImageVector = when (page) {
    ToolPage.INSTALLATION -> Icons.Outlined.Inventory2
    ToolPage.UPDATE -> Icons.Outlined.VerticalAlignTop
    ToolPage.EXTENSIONS -> Icons.Outlined.Extension
    ToolPage.FILES -> Icons.Outlined.FolderOpen
    ToolPage.DIAGNOSTICS -> Icons.Outlined.MonitorHeart
    ToolPage.LOGS -> Icons.Outlined.Subject
    ToolPage.REPAIR -> Icons.Outlined.RestartAlt
    ToolPage.RESET -> Icons.Outlined.DeleteOutline
    ToolPage.INDEX -> Icons.Outlined.Build
}

@Composable internal fun ToolGroup(title: String = "", action: String? = null, onAction: () -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (action != null) TextButton(onClick = onAction, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.secondary)) { Text(action) }
        }
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(content = content)
        }
    }
}

@Composable internal fun ToolRow(label: String, note: String = "", trailing: String = "", icon: ImageVector? = null,
    danger: Boolean = false, current: Boolean = false, accent: Boolean = false, singleLine: Boolean = false,
    dataNote: Boolean = false, onClick: (() -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().background(if (current) cs.secondaryContainer else Color.Transparent)
        .v2SelectionEdge(current, cs.secondary)
        .then(if (onClick != null) Modifier.v2RowTap("tool:$label", click = onClick) else Modifier)
        .heightIn(min = 56.dp).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        icon?.let { Icon(it, null, Modifier.size(20.dp), tint = when { danger -> cs.error; current -> cs.onSecondaryContainer; else -> cs.onSurfaceVariant }) }
        Column(Modifier.weight(1f)) {
            Text(label, color = if (danger) cs.error else cs.onSurface, style = MaterialTheme.typography.bodyLarge,
                maxLines = if (singleLine) 1 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
            if (note.isNotBlank()) Text(note, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, fontFamily = if (dataNote) V2Mono else null)
        }
        if (trailing.isNotBlank()) Text(trailing, style = MaterialTheme.typography.bodySmall, fontFamily = if (trailing.any(Char::isDigit)) V2Mono else null,
            color = if (accent) cs.secondary else cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (onClick != null) Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = cs.onSurfaceVariant)
    }
}

@Composable internal fun ToolDivider(start: Int = 20) = HorizontalDivider(Modifier.padding(start = start.dp, end = 20.dp),
    color = MaterialTheme.colorScheme.outlineVariant)

@Composable internal fun Lead(title: String, note: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
        if (note.isNotBlank()) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable internal fun DetailLayout(modifier: Modifier, footer: @Composable RowScope.() -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp), content = content)
        V2BottomActions { Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), content = footer) }
    }
}

@Composable private fun UpdateDetail(state: LauncherUiState, vm: LauncherViewModel, onUpdate: () -> Unit, onInstall: () -> Unit, mod: Modifier, onAllChanges: () -> Unit) {
    UpdateManagementContent(state, mod, vm::checkForUpdates, vm::setAutoBackupBeforeUpdate,
        onUpdate, onInstall, vm::openPreviousVersions, vm::openBackups, onAllChanges)
}


@Composable private fun MaintenanceDetail(page: ToolPage, state: LauncherUiState, vm: LauncherViewModel, repair: () -> Unit, reset: (Boolean) -> Unit, mod: Modifier) {
    val resetMode = page == ToolPage.RESET
    val env = state.environment
    var protect by rememberSaveable { mutableStateOf(true) }
    val stop = rememberStopToContinue(state, page)
    val eligible = env.managerConnected && !state.isWorking && !env.operationActive && (!resetMode || !env.recoveryPending) &&
        (env.sillyTavernInstalled || (!resetMode && env.recoveryPending))
    val ready = eligible && !env.processRunning
    val canEnter = ready || (eligible && stop != null)
    var impact by remember(state.navigation.entryId, resetMode) { mutableStateOf<ResetImpact?>(null) }
    var countedAt by remember(state.navigation.entryId, resetMode) { mutableStateOf<Long?>(null) }
    val canCount = resetMode && state.canInspectResetImpact()
    val changedAt = state.lastOperationResult?.takeIf { it.operation in setOf(
        "reset-installation", "restore", "restore-selected", "import-install", "import-backup", "backup", "delete-backup"
    ) }?.completedAtMillis
    LaunchedEffect(canCount, changedAt, state.navigation.entryId) {
        if (canCount && (impact == null || changedAt != null && changedAt != countedAt)) {
            impact = vm.inspectResetImpact()
            countedAt = changedAt
        }
    }
    DetailLayout(mod, footer = {
        Button({ enterStoppedAction(env.processRunning, stop, if (resetMode) "초기화 확인" else "점검·복구 확인") {
            if (resetMode) reset(protect) else repair()
        } }, Modifier.weight(1f).heightIn(min = 52.dp), enabled = canEnter,
            colors = if (resetMode) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors(),
            shape = RoundedCornerShape(14.dp)) { Text(if (resetMode) "초기화" else "점검·복구 시작") }
    }) {
        if (resetMode) {
            Lead("처음 설치한 상태로 되돌려요", "되돌릴 수 없어요. 같은 브랜치를 새로 설치해요.")
            ToolGroup("지워지는 것") { listOf("캐릭터" to impact?.characters, "채팅" to impact?.chats, "확장 프로그램" to impact?.extensions,
                "설정·API 키" to null).forEachIndexed { index, (label, count) ->
                if (index != 0) ToolDivider(); ToolRow(label, trailing = count?.let { "${it}개" } ?: if (index < 3) "미확인" else "",
                    icon = Icons.Outlined.RemoveCircleOutline, danger = true)
            } }
            ToolGroup("남는 것") { ToolRow("Download의 백업 파일", trailing = impact?.backups?.let { "${it}개" } ?: "미확인", icon = Icons.Outlined.Check) }
            Row(Modifier.fillMaxWidth().toggleable(value = protect, role = Role.Checkbox) { protect = it }
                .heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Checkbox(protect, onCheckedChange = null); Text("초기화 전에 전체 백업하기")
            }
        } else {
            Lead("실행 파일만 다시 맞춰요", "약 3–5분 · 인터넷 필요 (기기·네트워크에 따라 더 걸릴 수 있어요)")
            Text("✓  캐릭터·채팅·설정은 건드리지 않아요")
            ToolGroup("진행 순서") {
                ToolRow("①   Termux 패키지 목록 확인", "목록을 갱신해요. 패키지 전체를 자동으로 갱신하지는 않아요."); ToolDivider()
                ToolRow("②   Git·Node.js 확인", "필요한 도구를 설치하고 Node.js·npm이 실행되는지 확인해요."); ToolDivider()
                ToolRow("③   실행 모듈 다시 구성", "node_modules를 새로 받아요")
            }
        }
        if (!ready) Text(when {
            canEnter -> "버튼을 누르면 서버를 끈 뒤 선택한 작업을 확인해요."
            state.isWorking || env.operationActive -> "진행 중인 작업이 끝난 뒤 시도해 주세요."
            else -> "Termux 연결 및 설치 상태를 확인해 주세요."
        },
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun ToolLogs(state: LauncherUiState, vm: LauncherViewModel, mod: Modifier) {
    var menu by remember { mutableStateOf(false) }
    val panel = state.diagnosticPanel
    val log = when (panel) { DiagnosticPanel.PREVIOUS_SERVER_LOG -> state.previousServerLogs; DiagnosticPanel.WORK_HISTORY -> state.operationHistory; else -> state.logs }
    Column(mod, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            listOf(DiagnosticPanel.SERVER_LOG to "현재 실행", DiagnosticPanel.PREVIOUS_SERVER_LOG to "이전 실행").forEach { (choice, label) ->
                FilterChip(panel == choice, { vm.openDiagnostics(choice) }, { Text(label) }, Modifier.weight(1f).padding(end = 4.dp))
            }
            Box { IconButton({ menu = true }) { Icon(Icons.Outlined.MoreVert, "로그 옵션") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("작업 기록") }, { menu = false; vm.openDiagnostics(DiagnosticPanel.WORK_HISTORY) })
                    DropdownMenuItem({ Text("다시 읽기") }, { menu = false; vm.openDiagnostics(panel) })
                    DropdownMenuItem({ Text("Termux에서 실시간 로그 보기") }, { menu = false; vm.openServerLogTerminal() }, enabled = state.environment.processRunning)
                }
            }
        }
        if (panel == DiagnosticPanel.WORK_HISTORY) Text("런처 작업 기록", style = MaterialTheme.typography.titleMedium)
        if (panel == DiagnosticPanel.SERVER_LOG && !state.canReadLiveServerLog())
            Text("이 작업이 끝나면 실시간 로그가 다시 갱신돼요.", style = MaterialTheme.typography.bodySmall)
        LogViewer(log, panel.name, Modifier.fillMaxWidth().weight(1f))
    }
}

@Composable private fun ToolFolder(state: LauncherUiState, vm: LauncherViewModel, back: () -> Unit, twoColumns: Boolean) {
    if (!state.fileBrowserOpen) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Lead("파일", "SillyTavern 설치와 Termux 연결을 확인해 주세요.")
            OutlinedButton(vm::openSillyTavernFolder, enabled = !state.isWorking && state.environment.managerConnected) { Text("폴더 다시 열기") }
            TextButton(back) { Text("도구 목록") }
        }
        return
    }
    val file = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importSillyTavernFile) }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(vm::importSillyTavernFolder) }
    TavernFileManagerDialog(state, vm::loadSillyTavernFolder, vm::openSillyTavernFile, vm::openSillyTavernDirectory,
        vm::readSillyTavernTextFile, vm::saveSillyTavernTextFile, vm::closeTextEditor, vm::createSillyTavernFolder,
        vm::createSillyTavernFile, vm::renameSillyTavernEntry, vm::trashSillyTavernEntry,
        { runCatching { file.launch(arrayOf("*/*")) }.onFailure { vm.showMessage("파일 선택 화면을 열 수 없습니다.") } },
        { runCatching { folder.launch(null) }.onFailure { vm.showMessage("폴더 선택 화면을 열 수 없습니다.") } }, back,
        vm::trashSillyTavernEntries, vm::previewSillyTavernFile, state.filePreview, vm::closeFilePreview,
        embedded = true, sideBySide = twoColumns, readTrash = vm::readSillyTavernTrash, onRestoreTrash = vm::restoreTrashedFile)
}
