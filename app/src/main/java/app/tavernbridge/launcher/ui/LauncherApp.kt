package app.tavernbridge.launcher.ui

import android.app.Activity
import android.content.Intent
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.InstallMobile
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Launch
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text as MaterialText
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.tavernbridge.launcher.BuildConfig
import app.tavernbridge.launcher.LauncherViewModel
import app.tavernbridge.launcher.model.AppTheme
import app.tavernbridge.launcher.model.BackupArchive
import app.tavernbridge.launcher.model.BackupCategory
import app.tavernbridge.launcher.model.BrowserOption
import app.tavernbridge.launcher.model.backupFolderInfo
import app.tavernbridge.launcher.model.DiagnosticItem
import app.tavernbridge.launcher.model.DiagnosticPanel
import app.tavernbridge.launcher.model.DiagnosticReport
import app.tavernbridge.launcher.model.DiagnosticStatus
import app.tavernbridge.launcher.model.EnvironmentStatus
import app.tavernbridge.launcher.model.LauncherUiState
import app.tavernbridge.launcher.model.MainSection
import app.tavernbridge.launcher.model.ManagementPanel
import app.tavernbridge.launcher.model.SettingsPanel
import app.tavernbridge.launcher.model.ServerReadiness
import app.tavernbridge.launcher.model.SillyBranch
import app.tavernbridge.launcher.termux.TermuxContract
import app.tavernbridge.launcher.ui.components.OperationResultCard
import app.tavernbridge.launcher.ui.components.WorkProgressDetails
import app.tavernbridge.launcher.ui.theme.SillyTavernLauncherTheme
import kotlinx.coroutines.delay
import java.util.Locale

private data class Confirmation(
    val title: String,
    val description: String,
    val confirmLabel: String,
    val action: () -> Unit,
)

