package app.tavernbridge.launcher.ui

import android.os.Build
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.text.format.Formatter
import app.tavernbridge.launcher.LauncherViewModel
import app.tavernbridge.launcher.model.*
import app.tavernbridge.launcher.ui.theme.V2Mono
import app.tavernbridge.launcher.ui.theme.LocalV2ReducedMotion

@Composable
internal fun V2ToolsScreen(state: LauncherUiState, viewModel: LauncherViewModel,
    onUpdate: () -> Unit, onRepair: () -> Unit, onReset: (Boolean) -> Unit, onOpenInstallation: () -> Unit) {
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
                    .fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = if (wide) 24.dp else 20.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    Text("도구", style = MaterialTheme.typography.displaySmall, modifier = Modifier.semantics { heading() })
                    val groups = listOf("관리" to listOf(ToolPage.UPDATE, ToolPage.EXTENSIONS, ToolPage.FILES),
                        "문제 해결" to listOf(ToolPage.DIAGNOSTICS, ToolPage.LOGS, ToolPage.REPAIR), "주의" to listOf(ToolPage.RESET))
                    groups.forEach { (label, pages) ->
                        ToolGroup(label) { pages.forEachIndexed { index, page ->
                            val hint = when (page) {
                                ToolPage.UPDATE -> state.updatePreflight?.let { if (it.updateAvailable) "새 버전 ${it.targetVersion}".trim() else "최신" }
                                ToolPage.EXTENSIONS -> state.extensionInventory?.let { "${it.items.size}개${if (it.nextCursor != null) "+" else ""}" }
                                ToolPage.DIAGNOSTICS -> state.diagnosticReport?.let { if (it.warningCount + it.errorCount > 0) "확인 필요" else "정상" }
                                else -> null
                            }
                            ToolRow(page.label, icon = toolIcon(page), trailing = hint.orEmpty(),
                                danger = page == ToolPage.RESET, current = wide && displayed == page,
                                accent = page == ToolPage.UPDATE && state.updatePreflight?.updateAvailable == true,
                                onClick = { viewModel.selectTool(page) })
                            if (index < pages.lastIndex) ToolDivider(52)
                        } }
                    }
                }
            }
            if (wide) VerticalDivider()
            if (displayed != ToolPage.INDEX) Crossfade(displayed, modifier = Modifier.weight(1f).fillMaxHeight(),
                animationSpec = tween(if (wide && !reduced) 120 else 0), label = "도구 상세 전환") { page ->
                Column(Modifier.fillMaxSize().then(if (wide) Modifier.background(MaterialTheme.colorScheme.surface) else Modifier)) {
                    val crossSection = state.navigation.previous.lastOrNull()?.section?.let { it != MainSection.LOGS } == true
                    val nested = page == ToolPage.UPDATE && allChanges
                    if (page != ToolPage.FILES && (!wide || crossSection || nested)) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { if (nested) allChanges = false else goBack() }) { Icon(Icons.Outlined.ArrowBack, "뒤로") }
                        Text(if (nested) "바뀐 내용 전체" else page.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
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
                        ToolPage.DIAGNOSTICS -> DiagnosticDetail(state, viewModel, detailMod)
                        ToolPage.LOGS -> ToolLogs(state, viewModel, detailMod)
                        ToolPage.REPAIR, ToolPage.RESET -> MaintenanceDetail(page, state, viewModel, onRepair, onReset, detailMod)
                        ToolPage.EXTENSIONS -> ExtensionManagerDialog(
                            state.extensionInventory, state.extensionLoading, state.isWorking, state.environment.managerConnected &&
                                !state.environment.processRunning && !state.environment.recoveryPending, state.extensionError,
                            { viewModel.loadExtensions() }, { viewModel.loadExtensions(true) }, viewModel::recoverExtension,
                            viewModel::changeExtension, goBack, embedded = true, onCheckUpdates = viewModel::checkExtensionVersions,
                            onUpdateAll = viewModel::updateCheckedExtensions)
                        ToolPage.FILES -> ToolFolder(state, viewModel, goBack, twoFileColumns)
                        ToolPage.INDEX -> Unit
                    }
                }
            }
        }
    }
}

private fun toolIcon(page: ToolPage): ImageVector = when (page) {
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
    Row(Modifier.fillMaxWidth().background(if (current) cs.surfaceContainerHigh else Color.Transparent)
        .then(if (onClick != null) Modifier.v2RowTap("tool:$label", click = onClick) else Modifier)
        .heightIn(min = 56.dp).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        icon?.let { Icon(it, null, Modifier.size(20.dp), tint = if (danger) cs.error else cs.onSurfaceVariant) }
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

@Composable private fun Lead(title: String, note: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
        if (note.isNotBlank()) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun DetailLayout(modifier: Modifier, footer: @Composable RowScope.() -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp), content = content)
        V2BottomActions { Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), content = footer) }
    }
}

