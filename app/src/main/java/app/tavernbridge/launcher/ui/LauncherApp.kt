package app.tavernbridge.launcher.ui

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.DocumentsContract
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.compose.ui.graphics.graphicsLayer
import app.tavernbridge.launcher.model.masterEntry
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.InstallMobile
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.FileDownload
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text as MaterialText
import androidx.compose.runtime.Composable
import app.tavernbridge.launcher.model.canModifyTavernFiles
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import app.tavernbridge.launcher.ui.theme.V2Mono
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import app.tavernbridge.launcher.R
import app.tavernbridge.launcher.LauncherViewModel
import app.tavernbridge.launcher.model.BackupArchive
import app.tavernbridge.launcher.model.backupRestoreWarnings
import app.tavernbridge.launcher.model.BackupCategory
import app.tavernbridge.launcher.model.BackupTransferAction
import app.tavernbridge.launcher.model.DiagnosticPanel
import app.tavernbridge.launcher.model.LauncherUiState
import app.tavernbridge.launcher.model.canOfferRuntimeSync
import app.tavernbridge.launcher.model.MainSection
import app.tavernbridge.launcher.model.ToolPage
import app.tavernbridge.launcher.model.ManagementPanel
import app.tavernbridge.launcher.model.SillyBranch
import app.tavernbridge.launcher.model.TermuxSetupStatus
import app.tavernbridge.launcher.model.hasInstallationHistory
import app.tavernbridge.launcher.model.shouldShowSetupGuide
import app.tavernbridge.launcher.model.visibleOperation
import app.tavernbridge.launcher.model.canReadLiveServerLog
import app.tavernbridge.launcher.termux.TermuxContract
import app.tavernbridge.launcher.ui.components.OperationResultCard
import app.tavernbridge.launcher.ui.components.HomeStatusCard
import app.tavernbridge.launcher.ui.components.OperationCancelControl
import app.tavernbridge.launcher.ui.components.WorkProgressLog
import app.tavernbridge.launcher.ui.components.WorkElapsedTime
import app.tavernbridge.launcher.ui.theme.SillyTavernLauncherTheme
import kotlinx.coroutines.delay
import java.util.Locale

private val sentenceBoundary = Regex("""(?<=\.)[ \t]+(?=[가-힣])""")
internal fun formatUiText(text: String): String = text.replace(sentenceBoundary, "\n")

@Composable
private fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = androidx.compose.material3.LocalTextStyle.current,
) {
    MaterialText(
        text = if (fontFamily == V2Mono || style.fontFamily == V2Mono) text else formatUiText(text),
        modifier = modifier,
        color = color,
        fontSize = fontSize,
        fontStyle = fontStyle,
        fontWeight = fontWeight,
        fontFamily = fontFamily,
        letterSpacing = letterSpacing,
        textDecoration = textDecoration,
        textAlign = textAlign,
        lineHeight = lineHeight,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        minLines = minLines,
        onTextLayout = onTextLayout,
        // Wrap at natural phrase boundaries without inserting invisible characters into
        // button names, screen-reader text, selection, search or copied values.
        style = if (fontFamily != V2Mono && style.fontFamily != V2Mono && LocalDensity.current.fontScale < 1.3f)
            style.copy(lineBreak = androidx.compose.ui.text.style.LineBreak.Heading) else style,
    )
}

private fun backupPickerIntent(): Intent {
    val downloadsRoot = DocumentsContract.buildRootUri(
        "com.android.providers.downloads.documents",
        "downloads",
    )
    return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        // Backup files are validated after selection. Using */* also exposes ZIP files whose
        // MIME type was changed by a file manager, cloud app, or another launcher.
        type = "*/*"
        addCategory(Intent.CATEGORY_OPENABLE)
        putExtra(DocumentsContract.EXTRA_INITIAL_URI, downloadsRoot)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    }
}

@Composable
fun LauncherApp(viewModel: LauncherViewModel) {
    val state by viewModel.state.collectAsState()
    LauncherScreen(state, viewModel)
}