internal fun formatUiText(text: String, keepWordsTogether: Boolean): String {
    val sentenceWrapped = text.replace(Regex("""(?<=\.)[ \t]+(?=[가-힣])"""), "\n")
    if (!keepWordsTogether) return sentenceWrapped
    return sentenceWrapped.replace(Regex("""(?<=\S)(?=\S)"""), "\u2060")
}

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
        text = formatUiText(
            text = text,
            keepWordsTogether = fontFamily != FontFamily.Monospace &&
                style.fontFamily != FontFamily.Monospace,
        ),
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
        style = style,
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
    LaunchedEffect(state.section, state.diagnosticPanel, state.environment.processRunning, state.isWorking) {
        if (state.section == MainSection.LOGS && state.diagnosticPanel == DiagnosticPanel.SERVER_LOG && !state.isWorking) {
            while (true) {
                viewModel.refreshLogsSilently()
                delay(2_000)
            }
        }
    }
    SillyTavernLauncherTheme(state.theme) {
        LauncherScaffold(state, viewModel)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LauncherScaffold(state: LauncherUiState, viewModel: LauncherViewModel) {
    val snackbarHostState = remember { SnackbarHostState() }
    var confirmation by remember { mutableStateOf<Confirmation?>(null) }
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
                    description = "압축을 한 번만 풀어 구조와 용량을 검사한 뒤 복원합니다. 같은 항목의 현재 데이터는 백업 내용으로 교체되며, 교체 전 현재 상태를 안전 보관합니다. 원본 ZIP은 변경하지 않습니다. 서버를 먼저 종료해 주세요.",
                    confirmLabel = "검사 후 복원",
                    action = { viewModel.importBackup(uri) },
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

    LaunchedEffect(state.message, state.error, state.lastOperationResult) {
        val notice = state.error ?: state.message
        if (notice != null) {
            val retryable = state.error != null && state.lastOperationResult?.canRetry == true
            val result = snackbarHostState.showSnackbar(
                message = notice,
                actionLabel = if (retryable) "다시 시도" else null,
                withDismissAction = retryable,
            )
            viewModel.consumeNotice()
            if (result == SnackbarResult.ActionPerformed) viewModel.retryLastOperation()
        }
    }

    confirmation?.let { pending ->
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text(pending.title) },
            text = { Text(pending.description) },
            confirmButton = {
                Button(onClick = {
                    confirmation = null
                    pending.action()
                }) { Text(pending.confirmLabel) }
            },
            dismissButton = {
                TextButton(onClick = { confirmation = null }) { Text("취소") }
            },
        )
    }

    state.pendingInstallation?.let { candidate ->
        InstallationImportConfirmation(
            installation = candidate,
            destinationExists = state.environment.sillyTavernInstalled,
            onConfirm = viewModel::confirmInstallationImport,
            onDismiss = viewModel::dismissInstallationImport,
        )
    }

    if (browserPickerOpen) {
        val browsers = viewModel.browserOptions()
        val selected = selectedBrowserPackage ?: browsers.firstOrNull()?.packageName
        AlertDialog(
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
                Button(
                    enabled = selected != null,
                    onClick = {
                        selected?.let { viewModel.openBrowser(it, rememberChoice = true) }
                        browserPickerOpen = false
                        selectedBrowserPackage = null
                    },
                ) { Text("항상") }
            },
            dismissButton = {
                Row {
                    TextButton(
                        enabled = selected != null,
                        onClick = {
                            selected?.let { viewModel.openBrowser(it, rememberChoice = false) }
                            browserPickerOpen = false
                            selectedBrowserPackage = null
                        },
                    ) { Text("한 번만") }
                    TextButton(
                        onClick = {
                            browserPickerOpen = false
                            selectedBrowserPackage = null
                        },
                    ) { Text("취소") }
                }
            },
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                modifier = Modifier.statusBarsPadding(),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                title = {
                    Column {
                        Text("실리태번 런처", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "비공식 Termux 런처",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh, enabled = !state.isWorking) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "새로고침")
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar(modifier = Modifier.navigationBarsPadding()) {
                MainSection.entries.forEach { section ->
                    NavigationBarItem(
                        selected = state.section == section,
                        onClick = { viewModel.selectSection(section) },
                        icon = { Icon(section.icon(), contentDescription = null) },
                        label = { Text(section.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            AnimatedContent(targetState = state.section, label = "section") { section ->
                when (section) {
                    MainSection.HOME -> HomeScreen(
                        state = state,
                        onOpen = {
                            val preferred = viewModel.preferredBrowser()
                            if (preferred != null) {
                                viewModel.openBrowser(preferred.packageName)
                            } else {
                                selectedBrowserPackage = viewModel.browserOptions().firstOrNull()?.packageName
                                browserPickerOpen = true
                            }
                        },
                        onStart = viewModel::start,
                        onStop = viewModel::stop,
                        onRestart = viewModel::restart,
                        onSetup = { viewModel.selectSection(MainSection.SETUP) },
                        onOpenTermuxSettings = viewModel::openTermuxAppSettings,
                        onOpenTermux = viewModel::openTermux,
                        onRefresh = viewModel::refresh,
                        onRetry = viewModel::retryLastOperation,
                    )
                    MainSection.SETUP -> if (!state.environmentChecked) {
                        EnvironmentCheckingScreen(
                            state = state,
                            onOpenTermuxSettings = viewModel::openTermuxAppSettings,
                            onOpenTermux = viewModel::openTermux,
                            onRefresh = viewModel::refresh,
                        )
                    } else if (state.environment.sillyTavernInstalled) {
                        ManagementScreen(
                            state = state,
                            onSelectPanel = viewModel::selectManagementPanel,
                            onCheckUpdate = viewModel::checkForUpdates,
                            onAutoBackupChange = viewModel::setAutoBackupBeforeUpdate,
                            onUpdate = {
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
                                    action = viewModel::update,
                                )
                            },
                            onCreateBackup = viewModel::createBackup,
                            onRefreshBackups = viewModel::loadBackups,
                            onOpenBackupFolder = viewModel::openDownloadsFolder,
                            onImportBackup = pickBackup,
                            onPickInstallation = pickInstallation,
                            onInspectInstallation = viewModel::inspectExistingInstallation,
                            storageSetupCommand = viewModel.storageSetupCommand,
                            onOpenTermux = viewModel::openTermux,
                            onStorageCommandCopied = { viewModel.showMessage("명령을 복사하고 Termux를 열었습니다.") },
                            onRestoreBackup = { backup ->
                                confirmation = Confirmation(
                                    title = "이 백업으로 복원할까요?",
                                    description = backupRestorePreview(backup),
                                    confirmLabel = "안전하게 복원",
                                    action = { viewModel.restoreBackup(backup.fileName) },
                                )
                            },
                            onDeleteBackups = { backups ->
                                confirmation = Confirmation(
                                    title = "선택한 백업 ${backups.size}개를 삭제할까요?",
                                    description = "총 ${formatBackupBytes(backups.sumOf { it.sizeBytes })}의 ZIP을 휴대폰 Download 폴더에서 삭제합니다. 삭제한 파일은 앱에서 복구할 수 없습니다.",
                                    confirmLabel = "선택 항목 삭제",
                                    action = { viewModel.deleteBackups(backups.map { it.fileName }) },
                                )
                            },
                            onSwitchBranch = { branch ->
                                val modifiedFiles = state.environment.modifiedFiles
                                confirmation = Confirmation(
                                    title = if (modifiedFiles.isEmpty()) {
                                        "${branch.label}로 변경할까요?"
                                    } else {
                                        "수정된 파일이 있어도 ${branch.label}로 변경할까요?"
                                    },
                                    description = if (modifiedFiles.isEmpty()) {
                                        "실행 중이라면 종료한 뒤 현재 설치를 안전 보관하고 브랜치를 변경합니다."
                                    } else {
                                        "다음 수정 내용을 전체 안전 백업에 보관한 뒤 Git 작업 트리에서는 제거하고 브랜치를 변경합니다. 수정 내용은 자동으로 다시 적용되지 않습니다.\n\n${modifiedFiles.joinToString("\n")}"
                                    },
                                    confirmLabel = if (modifiedFiles.isEmpty()) "브랜치 변경" else "보관 후 변경",
                                    action = { viewModel.switchBranch(branch) },
                                )
                            },
                            onRepair = {
                                confirmation = Confirmation(
                                    title = "설치 패키지를 점검·복구할까요?",
                                    description = "캐릭터·대화·설정은 유지하고 Termux 도구와 node_modules를 다시 구성합니다. 서버는 먼저 종료해 주세요.",
                                    confirmLabel = "점검·복구",
                                    action = viewModel::repair,
                                )
                            },
                            onResetInstallation = {
                                confirmation = Confirmation(
                                    title = "SillyTavern을 완전히 초기화할까요?",
                                    description = "현재 캐릭터·채팅·설정·확장 프로그램을 제거하고 같은 브랜치를 새로 설치합니다. 재설치가 실패하면 현재 설치로 자동 복구하며, 휴대폰 Download의 ZIP 백업은 삭제하지 않습니다. 서버를 먼저 종료해 주세요.",
                                    confirmLabel = "초기화 후 재설치",
                                    action = viewModel::resetInstallation,
                                )
                            },
                        )
                    } else {
                        SetupScreen(
                            state = state,
                            setupCommand = viewModel.setupCommand,
                            onDownloadTermux = viewModel::openTermuxDownload,
                            onOpenTermux = viewModel::openTermux,
                            onRequestPermission = { permissionLauncher.launch(TermuxContract.PERMISSION_RUN_COMMAND) },
                            onOpenPermissionSettings = viewModel::openPermissionSettings,
                            onConnect = viewModel::connectManager,
                            onSelectBranch = viewModel::selectInstallBranch,
                            onInstall = {
                                confirmation = Confirmation(
                                    title = "${state.selectedInstallBranch.label}를 설치할까요?",
                                    description = "기존 ~/SillyTavern 폴더가 있으면 설치를 중단하며 데이터를 덮어쓰지 않습니다.",
                                    confirmLabel = "설치",
                                    action = viewModel::install,
                                )
                            },
                            onSwitchBranch = viewModel::switchBranch,
                            onBackup = viewModel::backup,
                            onPickInstallation = pickInstallation,
                            onInspectInstallation = viewModel::inspectExistingInstallation,
                            onImportBackup = pickBackup,
                            onCopied = { viewModel.showMessage("명령을 복사하고 Termux를 열었습니다.") },
                        )
                    }
                    MainSection.LOGS -> DiagnosticsScreen(
                        state = state,
                        onSelectPanel = viewModel::openDiagnostics,
                        onRunDiagnostics = viewModel::runDiagnostics,
                        onRefresh = viewModel::loadLogs,
                        onLoadPreviousLogs = viewModel::loadPreviousServerLogs,
                        onLoadHistory = viewModel::loadOperationHistory,
                        onOpenTerminalLog = viewModel::openServerLogTerminal,
                        onOpenBatterySettings = viewModel::openBatteryOptimizationSettings,
                        storageSetupCommand = viewModel.storageSetupCommand,
                        onOpenTermux = viewModel::openTermux,
                        onStorageCommandCopied = { viewModel.showMessage("저장공간 연결 명령을 복사하고 Termux를 열었습니다.") },
                        onCopied = { viewModel.showMessage("내용을 클립보드에 복사했습니다.") },
                    )
                    MainSection.SETTINGS -> SettingsScreen(
                        state = state,
                        onSelectPanel = viewModel::selectSettingsPanel,
                        onTheme = viewModel::selectTheme,
                        onSaveServerConnection = viewModel::saveServerConnection,
                        onOpenSillyTavernFolder = viewModel::openSillyTavernFolder,
                        onWakeLockChange = viewModel::setWakeLockEnabled,
                        onOpenDeviceBatterySettings = viewModel::openDeviceBatterySettings,
                        onOpenTermuxAppSettings = viewModel::openTermuxAppSettings,
                        onOpenLauncherAppSettings = viewModel::openLauncherAppSettings,
                        preferredBrowser = viewModel.preferredBrowser(),
                        onResetBrowser = viewModel::clearPreferredBrowser,
                    )
                }
            }

            if (state.isWorking) {
                WorkingOverlay(
                    label = state.workingLabel,
                    progress = state.workProgress,
                    onCancel = viewModel::cancelCurrentOperation,
                )
            }
            if (state.fileBrowserOpen) {
                TavernFileManagerDialog(
                    state = state,
                    onNavigate = viewModel::loadSillyTavernFolder,
                    onOpenFile = viewModel::openSillyTavernFile,
                    onOpenDirectory = viewModel::openSillyTavernDirectory,
                    onEdit = viewModel::readSillyTavernTextFile,
                    onSave = viewModel::saveSillyTavernTextFile,
                    onCloseEditor = viewModel::closeTextEditor,
                    onCreateFolder = viewModel::createSillyTavernFolder,
                    onRename = viewModel::renameSillyTavernEntry,
                    onTrash = viewModel::trashSillyTavernEntry,
                    onUndoTrash = viewModel::undoLastTrashedEntry,
                    onImport = {
                        runCatching { fileImportPicker.launch(arrayOf("*/*")) }
                            .onFailure { viewModel.showMessage("파일 선택 화면을 열 수 없습니다.") }
                    },
                    onClose = viewModel::closeSillyTavernFolder,
                )
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
    onRestart: () -> Unit,
    onSetup: () -> Unit,
    onOpenTermuxSettings: () -> Unit,
    onOpenTermux: () -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
) {
    val environment = state.environment
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            if (!state.environmentChecked) {
                EnvironmentCheckingCard(state, onOpenTermuxSettings, onOpenTermux, onRefresh)
            } else if (!environment.sillyTavernInstalled) {
                EmptyHomeCard(onSetup)
            } else {
                ServerHeroCard(environment, onOpen, onStart, onStop)
            }
        }
        state.lastOperationResult?.let { summary ->
            item { OperationResultCard(summary, onRetry) }
        }
        if (environment.sillyTavernInstalled) {
            item {
                QuickAction(
                    modifier = Modifier.fillMaxWidth(),
                    icon = Icons.Outlined.RestartAlt,
                    label = "서버 재시작",
                    onClick = onRestart,
                )
            }
            item {
                ServerSessionCard(environment)
            }
            item {
                Text(
                    "서버는 이 기기의 127.0.0.1에서만 열립니다. 외부 네트워크에는 노출되지 않습니다.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun EnvironmentCheckingScreen(
    state: LauncherUiState,
    onOpenTermuxSettings: () -> Unit,
    onOpenTermux: () -> Unit,
    onRefresh: () -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EnvironmentCheckingCard(state, onOpenTermuxSettings, onOpenTermux, onRefresh)
    }
}

@Composable
private fun EnvironmentCheckingCard(
    state: LauncherUiState,
    onOpenTermuxSettings: () -> Unit,
    onOpenTermux: () -> Unit,
    onRefresh: () -> Unit,
) {
    val checking = state.isWorking
    val wakeBlocked = state.termuxWakeBlocked
    Card(modifier = Modifier.padding(20.dp)) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (checking) {
                    DirectSpinner(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
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
                        else -> "설치 상태를 확인하지 못했어요"
                    },
                    style = MaterialTheme.typography.titleMedium,
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
            if (!checking && wakeBlocked) {
                Button(onClick = onOpenTermuxSettings, modifier = Modifier.fillMaxWidth()) {
                    Text("Termux 배터리 설정 열기")
                }
                OutlinedButton(onClick = onOpenTermux, modifier = Modifier.fillMaxWidth()) {
                    Text("Termux 한 번 열기")
                }
            }
            if (!checking) {
                TextButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                    Text("설치 상태 다시 확인")
                }
            }
        }
    }
}

@Composable
private fun EmptyHomeCard(onSetup: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(28.dp),
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                Icons.Outlined.InstallMobile,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text("설치를 준비해볼까요?", style = MaterialTheme.typography.headlineMedium)
            Text(
                "기존 설치가 있다면 그대로 연결하고, 없다면 Release 또는 Staging을 선택해 설치할 수 있어요.",
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .76f),
            )
            Button(onClick = onSetup, modifier = Modifier.fillMaxWidth()) {
                Text("설치 상태 확인")
            }
        }
    }
}

@Composable
private fun ServerHeroCard(
    environment: EnvironmentStatus,
    onOpen: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val readiness = environment.serverReadiness
    val online = readiness == ServerReadiness.READY
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (online) 0.dp else 1.dp),
        shape = RoundedCornerShape(28.dp),
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(11.dp)
                        .clip(CircleShape)
                        .background(if (online) Color(0xFF9AE6B4) else MaterialTheme.colorScheme.outline),
                )
                Spacer(Modifier.width(9.dp))
                Text(
                    when (readiness) {
                        ServerReadiness.READY -> "서버 정상 응답"
                        ServerReadiness.STARTING -> "서버 시작 중"
                        ServerReadiness.UNRESPONSIVE -> "서버 응답 없음"
                        ServerReadiness.PORT_CONFLICT -> "다른 프로세스가 포트 사용 중"
                        ServerReadiness.OFFLINE -> "서버 꺼짐"
                    },
                    color = if (online) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Column {
                Text(
                    when (readiness) {
                        ServerReadiness.READY -> "바로 대화를 시작하세요"
                        ServerReadiness.PORT_CONFLICT -> "포트 상태를 진단해 주세요"
                        ServerReadiness.UNRESPONSIVE -> "서버 로그를 확인해 주세요"
                        else -> "SillyTavern을 시작하세요"
                    },
                    color = if (online) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.headlineMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "127.0.0.1:${environment.port}",
                    color = if (online) MaterialTheme.colorScheme.onPrimary.copy(alpha = .68f)
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (online) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onOpen,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    ) {
                        Icon(Icons.Outlined.Launch, null)
                        Spacer(Modifier.width(8.dp))
                        Text("브라우저로 열기")
                    }
                    OutlinedButton(
                        onClick = onStop,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onPrimary.copy(alpha = .72f)),
                    ) {
                        Icon(
                            Icons.Outlined.Stop,
                            contentDescription = "종료",
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            } else {
                Button(
                    onClick = onStart,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = readiness != ServerReadiness.PORT_CONFLICT && readiness != ServerReadiness.STARTING,
                ) {
                    Icon(Icons.Outlined.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text("SillyTavern 시작")
                }
            }
        }
    }
}

@Composable
private fun QuickAction(modifier: Modifier, icon: ImageVector, label: String, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        shape = RoundedCornerShape(16.dp),
    ) {
        Icon(icon, null)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
private fun ServerSessionCard(environment: EnvironmentStatus) {
    var now by remember(environment.sessionStartedEpoch, environment.processRunning) {
        mutableStateOf(System.currentTimeMillis())
    }
    LaunchedEffect(environment.sessionStartedEpoch, environment.processRunning) {
        while (environment.processRunning) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val elapsedSeconds = if (environment.processRunning && environment.sessionStartedEpoch > 0) {
        ((now / 1_000) - environment.sessionStartedEpoch).coerceAtLeast(0)
    } else 0
    val elapsed = when {
        !environment.processRunning -> "실행 중인 세션 없음"
        environment.sessionStartedEpoch <= 0 -> "확인 중"
        elapsedSeconds >= 86_400 -> "%d일 %02d:%02d:%02d".format(
            elapsedSeconds / 86_400,
            elapsedSeconds % 86_400 / 3_600,
            elapsedSeconds % 3_600 / 60,
            elapsedSeconds % 60,
        )
        else -> "%02d:%02d:%02d".format(
            elapsedSeconds / 3_600,
            elapsedSeconds % 3_600 / 60,
            elapsedSeconds % 60,
        )
    }
    val recentStatus = when (environment.lastServerAction) {
        "start" -> "서버 시작"
        "restart" -> "서버 재시작"
        "detected_running" -> "실행 중인 서버 인식"
        "stop" -> "정상 종료"
        "unexpected_stop" -> "예기치 않게 종료됨"
        "start_failed" -> "시작 실패"
        else -> "기록 없음"
    }
    Card(shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("현재 서버 세션", style = MaterialTheme.typography.titleLarge)
            InfoRow("세션", if (environment.processRunning) "실행 중" else "종료됨")
            InfoRow("실행 시간", elapsed, monospace = environment.processRunning)
            InfoRow("시작 시각", environment.sessionStartedAt.ifBlank { "기록 없음" })
            HorizontalDivider()
            InfoRow("최근 상태", recentStatus)
            InfoRow("상태 시각", environment.lastServerActionAt.ifBlank { "기록 없음" })
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, monospace: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            fontWeight = FontWeight.SemiBold,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
        )
    }
}

@Composable
private fun SamsungBatteryStep(number: String, title: String, description: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surface.copy(alpha = .62f),
                RoundedCornerShape(14.dp),
            )
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
            Text(
                number,
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.onPrimary,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(
                description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun DirectSpinner(
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 2.dp,
) {
    var rotation by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(16)
            rotation = (rotation + 7f) % 360f
        }
    }
    CircularProgressIndicator(
        progress = { .72f },
        modifier = modifier.graphicsLayer { rotationZ = rotation },
        strokeWidth = strokeWidth,
    )
}

@Composable
private fun BackupCreatorCard(
    serverRunning: Boolean,
    availableFolders: List<app.tavernbridge.launcher.model.CustomBackupFolder>,
    onCreateBackup: (Set<BackupCategory>, Boolean, Set<String>) -> Unit,
) {
    var selectedKeys by rememberSaveable { mutableStateOf(listOf(BackupCategory.USER_DATA.key)) }
    var selectedFolders by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var includeSecrets by rememberSaveable { mutableStateOf(false) }
    val selected = selectedKeys.mapNotNull(BackupCategory::fromKey).toSet()
    val canIncludeSecrets = BackupCategory.USER_DATA in selected || BackupCategory.FULL in selected

    Card(shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("새 백업 만들기", style = MaterialTheme.typography.titleLarge)
            Text(
                "필요한 항목만 골라 검증된 ZIP으로 Download 폴더에 저장합니다.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BackupCategory.entries.forEach { category ->
                val checked = category in selected
                Row(verticalAlignment = Alignment.Top) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = { enabled ->
                            selectedKeys = when {
                                category == BackupCategory.FULL && enabled -> listOf(category.key)
                                category == BackupCategory.FULL -> selectedKeys - category.key
                                enabled -> (selectedKeys - BackupCategory.FULL.key + category.key).distinct()
                                else -> selectedKeys - category.key
                            }
                            if (category == BackupCategory.CUSTOM && !enabled) selectedFolders = emptyList()
                        },
                    )
                    Column(Modifier.padding(top = 10.dp)) {
                        Text(category.label, fontWeight = FontWeight.SemiBold)
                        Text(
                            category.description,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            if (BackupCategory.CUSTOM in selected) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("~/SillyTavern/data/default-user", style = MaterialTheme.typography.titleSmall)
                        if (availableFolders.isEmpty()) {
                            Text(
                                "선택할 사용자 폴더가 없습니다.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        availableFolders.forEach { folder ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = folder.key in selectedFolders,
                                    onCheckedChange = { enabled ->
                                        selectedFolders = if (enabled) {
                                            (selectedFolders + folder.key).distinct()
                                        } else {
                                            selectedFolders - folder.key
                                        }
                                    },
                                )
                                Column(Modifier.padding(vertical = 6.dp)) {
                                    Text(folder.label, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        backupFolderInfo(folder.key).description,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            FilterChip(
                selected = includeSecrets && canIncludeSecrets,
                enabled = canIncludeSecrets,
                onClick = { includeSecrets = !includeSecrets },
                label = { Text("API 키 등 비밀 설정 포함") },
                leadingIcon = { Icon(Icons.Outlined.WarningAmber, null, Modifier.size(18.dp)) },
            )
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(
                    if (includeSecrets && canIncludeSecrets) {
                        "주의: secrets.json을 포함합니다. ZIP은 암호화되지 않으므로 공유하거나 클라우드에 올리지 마세요."
                    } else {
                        "secrets.json은 기본적으로 제외됩니다. ZIP 자체는 암호화되지 않습니다."
                    },
                    modifier = Modifier.padding(14.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(
                onClick = {
                    onCreateBackup(selected, includeSecrets && canIncludeSecrets, selectedFolders.toSet())
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !serverRunning && selected.isNotEmpty() &&
                    (BackupCategory.CUSTOM !in selected || selectedFolders.isNotEmpty()),
            ) {
                Icon(Icons.Outlined.CloudDownload, null)
                Spacer(Modifier.width(8.dp))
                Text(if (serverRunning) "서버 종료 후 백업" else "선택한 항목 백업")
            }
            Text(
                "백업 중 파일이 바뀌지 않도록 서버를 종료한 상태에서만 만들 수 있습니다.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun BackupArchiveCard(
    backup: BackupArchive,
    serverRunning: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    onSelectedChange: (Boolean) -> Unit,
    onRestore: () -> Unit,
) {
    val itemLabels = backup.categories.map { it.label }.toMutableList().apply {
        if (backup.customFolders.isNotEmpty()) add("직접 선택 ${backup.customFolders.size}개")
    }
    Card(shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (selectionMode) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = selected, onCheckedChange = onSelectedChange)
                    Text(if (selected) "삭제 대상으로 선택됨" else "삭제할 백업 선택")
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(backup.createdAt.ifBlank { "날짜 알 수 없음" }, fontWeight = FontWeight.SemiBold)
                Text(formatBackupBytes(backup.sizeBytes), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                itemLabels.distinct().joinToString(" · ").ifBlank { "포함 항목 알 수 없음" },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (backup.sillyTavernVersion.isNotBlank()) {
                Text("SillyTavern ${backup.sillyTavernVersion}", style = MaterialTheme.typography.bodySmall)
            }
            if (backup.expandedBytes > 0) {
                Text(
                    "해제 크기 ${formatBackupBytes(backup.expandedBytes)} · 항목 ${backup.entryCount}개",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (backup.integrity.isNotBlank()) {
                Text(
                    "무결성 검사 ${if (backup.integrity == "zip-crc32") "ZIP CRC" else backup.integrity}",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (!backup.restoreSpaceReady) {
                Text(
                    "복원 공간 부족 · ${formatBackupBytes(backup.requiredRestoreBytes)} 필요",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (backup.includesSecrets) {
                Text(
                    "비밀 설정 포함 · 외부 공유 주의",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            OutlinedButton(
                onClick = onRestore,
                enabled = !serverRunning && backup.restoreSpaceReady,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.RestartAlt, null)
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        serverRunning -> "서버 종료 후 복원"
                        !backup.restoreSpaceReady -> "저장공간 확보 후 복원"
                        else -> "복원 내용 미리보기"
                    },
                )
            }
        }
    }
}

@Composable
private fun BackupStorageSetupCard(
    command: String,
    onOpenTermux: () -> Unit,
    onRefresh: () -> Unit,
    onCopied: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
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
                    "1. 아래 명령을 복사해 Termux에 붙여넣고 Enter\n2. Android에 표시되는 파일·사진 및 동영상 등 저장공간 접근 요청을 허용\n3. 런처로 돌아와 ‘연결 확인’을 누르기",
                    modifier = Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp)) {
                Text(command, Modifier.padding(12.dp), fontFamily = FontFamily.Monospace)
            }
            Button(
                onClick = {
                    clipboard.setText(AnnotatedString(command))
                    onCopied()
                    onOpenTermux()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.ContentCopy, null)
                Spacer(Modifier.width(8.dp))
                Text("복사하고 Termux 열기")
            }
            TextButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Refresh, null)
                Spacer(Modifier.width(8.dp))
                Text("연결 확인")
            }
        }
    }
}

private fun backupRestorePreview(backup: BackupArchive): String = buildString {
    appendLine("생성: ${backup.createdAt.ifBlank { "알 수 없음" }}")
    if (backup.sillyTavernVersion.isNotBlank()) appendLine("SillyTavern: ${backup.sillyTavernVersion}")
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
    append("현재 데이터를 임시 보관한 뒤 교체하며, 실패하면 기존 상태로 자동 복구합니다.")
}.trim()

private fun formatBackupBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(Locale.KOREA, "%.1f GB", bytes / 1073741824.0)
    bytes >= 1024L * 1024 -> String.format(Locale.KOREA, "%.1f MB", bytes / 1048576.0)
    bytes >= 1024L -> String.format(Locale.KOREA, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

@Composable
private fun ManagementScreen(
    state: LauncherUiState,
    onSelectPanel: (ManagementPanel) -> Unit,
    onCheckUpdate: () -> Unit,
    onAutoBackupChange: (Boolean) -> Unit,
    onUpdate: () -> Unit,
    onCreateBackup: (Set<BackupCategory>, Boolean, Set<String>) -> Unit,
    onRefreshBackups: () -> Unit,
    onOpenBackupFolder: () -> Unit,
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
) {
    val environment = state.environment
    val updateReport = state.updatePreflight
    val updateReady = updateReport != null && updateReport.updateAvailable &&
        updateReport.nodeCompatible && updateReport.spaceReady &&
        (!state.autoBackupBeforeUpdate || updateReport.backupStorageReady)
    val selectedPanel = state.managementPanel
    var backupDeleteMode by rememberSaveable { mutableStateOf(false) }
    var selectedBackupFiles by rememberSaveable { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(selectedPanel, state.environment.managerConnected) {
        if (selectedPanel == ManagementPanel.BACKUP && state.environment.managerConnected) onRefreshBackups()
    }
    LaunchedEffect(state.backups.map { it.fileName }) {
        selectedBackupFiles = selectedBackupFiles.filter { selected -> state.backups.any { it.fileName == selected } }
        if (backupDeleteMode && selectedBackupFiles.isEmpty()) backupDeleteMode = false
    }
    Column(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("관리", style = MaterialTheme.typography.displaySmall)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ManagementPanel.entries.forEach { panel ->
                    FilterChip(
                        selected = selectedPanel == panel,
                        onClick = { onSelectPanel(panel) },
                        label = { Text(panel.label) },
                    )
                }
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(20.dp, 0.dp, 20.dp, 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (selectedPanel == ManagementPanel.INSTALL) {
        item {
            ExistingInstallationImportCard(
                enabled = !state.isWorking && !environment.processRunning && !environment.operationActive,
                onPickFolder = onPickInstallation,
                onInspectPath = onInspectInstallation,
            )
        }
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
            Card(shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("설치 유지관리", style = MaterialTheme.typography.titleLarge)
                    Text("브랜치 변경", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SillyBranch.entries.forEach { branch ->
                            FilterChip(
                                selected = environment.branch == branch,
                                enabled = environment.branch != branch,
                                onClick = { onSwitchBranch(branch) },
                                label = { Text(branch.label) },
                            )
                        }
                    }
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
                    HorizontalDivider()
                    Text("SillyTavern 초기화", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "현재 데이터를 비우고 같은 브랜치를 새로 설치합니다. Download 폴더의 백업 ZIP은 유지됩니다.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(
                        onClick = onResetInstallation,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !environment.processRunning,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) {
                        Icon(Icons.Outlined.RestartAlt, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (environment.processRunning) "서버 종료 후 초기화" else "초기화 후 재설치")
                    }
                }
            }
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
                                            fontFamily = FontFamily.Monospace,
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
                    }
                }
            }
        }
            }

            if (selectedPanel == ManagementPanel.BACKUP) {
        if (state.backupStorageReady == false) {
            item {
                BackupStorageSetupCard(
                    command = storageSetupCommand,
                    onOpenTermux = onOpenTermux,
                    onRefresh = onRefreshBackups,
                    onCopied = onStorageCommandCopied,
                )
            }
        } else if (state.backupStorageReady == true) {
            item {
                BackupCreatorCard(
                    serverRunning = environment.processRunning,
                    availableFolders = state.backupFolders,
                    onCreateBackup = onCreateBackup,
                )
            }
        } else {
            item {
                Card(shape = RoundedCornerShape(22.dp)) {
                    Row(
                        modifier = Modifier.padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DirectSpinner(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("백업 저장공간 연결 상태를 확인하고 있습니다.")
                    }
                }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("저장된 백업", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "내 파일 → Download",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row {
                    IconButton(onClick = onOpenBackupFolder) {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = "Download 폴더 열기")
                    }
                    IconButton(onClick = onRefreshBackups) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "백업 목록 새로고침")
                    }
                }
            }
        }
        if (state.backups.isNotEmpty()) {
            item {
                if (backupDeleteMode) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    selectedBackupFiles = if (selectedBackupFiles.size == state.backups.size) {
                                        emptyList()
                                    } else {
                                        state.backups.map { it.fileName }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text(if (selectedBackupFiles.size == state.backups.size) "전체 해제" else "전체 선택") }
                            TextButton(
                                onClick = {
                                    backupDeleteMode = false
                                    selectedBackupFiles = emptyList()
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text("취소") }
                        }
                        Button(
                            onClick = {
                                val selected = state.backups.filter { it.fileName in selectedBackupFiles }
                                if (selected.isNotEmpty()) onDeleteBackups(selected)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = selectedBackupFiles.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ),
                        ) { Text("선택한 백업 ${selectedBackupFiles.size}개 삭제") }
                    }
                } else {
                    OutlinedButton(
                        onClick = { backupDeleteMode = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("백업 선택 삭제") }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onImportBackup,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !environment.processRunning && state.backupStorageReady == true,
                ) {
                    Icon(Icons.Outlined.CloudDownload, null)
                    Spacer(Modifier.width(6.dp))
                    Text("ZIP에서 바로 복원")
                }
                if (environment.processRunning) {
                    Text(
                        "백업 ZIP을 가져오려면 먼저 서버를 종료해 주세요.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text(
                        "런처 백업과 일반 SillyTavern ZIP을 검사한 뒤 바로 복원합니다. 변환용 ZIP을 새로 만들지 않으며, secrets.json이 포함되어 있으면 비밀 설정도 복원될 수 있습니다.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        if (!state.backupsLoaded && state.backupStorageReady == true) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DirectSpinner(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Download 폴더의 백업 파일을 검색하고 있습니다.")
                }
            }
        }
        if (state.backupsLoaded && state.backups.isEmpty()) {
            item {
                Text(
                    "아직 런처에서 만든 ZIP 백업이 없습니다.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(state.backups, key = { it.fileName }) { backup ->
            BackupArchiveCard(
                backup = backup,
                serverRunning = environment.processRunning,
                selectionMode = backupDeleteMode,
                selected = backup.fileName in selectedBackupFiles,
                onSelectedChange = { selected ->
                    selectedBackupFiles = if (selected) {
                        (selectedBackupFiles + backup.fileName).distinct()
                    } else {
                        selectedBackupFiles - backup.fileName
                    }
                },
                onRestore = { onRestoreBackup(backup) },
            )
        }
            }
        }
    }
}

@Composable
private fun SetupScreen(
    state: LauncherUiState,
    setupCommand: String,
    onDownloadTermux: () -> Unit,
    onOpenTermux: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenPermissionSettings: () -> Unit,
    onConnect: () -> Unit,
    onSelectBranch: (SillyBranch) -> Unit,
    onInstall: () -> Unit,
    onSwitchBranch: (SillyBranch) -> Unit,
    onBackup: () -> Unit,
    onCopied: () -> Unit,
    onPickInstallation: () -> Unit,
    onInspectInstallation: (String) -> Unit,
    onImportBackup: () -> Unit,
) {
    val env = state.environment
    val clipboard = LocalClipboardManager.current
    var currentStep by rememberSaveable {
        mutableStateOf(
            when {
                env.managerConnected -> 5
                env.commandPermissionGranted -> 4
                env.termuxInstalled -> 2
                else -> 1
            },
        )
    }

    LaunchedEffect(env.termuxInstalled, env.commandPermissionGranted, env.managerConnected) {
        if (!env.termuxInstalled) currentStep = 1
        if (currentStep == 1 && env.termuxInstalled) currentStep = 2
        if (currentStep >= 4 && !env.commandPermissionGranted) currentStep = 3
        if (currentStep == 3 && env.commandPermissionGranted) currentStep = 4
        if (env.managerConnected) currentStep = 5
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("처음 설정", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "$currentStep / 5 · 한 단계씩 진행해요",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (currentStep > 1 && !state.isWorking) {
                TextButton(onClick = { currentStep -= 1 }) { Text("이전") }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(5) { index ->
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .height(5.dp),
                    color = if (index < currentStep) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant,
                    shape = CircleShape,
                ) {}
            }
        }

        AnimatedContent(
            targetState = currentStep,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            label = "setup-step",
        ) { step ->
            when (step) {
                1 -> WizardStepCard(
                    icon = Icons.Outlined.Terminal,
                    title = "Termux를 준비할게요",
                    description = if (env.termuxInstalled) {
                        "Termux가 확인됐어요. 기존 설치와 파일은 그대로 유지됩니다."
                    } else {
                        "F-Droid판 Termux를 설치하고 한 번 실행해 주세요."
                    },
                ) {
                    Button(
                        onClick = { if (env.termuxInstalled) currentStep = 2 else onDownloadTermux() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (env.termuxInstalled) "다음" else "F-Droid에서 받기") }
                    if (env.termuxInstalled) {
                        OutlinedButton(onClick = onOpenTermux, modifier = Modifier.fillMaxWidth()) {
                            Text("Termux 열어보기")
                        }
                    }
                }

                2 -> WizardStepCard(
                    icon = Icons.Outlined.ContentCopy,
                    title = "외부 명령을 허용해 주세요",
                    description = "아래 명령을 복사한 뒤 Termux에 붙여넣고 Enter를 누르세요. 기존 SillyTavern에는 영향을 주지 않습니다.",
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text(
                            setupCommand,
                            modifier = Modifier.padding(14.dp),
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                        )
                    }
                    Button(
                        onClick = {
                            clipboard.setText(AnnotatedString(setupCommand))
                            onCopied()
                            onOpenTermux()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.ContentCopy, null)
                        Spacer(Modifier.width(8.dp))
                        Text("복사하고 Termux 열기")
                    }
                    TextButton(onClick = { currentStep = 3 }, modifier = Modifier.fillMaxWidth()) {
                        Text("붙여넣고 실행했어요")
                    }
                }

                3 -> WizardStepCard(
                    icon = Icons.Outlined.CheckCircle,
                    title = "실행 권한을 확인할게요",
                    description = if (env.commandPermissionGranted) {
                        "권한이 정상적으로 허용됐어요."
                    } else {
                        "Android의 추가 권한에서 ‘Termux 환경에서 명령 실행’을 허용해 주세요."
                    },
                ) {
                    Button(
                        onClick = { if (env.commandPermissionGranted) currentStep = 4 else onRequestPermission() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (env.commandPermissionGranted) "다음" else "권한 허용") }
                    if (!env.commandPermissionGranted) {
                        OutlinedButton(onClick = onOpenPermissionSettings, modifier = Modifier.fillMaxWidth()) {
                            Text("앱 권한 설정 열기")
                        }
                    }
                }

                4 -> WizardStepCard(
                    icon = Icons.Outlined.InstallMobile,
                    title = "런처를 연결할게요",
                    description = "관리 스크립트를 Termux 안에 설치하고 기존 ~/SillyTavern을 안전하게 확인합니다.",
                ) {
                    Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
                        Text("연결 시작")
                    }
                    Text(
                        "삭제나 덮어쓰기는 하지 않아요.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                else -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    if (env.sillyTavernInstalled) {
                        ExistingInstallCard(env, onSwitchBranch, onBackup)
                    } else {
                        ExistingInstallationImportCard(
                            enabled = !state.isWorking && env.managerConnected && !env.processRunning,
                            onPickFolder = onPickInstallation,
                            onInspectPath = onInspectInstallation,
                        )
                        InstallBranchCard(state.selectedInstallBranch, onSelectBranch, onInstall)
                        OutlinedButton(onClick = onImportBackup, enabled = !state.isWorking && env.managerConnected,
                            modifier = Modifier.fillMaxWidth()) { Text("전체 설치 ZIP에서 복원") }
                    }
                }
            }
        }
    }
}

@Composable
private fun WizardStepCard(
    icon: ImageVector,
    title: String,
    description: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(26.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}

@Composable
private fun InstallBranchCard(
    selected: SillyBranch,
    onSelect: (SillyBranch) -> Unit,
    onInstall: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("SillyTavern 설치", style = MaterialTheme.typography.titleLarge)
            Text("설치할 브랜치를 선택하세요.", color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .72f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SillyBranch.entries.forEach { branch ->
                    FilterChip(
                        selected = selected == branch,
                        onClick = { onSelect(branch) },
                        label = { Text(branch.label) },
                    )
                }
            }
            Text(selected.description, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onInstall, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Download, null)
                Spacer(Modifier.width(8.dp))
                Text("${selected.label} 설치")
            }
        }
    }
}

@Composable
private fun ExistingInstallCard(
    environment: EnvironmentStatus,
    onSwitchBranch: (SillyBranch) -> Unit,
    onBackup: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(Icons.Outlined.CheckCircle, null, modifier = Modifier.size(32.dp))
            Text("기존 설치 연결됨", style = MaterialTheme.typography.titleLarge)
            Text(
                "~/SillyTavern의 데이터를 그대로 사용합니다. 현재 ${environment.branch?.label ?: "알 수 없는"} 브랜치입니다.",
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .76f),
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .12f))
            Text("브랜치 변경", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SillyBranch.entries.forEach { branch ->
                    FilterChip(
                        selected = environment.branch == branch,
                        enabled = environment.branch != branch,
                        onClick = { onSwitchBranch(branch) },
                        label = { Text(branch.label) },
                    )
                }
            }
            Button(
                onClick = onBackup,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    contentColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Icon(Icons.Outlined.CloudDownload, null)
                Spacer(Modifier.width(8.dp))
                Text("전체 백업 만들기 (.zip)")
            }
            Text(
                "캐릭터·대화·설정 등 ~/SillyTavern을 Android 다운로드 폴더에 압축합니다. 다시 받을 수 있는 node_modules는 제외합니다.",
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .72f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun DiagnosticsScreen(
    state: LauncherUiState,
    onSelectPanel: (DiagnosticPanel) -> Unit,
    onRunDiagnostics: () -> Unit,
    onRefresh: () -> Unit,
    onLoadPreviousLogs: () -> Unit,
    onLoadHistory: () -> Unit,
    onOpenTerminalLog: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    storageSetupCommand: String,
    onOpenTermux: () -> Unit,
    onStorageCommandCopied: () -> Unit,
    onCopied: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val logScrollState = rememberScrollState()
    val visibleLog = when (state.diagnosticPanel) {
        DiagnosticPanel.SERVER_LOG -> state.logs
        DiagnosticPanel.PREVIOUS_SERVER_LOG -> state.previousServerLogs
        DiagnosticPanel.WORK_HISTORY -> state.operationHistory
        DiagnosticPanel.OVERVIEW -> ""
    }
    LaunchedEffect(visibleLog) {
        delay(50)
        logScrollState.scrollTo(logScrollState.maxValue)
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("진단", style = MaterialTheme.typography.displaySmall)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DiagnosticPanel.entries.forEach { panel ->
                FilterChip(
                    selected = state.diagnosticPanel == panel,
                    onClick = { onSelectPanel(panel) },
                    label = { Text(panel.label) },
                )
            }
        }

        if (state.diagnosticPanel == DiagnosticPanel.OVERVIEW) {
            DiagnosticOverview(
                report = state.diagnosticReport,
                onRun = onRunDiagnostics,
                onOpenBatterySettings = onOpenBatterySettings,
                onStorageSetup = {
                    clipboard.setText(AnnotatedString(storageSetupCommand))
                    onStorageCommandCopied()
                    onOpenTermux()
                },
                onCopied = onCopied,
                modifier = Modifier.weight(1f),
            )
        } else {
            val title = when (state.diagnosticPanel) {
                DiagnosticPanel.SERVER_LOG -> "SillyTavern 서버 로그"
                DiagnosticPanel.PREVIOUS_SERVER_LOG -> "이전 서버 세션 로그"
                DiagnosticPanel.WORK_HISTORY -> "런처 작업 기록"
                DiagnosticPanel.OVERVIEW -> ""
            }
            val description = when (state.diagnosticPanel) {
                DiagnosticPanel.SERVER_LOG -> "모델/API 오류와 서버 출력 · 2초마다 갱신"
                DiagnosticPanel.PREVIOUS_SERVER_LOG -> "새 서버 시작 직전에 보관한 직전 세션의 마지막 800줄"
                DiagnosticPanel.WORK_HISTORY -> "설치·복구·업데이트 단계 최근 300줄"
                DiagnosticPanel.OVERVIEW -> ""
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    Text(
                        description,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                IconButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(visibleLog))
                        onCopied()
                    },
                    enabled = visibleLog.isNotBlank(),
                ) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = "로그 전체 복사")
                }
                IconButton(
                    onClick = {
                        when (state.diagnosticPanel) {
                            DiagnosticPanel.SERVER_LOG -> onRefresh()
                            DiagnosticPanel.PREVIOUS_SERVER_LOG -> onLoadPreviousLogs()
                            DiagnosticPanel.WORK_HISTORY -> onLoadHistory()
                            DiagnosticPanel.OVERVIEW -> Unit
                        }
                    },
                    enabled = state.environment.managerConnected,
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "로그 새로고침")
                }
            }
            if (state.diagnosticPanel == DiagnosticPanel.SERVER_LOG) {
                FilledTonalButton(
                    onClick = onOpenTerminalLog,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.environment.processRunning,
                ) {
                    Icon(Icons.Outlined.Terminal, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Termux에서 실시간 로그 보기")
                }
            }
            Surface(
                modifier = Modifier.fillMaxWidth().weight(1f),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(20.dp),
            ) {
                SelectionContainer {
                    Text(
                        visibleLog,
                        modifier = Modifier.fillMaxSize().verticalScroll(logScrollState).padding(16.dp),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun DiagnosticOverview(
    report: DiagnosticReport?,
    onRun: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onStorageSetup: () -> Unit,
    onCopied: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                shape = RoundedCornerShape(24.dp),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.HealthAndSafety, null, modifier = Modifier.size(30.dp))
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("전체 환경 진단", style = MaterialTheme.typography.titleLarge)
                            Text(
                                report?.let { "정상 ${it.okCount} · 확인 필요 ${it.warningCount} · 오류 ${it.errorCount}" }
                                    ?: "기기와 Termux, SillyTavern 상태를 확인합니다.",
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .76f),
                            )
                        }
                    }
                    if (report != null) {
                        Text(
                            "마지막 검사 ${report.checkedAt}",
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .68f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Button(onClick = onRun, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.Refresh, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (report == null) "전체 진단 실행" else "다시 진단")
                    }
                }
            }
        }
        if (report == null) {
            item {
                Card(shape = RoundedCornerShape(20.dp)) {
                    Text(
                        "진단을 실행하면 저장 공간, 네트워크, Git·Node.js, 설치 폴더, 포트, 프로세스와 배터리 설정을 한 번에 확인합니다.",
                        modifier = Modifier.padding(20.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            val sorted = report.items.sortedBy { item ->
                when (item.status) {
                    DiagnosticStatus.ERROR -> 0
                    DiagnosticStatus.WARNING -> 1
                    DiagnosticStatus.INFO -> 2
                    DiagnosticStatus.OK -> 3
                }
            }
            items(sorted.size) { index ->
                val item = sorted[index]
                DiagnosticResultCard(
                    item = item,
                    onAction = when {
                        item.id == "battery" && item.status != DiagnosticStatus.OK -> onOpenBatterySettings
                        item.id == "download_storage" && item.value == "아직 사용하지 않음" -> onStorageSetup
                        else -> null
                    },
                    actionLabel = if (item.id == "download_storage") "명령 복사·Termux 열기" else "설정 열기",
                )
            }
            item {
                OutlinedButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(report.copyText()))
                        onCopied()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.ContentCopy, null)
                    Spacer(Modifier.width(8.dp))
                    Text("진단 내용 복사")
                }
            }
        }
    }
}