@Composable private fun UpdateDetail(state: LauncherUiState, vm: LauncherViewModel, onUpdate: () -> Unit, onInstall: () -> Unit, mod: Modifier, onAllChanges: () -> Unit) {
    val r = state.updatePreflight
    val env = state.environment
    val canCheck = !state.isWorking && env.managerConnected && env.sillyTavernInstalled
    val ready = canCheck && r != null && r.updateAvailable && r.nodeCompatible && r.spaceReady &&
        (!state.autoBackupBeforeUpdate || r.backupStorageReady)
    val context = LocalContext.current
    var details by rememberSaveable { mutableStateOf(false) }
    DetailLayout(mod, footer = {
        Button(if (ready) onUpdate else vm::checkForUpdates, Modifier.weight(1f).heightIn(min = 52.dp),
            enabled = canCheck, shape = RoundedCornerShape(14.dp)) { Text(if (ready) "업데이트" else if (r == null) "업데이트 확인" else "다시 확인") }
    }) {
        Lead(when { r == null -> "업데이트를 확인해요"; !r.updateAvailable -> "최신 버전이에요"
            else -> "${r.targetVersion.ifBlank { "새" }} 버전을\n받을 수 있어요" },
            listOfNotNull(env.sillyTavernVersion.takeIf(String::isNotBlank)?.let { "지금 $it" },
                r?.takeIf { it.updateAvailable }?.let { "새 커밋 ${it.commitsBehind}개 · 필요 공간 ${Formatter.formatFileSize(context, it.requiredBytes)}" }).joinToString(" · "))
        if (!canCheck && !state.isWorking) Text("설치를 완료하고 Termux 연결을 확인해 주세요.")
        if (r != null && r.updateAvailable) {
            ToolGroup("바뀐 내용", if (r.changes.size > 3) "${r.changes.size}개 모두" else null, onAllChanges) {
                val changes = r.changes.take(3)
                changes.forEachIndexed { index, c -> ToolRow(c.subject, c.commit.take(7), dataNote = true); if (index != changes.lastIndex) ToolDivider() }
                if (changes.isEmpty()) ToolRow("커밋 목록을 확인하지 못했어요.")
            }
            val warning = listOfNotNull(
                if (!r.nodeCompatible) "Node.js 버전이 맞지 않아요. 필요: ${r.nodeRequired}" else null,
                if (!r.spaceReady) "남은 공간이 부족해요." else null,
                if (!r.backupStorageReady && state.autoBackupBeforeUpdate) "자동 백업을 위해 백업 탭에서 저장공간을 연결해 주세요." else null,
                if (r.modifiedFiles.isNotEmpty()) "수정된 파일 ${r.modifiedFiles.size}개를 별도로 보관한 후 업데이트해요." else null).joinToString("\n")
            if (warning.isNotBlank()) Text(warning, color = MaterialTheme.colorScheme.error)
        }
        ToolGroup { SettingsRow("업데이트 전에 자동 백업", checked = state.autoBackupBeforeUpdate,
            onClick = { vm.setAutoBackupBeforeUpdate(!state.autoBackupBeforeUpdate) }) }
        TextButton(onClick = { details = !details }) { Text(if (details) "추가 옵션 접기" else "설치 정보·이전 버전 및 추가 옵션") }
        if (details) ToolGroup {
            ToolRow("설치 정보·가져오기·브랜치", onClick = onInstall)
            if (canCheck && !env.processRunning) ToolRow("이전 버전 선택·복귀", onClick = vm::openPreviousVersions)
            if (r != null) ToolRow("안전 검사 결과", "${r.currentCommit.take(7)} → ${r.targetCommit.take(7)}\nNode.js ${r.nodeVersion} · 여유 ${Formatter.formatFileSize(context, r.freeBytes)}")
            state.lastUpdateRecord?.let { ToolRow("최근 업데이트", "${it.createdAt} · ${it.result}") }
            if (canCheck) ToolRow("업데이트 다시 확인", onClick = vm::checkForUpdates)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun DiagnosticDetail(state: LauncherUiState, vm: LauncherViewModel, mod: Modifier) {
    val report = state.diagnosticReport
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    var all by rememberSaveable { mutableStateOf(false) }
    var itemId by rememberSaveable { mutableStateOf<String?>(null) }
    DetailLayout(mod, footer = {
        OutlinedButton({ report?.let { clipboard.setText(AnnotatedString(it.copyText())); vm.showMessage("진단 보고서를 복사했습니다.") } },
            Modifier.weight(1f).heightIn(min = 52.dp), enabled = report != null, shape = RoundedCornerShape(14.dp)) { Text("보고서 복사") }
        Button(vm::runDiagnostics, Modifier.weight(1f).heightIn(min = 52.dp), enabled = !state.isWorking, shape = RoundedCornerShape(14.dp)) { Text(if (report == null) "진단 시작" else "다시 진단") }
    }) {
        Lead(report?.let { "${it.items.size}개 중 ${it.okCount}개 정상" } ?: "환경을 진단해요",
            report?.let { "${it.checkedAt} 진단" } ?: "저장 공간과 Termux, 서버 실행 환경을 확인해요.")
        if (report != null) {
            val issues = report.items.filter { it.status == DiagnosticStatus.ERROR || it.status == DiagnosticStatus.WARNING }
                .sortedBy { if (it.status == DiagnosticStatus.ERROR) 0 else 1 }
            val normal = report.items.filter { it.status == DiagnosticStatus.OK }
            val info = report.items.filter { it.status == DiagnosticStatus.INFO }
            if (issues.isNotEmpty()) ToolGroup("확인 필요") { issues.forEachIndexed { index, item ->
                ToolRow(item.title, item.value, "해결", Icons.Outlined.ErrorOutline, danger = true, onClick = { itemId = item.id })
                if (index < issues.lastIndex) ToolDivider()
            } }
            if (normal.isNotEmpty()) ToolGroup("정상", if (all) "접기" else "${normal.size}개 모두", { all = !all }) {
                if (all) normal.forEachIndexed { index, item ->
                    if (index != 0) ToolDivider(); ToolRow(item.title, trailing = item.value, onClick = { itemId = item.id })
                }
            }
            if (info.isNotEmpty()) ToolGroup("참고") { info.forEach { item -> ToolRow(item.title, item.value, onClick = { itemId = item.id }) } }
        }
        TermuxProcessHelpCard(Build.VERSION.SDK_INT, vm::openTermuxAppSettings, vm::openBatteryOptimizationSettings) { url ->
            runCatching { uriHandler.openUri(url) }.onFailure { vm.showMessage("도움말 링크를 열 수 없어요. 브라우저 앱을 확인해 주세요.") }
        }
    }
    report?.items?.firstOrNull { it.id == itemId }?.let { item ->
        V2BottomSheet(onClose = { itemId = null }) {
                Lead(item.title, item.value)
                if (item.detail.isNotBlank()) Text(item.detail)
                if (item.recommendation.isNotBlank()) Text(item.recommendation)
                when (item.id) {
                    "battery" -> Button(onClick = vm::openBatteryOptimizationSettings) { Text("배터리 설정 열기") }
                    "download_storage" -> Button(onClick = { clipboard.setText(AnnotatedString(vm.storageSetupCommand)); vm.openTermux() }) { Text("연결 명령 복사·Termux 열기") }
                }
                TextButton(onClick = { itemId = null }) { Text("확인") }
        }
    }
}

@Composable private fun MaintenanceDetail(page: ToolPage, state: LauncherUiState, vm: LauncherViewModel, repair: () -> Unit, reset: (Boolean) -> Unit, mod: Modifier) {
    val resetMode = page == ToolPage.RESET
    val env = state.environment
    var protect by rememberSaveable { mutableStateOf(true) }
    val ready = env.managerConnected && !state.isWorking && !env.processRunning && !env.operationActive && (env.sillyTavernInstalled || (!resetMode && env.recoveryPending))
    val impact by produceState<ResetImpact?>(null, resetMode, ready, state.lastOperationResult) {
        value = if (resetMode && ready) vm.inspectResetImpact() else null
    }
    DetailLayout(mod, footer = {
        Button({ if (resetMode) reset(protect) else repair() }, Modifier.weight(1f).heightIn(min = 52.dp), enabled = ready,
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
            ToolGroup("남는 것") { ToolRow("Download의 백업 파일", trailing = impact?.backups?.let { "${it}개" } ?: if (state.backupsLoaded) "${state.backups.size}개" else "미확인", icon = Icons.Outlined.Check) }
            Row(Modifier.fillMaxWidth().toggleable(value = protect, role = Role.Checkbox) { protect = it }
                .heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Checkbox(protect, onCheckedChange = null); Text("초기화 전에 전체 백업하기")
            }
        } else {
            Lead("실행 파일만 다시 맞춰요", "약 3–5분 · 인터넷 필요 (기기·네트워크에 따라 더 걸릴 수 있어요)")
            Text("✓  캐릭터·채팅·설정은 건드리지 않아요")
            ToolGroup("진행 순서") {
                ToolRow("①   Termux 패키지 확인", "오래된 패키지를 최신으로 맞춰요"); ToolDivider()
                ToolRow("②   Git·Node.js 다시 설치", "실행 도구가 망가졌을 때 바로잡아요"); ToolDivider()
                ToolRow("③   실행 모듈 다시 구성", "node_modules를 새로 받아요")
            }
        }
        if (!ready) Text(if (env.processRunning || env.operationActive) "서버와 진행 중인 작업을 먼저 종료해 주세요." else "Termux 연결 및 설치 상태를 확인해 주세요.",
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
        embedded = true, sideBySide = twoColumns)
}