@Composable
internal fun LauncherScreen(state: LauncherUiState, viewModel: LauncherViewModel) {
    LaunchedEffect(
        state.environment.processRunning,
        state.environment.serverReachable,
        state.isWorking,
    ) {
        if (state.environment.processRunning && !state.environment.serverReachable && !state.isWorking) {
            while (true) {
                delay(5_000)
                viewModel.refreshSilently()
            }
        }
    }
    val mayReadLog = state.canReadLiveServerLog()
    LaunchedEffect(state.section, state.toolPage, state.diagnosticPanel, state.environment.processRunning, state.isWorking, mayReadLog) {
        if (state.section == MainSection.LOGS && state.toolPage == ToolPage.LOGS && state.diagnosticPanel == DiagnosticPanel.SERVER_LOG && mayReadLog) {
            watchServerLog(state.environment.processRunning || state.isWorking, viewModel::refreshLogsSilently)
        }
    }
    SillyTavernLauncherTheme(state.theme, state.displayPreferences) {
        LauncherScaffold(state, viewModel)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LauncherScaffold(state: LauncherUiState, viewModel: LauncherViewModel) {
    BackupTransferHost(state.backupTransfer, viewModel)
    EncryptedBackupHost(state, viewModel)
    val localTransfer = localTransferProgress(state.backupTransfer, state.encryptedBackup)
    val showLocalProgress = rememberProgressVisible(localTransfer != null, localTransfer?.key)
    val commandAtHome = state.section == MainSection.HOME && state.environment.sillyTavernInstalled &&
        !state.environment.recoveryPending && state.visibleOperation in setOf("start", "stop")
    val showWorkProgress = rememberProgressVisible(state.isWorking, state.workingStartedAtMillis)
    var localFolded by rememberSaveable(localTransfer?.key) { mutableStateOf(false) }
    val localStarted = rememberSaveable(localTransfer?.key) { System.currentTimeMillis() }
    if (localTransfer != null && showLocalProgress && !localFolded) {
        WorkingOverlay(localTransfer.work.phase, localTransfer.work, localStarted, localTransfer.work.cancellationRequested,
            onCancel = if (localTransfer.encrypted) viewModel::cancelEncryptedBackup else viewModel::cancelBackupTransfer,
            onFold = { localFolded = true })
    }
    EntryRequestDialog(state.pendingEntry, viewModel.entryActionGate(), viewModel::confirmEntryAction, viewModel::dismissEntryAction)
    val context = LocalContext.current
    val resources = LocalResources.current
    val snackbarHostState = remember { SnackbarHostState() }
    var progressFolded by rememberSaveable(state.workingStartedAtMillis) { mutableStateOf(false) }
    var backupScopeOpen by rememberSaveable { mutableStateOf(false) }
    val backupWork = state.isWorking && state.visibleOperation == "backup"
    LaunchedEffect(state.isWorking, state.section) {
        if (state.isWorking || state.section != MainSection.BACKUPS) backupScopeOpen = false
    }
    var lastOfferedUndo by rememberSaveable { mutableStateOf(state.lastTrashedEntryId) }
    LaunchedEffect(state.isWorking, state.lastTrashedEntryId, state.lastOperationResult?.completedAtMillis) {
        if (state.isWorking) return@LaunchedEffect
        val receipt = state.lastTrashedEntryId
        if (lastOfferedUndo == receipt) return@LaunchedEffect
        lastOfferedUndo = receipt
        if (receipt.isBlank() || state.lastOperationResult?.let { it.operation == "delete-st" && it.succeeded } != true) return@LaunchedEffect
        val result = showV2Notice(snackbarHostState, "휴지통으로 옮겼어요.", "실행 취소")
        val current = viewModel.state.value
        if (result == SnackbarResult.ActionPerformed && current.lastTrashedEntryId == receipt && current.canModifyTavernFiles())
            viewModel.undoLastTrashedEntry()
    }
    var confirmation by rememberSaveable(stateSaver = ConfirmationSaver) { mutableStateOf<Confirmation?>(null) }
    var welcomeAfterInstall by rememberSaveable { mutableStateOf(false) }
    var requestedArchive by rememberSaveable { mutableStateOf<String?>(null) }
    var archiveReturnToOrigin by rememberSaveable { mutableStateOf(false) }
    val showWelcome = welcomeAfterInstall && state.environment.sillyTavernInstalled &&
        state.section in setOf(MainSection.HOME, MainSection.SETUP, MainSection.BACKUPS) &&
        state.lastOperationResult?.let { it.succeeded && it.operation in setOf("install", "import-install", "import-backup") } == true
    val section = if (showWelcome) MainSection.SETUP else state.section
    val setupFullScreen = section in setOf(MainSection.SETUP, MainSection.BACKUPS) &&
        (showWelcome || state.shouldShowSetupGuide || state.environmentChecked && !state.environment.sillyTavernInstalled)
    var browserPickerOpen by rememberSaveable { mutableStateOf(false) }
    var selectedBrowserPackage by rememberSaveable { mutableStateOf<String?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> viewModel.onCommandPermissionResult(granted) }
    val backupPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                confirmation = Confirmation(
                    title = "이 ZIP에서 바로 복원할까요?",
                    description = "압축을 한 번만 풀어 구조와 용량을 검사한 뒤 복원합니다. 같은 항목의 현재 데이터는 백업 내용으로 교체되므로, 백업 이후 생긴 대화도 사라질 수 있습니다. 실패 시 복구용 임시 사본은 성공 후 삭제됩니다. 원본 ZIP은 변경하지 않습니다. 서버를 먼저 종료해 주세요.",
                    confirmLabel = "검사 후 복원",
                    action = ConfirmationAction.IMPORT_BACKUP,
                    arguments = listOf(uri.toString()),
                    allowBackup = state.environment.sillyTavernInstalled,
                )
            }
        }
    }

    val installationPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel::inspectSharedInstallation)
    }
    val fileImportPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importSillyTavernFile)
    }
    val folderImportPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel::importSillyTavernFolder)
    }
    val pickInstallation = {
        runCatching { installationPicker.launch(null) }
            .onFailure { viewModel.showMessage("폴더 선택 화면을 열 수 없습니다. Termux 폴더 경로 입력을 사용해 주세요.") }
        Unit
    }
    val pickBackup = {
        runCatching { backupPicker.launch(backupPickerIntent()) }
            .onFailure { viewModel.showMessage("파일 선택 화면을 열 수 없습니다. 잠시 후 다시 시도해 주세요.") }
        Unit
    }
    val requestBranchSwitch: (SillyBranch) -> Unit = { branch ->
        val modified = state.environment.modifiedFiles
        val risk = when {
            branch == SillyBranch.RELEASE && state.environment.branch == SillyBranch.STAGING -> resources.getString(R.string.ui_branch_release_warning)
            branch == SillyBranch.STAGING -> resources.getString(R.string.ui_branch_staging_warning)
            else -> ""
        }
        confirmation = Confirmation(
            title = resources.getString(R.string.ui_branch_change_question, branch.label),
            description = listOf(risk, if (modified.isEmpty()) resources.getString(R.string.ui_branch_keep_description)
                else resources.getString(R.string.ui_branch_modified_description, modified.joinToString("\n"))).filter(String::isNotBlank).joinToString("\n\n"),
            confirmLabel = resources.getString(R.string.ui_branch_change),
            action = ConfirmationAction.SWITCH_BRANCH,
            arguments = listOf(branch.value),
        )
    }

    val requestUpdate: () -> Unit = {
        val modifiedFiles = state.updatePreflight?.modifiedFiles.orEmpty()
        confirmation = Confirmation(
            title = if (modifiedFiles.isEmpty()) {
                "SillyTavern을 업데이트할까요?"
            } else {
                "수정된 파일이 있어도 업데이트할까요?"
            },
            description = if (modifiedFiles.isEmpty()) {
                "현재 커밋과 패키지를 보존한 뒤 업데이트합니다. 패키지 설치 또는 서버 응답 검사에 실패하면 이전 커밋과 기존 패키지로 자동 롤백합니다."
            } else {
                "다음 수정 내용을 별도 안전 파일로 보관한 뒤 Git 작업 트리에서는 제거하고 업데이트합니다. 수정 내용은 자동으로 다시 적용되지 않습니다.\n\n${modifiedFiles.joinToString("\n")}"
            },
            confirmLabel = if (modifiedFiles.isEmpty()) "업데이트" else "보관 후 진행",
            action = ConfirmationAction.UPDATE,
        )
    }

    val requestRepair: () -> Unit = {
        confirmation = Confirmation(
            title = "설치 패키지를 점검·복구할까요?",
            description = "캐릭터·대화·설정은 유지하고 Termux 도구와 node_modules를 다시 구성합니다. 서버는 먼저 종료해 주세요.",
            confirmLabel = "점검·복구",
            action = ConfirmationAction.REPAIR,
        )
    }

    val requestReset: (Boolean) -> Unit = { keepBackup ->
        confirmation = Confirmation(
            title = "SillyTavern을 초기화할까요?",
            description = "채팅·캐릭터·설정·확장이 지워져요. 백업이 없으면 되돌릴 수 없어요.",
            confirmLabel = "초기화",
            action = ConfirmationAction.RESET,
            allowBackup = true, backupBefore = keepBackup,
            withoutBackupLabel = "백업 없이 초기화",
        )
    }

    var seenResult by rememberSaveable { mutableStateOf(state.lastOperationResult?.completedAtMillis ?: -1L) }
    var failedResult by rememberSaveable { mutableStateOf(-1L) }
    val summary = state.lastOperationResult
    LaunchedEffect(summary?.completedAtMillis, state.isWorking) {
        if (!state.isWorking && summary != null && summary.completedAtMillis != seenResult) {
            seenResult = summary.completedAtMillis
            if (!summary.succeeded && summary.errorCode != "OPERATION_CANCELLED" &&
                !(section == MainSection.HOME && summary.operation == "start" && summary.errorCode.contains("PORT"))) {
                failedResult = summary.completedAtMillis
            }
            if (summary.succeeded && summary.operation == "restore-st-trash") showV2Notice(snackbarHostState, "되돌렸어요")
        }
    }
    if (summary != null && !state.isWorking && failedResult == summary.completedAtMillis && !summary.succeeded) {
        if (state.canOfferRuntimeSync(failedResult)) {
            RuntimeLibraryRepairSheet(state, failedResult,
                onDismiss = { failedResult = -1L },
                onRetry = { failedResult = -1L; viewModel.retryLastOperation() },
                onHistory = { failedResult = -1L; viewModel.openDiagnostics(DiagnosticPanel.WORK_HISTORY) },
                onSync = { time -> failedResult = -1L; viewModel.confirmRuntimeLibrarySync(time) })
        } else V2BottomSheet(onClose = { failedResult = -1L }, footer = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                V2SheetPrimaryButton(if (summary.canRetry) "다시 시도" else "진단 열기") {
                    failedResult = -1L
                    if (summary.canRetry) viewModel.retryLastOperation() else viewModel.selectTool(ToolPage.DIAGNOSTICS)
                }
                TextButton(onClick = { failedResult = -1L; viewModel.openDiagnostics(DiagnosticPanel.WORK_HISTORY) },
                    modifier = Modifier.fillMaxWidth()) { Text("기록 보기") }
            }
        }) {
            Text("${summary.title} 작업을 마치지 못했어요", style = MaterialTheme.typography.titleLarge)
            Text(summary.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (summary.errorCode.isNotBlank()) Text(summary.errorCode, style = MaterialTheme.typography.labelMedium)
            Text(when {
                state.environment.recoveryPending -> "이전 작업의 복구가 필요해요. 복구 전에는 설치 파일을 변경하지 마세요."
                summary.operation in setOf("backup", "export-backup", "diagnose", "check-update", "update-preflight") -> "이 작업은 현재 설치 데이터를 바꾸지 않아요."
                else -> "실패한 단계와 파일 상태는 작업 기록에서 확인할 수 있어요."
            })
        }
    }
    LaunchedEffect(state.message, state.error, state.lastOperationResult) {
        val notice = state.error ?: state.message
        if (notice != null) {
            if (summary != null && !summary.succeeded && state.error != null && (summary.detail == notice || notice.contains(summary.detail))) {
                viewModel.consumeNotice()
                return@LaunchedEffect
            }
            val op = summary?.takeIf { it.succeeded && it.detail == notice && state.error == null }?.operation
            if (op == "start" && state.autoOpenedStartAtMillis >= 0 && state.autoOpenedStartAtMillis == state.dispatchedAtMillis) {
                viewModel.consumeNotice()
                return@LaunchedEffect
            }
            val archive = if (op == "backup") state.backups.find { it.fileName == state.lastCreatedBackupName } else null
            val action = when {
                op == "start" -> "대화 열기"
                op == "save-lan-security" && state.environment.externalAccessEnabled -> "주소 보기"
                archive != null -> "보기"
                else -> null
            }
            val result = showV2Notice(snackbarHostState,
                message = when {
                    op == "start" -> "서버를 켰어요"
                    archive != null -> "백업했어요 · ${formatBackupBytes(archive.sizeBytes)}"
                    else -> notice
                }, actionLabel = action,
            )
            viewModel.consumeNotice()
            if (result == SnackbarResult.ActionPerformed) when {
                op == "start" -> {
                    val browser = viewModel.preferredBrowser()
                    if (browser != null) viewModel.openBrowser(browser.packageName)
                    else { selectedBrowserPackage = null; browserPickerOpen = true }
                }
                op == "save-lan-security" -> viewModel.openLanAddressFromNotice()
                archive != null -> {
                    archiveReturnToOrigin = viewModel.state.value.section != MainSection.BACKUPS
                    requestedArchive = archive.fileName
                    viewModel.openBackups()
                }
            }
        }
    }

    confirmation?.let { pending ->
        V2ConfirmDialog(
            onDismissRequest = { confirmation = null },
            title = { Text(pending.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(pending.description)
                    if (pending.action == ConfirmationAction.RESET && pending.allowBackup)
                        ResetBackupOption(pending.backupBefore) { confirmation = pending.copy(backupBefore = it) }
                    else if (pending.allowBackup) ReplacementBackupChoice(pending.backupBefore) {
                        confirmation = pending.copy(backupBefore = it)
                    }
                }
            },
            confirmButton = { finish ->
                V2DialogAction(onClick = { finish {
                    confirmation = null
                    if (ConfirmationCodec.decode(ConfirmationCodec.encode(pending)) == null) {
                        viewModel.showMessage(resources.getString(R.string.ui_confirmation_expired))
                    } else when (pending.action) {
                        ConfirmationAction.INSTALL -> {
                            welcomeAfterInstall = setupFullScreen
                            viewModel.selectInstallBranch(SillyBranch.entries.first { it.value == pending.arguments.single() })
                            viewModel.install()
                        }
                        ConfirmationAction.UPDATE -> viewModel.update()
                        ConfirmationAction.RESTORE_BACKUP -> viewModel.restoreBackup(pending.arguments.single(), pending.allowBackup && pending.backupBefore)
                        ConfirmationAction.IMPORT_BACKUP -> {
                            val uri = Uri.parse(pending.arguments.single())
                            if (context.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
                                welcomeAfterInstall = setupFullScreen
                                viewModel.importBackup(uri, pending.allowBackup && pending.backupBefore)
                            } else viewModel.showMessage(resources.getString(R.string.ui_document_access_lost))
                        }
                        ConfirmationAction.DELETE_BACKUPS -> viewModel.deleteBackups(pending.arguments)
                        ConfirmationAction.SWITCH_BRANCH -> viewModel.switchBranch(SillyBranch.entries.first { it.value == pending.arguments.single() })
                        ConfirmationAction.REPAIR -> viewModel.repair()
                        ConfirmationAction.RESET -> viewModel.resetInstallation(pending.allowBackup && pending.backupBefore)
                    }
                } }, enabled = !state.isWorking,
                    tone = if (pending.action in setOf(ConfirmationAction.DELETE_BACKUPS, ConfirmationAction.RESET)) V2DialogTone.Danger else V2DialogTone.Primary
                ) { Text(if (pending.allowBackup && !pending.backupBefore) pending.withoutBackupLabel else pending.confirmLabel,
                    style = MaterialTheme.typography.titleMedium) }
            },
            dismissButton = { cancel ->
                V2DialogAction(onClick = cancel, tone = V2DialogTone.Quiet) { Text(stringResource(R.string.ui_cancel)) }
            },
        )
    }

    state.pendingInstallation?.let { candidate ->
        InstallationImportConfirmation(
            installation = candidate,
            destinationExists = candidate.destinationExists,
            onConfirm = { mode, backup ->
                welcomeAfterInstall = setupFullScreen
                viewModel.confirmInstallationImport(mode, backup)
            },
            onDismiss = viewModel::dismissInstallationImport,
        )
    }

    if (browserPickerOpen) {
        // PackageManager crosses into the OS and resolves app labels. Query once per open dialog.
        val browsers = remember(viewModel, state.environment.port) { viewModel.browserOptions() }
        val selected = selectedBrowserPackage ?: browsers.firstOrNull()?.packageName
        V2AlertDialog(
            onDismissRequest = {
                browserPickerOpen = false
                selectedBrowserPackage = null
            },
            title = { Text("열 앱 선택") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (browsers.isEmpty()) {
                            "이 주소를 열 수 있는 앱을 찾지 못했습니다."
                        } else {
                            "브라우저 또는 연결된 앱을 선택하세요. Android 기본 열기를 선택하면 기존 연결 설정에 따라 앱이 자동으로 열릴 수 있습니다."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    browsers.forEach { browser ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedBrowserPackage = browser.packageName }
                                .padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selected == browser.packageName,
                                onClick = { selectedBrowserPackage = browser.packageName },
                            )
                            Text(browser.label)
                        }
                    }
                }
            },
            confirmButton = {
                V2DialogAction(
                    enabled = selected != null,
                    onClick = {
                        selected?.let { viewModel.openBrowser(it, rememberChoice = true) }
                        browserPickerOpen = false
                        selectedBrowserPackage = null
                    },
                ) { Text("항상") }
            },
            dismissButton = {
                V2DialogAction(tone = V2DialogTone.Quiet, onClick = {
                    browserPickerOpen = false
                    selectedBrowserPackage = null
                }) { Text("취소") }
                V2DialogAction(tone = V2DialogTone.Quiet, enabled = selected != null, onClick = {
                    selected?.let { viewModel.openBrowser(it, rememberChoice = false) }
                    browserPickerOpen = false
                    selectedBrowserPackage = null
                }) { Text("한 번만") }
            },
        )
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
    val useRail = chooseLauncherNavigation(maxWidth.value) == LauncherNavigationMode.RAIL
    val tab = if (section == MainSection.SETUP) MainSection.LOGS else section
    val returnAnchors = remember { ReturnAnchors() }
    androidx.compose.runtime.SideEffect { returnAnchors.route(state.navigation) }
    val selectTab: (MainSection) -> Unit = { target ->
        returnAnchors.cancelTap()
        welcomeAfterInstall = false
        if (target == tab) viewModel.reselectTab(target)
        else if (target == MainSection.BACKUPS) viewModel.openBackups() else viewModel.selectSection(target)
    }
    val backProgress = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    // Restore the originating Settings subpage, Backup detail, list position, etc.
    // only when Back returns to that visit. Opening a screen afresh starts fresh.
    val savedScreens = rememberSaveableStateHolder()
    val knownKeys = remember { mutableSetOf<String>() }
    androidx.compose.runtime.DisposableEffect(state.navigation, section) {
        val live = state.navigation.previous.mapTo(mutableSetOf()) { "${it.section}:${it.id}" }
            .apply { add("${section}:${state.navigation.entryId}") }
        (knownKeys - live).forEach { savedScreens.removeState(it) }
        knownKeys.clear()
        knownKeys.addAll(live)
        onDispose {}
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalV2NoticeHost provides snackbarHostState,
        LocalV2ReturnAnchors provides returnAnchors, LocalV2TabAttention provides state.tabAttention(),
        LocalV2BackProgress provides { backProgress.floatValue = it },
        LocalBackupScopeOpener provides { backupScopeOpen = true }) {
    V2BackHandler(!setupFullScreen && (state.navigation.previous.isNotEmpty() || section == MainSection.SETUP)) {
        viewModel.navigateBack()
    }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { Column {
            if (localTransfer != null && localFolded && snackbarHostState.currentSnackbarData == null)
                WorkingMiniBar(localTransfer.work.phase, localTransfer.work) { localFolded = false }
            if (state.isWorking && progressFolded && snackbarHostState.currentSnackbarData == null)
                WorkingMiniBar(state.workingLabel, state.workProgress) { progressFolded = false }
            V2NoticeHost(snackbarHostState)
            if (!setupFullScreen && !useRail) V2TabBar(tab, selectTab)
        } },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
        ) {
            Row(Modifier.fillMaxSize()) {
            if (useRail && !setupFullScreen) LauncherNavigationRail(tab, selectTab, { it.icon() })
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
            val stableMaster = useRail && section == MainSection.LOGS
            val screenId = if (stableMaster) state.navigation.masterEntry(section) else state.navigation.entryId
            val route = V2RouteKey(screenId, section, section == MainSection.LOGS && state.toolPage != ToolPage.INDEX && !stableMaster)
            val reduced = app.tavernbridge.launcher.ui.theme.LocalV2ReducedMotion.current
            val offset = with(androidx.compose.ui.platform.LocalDensity.current) { 24.dp.roundToPx() }
            AnimatedContent(targetState = route, label = "화면 전환",
                modifier = Modifier.graphicsLayer {
                    if (!useRail && !reduced) { translationX = offset * backProgress.floatValue; alpha = 1f - .35f * backProgress.floatValue }
                },
                transitionSpec = {
                    val subpage = !reduced && (targetState.detail || initialState.detail || targetState.section == MainSection.SETUP || initialState.section == MainSection.SETUP)
                    val back = targetState.entry < initialState.entry
                    val ms = if (!subpage) 120 else if (back) 180 else 220
                    val ease = if (back) FastOutLinearInEasing else LinearOutSlowInEasing
                    val enter = fadeIn(tween(ms, easing = ease)) + slideInHorizontally(tween(ms, easing = ease)) { if (subpage && !back) offset else 0 }
                    val exit = fadeOut(tween(ms, easing = ease)) + slideOutHorizontally(tween(ms, easing = ease)) { if (subpage && back) offset else 0 }
                    enter togetherWith exit
                }) { entry ->
            // Freeze outgoing data until transition disposal; its scroll state belongs to its visit.
            val frame = remember(entry) { androidx.compose.runtime.mutableStateOf(state) }
            if (entry == route) androidx.compose.runtime.SideEffect { frame.value = state }
            val state = if (entry == route) state else frame.value
            val section = entry.section
            savedScreens.SaveableStateProvider("${entry.section}:${entry.entry}") {
                when (section) {
                    MainSection.HOME -> HomeScreen(
                        state = state,
                        onOpen = {
                            val preferred = viewModel.preferredBrowser()
                            if (preferred != null) {
                                viewModel.openBrowser(preferred.packageName)
                            } else {
                                selectedBrowserPackage = null // Dialog selects the first available handler.
                                browserPickerOpen = true
                            }
                        },
                        onStart = viewModel::start,
                        onStop = viewModel::stop,
                        onCancelStart = viewModel::cancelCurrentOperation,
                        onChangePort = viewModel::openPortEditor,
                        onBlocked = { viewModel.showMessage("다른 작업이 끝난 뒤에 할 수 있어요.") },
                        onRestart = viewModel::restart,
                        onRepair = viewModel::repair,
                        onSetup = { viewModel.selectSection(MainSection.SETUP) },
                        onOpenTermuxSettings = viewModel::openTermuxAppSettings,
                        onOpenTermux = viewModel::openTermux,
                        onRefresh = viewModel::refresh,
                        onRetry = viewModel::retryLastOperation,
                        setupCommand = viewModel.setupCommand,
                        onOpenPermissionSettings = viewModel::openPermissionSettings,
                        onOpenBackups = viewModel::openBackups,
                        onOpenArchive = { name -> archiveReturnToOrigin = true; requestedArchive = name; viewModel.openBackups() },
                        onUpdate = { viewModel.selectTool(ToolPage.UPDATE) },
                        onLogs = { viewModel.openDiagnostics(DiagnosticPanel.SERVER_LOG) },
                        onHistory = { viewModel.openDiagnostics(DiagnosticPanel.WORK_HISTORY) },
                        onReadHistory = viewModel::refreshHistorySilently,
                        onSnoozeBackupReminder = viewModel::snoozeBackupReminder,
                    )
                    MainSection.SETUP, MainSection.BACKUPS -> if (!state.environmentChecked && !state.shouldShowSetupGuide) {
                        EnvironmentCheckingScreen(
                            state = state,
                            onOpenTermuxSettings = viewModel::openTermuxAppSettings,
                            onOpenTermux = viewModel::openTermux,
                            onRefresh = viewModel::refresh,
                            setupCommand = viewModel.setupCommand,
                            onOpenPermissionSettings = viewModel::openPermissionSettings,
                        )
                    } else if (state.environment.recoveryPending && state.termuxSetup.verified && state.environment.managerConnected) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                            RestoreRecoveryCard(state, viewModel::repair, viewModel::stop, viewModel::openTermux)
                        }
                    } else if (!showWelcome && state.environment.sillyTavernInstalled && state.termuxSetup.verified && state.environment.managerConnected) {
                        ManagementScreen(
                            state = state,
                            onBack = if (state.navigation.previous.lastOrNull()?.section == MainSection.SETTINGS) viewModel::navigateBack else null,
                            panel = if (section == MainSection.BACKUPS) ManagementPanel.BACKUP else state.managementPanel,
                            requestedArchive = requestedArchive,
                            archiveReturnToOrigin = archiveReturnToOrigin,
                            onArchiveOpened = { requestedArchive = null },
                            onArchiveShortcutBack = { viewModel.navigateBack() },
                            onSelectPanel = viewModel::selectManagementPanel,
                            onCheckUpdate = viewModel::checkForUpdates,
                            onAutoBackupChange = viewModel::setAutoBackupBeforeUpdate,
                            onUpdate = requestUpdate,
                            onCreateBackup = viewModel::createBackup,
                            onRefreshBackups = viewModel::loadBackups,
                            onBackupReminderChange = viewModel::setBackupReminderEnabled,
                            onTransferBackup = viewModel::beginBackupTransfer,
                            onOpenBackupFolder = viewModel::openDownloadsFolder,
                            onOpenSafetyInventory = viewModel::openSafetyInventory,
                            onInspectStorage = viewModel::inspectStorageUsage,
                            onPreviewCleanup = viewModel::previewBackupCleanup,
                            onSaveBackupAnnotation = viewModel::saveBackupAnnotation,
                            onEncryptedBackup = viewModel::beginEncryptedBackup,
                            onSelectiveRestore = viewModel::openSelectiveRestore,
                            onPreviousVersions = viewModel::openPreviousVersions,
                            onImportBackup = pickBackup,
                            onPickInstallation = pickInstallation,
                            onInspectInstallation = viewModel::inspectExistingInstallation,
                            storageSetupCommand = viewModel.storageSetupCommand,
                            onOpenTermux = viewModel::openTermux,
                            onStorageCommandCopied = {},
                            onRestoreBackup = { backup ->
                                confirmation = Confirmation(
                                    title = "이 백업으로 복원할까요?",
                                    description = backupRestorePreview(backup, state.environment.sillyTavernVersion),
                                    confirmLabel = "안전하게 복원",
                                    action = ConfirmationAction.RESTORE_BACKUP,
                                    arguments = listOf(backup.fileName),
                                    allowBackup = state.environment.sillyTavernInstalled,
                                )
                            },
                            onDeleteBackups = { backups ->
                                confirmation = Confirmation(
                                    title = "선택한 백업 ${backups.size}개를 삭제할까요?",
                                    description = "총 ${formatBackupBytes(backups.sumOf { it.sizeBytes })}의 ZIP을 휴대폰 Download 폴더에서 삭제합니다. 삭제한 파일은 앱에서 복구할 수 없습니다.",
                                    confirmLabel = "선택 항목 삭제",
                                    action = ConfirmationAction.DELETE_BACKUPS,
                                    arguments = backups.map(app.tavernbridge.launcher.model.BackupDeleteSelection::key),
                                )
                            },
                            onSwitchBranch = requestBranchSwitch,
                            onRepair = requestRepair,
                            onResetInstallation = { requestReset(true) },
                        )
                    } else {
                        SetupScreen(
                            state = state,
                            onLeave = {
                                welcomeAfterInstall = false
                                if (state.environment.sillyTavernInstalled) viewModel.finishSetup()
                                else viewModel.deferSetup()
                            },
                            setupCommand = viewModel.setupCommand,
                            onDownloadTermux = viewModel::openTermuxDownload,
                            onOpenTermux = viewModel::openTermux,
                            onRequestPermission = { permissionLauncher.launch(TermuxContract.PERMISSION_RUN_COMMAND) },
                            onOpenPermissionSettings = viewModel::openPermissionSettings,
                            onVerifyTermux = viewModel::verifyTermuxSetup,
                            onConfirmFirstConnection = viewModel::confirmFirstConnection,
                            onOpenSetupTermux = viewModel::openTermuxForSetup,
                            onConnect = viewModel::connectManager,
                            onSelectBranch = viewModel::selectInstallBranch,
                            onInstall = {
                                confirmation = Confirmation(
                                    title = "${state.selectedInstallBranch.label}를 설치할까요?",
                                    description = "기존 ~/SillyTavern 폴더가 있으면 설치를 중단하며 데이터를 덮어쓰지 않습니다.",
                                    confirmLabel = "설치",
                                    action = ConfirmationAction.INSTALL,
                                    arguments = listOf(state.selectedInstallBranch.value),
                                )
                            },
                            onSwitchBranch = requestBranchSwitch,
                            onBackup = viewModel::backup,
                            onPickInstallation = pickInstallation,
                            onInspectInstallation = viewModel::inspectExistingInstallation,
                            onImportBackup = pickBackup,
                            onCopied = {},
                        )
                    }
                    MainSection.LOGS -> V2ToolsScreen(state, viewModel,
                        onUpdate = requestUpdate, onRepair = requestRepair, onReset = requestReset,
                        onOpenInstallation = {
                            viewModel.selectManagementPanel(ManagementPanel.INSTALL)
                            viewModel.selectSection(MainSection.SETUP)
                        })
                    MainSection.SETTINGS -> V2SettingsScreen(state, viewModel)
                }
            }
            }
            }
            }
            if (state.safetyInventory.open && !state.isWorking) {
                SafetyInventoryDialog(
                    state = state.safetyInventory,
                    serverRunning = state.environment.processRunning,
                    onClose = viewModel::closeSafetyInventory,
                    onRefresh = { viewModel.loadSafetyInventory() },
                    onMore = { viewModel.loadSafetyInventory(more = true) },
                    onAction = viewModel::changeSafetyItem,
                )
            }
            state.backupCleanupPreview?.takeUnless { state.isWorking }?.let { preview ->
                BackupCleanupDialog(preview, viewModel::closeBackupCleanup, viewModel::deleteCleanupSelection)
            }
            if (state.extensionManagerOpen && !state.isWorking) ExtensionManagerDialog(
                inventory = state.extensionInventory, loading = state.extensionLoading, busy = state.isWorking,
                canModify = !state.environment.processRunning && !state.environment.recoveryPending,
                error = state.extensionError, onRefresh = { viewModel.loadExtensions() },
                onMore = { viewModel.loadExtensions(true) }, onRecover = viewModel::recoverExtension,
                onAction = viewModel::changeExtension, onClose = viewModel::closeExtensions,
                onCheckUpdates = viewModel::checkExtensionVersions, onUpdateAll = viewModel::updateCheckedExtensions,
            )
            state.selectiveRestore?.takeUnless { state.isWorking }?.let { selection ->
                SelectiveRestoreDialog(selection,
                    canRestore = !state.environment.processRunning && !state.environment.recoveryPending && state.environment.extensionRecovery == "none",
                    onMore = { viewModel.loadSelectiveRestore(true) },
                    onRefresh = { viewModel.loadSelectiveRestore() },
                    onRestore = viewModel::restoreSelection, onClose = viewModel::closeSelectiveRestore)
            }
            state.previousVersions?.takeUnless { state.isWorking }?.let { versions ->
                PreviousVersionDialog(versions,
                    canChange = !state.environment.processRunning && !state.environment.recoveryPending && state.environment.extensionRecovery == "none",
                    onRefresh = viewModel::loadPreviousVersions,
                    onChange = viewModel::changeVersion, onClose = viewModel::closePreviousVersions)
            }
            if (state.fileBrowserOpen && !(state.section == MainSection.LOGS && state.toolPage == ToolPage.FILES)) {
                TavernFileManagerDialog(
                    state = state,
                    onNavigate = viewModel::loadSillyTavernFolder,
                    onOpenFile = viewModel::openSillyTavernFile,
                    onOpenDirectory = viewModel::openSillyTavernDirectory,
                    onEdit = viewModel::readSillyTavernTextFile,
                    onSave = viewModel::saveSillyTavernTextFile,
                    onCloseEditor = viewModel::closeTextEditor,
                    onCreateFolder = viewModel::createSillyTavernFolder,
                    onCreateFile = viewModel::createSillyTavernFile,
                    onRename = viewModel::renameSillyTavernEntry,
                    onTrash = viewModel::trashSillyTavernEntry,
                    readTrash = viewModel::readSillyTavernTrash,
                    onRestoreTrash = viewModel::restoreTrashedFile,
                    onTrashMany = viewModel::trashSillyTavernEntries,
                    onPreview = viewModel::previewSillyTavernFile,
                    preview = state.filePreview,
                    onClosePreview = viewModel::closeFilePreview,
                    onImport = {
                        runCatching { fileImportPicker.launch(arrayOf("*/*")) }
                            .onFailure { viewModel.showMessage("파일 선택 화면을 열 수 없습니다.") }
                    },
                    onImportFolder = {
                        runCatching { folderImportPicker.launch(null) }
                            .onFailure { viewModel.showMessage("폴더 선택 화면을 열 수 없습니다.") }
                    },
                    onClose = viewModel::closeSillyTavernFolder,
                )
            }
            val scope = backupScopeOpen && !state.isWorking && state.section == MainSection.BACKUPS
            if (scope || (backupWork && !progressFolded)) {
                V2BackupFlowSheet(state, scope, viewModel::createBackup, viewModel::cancelCurrentOperation) {
                    if (backupWork) progressFolded = true else backupScopeOpen = false
                }
            } else if (showWorkProgress && !progressFolded && !commandAtHome &&
                !(state.section == MainSection.SETTINGS && state.runningSettingsDiagnostic)) {
                WorkingOverlay(state.workingLabel, state.workProgress, state.workingStartedAtMillis,
                    state.cancellationRequested, viewModel::cancelCurrentOperation) { progressFolded = true }
            }
        }
    }
    }
    }
}