@Composable
private fun DiagnosticResultCard(
    item: DiagnosticItem,
    onAction: (() -> Unit)?,
    actionLabel: String,
) {
    val (icon, container, content) = when (item.status) {
        DiagnosticStatus.OK -> Triple(Icons.Outlined.CheckCircle, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        DiagnosticStatus.WARNING -> Triple(Icons.Outlined.WarningAmber, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
        DiagnosticStatus.ERROR -> Triple(Icons.Outlined.ErrorOutline, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        DiagnosticStatus.INFO -> Triple(Icons.Outlined.Info, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Card(colors = CardDefaults.cardColors(containerColor = container), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = content, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.title, color = content, fontWeight = FontWeight.SemiBold)
                    Text(item.value, color = content.copy(alpha = .82f), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (item.detail.isNotBlank()) {
                SelectionContainer {
                    Text(
                        item.detail,
                        color = content.copy(alpha = .76f),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = if (item.id == "git_changes" || item.id == "last_error") FontFamily.Monospace else null,
                    )
                }
            }
            if (item.recommendation.isNotBlank()) {
                Surface(
                    color = content.copy(alpha = .08f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        "권장 조치 · ${item.recommendation}",
                        modifier = Modifier.padding(10.dp),
                        color = content,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (onAction != null) {
                TextButton(onClick = onAction, modifier = Modifier.align(Alignment.End)) { Text(actionLabel) }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    state: LauncherUiState,
    onSelectPanel: (SettingsPanel) -> Unit,
    onTheme: (AppTheme) -> Unit,
    onSaveServerConnection: (Int, Boolean, String) -> Unit,
    onOpenSillyTavernFolder: () -> Unit,
    onWakeLockChange: (Boolean) -> Unit,
    onOpenDeviceBatterySettings: () -> Unit,
    onOpenTermuxAppSettings: () -> Unit,
    onOpenLauncherAppSettings: () -> Unit,
    preferredBrowser: BrowserOption?,
    onResetBrowser: () -> Unit,
) {
    val selectedTheme = state.theme
    val background = state.backgroundStatus
    val selectedPanel = state.settingsPanel
    var portText by rememberSaveable(state.configuredPort) { mutableStateOf(state.configuredPort.toString()) }
    var externalAccess by rememberSaveable(state.environment.externalAccessEnabled) {
        mutableStateOf(state.environment.externalAccessEnabled)
    }
    var whitelistText by rememberSaveable(state.environment.whitelist) {
        mutableStateOf(state.environment.whitelist.joinToString("\n"))
    }
    val parsedPort = portText.toIntOrNull()
    val connectionChanged = parsedPort != state.configuredPort ||
        externalAccess != state.environment.externalAccessEnabled ||
        whitelistText.lineSequence().map(String::trim).filter(String::isNotBlank).toList() != state.environment.whitelist
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("설정", style = MaterialTheme.typography.displaySmall)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SettingsPanel.entries.forEach { panel ->
                        FilterChip(
                            selected = selectedPanel == panel,
                            onClick = { onSelectPanel(panel) },
                            label = { Text(panel.label) },
                        )
                    }
                }
            }
        }
        if (selectedPanel == SettingsPanel.BATTERY) {
        item {
            Card(shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("배터리·백그라운드", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Android의 Doze와 절전 정책은 화면이 꺼진 뒤 Termux의 CPU·네트워크 작업을 지연시킬 수 있습니다.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    HorizontalDivider()
                    InfoRow(
                        "Termux 배터리",
                        if (background.termuxBatteryUnrestricted) "제한 없음" else "최적화 대상",
                    )
                    InfoRow(
                        "Termux 알림",
                        "직접 확인 필요",
                    )
                    InfoRow(
                        "Termux Wake lock",
                        when {
                            background.wakeLockActive -> "Termux에서 작동 중"
                            background.wakeLockEnabled -> "켜짐 · 서버 시작 시 적용"
                            else -> "꺼짐"
                        },
                    )
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Termux Wake lock", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "서버 실행 중 Termux가 CPU와 Wi-Fi 절전을 방지합니다. 런처 알림은 추가되지 않습니다.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = background.wakeLockEnabled,
                            onCheckedChange = onWakeLockChange,
                        )
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text(
                            "Wake lock은 배터리 소모를 늘리며 CPU와 Wi-Fi 절전을 방지합니다. 삼성 등 제조사 정책에 따른 Termux 프로세스 종료까지 완전히 막는 기능은 아닙니다.",
                            modifier = Modifier.padding(14.dp),
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        if (background.isSamsung) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    shape = RoundedCornerShape(22.dp),
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("삼성 절전 예외 등록 안내", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "아래 순서대로 한 단계씩 확인해 주세요. One UI 버전에 따라 메뉴 이름이 조금 다를 수 있습니다.",
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = .78f),
                        )
                        SamsungBatteryStep("1", "배터리 설정 열기", "백그라운드 사용 제한을 선택합니다.")
                        SamsungBatteryStep("2", "절전 목록에서 제거", "절전 상태 앱·초절전 상태 앱에 Termux가 있으면 제거합니다.")
                        SamsungBatteryStep("3", "절전 예외에 추가", "자동 절전 안 함 앱 또는 절전 예외 앱에 Termux를 추가합니다.")
                        SamsungBatteryStep("4", "Termux를 제한 없음으로", "Termux 앱 정보 → 배터리에서 제한 없음을 선택합니다.")
                        Button(onClick = onOpenDeviceBatterySettings, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.Settings, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Termux 배터리 설정 열기")
                        }
                        OutlinedButton(onClick = onOpenTermuxAppSettings, modifier = Modifier.fillMaxWidth()) {
                            Text("Termux 앱 정보 열기")
                        }
                    }
                }
            }
        }
        }
        if (selectedPanel == SettingsPanel.SERVER) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("열 앱 선택", style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (preferredBrowser == null) {
                            "아직 ‘항상’ 사용할 앱을 선택하지 않았습니다. 홈에서 SillyTavern을 열 때 선택할 수 있습니다."
                        } else {
                            "현재 항상 사용하는 앱: ${preferredBrowser.label}"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (preferredBrowser != null) {
                        OutlinedButton(
                            onClick = onResetBrowser,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("열 앱 다시 선택")
                        }
                    }
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("서버 주소", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "변경한 주소는 다음 서버 시작부터 적용됩니다.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { value ->
                            if (value.length <= 5 && value.all(Char::isDigit)) portText = value
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("포트 번호") },
                        prefix = { Text(if (externalAccess) "0.0.0.0:" else "127.0.0.1:") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = parsedPort != null && parsedPort !in 1024..65535,
                        supportingText = { Text("사용 가능 범위: 1024–65535") },
                    )
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("외부 접속 허용", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "같은 Wi-Fi의 허용된 기기에서 접속할 수 있게 합니다.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(checked = externalAccess, onCheckedChange = { externalAccess = it })
                    }
                    if (externalAccess) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Text(
                                "인터넷 공유기 포트 개방에는 사용하지 마세요. 아래 화이트리스트에 적은 주소만 허용되며, 서버 재시작 후 적용됩니다.",
                                modifier = Modifier.padding(14.dp),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    OutlinedTextField(
                        value = whitelistText,
                        onValueChange = { whitelistText = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("화이트리스트 · 한 줄에 하나") },
                        placeholder = { Text("192.168.0.5\n192.168.0.0/24") },
                        minLines = 4,
                        supportingText = {
                            Text("개별 IP, CIDR, 와일드카드를 사용할 수 있습니다. localhost 주소는 저장할 때 자동 유지됩니다.")
                        },
                    )
                    Button(
                        onClick = { parsedPort?.let { onSaveServerConnection(it, externalAccess, whitelistText) } },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.environment.processRunning && parsedPort != null &&
                            parsedPort in 1024..65535 && connectionChanged,
                    ) { Text(if (state.environment.processRunning) "서버 종료 후 저장" else "서버 연결 설정 저장") }
                }
            }
        }
        }
        if (selectedPanel == SettingsPanel.GENERAL) {
        item {
            Card(shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("테마", style = MaterialTheme.typography.titleLarge)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AppTheme.entries.forEach { theme ->
                            FilterChip(
                                selected = selectedTheme == theme,
                                onClick = { onTheme(theme) },
                                leadingIcon = { Icon(theme.icon(), null, modifier = Modifier.size(18.dp)) },
                                label = { Text(theme.label) },
                            )
                        }
                    }
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("SillyTavern 폴더", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "실제 설치 폴더를 탐색하고 파일과 폴더를 관리합니다. 변경 작업은 서버를 종료한 뒤 사용할 수 있습니다.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(
                        onClick = onOpenSillyTavernFolder,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = state.environment.sillyTavernInstalled,
                    ) {
                        Icon(Icons.Outlined.FolderOpen, null)
                        Spacer(Modifier.width(8.dp))
                        Text("SillyTavern 폴더 관리")
                    }
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("앱 정보", style = MaterialTheme.typography.titleLarge)
                    InfoRow("앱 버전", BuildConfig.VERSION_NAME)
                    InfoRow("제작·배포", "깡통 커뮤니티")
                    Text(
                        "SillyTavern 및 Termux와 별개로 제작되는 비공식 런처입니다.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(onClick = onOpenLauncherAppSettings, modifier = Modifier.fillMaxWidth()) {
                        Text("런처 앱 상세 설정 열기")
                    }
                }
            }
        }
        }
    }
}


@Composable
private fun WorkingOverlay(
    label: String,
    progress: app.tavernbridge.launcher.model.WorkProgress?,
    onCancel: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var detailsExpanded by remember { mutableStateOf(true) }
    var collapsed by remember { mutableStateOf(false) }
    var cancelConfirmation by remember { mutableStateOf(false) }
    var elapsedSeconds by remember(progress?.operation, label) { mutableStateOf(0L) }
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    var spinnerRotation by remember { mutableFloatStateOf(0f) }
    val finalConfirmation = label == "작업 완료" || label == "진단 완료"
    val finalizing = progress?.status == "success" && !finalConfirmation
    val measuredPercent = progress?.measuredPercent.takeUnless { finalizing }
    val cancellable = progress?.operation in setOf("install", "start") &&
        progress?.status == "running" && !finalizing
    LaunchedEffect(Unit) {
        while (true) {
            delay(16)
            spinnerRotation = (spinnerRotation + 7f) % 360f
        }
    }
    val logScrollState = rememberScrollState()
    LaunchedEffect(progress?.operation, label) {
        while (true) {
            delay(1_000)
            elapsedSeconds += 1
            nowMillis = System.currentTimeMillis()
        }
    }
    val recentLog = progress?.logText
        ?.lineSequence()
        ?.filter { it.isNotBlank() }
        ?.toList()
        ?.takeLast(50)
        ?.joinToString("\n")
        .orEmpty()
    val elapsedLabel = if (elapsedSeconds < 60) {
        "${elapsedSeconds}초 경과"
    } else {
        "${elapsedSeconds / 60}분 ${elapsedSeconds % 60}초 경과"
    }
    LaunchedEffect(detailsExpanded, recentLog) {
        if (detailsExpanded) {
            delay(50)
            logScrollState.scrollTo(logScrollState.maxValue)
        }
    }
    if (cancelConfirmation) {
        AlertDialog(
            onDismissRequest = { cancelConfirmation = false },
            title = { Text("진행 중인 작업을 중단할까요?") },
            text = {
                Text(
                    if (progress?.operation == "install") {
                        "내려받던 부분 설치를 정리한 뒤 중단합니다. 다음 설치 때 처음부터 다시 진행됩니다."
                    } else {
                        "서버 시작 준비를 중단하고, 이번 작업에서 서버가 실행됐다면 함께 종료합니다."
                    },
                )
            },
            confirmButton = {
                Button(onClick = {
                    cancelConfirmation = false
                    onCancel()
                }) { Text("안전하게 중단") }
            },
            dismissButton = {
                TextButton(onClick = { cancelConfirmation = false }) { Text("계속 진행") }
            },
        )
    }
    if (collapsed) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            contentAlignment = Alignment.BottomEnd,
        ) {
            SmallFloatingActionButton(onClick = { collapsed = false }) {
                Icon(
                    Icons.Outlined.KeyboardArrowUp,
                    contentDescription = "진행창 펼치기",
                )
            }
        }
    } else Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = .36f)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp),
            shape = RoundedCornerShape(22.dp),
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        progress = { .72f },
                        modifier = Modifier
                            .size(24.dp)
                            .graphicsLayer { rotationZ = spinnerRotation },
                        strokeWidth = 3.dp,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        if (progress != null && !finalizing && progress.status != "success") {
                            Text(
                                "현재 단계",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        Text(
                            if (finalizing) "마무리 확인 중" else progress?.phase?.ifBlank { label }
                                ?: label.ifBlank { "처리 중" },
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (finalizing) {
                            Text(
                                "작업 결과를 최종 확인하고 있습니다.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        } else if (!progress?.detail.isNullOrBlank()) {
                            Text(
                                progress?.detail.orEmpty(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text(
                            elapsedLabel,
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    if (measuredPercent != null) {
                        Text("${measuredPercent}%", fontWeight = FontWeight.Bold)
                    }
                    IconButton(onClick = { collapsed = true }) {
                        Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "진행창 접기")
                    }
                }
                if (measuredPercent != null) {
                    LinearProgressIndicator(
                        progress = { measuredPercent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    if (progress == null) {
                        Text(
                            "Termux의 응답을 기다리고 있습니다.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (progress != null && !finalizing) WorkProgressDetails(progress, nowMillis)
                Text(
                    "설치 위치  ~/SillyTavern",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (elapsedSeconds >= 60 && progress?.phase?.let {
                        it.contains("Node") || it.contains("서버") || it.contains("패키지")
                    } == true
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(
                            "첫 설치, 네트워크 상태 또는 기본 콘텐츠 초기화 때문에 오래 걸릴 수 있습니다. 마지막 처리·출력 변화 시간과 상세 로그를 함께 확인하세요.",
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (progress != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { detailsExpanded = !detailsExpanded },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(if (detailsExpanded) "상세 로그 접기" else "상세 로그 보기")
                        }
                        IconButton(
                            onClick = { clipboard.setText(AnnotatedString(progress.logText)) },
                            enabled = progress.logText.isNotBlank(),
                        ) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = "전체 로그 복사")
                        }
                    }
                    if (cancellable) {
                        OutlinedButton(
                            onClick = { cancelConfirmation = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Outlined.Stop, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("작업 중단")
                        }
                    }
                    if (detailsExpanded) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(210.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            SelectionContainer {
                                Text(
                                    recentLog.ifBlank { "아직 출력된 상세 로그가 없습니다." },
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(logScrollState)
                                        .padding(12.dp),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    lineHeight = 16.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun MainSection.icon(): ImageVector = when (this) {
    MainSection.HOME -> Icons.Outlined.Home
    MainSection.SETUP -> Icons.Outlined.Update
    MainSection.LOGS -> Icons.Outlined.HealthAndSafety
    MainSection.SETTINGS -> Icons.Outlined.Settings
}

private fun AppTheme.icon(): ImageVector = when (this) {
    AppTheme.SYSTEM -> Icons.Outlined.MoreHoriz
    AppTheme.LIGHT -> Icons.Outlined.LightMode
    AppTheme.DARK -> Icons.Outlined.DarkMode
    AppTheme.PASTEL, AppTheme.AVOCADO, AppTheme.BANANA, AppTheme.BLUEBERRY -> Icons.Outlined.Palette
}