@Composable
private fun HomeScreen(
    state: LauncherUiState,
    onOpen: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCancelStart: () -> Unit,
    onChangePort: () -> Unit,
    onBlocked: () -> Unit,
    onRestart: () -> Unit,
    onRepair: () -> Unit,
    onSetup: () -> Unit,
    onOpenTermuxSettings: () -> Unit,
    onOpenTermux: () -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    setupCommand: String,
    onOpenPermissionSettings: () -> Unit,
    onOpenBackups: () -> Unit,
    onOpenArchive: (String) -> Unit,
    onUpdate: () -> Unit,
    onLogs: () -> Unit,
    onHistory: () -> Unit,
    onReadHistory: () -> Unit,
    onSnoozeBackupReminder: () -> Unit,
) {
    val env = state.environment
    if (state.environmentChecked && env.sillyTavernInstalled && !env.recoveryPending) {
        V2HomeDashboard(state, onOpen, onStart, onStop, onRestart, onOpenBackups,
            onUpdate, onLogs, onHistory, onReadHistory, onRefresh, onRetry, onSnoozeBackupReminder, onOpenArchive,
            onCancelStart, onChangePort, onBlocked)
    } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        when {
            env.recoveryPending -> RestoreRecoveryCard(state, onRepair, onStop, onOpenTermux)
            state.shouldShowSetupGuide || (state.setupDeferred && !state.hasInstallationHistory) ->
                EmptyHomeCard(state.setupDeferred, onSetup, onRefresh)
            !state.environmentChecked -> EnvironmentCheckingCard(state, onOpenTermuxSettings, onOpenTermux, onRefresh,
                setupCommand, onOpenPermissionSettings)
            else -> EmptyHomeCard(state.setupDeferred, onSetup, onRefresh)
        }
        if (!env.recoveryPending) state.lastOperationResult?.let { OperationResultCard(it, onRetry) }
    }
}
@Composable
private fun RestoreRecoveryCard(
    state: LauncherUiState,
    onRepair: () -> Unit,
    onStop: () -> Unit,
    onOpenTermux: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("중단된 복원을 확인해 주세요", style = MaterialTheme.typography.titleLarge)
            SelectionContainer {
                Text(
                    state.environment.recoveryMessage.ifBlank {
                        "완료되지 않은 복원 기록이 있습니다. 보호사본을 지우거나 새로 설치하지 말고 점검·복구를 실행해 주세요."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Button(
                onClick = if (state.environment.processRunning) onStop else onRepair,
                enabled = !state.isWorking && !state.environment.operationActive,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.environment.processRunning) "먼저 서버 종료" else "점검·복구")
            }
            TextButton(onClick = onOpenTermux) { Text("Termux 열기") }
        }
    }
}

@Composable
private fun EnvironmentCheckingScreen(
    state: LauncherUiState,
    onOpenTermuxSettings: () -> Unit,
    onOpenTermux: () -> Unit,
    onRefresh: () -> Unit,
    setupCommand: String,
    onOpenPermissionSettings: () -> Unit,
) {
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), contentAlignment = Alignment.Center) {
        EnvironmentCheckingCard(state, onOpenTermuxSettings, onOpenTermux, onRefresh,
            setupCommand, onOpenPermissionSettings)
    }
}

@Composable
internal fun EnvironmentCheckingCard(
    state: LauncherUiState,
    onOpenTermuxSettings: () -> Unit,
    onOpenTermux: () -> Unit,
    onRefresh: () -> Unit,
    setupCommand: String,
    onOpenPermissionSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    // Before the first resumed probe UNKNOWN is not a failed connection.
    val checking = state.isWorking || state.termuxSetup.status == TermuxSetupStatus.CHECKING ||
        (state.termuxSetup.status == TermuxSetupStatus.UNKNOWN && !state.termuxWakeBlocked && state.error == null)
    val wakeBlocked = state.termuxWakeBlocked
    val showPending = rememberProgressVisible(checking)
    if (checking && !showPending) return
    HomeStatusCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (checking) {
                VisibleBusyIndicator(Modifier.size(24.dp), linear = false, strokeWidth = 2.5.dp)
            } else {
                Icon(
                    Icons.Outlined.WarningAmber,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                when {
                    checking -> "기존 설치를 확인하고 있어요"
                    wakeBlocked -> "Android가 Termux 실행을 막았어요"
                    state.hasInstallationHistory -> "기존 설치에 다시 연결하지 못했어요"
                    else -> "설치 상태를 확인하지 못했어요"
                },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            when {
                checking -> "확인이 끝날 때까지 설치 화면으로 전환하지 않습니다."
                wakeBlocked -> "Termux 앱 정보에서 배터리를 ‘제한 없음’으로 바꾸고 Termux를 한 번 연 뒤 다시 확인해 주세요. 기존 SillyTavern 데이터는 그대로입니다."
                else -> "기존 설치를 미설치로 처리하지 않았습니다. 다시 확인해 주세요."
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (!checking) {
            if (state.termuxSetup.detail.isNotBlank()) {
                SelectionContainer {
                    Text(state.termuxSetup.detail, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (wakeBlocked) {
                Button(onClick = onOpenTermuxSettings, modifier = Modifier.fillMaxWidth()) {
                    Text("Termux 배터리 설정 열기")
                }
            }
            if (state.termuxSetup.status == TermuxSetupStatus.PERMISSION_REQUIRED) {
                Button(onClick = onOpenPermissionSettings, modifier = Modifier.fillMaxWidth()) {
                    Text("런처 실행 권한 설정")
                }
            }
            if (state.termuxSetup.status == TermuxSetupStatus.EXTERNAL_APPS_DISABLED) {
                Button(onClick = {
                    clipboard.setText(AnnotatedString(setupCommand))
                    onOpenTermux()
                }, modifier = Modifier.fillMaxWidth()) {
                    Text("연결 설정 명령 복사·Termux 열기")
                }
            }
            OutlinedButton(onClick = onOpenTermux, modifier = Modifier.fillMaxWidth()) {
                Text("Termux 한 번 열기")
            }
            TextButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                Text("설치 상태 다시 확인")
            }
        }
    }
}

@Composable
private fun EmptyHomeCard(deferred: Boolean, onSetup: () -> Unit, onRefresh: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(if (deferred) "설정을 이어갈까요?" else "SillyTavern을 설치하세요", style = MaterialTheme.typography.displaySmall)
        Text(if (deferred) "처음 설정을 건너뛰었어요. 끝내지 않은 단계부터 이어갈 수 있어요."
            else "설치를 마치면 이곳에서 서버를 시작하고 대화를 열 수 있어요.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onSetup, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text(if (deferred) "이어서 하기" else "설치하기")
        }
        TextButton(onClick = onRefresh) { Text("설치 상태 다시 확인") }
    }
}

@Composable
private fun InfoRow(label: String, value: String, monospace: Boolean = false) {
    AdaptiveInfoRow(label, value, monospace)
}

@Composable
internal fun V2BackupFlowSheet(state: LauncherUiState, choosingScope: Boolean,
    onCreate: (Set<BackupCategory>, Boolean, Set<String>) -> Unit, onCancel: () -> Unit, onClose: () -> Unit) {
    BackupScopeSheet(state, choosingScope, onCreate, onClose) {
        WorkingProgressContent(state.workingLabel, state.workProgress, state.workingStartedAtMillis,
            state.cancellationRequested, onCancel)
    }
}


@Composable
internal fun BackupStorageSetupCard(
    command: String,
    onOpenTermux: () -> Unit,
    onRefresh: () -> Unit,
    onCopied: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var copiedAt by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    LaunchedEffect(copiedAt) { if (copiedAt > 0) { delay(3_000); copiedAt = 0L } }
    var checkOnReturn by rememberSaveable { mutableStateOf(false) }
    val check by androidx.compose.runtime.rememberUpdatedState(onRefresh)
    val lifecycle = androidx.compose.ui.platform.LocalView.current.findViewTreeLifecycleOwner()?.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && checkOnReturn) {
                checkOnReturn = false
                check()
            }
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("백업 저장공간을 한 번 연결해 주세요", style = MaterialTheme.typography.titleLarge)
            Text(
                "백업 ZIP은 Termux 내부가 아니라 휴대폰 내장 저장소의 ‘내 파일 → Download’에 저장됩니다. 기기 변경 시 USB·클라우드·Quick Share 등으로 새 휴대폰에 옮길 수 있습니다.",
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(
                    "1. 아래 명령을 눌러 복사하세요.\n2. Termux를 열어 붙여넣고 Enter를 누른 뒤 저장공간 접근을 허용하세요.\n3. 런처로 돌아오면 연결을 확인해요.",
                    modifier = Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Surface(onClick = {
                clipboard.setText(AnnotatedString(command))
                copiedAt = android.os.SystemClock.elapsedRealtime()
                onCopied()
            }, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp)) {
                Text(if (copiedAt > 0) "복사됨" else command, Modifier.padding(12.dp), fontFamily = V2Mono,
                    color = if (copiedAt > 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface)
            }
            Button(
                onClick = {
                    checkOnReturn = true
                    onOpenTermux()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Termux 열기")
            }
            TextButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Refresh, null)
                Spacer(Modifier.width(8.dp))
                Text("연결 확인")
            }
        }
    }
}

private fun backupRestorePreview(backup: BackupArchive, currentVersion: String): String = buildString {
    appendLine("생성: ${backup.createdAt.ifBlank { "알 수 없음" }}")
    if (backup.sillyTavernVersion.isNotBlank()) appendLine("SillyTavern: ${backup.sillyTavernVersion}")
    backupRestoreWarnings(backup, currentVersion, System.currentTimeMillis()).forEach { appendLine("주의: $it") }
    appendLine("캐릭터 파일: ${backup.characterFiles?.toString() ?: "기록 없음"} · 채팅 파일: ${backup.chatFiles?.toString() ?: "기록 없음"}")
    appendLine("백업 당시 파일 수이며 현재 데이터와 비교한 결과는 아닙니다.")
    if (backup.branch.isNotBlank()) appendLine("브랜치: ${backup.branch}")
    appendLine("포함 항목: ${backup.categories.joinToString(" · ") { it.label }.ifBlank { "알 수 없음" }}")
    if (backup.expandedBytes > 0) {
        appendLine("해제 예상: ${formatBackupBytes(backup.expandedBytes)} · 파일/폴더 ${backup.entryCount}개")
        appendLine("필요 공간: ${formatBackupBytes(backup.requiredRestoreBytes)} · 현재 여유 ${formatBackupBytes(backup.availableRestoreBytes)}")
    } else {
        appendLine("필요 공간은 복원 시작 전에 기기에서 다시 계산합니다.")
    }
    appendLine("비밀 설정: ${if (backup.includesSecrets) "포함됨" else "포함되지 않음 · 현재 secrets.json 유지"}")
    appendLine("무결성: ${if (backup.integrity == "zip-crc32") "ZIP CRC 검사" else "복원 전 전체 ZIP 검사"}")
    appendLine()
    append("현재 데이터를 백업 내용으로 교체합니다. 백업 이후 생긴 대화는 사라질 수 있습니다. 실패 시 복구용 임시 사본은 성공 후 삭제됩니다.")
}.trim()

internal fun formatBackupBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(Locale.KOREA, "%.1f GB", bytes / 1073741824.0)
    bytes >= 1024L * 1024 -> String.format(Locale.KOREA, "%.1f MB", bytes / 1048576.0)
    bytes >= 1024L -> String.format(Locale.KOREA, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

@Composable
private fun ManagementScreen(
    state: LauncherUiState,
    onBack: (() -> Unit)?,
    onSelectPanel: (ManagementPanel) -> Unit,
    onCheckUpdate: () -> Unit,
    onAutoBackupChange: (Boolean) -> Unit,
    onUpdate: () -> Unit,
    onCreateBackup: (Set<BackupCategory>, Boolean, Set<String>) -> Unit,
    onRefreshBackups: () -> Unit,
    onBackupReminderChange: (Boolean) -> Unit,
    onTransferBackup: (BackupArchive, BackupTransferAction) -> Unit,
    onOpenBackupFolder: () -> Unit,
    onOpenSafetyInventory: () -> Unit,
    onInspectStorage: () -> Unit,
    onPreviewCleanup: (Int, Int) -> Unit,
    onSaveBackupAnnotation: (String, Boolean, String) -> Unit,
    onEncryptedBackup: (app.tavernbridge.launcher.model.EncryptedBackupMode) -> Unit,
    onSelectiveRestore: (BackupArchive) -> Unit,
    onPreviousVersions: () -> Unit,
    onImportBackup: () -> Unit,
    onPickInstallation: () -> Unit,
    onInspectInstallation: (String) -> Unit,
    storageSetupCommand: String,
    onOpenTermux: () -> Unit,
    onStorageCommandCopied: () -> Unit,
    onRestoreBackup: (BackupArchive) -> Unit,
    onDeleteBackups: (List<BackupArchive>) -> Unit,
    onSwitchBranch: (SillyBranch) -> Unit,
    onRepair: () -> Unit,
    onResetInstallation: () -> Unit,
    panel: ManagementPanel = state.managementPanel,
    requestedArchive: String?,
    archiveReturnToOrigin: Boolean,
    onArchiveOpened: () -> Unit,
    onArchiveShortcutBack: () -> Unit,
) {
    if (panel == ManagementPanel.BACKUP) {
        V2BackupScreen(
            state, onCreateBackup, onRefreshBackups, onBackupReminderChange, onTransferBackup,
            onOpenBackupFolder, onOpenSafetyInventory, onInspectStorage, onPreviewCleanup,
            onSaveBackupAnnotation, onEncryptedBackup, onSelectiveRestore, onImportBackup,
            storageSetupCommand, onOpenTermux, onStorageCommandCopied, onRestoreBackup, onDeleteBackups,
            requestedArchive, onArchiveOpened, onArchiveShortcutBack, archiveReturnToOrigin,
        )
        return
    }
    val environment = state.environment
    val updateReport = state.updatePreflight
    val updateReady = updateReport != null && updateReport.updateAvailable &&
        updateReport.nodeCompatible && updateReport.spaceReady &&
        (!state.autoBackupBeforeUpdate || updateReport.backupStorageReady)
    val selectedPanel = panel
    Column(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "뒤로") }
                Text("관리", style = MaterialTheme.typography.displaySmall, modifier = Modifier.semantics { heading() })
            }
            SectionTabs(ManagementPanel.entries, selectedPanel, onSelectPanel) { it.label }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(20.dp, 0.dp, 20.dp, 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (selectedPanel == ManagementPanel.INSTALL) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                shape = RoundedCornerShape(24.dp),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.CheckCircle, null, modifier = Modifier.size(26.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("설치 정보", style = MaterialTheme.typography.titleLarge)
                    }
                    InfoRow("버전", environment.sillyTavernVersion.ifBlank { "알 수 없음" })
                    InfoRow("브랜치", environment.branch?.label ?: "알 수 없음")
                    InfoRow("설치 위치", "~/SillyTavern", monospace = true)
                    InfoRow("Node.js", environment.nodeVersion.ifBlank { "확인 필요" }, monospace = true)
                    InfoRow("커밋", environment.commit.ifBlank { "알 수 없음" }, monospace = true)
                }
            }
        }
        item {
            ExistingInstallationImportCard(
                enabled = !state.isWorking && !environment.processRunning && !environment.operationActive,
                onPickFolder = onPickInstallation,
                onInspectPath = onInspectInstallation,
            )
        }
        item {
            Card(shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("설치 유지관리", style = MaterialTheme.typography.titleLarge)
                    BranchChangeButtons(environment.branch, onSwitchBranch, enabled = !state.isWorking)
                    HorizontalDivider()
                    Text(
                        "오류가 반복될 때 사용자 데이터는 유지하고 Termux 도구와 실행 패키지만 다시 구성합니다.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(
                        onClick = onRepair,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !environment.processRunning,
                    ) {
                        Icon(Icons.Outlined.RestartAlt, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (environment.processRunning) "서버 종료 후 점검·복구" else "설치 점검·복구")
                    }
                }
            }
        }
        item(key = "dangerous_installation_actions") {
            DangerousInstallationActions(environment.processRunning, enabled = !state.isWorking, onReset = onResetInstallation)
        }
            }

            if (selectedPanel == ManagementPanel.UPDATE) {
        item {
            Card(shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("안전 업데이트", style = MaterialTheme.typography.titleLarge)
                    Text(
                        when {
                            updateReport == null -> "업데이트 전에 커밋·Node.js·저장 공간·수정 파일을 검사합니다."
                            !updateReport.updateAvailable -> "현재 ${updateReport.branch} 브랜치가 최신 커밋입니다."
                            updateReport.currentVersion == updateReport.targetVersion ->
                                "버전 번호는 같지만 ${updateReport.commitsBehind.coerceAtLeast(1)}개의 새 커밋이 있습니다."
                            else -> "${updateReport.currentVersion.ifBlank { "현재 버전" }} → ${updateReport.targetVersion.ifBlank { "새 버전" }}"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("업데이트 전 자동 백업", fontWeight = FontWeight.SemiBold)
                            Text(
                                "사용자 데이터·확장·config.yaml · 비밀 설정 제외",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = state.autoBackupBeforeUpdate,
                            onCheckedChange = onAutoBackupChange,
                        )
                    }

                    if (updateReport != null) {
                        HorizontalDivider()
                        InfoRow(
                            "커밋",
                            "${updateReport.currentCommit.take(7)} → ${updateReport.targetCommit.take(7)}",
                            monospace = true,
                        )
                        if (updateReport.updateAvailable) {
                            InfoRow("새 커밋", "${updateReport.commitsBehind.coerceAtLeast(1)}개")
                            UpdateChangesPreview(updateReport)
                        }
                        InfoRow(
                            "Node.js",
                            "${updateReport.nodeVersion.ifBlank { "없음" }} / 필요 ${updateReport.nodeRequired.ifBlank { "알 수 없음" }}",
                        )
                        InfoRow(
                            "업데이트 공간",
                            "${formatBackupBytes(updateReport.requiredBytes)} 필요 · ${formatBackupBytes(updateReport.freeBytes)} 여유",
                        )
                        if (!updateReport.nodeCompatible || !updateReport.spaceReady) {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = RoundedCornerShape(14.dp),
                            ) {
                                Text(
                                    when {
                                        !updateReport.nodeCompatible -> "현재 Node.js가 새 버전의 요구 조건을 충족하지 않습니다."
                                        else -> "기존 패키지를 보존하고 새 패키지를 설치할 공간이 부족합니다."
                                    },
                                    modifier = Modifier.padding(14.dp),
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        }
                        if (state.autoBackupBeforeUpdate && !updateReport.backupStorageReady) {
                            Text(
                                "자동 백업을 사용하려면 먼저 아래 백업 저장공간을 연결해 주세요.",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        if (updateReport.modifiedFiles.isNotEmpty()) {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = RoundedCornerShape(14.dp),
                            ) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        "수정된 파일 ${updateReport.modifiedFiles.size}개",
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    SelectionContainer {
                                        Text(
                                            updateReport.modifiedFiles.joinToString("\n"),
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                            fontFamily = V2Mono,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                    Text(
                                        "진행하면 수정 내용은 별도 안전 파일에 보관되지만 새 버전에는 자동 적용되지 않습니다.",
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }

                    OutlinedButton(onClick = onCheckUpdate, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.HealthAndSafety, null)
                        Spacer(Modifier.width(8.dp))
                        Text("업데이트 안전 검사")
                    }
                    Button(
                        onClick = onUpdate,
                        enabled = updateReady,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.Update, null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when {
                                updateReport?.updateAvailable == false -> "최신 상태"
                                state.autoBackupBeforeUpdate -> "백업 후 안전하게 업데이트"
                                else -> "안전하게 업데이트"
                            },
                        )
                    }
                    TextButton(onClick = onPreviousVersions, enabled = !environment.processRunning) {
                        Text("이전 버전 선택·복귀")
                    }
                    state.lastUpdateRecord?.let { record ->
                        HorizontalDivider()
                        Text("최근 업데이트 기록", style = MaterialTheme.typography.titleSmall)
                        InfoRow("시각", record.createdAt.ifBlank { "알 수 없음" })
                        InfoRow("브랜치", record.branch.ifBlank { "알 수 없음" })
                        InfoRow(
                            "기록된 커밋",
                            "${record.oldCommit.take(7)} → ${(record.newCommit.ifBlank { record.targetCommit }).take(7)}",
                            monospace = true,
                        )
                        Text(
                            when {
                                record.result == "success" -> "업데이트 및 서버 검증 완료"
                                record.result.startsWith("rolled_back") -> "업데이트 실패 후 이전 상태로 롤백됨"
                                record.result == "rollback_failed" -> "자동 롤백 확인 필요"
                                else -> "업데이트 기록: ${record.result.ifBlank { "알 수 없음" }}"
                            },
                            color = when {
                                record.result == "success" -> MaterialTheme.colorScheme.primary
                                record.result == "rollback_failed" -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (record.result.startsWith("rolled_back") || record.result == "rollback_failed") {
                            TextButton(onClick = { onSelectPanel(ManagementPanel.BACKUP) }) {
                                Text("업데이트 전 ZIP 백업 확인·복원")
                            }
                        }
                    }
                }
            }
        }
            }

        }
    }
}


@Composable
internal fun WorkingOverlay(
    label: String,
    progress: app.tavernbridge.launcher.model.WorkProgress?,
    startedAtMillis: Long,
    cancellationRequested: Boolean,
    onCancel: () -> Unit,
    onFold: () -> Unit,
) {
    V2BottomSheet(onClose = onFold, handleLabel = stringResource(R.string.ui_collapse_progress)) {
        WorkingProgressContent(label, progress, startedAtMillis, cancellationRequested, onCancel)
    }
}

@Composable
private fun WorkingProgressContent(label: String, progress: app.tavernbridge.launcher.model.WorkProgress?,
    startedAtMillis: Long, cancellationRequested: Boolean, onCancel: () -> Unit) {
    val finalConfirmation = label == "작업 완료" || label == "진단 완료"
    val finalizing = progress?.status == "success" && !finalConfirmation
    val measuredPercent = progress?.measuredPercent.takeUnless { finalizing }
    val fallbackPhase = stringResource(R.string.ui_processing)
    val phase = if (finalizing) stringResource(R.string.ui_finalizing) else progress?.phase?.takeIf(String::isNotBlank)
        ?: label.takeIf(String::isNotBlank) ?: fallbackPhase
    val measuredCount = if (finalizing) "" else when {
        progress?.progressMode == "bytes" && progress.totalBytes > 0 ->
            "${formatBackupBytes(progress.completedBytes.coerceIn(0, progress.totalBytes))} / ${formatBackupBytes(progress.totalBytes)}"
        progress?.progressMode == "files" && progress.totalFiles > 0 ->
            stringResource(R.string.ui_progress_files, progress.completedFiles.coerceIn(0, progress.totalFiles), progress.totalFiles)
        else -> ""
    }
    val reduce = app.tavernbridge.launcher.ui.theme.LocalV2ReducedMotion.current
    val tracker = remember(startedAtMillis, progress?.phaseStartedAtMillis, progress?.phase, progress?.progressMode,
        progress?.totalBytes, progress?.totalFiles) { app.tavernbridge.launcher.model.PhaseEta() }
    var eta by remember(tracker) { mutableStateOf<Long?>(null) }
    val done = when (progress?.progressMode) { "bytes" -> progress.completedBytes; "files" -> progress.completedFiles; else -> null }
    val total = when (progress?.progressMode) { "bytes" -> progress.totalBytes; "files" -> progress.totalFiles; else -> null }
    LaunchedEffect(tracker, done, total, measuredPercent != null) {
        eta = if (measuredPercent != null && done != null && total != null) {
            tracker.observe(android.os.SystemClock.elapsedRealtime(), done, total)
        } else null
        // Only a new measured sample can improve an estimate. One timer hides a stale one.
        if (eta != null) {
            kotlinx.coroutines.delay(app.tavernbridge.launcher.model.PhaseEta.STALE_MS + 1)
            eta = null
        }
    }
    val title = label.ifBlank { phase }
    val bar: @Composable () -> Unit = {
        if (measuredPercent != null) LinearProgressIndicator(progress = { measuredPercent / 100f },
            color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.fillMaxWidth().height(4.dp))
        else if (!reduce) LinearProgressIndicator(color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.fillMaxWidth().height(4.dp))
        else HorizontalDivider(thickness = 3.dp, color = MaterialTheme.colorScheme.outlineVariant)
    }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            measuredPercent?.let { Text("$it%", fontFamily = app.tavernbridge.launcher.ui.theme.V2Mono, style = MaterialTheme.typography.titleLarge) }
        }
        bar()
        if (phase != title) Text(phase, style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (measuredCount.isNotEmpty()) Text(measuredCount, Modifier.weight(1f), fontFamily = app.tavernbridge.launcher.ui.theme.V2Mono,
                style = MaterialTheme.typography.bodySmall)
            WorkElapsedTime(startedAtMillis)
        }
        eta?.let { seconds ->
            val estimate = when { seconds < 60 -> "${seconds}초"; seconds < 3600 -> "${(seconds + 59) / 60}분"; else -> "${seconds / 3600}시간 ${seconds % 3600 / 60}분" }
            Text("이 단계 약 $estimate 남음", fontFamily = app.tavernbridge.launcher.ui.theme.V2Mono,
                style = MaterialTheme.typography.bodySmall)
        }
        WorkProgressLog(progress?.logText.orEmpty(), initiallyExpanded = false) {
            OperationCancelControl(progress, cancellationRequested, startedAtMillis, onCancel, modifier = Modifier.weight(1f))
        }
}
@Composable
internal fun WorkingMiniBar(label: String, progress: app.tavernbridge.launcher.model.WorkProgress?, expand: () -> Unit) {
    val percent = progress?.measuredPercent.takeUnless { progress?.status == "success" }
    Surface(onClick = expand, color = MaterialTheme.colorScheme.surface) {
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = 49.dp).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                percent?.let { Text("$it%", fontFamily = app.tavernbridge.launcher.ui.theme.V2Mono) }
                Icon(Icons.Outlined.KeyboardArrowUp, stringResource(R.string.ui_expand_progress))
            }
            if (percent != null) LinearProgressIndicator(progress = { percent / 100f },
                color = MaterialTheme.colorScheme.secondary, modifier = Modifier.fillMaxWidth().height(3.dp))
            else HorizontalDivider(thickness = 3.dp, color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}
internal fun MainSection.icon(): ImageVector = when (this) {
    MainSection.HOME -> Icons.Outlined.Home
    MainSection.SETUP -> Icons.Outlined.Update
    MainSection.BACKUPS -> Icons.Outlined.FileDownload
    MainSection.LOGS -> Icons.Outlined.Build
    MainSection.SETTINGS -> Icons.Outlined.Settings
}
