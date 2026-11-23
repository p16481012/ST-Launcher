package app.tavernbridge.launcher

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.tavernbridge.launcher.data.LauncherRepository
import app.tavernbridge.launcher.data.OperationResultStore
import app.tavernbridge.launcher.data.ThemePreferences
import app.tavernbridge.launcher.data.BackupReminderStore
import app.tavernbridge.launcher.data.BackupTransferController
import app.tavernbridge.launcher.model.BackupArchive
import app.tavernbridge.launcher.model.BackupTransferAction
import app.tavernbridge.launcher.model.BackupTransferPhase
import app.tavernbridge.launcher.model.BackupTransferState
import app.tavernbridge.launcher.model.AppTheme
import app.tavernbridge.launcher.model.BackupCategory
import app.tavernbridge.launcher.model.BackupRequest
import app.tavernbridge.launcher.model.BackupRequestCodec
import app.tavernbridge.launcher.model.backupFailureDetail
import app.tavernbridge.launcher.model.backupFailureLog
import app.tavernbridge.launcher.model.operationErrorSummary
import app.tavernbridge.launcher.model.operationFailureSummary
import app.tavernbridge.launcher.model.checkCompletedOperation
import app.tavernbridge.launcher.model.OperationCancellationControl
import app.tavernbridge.launcher.model.UserOperationCancelled
import app.tavernbridge.launcher.model.cancellationTarget
import app.tavernbridge.launcher.model.validOperationId
import app.tavernbridge.launcher.model.operationCheckpoint
import app.tavernbridge.launcher.model.withoutOperationCancellation
import app.tavernbridge.launcher.model.DiagnosticPanel
import app.tavernbridge.launcher.model.LauncherUiState
import app.tavernbridge.launcher.model.EnvironmentRefreshGate
import app.tavernbridge.launcher.model.TermuxSetupState
import app.tavernbridge.launcher.model.TermuxSetupStatus
import app.tavernbridge.launcher.model.beginTermuxSetupCheck
import app.tavernbridge.launcher.model.awaitingFirstConnection
import app.tavernbridge.launcher.model.waitForFirstConnection
import app.tavernbridge.launcher.model.firstConnectionFailed
import app.tavernbridge.launcher.model.hasInstallationHistory
import app.tavernbridge.launcher.model.SetupRefresh
import app.tavernbridge.launcher.model.setupRefresh
import app.tavernbridge.launcher.model.finishTermuxSetupCheck
import app.tavernbridge.launcher.model.sectionAfterEnvironmentCheck
import app.tavernbridge.launcher.model.indicatesExistingInstallation
import app.tavernbridge.launcher.model.MainSection
import app.tavernbridge.launcher.model.OperationResultSummary
import app.tavernbridge.launcher.model.RetryAction
import app.tavernbridge.launcher.model.ManagementPanel
import app.tavernbridge.launcher.model.SettingsPanel
import app.tavernbridge.launcher.model.SillyBranch
import app.tavernbridge.launcher.model.WorkProgress
import app.tavernbridge.launcher.model.LocalProgressJournal
import app.tavernbridge.launcher.model.ProgressLogHistory
import app.tavernbridge.launcher.model.belongsToStartedOperation
import app.tavernbridge.launcher.model.RecoveredOperationStatus
import app.tavernbridge.launcher.model.recoveredOperationStatus
import app.tavernbridge.launcher.model.recoveredSubstepNotice
import app.tavernbridge.launcher.model.reconnectingAfterTimeout
import app.tavernbridge.launcher.model.resolveWorkingStartedAtMillis
import app.tavernbridge.launcher.model.shouldRestoreUpdateServer
import app.tavernbridge.launcher.model.retryActionAfterFailure
import app.tavernbridge.launcher.model.ExistingInstallation
import app.tavernbridge.launcher.model.InstallationImportMode
import app.tavernbridge.launcher.model.protectBeforeReplacement
import app.tavernbridge.launcher.model.canModifyTavernFiles
import app.tavernbridge.launcher.model.validTavernEntryName
import app.tavernbridge.launcher.termux.TermuxCommandResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class LauncherViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = LauncherRepository(application)
    private val operationResultStore = OperationResultStore(application)
    private val savedOperationResult = operationResultStore.load()
    private val themePreferences = ThemePreferences(application)
    private val backupReminderStore = BackupReminderStore(application)
    private val backupTransferController = BackupTransferController(application)
    private var backupTransferJob: Job? = null
    private var backupTransferSource: BackupTransferController.Source? = null
    private var backupShareIntent: Intent? = null
    private val mutableState = MutableStateFlow(
        LauncherUiState(
            workingStartedAtMillis = System.currentTimeMillis(),
            theme = themePreferences.loadTheme(),
            configuredPort = repository.configuredPort(),
            previousInstallationKnown = (repository.hasKnownInstallation() || savedOperationResult.indicatesExistingInstallation())
                .also { if (it) repository.rememberKnownInstallation() },
            autoBackupBeforeUpdate = repository.autoBackupBeforeUpdate(),
            backupReminder = backupReminderStore.load(),
            backupReminderNowMillis = System.currentTimeMillis(),
            backgroundStatus = repository.backgroundStatus(),
            lastOperationResult = savedOperationResult,
            lastTrashedEntryId = repository.lastTrashedEntryId(),
        ),
    )
    val state = mutableState.asStateFlow()
    private var detachedOperationMonitor: Job? = null
    private val logReadJobs = mutableMapOf<DiagnosticPanel, Job>()
    private val logReadErrorRequests = mutableSetOf<DiagnosticPanel>()
    private var refreshJob: Job? = null
    private val environmentRefreshGate = EnvironmentRefreshGate()
    private val progressLogHistory = ProgressLogHistory()
    private var fileBrowserJob: Job? = null
    @Volatile private var operationCancellationRequested = false
    private var activeOperationControl: OperationCancellationControl? = null
    private var cancellationRequestJob: Job? = null
    private var pendingInitialCancellation = false
    @Volatile private var appInForeground = false

    val setupCommand: String get() = repository.setupCommand()
    val storageSetupCommand: String get() = repository.storageSetupCommand()

    fun onAppForegrounded() {
        appInForeground = true
        mutableState.update {
            it.copy(backgroundStatus = repository.backgroundStatus(it.environment.processRunning),
                backupReminderNowMillis = System.currentTimeMillis())
        }
    }

    fun onAppBackgrounded() {
        appInForeground = false
    }

    fun selectSection(section: MainSection) {
        mutableState.update { it.copy(section = section) }
        if (section == MainSection.LOGS && state.value.environment.managerConnected) {
            when (state.value.diagnosticPanel) {
                DiagnosticPanel.SERVER_LOG -> loadLogs()
                DiagnosticPanel.PREVIOUS_SERVER_LOG -> loadPreviousServerLogs()
                DiagnosticPanel.WORK_HISTORY -> loadOperationHistory()
                DiagnosticPanel.OVERVIEW -> Unit
            }
        }
    }

    fun selectManagementPanel(panel: ManagementPanel) {
        mutableState.update { it.copy(managementPanel = panel) }
    }

    fun selectSettingsPanel(panel: SettingsPanel) {
        mutableState.update { it.copy(settingsPanel = panel) }
    }

    fun openDiagnostics(panel: DiagnosticPanel) {
        mutableState.update { it.copy(section = MainSection.LOGS, diagnosticPanel = panel) }
        when (panel) {
            DiagnosticPanel.SERVER_LOG -> loadLogs()
            DiagnosticPanel.PREVIOUS_SERVER_LOG -> loadPreviousServerLogs()
            DiagnosticPanel.WORK_HISTORY -> loadOperationHistory()
            DiagnosticPanel.OVERVIEW -> Unit
        }
    }

    fun selectInstallBranch(branch: SillyBranch) {
        mutableState.update { it.copy(selectedInstallBranch = branch) }
    }

    fun selectTheme(theme: AppTheme) {
        themePreferences.saveTheme(theme)
        mutableState.update { it.copy(theme = theme) }
    }

    fun setAutoBackupBeforeUpdate(enabled: Boolean) {
        repository.saveAutoBackupBeforeUpdate(enabled)
        mutableState.update { it.copy(autoBackupBeforeUpdate = enabled) }
    }

    fun setBackupReminderEnabled(enabled: Boolean) {
        val reminder = backupReminderStore.setEnabled(enabled)
        mutableState.update { it.copy(backupReminder = reminder, backupReminderNowMillis = System.currentTimeMillis()) }
    }

    fun snoozeBackupReminder() {
        val reminder = backupReminderStore.snooze()
        mutableState.update { it.copy(backupReminder = reminder, backupReminderNowMillis = System.currentTimeMillis()) }
    }

    fun openBackups() {
        mutableState.update { it.copy(section = MainSection.SETUP, managementPanel = ManagementPanel.BACKUP) }
    }

    fun beginBackupTransfer(archive: BackupArchive, action: BackupTransferAction) {
        if (state.value.isWorking || state.value.backupTransfer != null) return
        backupTransferSource = null
        backupShareIntent = null
        mutableState.update { it.copy(backupTransfer = BackupTransferState(archive, action)) }
    }

    fun confirmBackupTransfer() {
        val transfer = state.value.backupTransfer ?: return
        if (transfer.phase != BackupTransferPhase.CONFIRM) return
        mutableState.update { it.copy(backupTransfer = transfer.copy(phase = BackupTransferPhase.CHECKING)) }
        backupTransferJob = viewModelScope.launch {
            try {
                val source = backupTransferController.findReadable(transfer.archive)
                if (source == null) mutableState.update {
                    it.copy(backupTransfer = transfer.copy(phase = BackupTransferPhase.PICK_SOURCE))
                } else prepareBackupTransfer(source, transfer)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { finishBackupTransferNotice(error.userMessage()) }
        }
    }

    fun backupTransferPickerOpened(source: Boolean): Boolean {
        val transfer = state.value.backupTransfer ?: return false
        val expected = if (source) BackupTransferPhase.PICK_SOURCE else BackupTransferPhase.PICK_DESTINATION
        if (transfer.phase != expected) return false
        mutableState.update { it.copy(backupTransfer = transfer.copy(phase =
            if (source) BackupTransferPhase.SOURCE_OPEN else BackupTransferPhase.DESTINATION_OPEN)) }
        return true
    }

    fun selectBackupTransferSource(uri: Uri?) {
        val transfer = state.value.backupTransfer ?: return
        if (transfer.phase != BackupTransferPhase.SOURCE_OPEN) return
        if (uri == null) { cancelBackupTransfer(); return }
        mutableState.update { it.copy(backupTransfer = transfer.copy(phase = BackupTransferPhase.CHECKING)) }
        backupTransferJob = viewModelScope.launch {
            try { prepareBackupTransfer(backupTransferController.selectSource(uri, transfer.archive), transfer) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { finishBackupTransferNotice(error.userMessage()) }
        }
    }

    private suspend fun prepareBackupTransfer(source: BackupTransferController.Source, transfer: BackupTransferState) {
        backupTransferSource = source
        if (transfer.action == BackupTransferAction.SHARE) {
            backupShareIntent = backupTransferController.shareIntent(source, transfer.archive)
            mutableState.update { it.copy(backupTransfer = transfer.copy(phase = BackupTransferPhase.SHARE_READY)) }
        } else mutableState.update { it.copy(backupTransfer = transfer.copy(phase = BackupTransferPhase.PICK_DESTINATION)) }
    }

    fun takeBackupShareIntent(): Intent? {
        if (state.value.backupTransfer?.phase != BackupTransferPhase.SHARE_READY) return null
        val intent = backupShareIntent
        backupShareIntent = null
        backupTransferSource = null
        mutableState.update { it.copy(backupTransfer = null) }
        return intent
    }

    fun exportBackupTo(uri: Uri?) {
        val transfer = state.value.backupTransfer ?: return
        if (transfer.phase != BackupTransferPhase.DESTINATION_OPEN) return
        if (uri == null) { cancelBackupTransfer(); return }
        val source = backupTransferSource ?: run { cancelBackupTransfer(); return }
        val started = System.currentTimeMillis()
        val interrupted = operationSummary("export-backup", "백업 내보내기",
            "내보내기 완료 여부를 확인하지 못했습니다. 저장 위치의 파일을 확인해 주세요. 원본 ZIP은 변경하지 않았습니다.",
            false, started, errorCode = "OPERATION_INTERRUPTED")
        mutableState.update { it.copy(backupTransfer = transfer.copy(phase = BackupTransferPhase.COPYING),
            lastOperationResult = interrupted) }
        backupTransferJob = viewModelScope.launch {
            try {
                backupTransferController.export(source, uri, transfer.archive) { progress ->
                    mutableState.update { current -> current.copy(backupTransfer = current.backupTransfer?.copy(
                        bytes = progress.bytes, total = progress.total)) }
                }
                val detail = "선택한 위치에 백업을 저장했습니다. 원본 ZIP도 그대로 유지했습니다."
                val summary = operationSummary("export-backup", "백업 내보내기", detail, true, started)
                mutableState.update { it.copy(lastOperationResult = summary) }
                finishBackupTransferNotice(detail)
            } catch (cancelled: CancellationException) {
                val detail = "내보내기를 중단했습니다. 원본 ZIP은 유지됩니다. 저장 위치에 미완성 파일이 남아 있는지 확인해 주세요."
                val summary = operationSummary("export-backup", "백업 내보내기", detail, false, started,
                    errorCode = "OPERATION_CANCELLED")
                mutableState.update { it.copy(lastOperationResult = summary) }
                finishBackupTransferNotice(detail)
                throw cancelled
            } catch (error: Exception) {
                val detail = "내보내기에 실패했습니다. 원본 ZIP은 유지됩니다.\n${error.userMessage()}"
                val summary = operationSummary("export-backup", "백업 내보내기", detail, false, started,
                    errorCode = "BACKUP_EXPORT_FAILED")
                mutableState.update { it.copy(lastOperationResult = summary) }
                finishBackupTransferNotice(detail)
            }
        }
    }

    fun cancelBackupTransfer() {
        val copying = state.value.backupTransfer?.phase == BackupTransferPhase.COPYING
        if (copying) mutableState.update { it.copy(backupTransfer = it.backupTransfer?.copy(cancellationRequested = true)) }
        backupTransferJob?.cancel()
        if (!copying) finishBackupTransferNotice(null)
    }

    fun failBackupTransferPicker() {
        cancelBackupTransfer()
        finishBackupTransferNotice("파일 선택 화면을 열 수 없습니다. 다른 파일 앱을 사용하거나 잠시 후 다시 시도해 주세요.")
    }

    private fun finishBackupTransferNotice(message: String?) {
        backupTransferSource = null
        backupShareIntent = null
        mutableState.update { it.copy(backupTransfer = null, message = message ?: it.message) }
    }

    private fun rememberSuccessfulBackup(request: BackupRequest?) {
        if (request == null) return
        // Bookkeeping must not turn an already committed backup into a failed operation.
        runCatching {
            val reminder = backupReminderStore.recordSuccessful(request, System.currentTimeMillis())
            mutableState.update { it.copy(backupReminder = reminder, backupReminderNowMillis = System.currentTimeMillis()) }
        }
    }

    fun setWakeLockEnabled(enabled: Boolean) {
        if (commandEntryBlocked()) return
        viewModelScope.launch {
            try {
                val running = state.value.environment.processRunning
                if (running || !enabled) repository.setTermuxWakeLock(enabled).requireSuccess()
                repository.saveWakeLockEnabled(enabled)
                mutableState.update {
                    it.copy(
                        backgroundStatus = repository.backgroundStatus(running),
                        message = when {
                            enabled && running -> "Termux Wake lock을 켰습니다. 기존 Termux 알림에서 상태를 확인할 수 있습니다."
                            enabled -> "서버를 시작할 때 Termux Wake lock을 함께 켭니다."
                            else -> "Termux Wake lock을 해제했습니다."
                        },
                    )
                }
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(error = "Termux Wake lock 설정을 변경하지 못했습니다.\n${error.userMessage()}")
                }
            }
        }
    }

    fun saveServerConnection(port: Int, externalAccessEnabled: Boolean, whitelistText: String) {
        if (commandEntryBlocked()) return
        if (port !in 1024..65535) {
            mutableState.update { it.copy(error = "포트는 1024부터 65535 사이로 입력해 주세요.") }
            return
        }
        if (state.value.environment.processRunning) {
            mutableState.update { it.copy(error = "서버 연결 설정을 바꾸려면 먼저 SillyTavern 서버를 종료해 주세요.") }
            return
        }
        val whitelist = whitelistText.lineSequence()
            .flatMap { it.split(',').asSequence() }
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .toMutableList()
            .apply {
                if ("127.0.0.1" !in this) add(0, "127.0.0.1")
                if ("::1" !in this) add(0, "::1")
            }
        viewModelScope.launch {
            val started = System.currentTimeMillis()
            progressLogHistory.clear()
            mutableState.update { it.copy(isWorking = true, workingLabel = "서버 연결 설정 저장 중",
                workingStartedAtMillis = started, workProgress = null) }
            val journal = LocalProgressJournal("save-server-connection", ::publishProgress)
            journal.phase("서버 연결 설정 저장", "포트·외부 접속·접근 허용 설정을 Termux에 전달하고 있습니다.")
            val progressJob = pollOperationProgress("save-server-connection", started)
            try {
                val result = cancellableOperation("save-server-connection", started) {
                    repository.saveServerConnectionSettings(port, externalAccessEnabled, whitelist)
                }
                if (!result.isSuccess) throw IllegalStateException(result.readableError())
                progressJob.cancel()
                journal.completedItem("서버 연결 설정 저장")
                val environment = repository.inspect(::publishProgress, "save-server-connection")
                mutableState.update {
                    it.copy(
                        configuredPort = port,
                        environment = environment,
                        isWorking = false,
                        workingLabel = "",
                        message = "서버 연결 설정을 저장했습니다. 다음 서버 시작부터 적용됩니다.",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (reconnectTimedOutOperation(errorCodeFrom(error.userMessage()), "save-server-connection", started)) return@launch
                mutableState.update {
                    it.copy(isWorking = false, workingLabel = "", error = error.userMessage())
                }
            } finally {
                progressJob.cancel()
                mutableState.update { if (it.isWorking) it else it.copy(workProgress = null) }
            }
        }
    }

    fun refresh() = refreshEnvironment(firstConnectionButton = false)

    /** Only the step-3 button is wired here, never a lifecycle or permission callback. */
    fun confirmFirstConnection() {
        if (!state.value.awaitingFirstConnection || state.value.isWorking) return
        refreshEnvironment(firstConnectionButton = true)
    }

    private fun refreshEnvironment(firstConnectionButton: Boolean) {
        if (state.value.backupTransfer != null) return
        if (refreshJob?.isActive == true) return
        if (!environmentRefreshGate.tryStart(
                appInForeground = appInForeground,
                isWorking = state.value.isWorking,
                refreshActive = refreshJob?.isActive == true,
            )) return
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            when (state.value.setupRefresh(firstConnectionButton)) {
                SetupRefresh.LOCAL_ONLY -> {
                    val base = repository.baseStatus()
                    mutableState.update { it.waitForFirstConnection(base) }
                    return@launch
                }
                SetupRefresh.VERIFY_INLINE -> {
                    verifyFirstConnectionInline()
                    return@launch
                }
                SetupRefresh.NORMAL -> Unit
            }
            val firstCheck = !state.value.environmentChecked
            val requestedSection = state.value.section
            if (!checkSetupBeforeRefresh()) return@launch
            val started = System.currentTimeMillis()
            progressLogHistory.clear()
            mutableState.update { it.copy(isWorking = true, workingLabel = "환경 확인 중",
                workingStartedAtMillis = started, workProgress = null) }
            val journal = LocalProgressJournal("refresh", ::publishProgress)
            try {
                cancellableOperation("refresh", started) {
                val environment = repository.inspect(::publishProgress)
                mutableState.update { it.copy(termuxSetup = repository.termuxSetupState()) }
                if (environment.processRunning && repository.wakeLockEnabled()) {
                    journal.phase("백그라운드 실행 설정 확인", "실행 중인 서버의 Termux Wake lock 설정을 적용합니다.")
                    repository.setTermuxWakeLock(true).requireSuccess()
                    journal.completedItem("Wake lock 설정 확인")
                }
                val backupStorageReady = if (environment.managerConnected && environment.sillyTavernInstalled) {
                    journal.phase("백업 저장소 확인", "Download 폴더 접근 상태를 확인합니다.")
                    try {
                        repository.backupStorageReady().also { journal.completedItem("백업 저장소 접근 검사") }
                    } catch (cancelled: CancellationException) { throw cancelled
                    } catch (_: Exception) { null }
                } else {
                    null
                }
                journal.phase("이전 작업 결과 확인", "Termux의 작업 상태를 확인하여 앱 종료 중 실행된 작업과 연결합니다.")
                val progress = if (environment.managerConnected) {
                    repository.readProgress()
                } else {
                    null
                }
                runCatching {
                    val reminder = backupReminderStore.recordRecovered(repository.savedBackupRequest(), progress)
                    mutableState.update { it.copy(backupReminder = reminder, backupReminderNowMillis = System.currentTimeMillis()) }
                }
                val recoveredTerminal = !environment.operationActive && !environment.recoveryPending &&
                    progress?.status in setOf("success", "error", "cancelled") &&
                    (progress?.finishedAtMillis ?: 0L) > (state.value.lastOperationResult?.completedAtMillis ?: 0L)
                val recoveredFailure = recoveredTerminal && progress?.status != "success"
                val recoveredCancellation = recoveredTerminal && progress?.status == "cancelled"
                val substepNotice = if (recoveredTerminal) recoveredSubstepNotice(
                    BackupRequestCodec.parentOperation(repository.savedBackupRequest(), progress),
                    progress?.operation.orEmpty(), progress?.status,
                ) else null
                val recoveredLogs = if (recoveredFailure && progress?.operation == "backup") {
                    backupFailureLog(progress.detail, progress.logText)
                } else if (recoveredFailure) {
                    try {
                        repository.logs().stdout.ifBlank { state.value.logs }
                    } catch (_: Exception) {
                        state.value.logs
                    }
                } else {
                    state.value.logs
                }
                val recoveredDetail = if (recoveredFailure && !recoveredCancellation && progress != null) {
                    if (progress.operation == "backup") backupFailureDetail(progress.detail, progress.logText)
                    else operationFailureSummary(progress.errorCode, progress.detail, progress.logText)
                } else substepNotice ?: progress?.detail.orEmpty().ifBlank {
                    if (recoveredFailure) "작업이 실패했습니다. 로그를 확인해 주세요." else "작업을 완료했습니다."
                }
                val recoveredSummary = if (recoveredTerminal && progress != null) {
                    operationSummary(
                        operation = progress.operation,
                        label = operationLabel(progress.operation),
                        detail = recoveredDetail.removePrefix("[${progress.errorCode}]").trim(),
                        succeeded = !recoveredFailure,
                        startedAtMillis = progress.operationStartedAtMillis.takeIf { it > 0L } ?: progress.finishedAtMillis,
                        errorCode = if (recoveredCancellation) "OPERATION_CANCELLED" else progress.errorCode,
                        retryAction = if (recoveredFailure && !recoveredCancellation) retryActionFor(progress.operation) else RetryAction.NONE,
                        backupRequest = BackupRequestCodec.matching(repository.savedBackupRequest(), progress),
                    )
                } else {
                    state.value.lastOperationResult
                }
                val initialSection = sectionAfterEnvironmentCheck(
                    environment, firstCheck, requestedSection, state.value.section,
                )
                val recoveredProgress = progress?.let(progressLogHistory::merge)
                mutableState.update {
                    it.copy(
                        environment = environment,
                        environmentChecked = true,
                        previousInstallationKnown = environment.sillyTavernInstalled,
                        termuxWakeBlocked = false,
                        section = initialSection,
                        isWorking = environment.operationActive,
                        cancellationRequested = environment.operationActive && progress?.cancellationRequested == true,
                        workingLabel = progress?.phase.orEmpty(),
                        workingStartedAtMillis = if (environment.operationActive) resolveWorkingStartedAtMillis(
                            0L, progress?.operationStartedAtMillis ?: 0L, started,
                        ) else it.workingStartedAtMillis,
                        workProgress = if (environment.operationActive) recoveredProgress else null,
                        error = if (recoveredCancellation) null else if (recoveredFailure) recoveredDetail else it.error,
                        message = if (recoveredCancellation) "작업이 중단되었습니다. 중단 결과를 복원했습니다."
                            else if (recoveredTerminal && !recoveredFailure) substepNotice
                                ?: "앱이 종료된 동안 완료된 작업 결과를 복원했습니다." else it.message,
                        lastOperationResult = recoveredSummary,
                        logs = recoveredLogs,
                        backupStorageReady = backupStorageReady ?: it.backupStorageReady,
                        backgroundStatus = repository.backgroundStatus(environment.processRunning),
                    )
                }
                operationCheckpoint()
                if (environment.operationActive) resumeDetachedOperation()
                }
            } catch (error: Exception) {
                if (error is CancellationException) {
                    val checked = repository.termuxSetupState().let {
                        if (it.status == TermuxSetupStatus.CHECKING) TermuxSetupState(TermuxSetupStatus.UNVERIFIED,
                            "환경 확인을 중단했습니다. 기존 설치는 변경하지 않았습니다.") else it
                    }
                    mutableState.update { it.finishTermuxSetupCheck(it.environment, checked) }
                    throw error
                }
                val diagnostic = error.userMessage()
                val wakeBlocked = diagnostic.contains("Not allowed to start service", ignoreCase = true) ||
                    diagnostic.contains("background", ignoreCase = true)
                val summary = operationSummary(
                    operation = "refresh",
                    label = "환경 확인 중",
                    detail = diagnostic,
                    succeeded = false,
                    startedAtMillis = System.currentTimeMillis(),
                    errorCode = errorCodeFrom(diagnostic),
                    retryAction = RetryAction.REFRESH,
                )
                // A failed inspection must retain the known installation, even if Android blocked Termux.
                val failedSetup = repository.termuxSetupState().let {
                    if (it.verified) TermuxSetupState(TermuxSetupStatus.UNVERIFIED, diagnostic) else it
                }
                mutableState.update {
                    it.finishTermuxSetupCheck(it.environment, failedSetup).copy(
                        environmentChecked = false,
                        isWorking = false,
                        workingLabel = "",
                        termuxWakeBlocked = wakeBlocked,
                        error = "환경 확인에 실패했습니다. 기존 설치 상태는 변경하지 않았습니다.\n$diagnostic",
                        workProgress = null,
                        lastOperationResult = summary,
                        logs = "최근 작업 실패\n\n$diagnostic",
                    )
                }
            }
        }
    }

    /** No operation panel, global notice, persisted failure or queued retry for onboarding. */
    private suspend fun verifyFirstConnectionInline() {
        if (!checkSetupBeforeRefresh()) return
        try {
            val environment = repository.inspect()
            mutableState.update {
                it.copy(environment = environment, environmentChecked = true,
                    termuxSetup = repository.termuxSetupState(),
                    previousInstallationKnown = environment.sillyTavernInstalled,
                    section = sectionAfterEnvironmentCheck(environment, true, it.section, it.section),
                    backgroundStatus = repository.backgroundStatus(environment.processRunning))
            }
            if (environment.operationActive) resumeDetachedOperation()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val base = repository.baseStatus()
            val classified = repository.termuxSetupState()
            val failure = when (classified.status) {
                TermuxSetupStatus.TERMUX_MISSING, TermuxSetupStatus.PERMISSION_REQUIRED -> classified
                TermuxSetupStatus.EXTERNAL_APPS_DISABLED -> TermuxSetupState(classified.status,
                    "Termux에서 외부 명령 실행을 허용하지 않았어요. 위 명령을 붙여넣고 Enter를 누른 뒤 다시 확인해 주세요.")
                else -> TermuxSetupState(TermuxSetupStatus.UNVERIFIED, error.userMessage())
            }
            mutableState.update { it.firstConnectionFailed(base, failure) }
        }
    }

    fun refreshSilently() {
        if (state.value.backupTransfer != null) return
        if (!appInForeground || state.value.isWorking || refreshJob?.isActive == true) return
        // Setup/reconnection needs the same recovery path as a cold start, not a
        // swallowed background exception or an unverified manager bootstrap.
        if (!state.value.termuxSetup.verified || !state.value.environment.managerConnected) {
            refresh()
            return
        }
        refreshJob = viewModelScope.launch {
            try {
                if (!checkSetupBeforeRefresh()) return@launch
                val environment = repository.inspect()
                mutableState.update {
                    it.copy(
                        environment = environment,
                        termuxSetup = repository.termuxSetupState(),
                        previousInstallationKnown = environment.sillyTavernInstalled,
                        environmentChecked = true,
                        termuxWakeBlocked = false,
                        section = if (!environment.sillyTavernInstalled) MainSection.SETUP else it.section,
                        backgroundStatus = repository.backgroundStatus(environment.processRunning),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val failedSetup = repository.termuxSetupState().let {
                    if (it.verified) TermuxSetupState(TermuxSetupStatus.UNVERIFIED, error.userMessage()) else it
                }
                mutableState.update { it.finishTermuxSetupCheck(it.environment, failedSetup) }
            }
        }
    }

    /** Local prerequisites only. The real doctor command verifies the Termux connection. */
    private suspend fun checkSetupBeforeRefresh(): Boolean {
        try {
            val base = repository.baseStatus()
            mutableState.update { it.beginTermuxSetupCheck(base) }
            val check = repository.prepareTermuxEnvironmentCheck()
            if (check.requiresUserAction) {
                mutableState.update { it.finishTermuxSetupCheck(repository.baseStatus(), check) }
                return false
            }
            return true
        } catch (cancelled: CancellationException) {
            mutableState.update { it.copy(termuxSetup = TermuxSetupState()) }
            throw cancelled
        } catch (_: Exception) {
            val check = TermuxSetupState(TermuxSetupStatus.UNVERIFIED,
                "연결을 확인하지 못했어요. Termux를 한 번 열고 돌아온 뒤 다시 확인해 주세요.")
            mutableState.update { it.finishTermuxSetupCheck(it.environment, check) }
            return false
        }
    }

    fun verifyTermuxSetup() {
        if (state.value.isWorking || refreshJob?.isActive == true) return
        refresh()
    }

    fun refreshAfterResume() {
        if (state.value.backupTransfer != null) return
        // A delayed resume callback may arrive after a quick button check has
        // already succeeded. Don't send a second doctor while still onboarding.
        if (!state.value.hasInstallationHistory) {
            if (refreshJob?.isActive == true || state.value.environment.operationActive) return
            if (state.value.termuxSetup.verified) {
                if (state.value.isWorking) return
                val base = repository.baseStatus()
                if (!base.termuxInstalled || !base.commandPermissionGranted) {
                    mutableState.update { it.waitForFirstConnection(base) }
                }
            } else refresh()
            return
        }
        if (refreshJob?.isActive == true) {
            viewModelScope.launch {
                refreshJob?.join()
                if (appInForeground && !state.value.termuxSetup.verified) verifyTermuxSetup()
            }
            return
        }
        if (state.value.environmentChecked) {
            refreshSilently()
        } else {
            refresh()
        }
    }

    private fun resumeDetachedOperation(
        fileBrowserPath: String? = null,
        startedAtMillis: Long? = null,
        requestedOperation: String? = null,
    ) {
        if (detachedOperationMonitor?.isActive == true) return
        detachedOperationMonitor = viewModelScope.launch {
            while (true) {
                delay(1_500)
                if (!appInForeground) continue
                var progress = try {
                    repository.readProgress()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                val environment = try {
                    repository.inspect()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    continue
                }

                if (environment.operationActive) {
                    val mergedProgress = progress?.let(progressLogHistory::merge)
                    mutableState.update {
                        it.copy(
                            environment = environment,
                            isWorking = true,
                            cancellationRequested = operationCancellationRequested || progress?.cancellationRequested == true,
                            workingLabel = progress?.phase ?: it.workingLabel,
                            workingStartedAtMillis = resolveWorkingStartedAtMillis(
                                startedAtMillis ?: 0L, progress?.operationStartedAtMillis ?: 0L,
                                it.workingStartedAtMillis,
                            ),
                            workProgress = mergedProgress ?: it.workProgress,
                        )
                    }
                    continue
                }

                // The operation can finish between the progress read and the
                // environment response. Re-read its terminal state before
                // treating the earlier running snapshot as an interruption.
                progress = try {
                    repository.readProgress() ?: progress
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    progress
                }
                val recoveredStatus = recoveredOperationStatus(progress?.status)
                runCatching {
                    val reminder = backupReminderStore.recordRecovered(repository.savedBackupRequest(), progress)
                    mutableState.update { it.copy(backupReminder = reminder, backupReminderNowMillis = System.currentTimeMillis()) }
                }
                val mergedProgress = progress?.let(progressLogHistory::merge)
                val failed = recoveredStatus != RecoveredOperationStatus.SUCCESS
                val interrupted = recoveredStatus == RecoveredOperationStatus.INTERRUPTED
                val cancelled = progress?.status == "cancelled"
                val latestLogs = if (failed && progress?.operation == "backup") {
                    backupFailureLog(progress.detail, progress.logText)
                } else if (failed) {
                    try {
                        repository.logs().stdout.ifBlank { state.value.logs }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        state.value.logs
                    }
                } else {
                    state.value.logs
                }
                val operation = progress?.operation.orEmpty().ifBlank { "unknown" }
                val reportUnavailable = operation in setOf("update-preflight", "diagnose")
                val substepNotice = recoveredSubstepNotice(
                    requestedOperation ?: BackupRequestCodec.parentOperation(repository.savedBackupRequest(), progress),
                    operation, progress?.status,
                )
                val resultDetail = when {
                    cancelled -> progress?.detail?.ifBlank { "작업을 안전하게 중단했습니다." } ?: "작업을 안전하게 중단했습니다."
                    interrupted -> "작업이 중단되어 완료 여부를 확인할 수 없습니다. 설치 상태와 로그를 확인해 주세요."
                    failed && operation == "backup" -> backupFailureDetail(progress?.detail.orEmpty(), latestLogs)
                    failed -> operationFailureSummary(progress?.errorCode.orEmpty(), progress?.detail.orEmpty(), progress?.logText.orEmpty())
                    substepNotice != null -> substepNotice
                    operation == "update-preflight" -> "작업은 완료됐지만 업데이트 검사 결과를 받지 못했습니다. 업데이트 확인을 다시 실행해 주세요."
                    operation == "diagnose" -> "작업은 완료됐지만 진단 보고서를 받지 못했습니다. 전체 환경 진단을 다시 실행해 주세요."
                    else -> "진행 중이던 작업을 완료했습니다."
                }
                val recoveredSummary = operationSummary(
                    operation = operation,
                    label = operationLabel(operation),
                    detail = resultDetail.removePrefix("[${progress?.errorCode.orEmpty()}]").trim(),
                    succeeded = !failed,
                    startedAtMillis = startedAtMillis ?: progress?.operationStartedAtMillis?.takeIf { it > 0L }
                        ?: System.currentTimeMillis(),
                    errorCode = if (cancelled) "OPERATION_CANCELLED" else if (interrupted) "OPERATION_INTERRUPTED" else progress?.errorCode.orEmpty(),
                    retryAction = if (failed && !interrupted && !cancelled) retryActionFor(operation) else RetryAction.NONE,
                    backupRequest = BackupRequestCodec.matching(repository.savedBackupRequest(), progress),
                )
                val refreshedFileEntries = if (fileBrowserPath != null && !failed) {
                    try {
                        repository.listSillyTavernFiles(fileBrowserPath)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                } else null
                mutableState.update {
                    val recovered = if (failed) {
                        it.copy(
                            environment = environment,
                            isWorking = false,
                            cancellationRequested = false,
                            workingLabel = "",
                            workProgress = null,
                            error = if (cancelled) null else resultDetail,
                            message = if (cancelled) resultDetail else it.message,
                            logs = latestLogs,
                            lastOperationResult = recoveredSummary,
                        )
                    } else {
                        it.copy(
                            environment = environment,
                            section = if (environment.sillyTavernInstalled && !reportUnavailable && substepNotice == null) MainSection.HOME else it.section,
                            isWorking = true,
                            workingLabel = "작업 완료",
                            workingStartedAtMillis = resolveWorkingStartedAtMillis(
                                startedAtMillis ?: 0L, progress?.operationStartedAtMillis ?: 0L,
                                it.workingStartedAtMillis,
                            ),
                            workProgress = completionProgress(mergedProgress, resultDetail),
                            error = null,
                            logs = latestLogs,
                            lastOperationResult = recoveredSummary,
                        )
                    }
                    if (fileBrowserPath == null) recovered else recovered.copy(
                        section = it.section,
                        error = null,
                        fileBrowserMutating = !failed,
                        fileBrowserLoading = false,
                        fileBrowserEntries = refreshedFileEntries ?: it.fileBrowserEntries,
                        fileBrowserError = if (failed && !cancelled) resultDetail else "",
                        fileBrowserNotice = if (cancelled) resultDetail else if (failed) "" else if (refreshedFileEntries != null) resultDetail
                            else "$resultDetail 목록을 새로고침해 주세요.",
                    )
                }
                if (!failed) {
                    delay(COMPLETION_ANIMATION_MILLIS)
                    mutableState.update {
                        it.copy(
                            isWorking = false,
                            workingLabel = "",
                            workProgress = null,
                            fileBrowserMutating = if (fileBrowserPath != null) false else it.fileBrowserMutating,
                            message = resultDetail,
                        )
                    }
                }
                operationCancellationRequested = false
                cancellationRequestJob?.cancel()
                mutableState.update { it.copy(cancellationRequested = false) }
                break
            }
        }
    }

    fun connectManager() = perform(
        operation = "connect-manager",
        label = "런처 연결 중",
        successMessage = "Termux와 런처가 연결되었습니다.",
        retryAction = RetryAction.CONNECT_MANAGER,
    ) { repository.connectManager() }

    fun install() {
        val branch = state.value.selectedInstallBranch
        perform(
            operation = "install",
            label = "${branch.label} 설치 중",
            successMessage = "SillyTavern ${branch.label} 설치를 완료했습니다.",
            retryAction = if (branch == SillyBranch.RELEASE) RetryAction.INSTALL_RELEASE else RetryAction.INSTALL_STAGING,
        ) { repository.install(branch) }
    }

    private suspend fun startServerWithWakeLock(): TermuxCommandResult {
        val useWakeLock = repository.wakeLockEnabled()
        if (useWakeLock) repository.setTermuxWakeLock(true).requireSuccess()
        val result = repository.start()
        if (!result.isSuccess && useWakeLock) repository.setTermuxWakeLock(false)
        return result
    }

    fun start() = perform(
        operation = "start",
        label = "SillyTavern 시작 중",
        successMessage = "SillyTavern을 시작했습니다.",
        retryAction = RetryAction.START,
    ) {
        startServerWithWakeLock()
    }

    fun stop() = perform(
        operation = "stop",
        label = "SillyTavern 종료 중",
        successMessage = "SillyTavern을 종료했습니다.",
        retryAction = RetryAction.STOP,
    ) {
        val result = repository.stop()
        if (result.isSuccess && repository.wakeLockEnabled()) repository.setTermuxWakeLock(false).requireSuccess()
        result
    }

    fun restart() = perform(
        operation = "restart",
        label = "SillyTavern 재시작 중",
        successMessage = "SillyTavern을 재시작했습니다.",
        retryAction = RetryAction.RESTART,
    ) {
        if (repository.wakeLockEnabled()) repository.setTermuxWakeLock(true).requireSuccess()
        repository.restart()
    }

    fun cancelCurrentOperation() {
        if (!state.value.isWorking || cancellationRequestJob?.isActive == true) return
        val control = activeOperationControl
        val initialProgress = state.value.workProgress
        val requestId = control?.requestId ?: initialProgress?.operationRequestId.orEmpty()
        val detachedId = initialProgress?.operationId.orEmpty()
        if (control == null && requestId.isBlank() && !validOperationId(detachedId)) {
            if (state.value.workProgress == null && !state.value.environment.operationActive) {
                pendingInitialCancellation = true
                mutableState.update { it.copy(cancellationRequested = true) }
                return
            }
            mutableState.update { it.copy(error = "현재 작업의 중단 정보를 아직 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.") }
            return
        }
        control?.requestCancellation()
        operationCancellationRequested = true
        mutableState.update {
            it.copy(cancellationRequested = true, error = null,
                workProgress = it.workProgress?.copy(logText = it.workProgress.logText + "\n[중단 요청] 현재 작업의 안전한 종료를 확인합니다."))
        }
        cancellationRequestJob = viewModelScope.launch {
            val requestedAt = System.currentTimeMillis()
            var pendingSent = false
            var lastTarget = ""
            while (isActive && state.value.isWorking && operationCancellationRequested) {
                try {
                    // The UUID-only request prevents a command queued behind bootstrap
                    // or a persistent-start substep from starting after cancellation.
                    if (!pendingSent && requestId.isNotBlank() && (control == null || control.hasDispatched)) {
                        repository.cancelOperation("", requestId).requireSuccess()
                        pendingSent = true
                    }
                    if (control == null || control.hasDispatched) {
                        val progress = repository.readProgress(backendOnly = true)
                        val target = cancellationTarget(progress, requestId, detachedId)
                        if (target != null && target != lastTarget) {
                            repository.cancelOperation(target, requestId).requireSuccess()
                            lastTarget = target
                        }
                        if (progress != null && (target != null || progress.operationRequestId == requestId && requestId.isNotBlank())) {
                            publishProgress(progress)
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (error: Exception) {
                    val awaitingBootstrap = control != null && !pendingSent && lastTarget.isBlank() &&
                        System.currentTimeMillis() - requestedAt < 60_000L
                    if (errorCodeFrom(error.userMessage()) != "TERMUX_TIMEOUT" && !awaitingBootstrap) {
                        mutableState.update { it.copy(cancellationRequested = false,
                            error = "작업 중단 요청을 전달하지 못했습니다. 현재 작업의 결과를 기다립니다.\n${error.userMessage()}") }
                        break
                    }
                    // An uncertain callback is not proof that the cancel request failed.
                    // Repeat the same expected identity; never target another operation.
                }
                delay(1_500)
            }
        }
    }

    private suspend fun <T> cancellableOperation(
        operation: String,
        started: Long,
        backupRequest: BackupRequest? = null,
        block: suspend () -> T,
    ): T {
        val control = OperationCancellationControl()
        activeOperationControl = control
        operationCancellationRequested = pendingInitialCancellation
        if (pendingInitialCancellation) control.requestCancellation()
        pendingInitialCancellation = false
        mutableState.update { it.copy(cancellationRequested = control.cancellationRequested) }
        try {
            return withContext(control) { operationCheckpoint(); block() }
        } catch (cancelled: UserOperationCancelled) {
            // The cooperative exception means either no dispatch occurred or a
            // terminal callback arrived. Preserve the UUID tombstone for queued
            // persistent-server substeps before reporting cancellation.
            if (control.hasDispatched && control.cancellationRequested) {
                repository.cancelOperation("", control.requestId).requireSuccess()
            }
            val environment = if (!control.hasDispatched) state.value.environment else try { repository.inspect() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { state.value.environment }
            val detail = "요청에 따라 작업을 중단했습니다. 이미 완료된 변경은 유지됩니다."
            val summary = operationSummary(operation, operationLabel(operation), detail, false, started,
                errorCode = "OPERATION_CANCELLED", backupRequest = backupRequest)
            mutableState.update { it.copy(environment = environment, isWorking = false,
                cancellationRequested = false, workingLabel = "", workProgress = null, error = null,
                message = detail, lastOperationResult = summary, fileBrowserMutating = false,
                fileBrowserError = "", fileBrowserNotice = if (it.fileBrowserOpen) detail else it.fileBrowserNotice) }
            throw cancelled
        } finally {
            if (activeOperationControl === control) activeOperationControl = null
            if (!state.value.isWorking || control.dispatchedOperation == null) {
                operationCancellationRequested = false
                cancellationRequestJob?.cancel()
                mutableState.update { it.copy(cancellationRequested = false) }
            }
        }
    }

    fun update() {
        val preflight = state.value.updatePreflight
        if (preflight?.updateAvailable != true) {
            mutableState.update {
                it.copy(updatePreflight = null, updateAvailable = false, message = "이미 최신 커밋입니다. 업데이트를 실행하지 않았습니다.")
            }
            return
        }
        perform(
            operation = "update",
            label = "업데이트 중",
            successMessage = "업데이트·패키지 설치·서버 응답 검사를 완료했습니다.",
            retryAction = RetryAction.CHECK_UPDATE,
            successMessageForResult = { result ->
                if (result.stdout.lineSequence().any { it == "followup_cancelled=1" }) {
                    "업데이트 적용은 완료했습니다. 중단 요청에 따라 서버 재시작은 진행하지 않았습니다."
                } else if (result.stdout.lineSequence().any { it == "updated=0" }) {
                    "이미 최신 커밋입니다. 패키지를 다시 설치하지 않았습니다."
                } else {
                    "업데이트·패키지 설치·서버 응답 검사를 완료했습니다."
                }
            },
        ) {
            val wasRunning = state.value.environment.processRunning
            val recordBeforeUpdate = readUpdateRecordSafely()
            var updateInvoked = false

            suspend fun recoverServerAfterFailure() {
                if (!wasRunning) return
                val environment = repository.inspect()
                // A lost callback or timeout does not mean the Termux operation stopped.
                // Never launch a server while an update/rollback is still changing files.
                if (environment.operationActive) return
                // Unlike the display-only refresh, recovery must not ignore a failed lookup:
                // it could hide a rollback failure and start an inconsistent installation.
                val latestRecord = if (updateInvoked) repository.lastUpdateRecord() else null
                val rollbackFailed = latestRecord?.result == "rollback_failed" && latestRecord != recordBeforeUpdate
                if (shouldRestoreUpdateServer(wasRunning, environment, rollbackFailed)) {
                    startServerWithWakeLock().requireSuccess()
                } else if (environment.processRunning && repository.wakeLockEnabled()) {
                    repository.setTermuxWakeLock(true).requireSuccess()
                }
            }

            try {
                if (wasRunning) {
                    repository.stop().requireSuccess()
                    if (repository.wakeLockEnabled()) repository.setTermuxWakeLock(false).requireSuccess()
                }
                if (state.value.autoBackupBeforeUpdate) {
                    repository.createBackup(
                        categories = setOf(BackupCategory.USER_DATA, BackupCategory.EXTENSIONS, BackupCategory.CONFIG),
                        includeSecrets = false,
                        customFolders = emptySet(),
                        parentOperation = "update",
                    ).requireSuccess()
                    rememberSuccessfulBackup(repository.savedBackupRequest())
                    refreshBackupListAfterOperation()
                }
                updateInvoked = true
                val result = repository.update(
                    keepRunning = wasRunning,
                    allowModifiedFiles = state.value.updatePreflight?.modifiedFiles?.isNotEmpty() == true,
                )
                val updateRecord = withoutOperationCancellation { readUpdateRecordSafely() }
                mutableState.update { it.copy(lastUpdateRecord = updateRecord ?: it.lastUpdateRecord) }
                result.requireSuccess()
                mutableState.update { it.copy(updatePreflight = null) }
                result
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (currentCoroutineContext()[OperationCancellationControl]?.cancellationRequested == true) throw error
                try {
                    recoverServerAfterFailure()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (recoveryError: Exception) {
                    throw IllegalStateException(
                        "${error.userMessage()}\n서버 실행 상태를 복구하지 못했습니다. ${recoveryError.userMessage()}",
                        error,
                    )
                }
                throw error
            }
        }
    }

    private suspend fun readUpdateRecordSafely() = try {
        repository.lastUpdateRecord()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    fun checkForUpdates() {
        if (commandEntryBlocked()) return
        viewModelScope.launch {
            val started = System.currentTimeMillis()
            progressLogHistory.clear()
            mutableState.update { it.copy(isWorking = true, workingLabel = "업데이트 안전 검사 중",
                workingStartedAtMillis = started, workProgress = null) }
            LocalProgressJournal("update-preflight", ::publishProgress).phase(
                "업데이트 검사 준비", "Termux에 최신 커밋·Node 호환성·공간 검사 요청을 전달합니다.")
            val progressJob = pollOperationProgress("update-preflight", started)
            try {
                val report = cancellableOperation("update-preflight", started) { repository.checkUpdate() }
                progressJob.cancel()
                mutableState.update {
                    it.copy(
                        isWorking = false,
                        workingLabel = "",
                        workProgress = null,
                        latestVersion = report.targetVersion,
                        updateAvailable = report.updateAvailable,
                        updatePreflight = report,
                        message = if (report.updateAvailable) "업데이트 안전 검사를 완료했습니다."
                        else "현재 브랜치가 최신 상태입니다.",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val diagnostic = error.userMessage()
                val errorCode = errorCodeFrom(diagnostic)
                if (reconnectTimedOutOperation(errorCode, "update-preflight", started)) return@launch
                val summary = operationSummary(
                    operation = "update-preflight",
                    label = "업데이트 안전 검사 중",
                    detail = diagnostic,
                    succeeded = false,
                    startedAtMillis = started,
                    errorCode = errorCode,
                    retryAction = retryActionAfterFailure(errorCode, RetryAction.CHECK_UPDATE),
                )
                mutableState.update {
                    it.copy(
                        isWorking = false,
                        workingLabel = "",
                        workProgress = null,
                        error = diagnostic,
                        lastOperationResult = summary,
                    )
                }
            } finally {
                progressJob.cancel()
            }
        }
    }

    fun switchBranch(branch: SillyBranch) = perform(
        operation = "switch-branch",
        label = "${branch.label}로 전환 중",
        successMessage = "${branch.label} 브랜치로 전환했습니다.",
        successMessageForResult = { result -> if (result.stdout.contains("followup_cancelled=1"))
            "${branch.label} 브랜치로 전환했습니다. 중단 요청에 따라 서버 재시작은 진행하지 않았습니다."
            else "${branch.label} 브랜치로 전환했습니다." },
    ) {
        val wasRunning = state.value.environment.processRunning
        if (wasRunning) {
            repository.stop().requireSuccess()
            if (repository.wakeLockEnabled()) repository.setTermuxWakeLock(false).requireSuccess()
        }
        val result = repository.switchBranch(
            branch = branch,
            allowModifiedFiles = state.value.environment.modifiedFiles.isNotEmpty(),
        )
        if (result.isSuccess) {
            mutableState.update { it.copy(updatePreflight = null) }
            if (wasRunning) {
                try { startServerWithWakeLock().requireSuccess() }
                catch (cancelled: UserOperationCancelled) {
                    val control = currentCoroutineContext()[OperationCancellationControl]
                    withoutOperationCancellation {
                        if (control != null) repository.cancelOperation("", control.requestId).requireSuccess()
                        repository.stop().requireSuccess()
                    }
                    return@perform result.copy(stdout = result.stdout + "\nfollowup_cancelled=1\n")
                }
            }
        }
        result
    }

    fun resetInstallation() = perform(
        operation = "reset-installation",
        label = "SillyTavern 초기화 중",
        successMessage = "SillyTavern을 초기 상태로 다시 설치했습니다. Download의 백업 파일은 유지됩니다.",
    ) {
        val result = repository.resetInstallation()
        if (result.isSuccess) {
            mutableState.update { it.copy(updatePreflight = null) }
        }
        result
    }

    fun backup() = createBackup(setOf(BackupCategory.FULL), false, emptySet())

    fun loadBackups() {
        val pendingRefresh = refreshJob
        if (pendingRefresh?.isActive == true) {
            viewModelScope.launch {
                pendingRefresh.join()
                if (appInForeground && state.value.environment.managerConnected && state.value.termuxSetup.verified) loadBackups()
            }
            return
        }
        if (!state.value.environment.managerConnected || !state.value.termuxSetup.verified) return
        if (commandEntryBlocked(allowWhileWorking = true)) return
        viewModelScope.launch {
            mutableState.update { it.copy(backupsLoaded = false) }
            try {
                val storageReady = repository.backupStorageReady()
                val backups = repository.listBackups()
                val reminder = backupReminderStore.observeArchives(backups)
                val folders = repository.listUserBackupFolders()
                val updateRecord = repository.lastUpdateRecord()
                mutableState.update {
                    it.copy(
                        backups = backups,
                        backupReminder = reminder,
                        backupReminderNowMillis = System.currentTimeMillis(),
                        backupFolders = folders,
                        backupsLoaded = true,
                        backupStorageReady = storageReady,
                        lastUpdateRecord = updateRecord,
                    )
                }
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(backupsLoaded = false, backupStorageReady = null, error = error.userMessage())
                }
            }
        }
    }

    fun importBackup(uri: Uri, backupBefore: Boolean = false) {
        if (state.value.environment.processRunning) {
            mutableState.update { it.copy(error = "백업 파일을 가져오려면 먼저 SillyTavern 서버를 종료해 주세요.") }
            return
        }
        perform(
            operation = "import-backup",
            label = "ZIP에서 바로 복원 중",
            successMessage = "선택한 ZIP의 복원을 완료했습니다. 원본 ZIP은 변경하지 않았습니다.",
        ) {
            val result = protectBeforeReplacement(backupBefore, { backupBeforeReplacement("import-backup") }) {
                repository.importBackup(uri)
            }
            if (result.isSuccess) refreshBackupListAfterOperation()
            result
        }
    }

    fun inspectExistingInstallation(path: String) {
        if (path.isBlank()) {
            mutableState.update { it.copy(error = "가져올 SillyTavern 폴더 경로를 입력해 주세요.") }
            return
        }
        inspectInstallation { repository.inspectExistingInstallation(path.trim()) }
    }

    fun inspectSharedInstallation(uri: Uri) {
        inspectInstallation { repository.stageInstallationFolder(uri) }
    }

    private fun inspectInstallation(block: suspend () -> ExistingInstallation) {
        if (commandEntryBlocked() || state.value.environment.operationActive) return
        if (state.value.environment.processRunning) {
            mutableState.update { it.copy(error = "기존 설치를 가져오려면 먼저 서버를 종료해 주세요.") }
            return
        }
        val started = System.currentTimeMillis()
        progressLogHistory.clear()
        mutableState.update {
            it.copy(isWorking = true, workingLabel = "기존 설치 폴더 검사 중", workProgress = null,
                workingStartedAtMillis = started, pendingInstallation = null)
        }
        viewModelScope.launch {
            val progressJob = pollOperationProgress("inspect-install", started)
            var reconnecting = false
            try {
                val candidate = cancellableOperation("inspect-install", started) { block() }
                mutableState.update { it.copy(pendingInstallation = candidate) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                reconnecting = reconnectTimedOutOperation(errorCodeFrom(error.userMessage()), "inspect-install", started)
                if (reconnecting) return@launch
                mutableState.update { it.copy(error = error.userMessage()) }
            } finally {
                progressJob.cancel()
                if (!reconnecting) mutableState.update { it.copy(isWorking = false, workingLabel = "", workProgress = null) }
            }
        }
    }

    fun dismissInstallationImport() {
        val source = state.value.pendingInstallation?.sourcePath
        mutableState.update { it.copy(pendingInstallation = null) }
        if (source != null) viewModelScope.launch {
            try { repository.discardInstallationImport(source)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { /* Cancelling selection must never change the source folder. */ }
        }
    }

    fun confirmInstallationImport(mode: InstallationImportMode, backupBefore: Boolean) {
        val candidate = state.value.pendingInstallation ?: return
        if (commandEntryBlocked() || state.value.environment.processRunning) return
        mutableState.update { it.copy(pendingInstallation = null) }
        perform(
            operation = "import-install",
            label = if (mode == InstallationImportMode.MOVE) "기존 설치 이동 중" else "기존 설치 가져오는 중",
            successMessage = if (candidate.sameInstallation) "현재 SillyTavern 설치를 확인했습니다. 기존 폴더를 그대로 사용합니다."
                else "기존 설치를 가져왔습니다.",
            successMessageForResult = { result ->
                when {
                    candidate.sameInstallation -> "현재 SillyTavern 설치를 확인했습니다. 기존 폴더를 그대로 사용합니다."
                    result.stdout.lineSequence().any { it == "source_cleanup_failed=1" } ->
                        "설치는 가져왔지만 원본 폴더를 정리하지 못했습니다. 새 설치를 확인한 뒤 원본을 직접 정리해 주세요."
                    result.stdout.lineSequence().any { it == "source_removed=1" } ->
                        "기존 설치를 이동했습니다. 검증 완료 후 원본 폴더를 정리했습니다."
                    result.stdout.lineSequence().any { it == "source_kept=1" } ->
                        "기존 설치를 가져왔습니다. 선택한 원본 폴더는 유지했으며, 이후 런처는 새 위치의 설치를 사용합니다."
                    else -> "기존 설치를 가져왔습니다. 원본 정리 여부는 작업 기록을 확인해 주세요."
                }
            },
        ) {
            protectBeforeReplacement(backupBefore && !candidate.sameInstallation, { backupBeforeReplacement("import-install") }) {
                repository.importExistingInstallation(candidate.sourcePath, mode, candidate.destinationExists)
            }
        }
    }

    private suspend fun backupBeforeReplacement(parentOperation: String) {
        // Full replacement needs a full recovery copy, including secrets. The confirmation
        // explicitly discloses that the ZIP is not encrypted and remains in Download.
        repository.createBackup(setOf(BackupCategory.FULL), includeSecrets = true, customFolders = emptySet(),
            parentOperation = parentOperation)
            .requireSuccess()
        rememberSuccessfulBackup(repository.savedBackupRequest())
        refreshBackupListAfterOperation()
        operationCheckpoint()
    }

    fun createBackup(
        categories: Set<BackupCategory>,
        includeSecrets: Boolean,
        customFolders: Set<String>,
    ) {
        if (state.value.isWorking) return
        val normalized = if (BackupCategory.FULL in categories) setOf(BackupCategory.FULL) else categories
        if (normalized.isEmpty()) {
            mutableState.update { it.copy(error = "백업할 항목을 하나 이상 선택해 주세요.") }
            return
        }
        if (BackupCategory.CUSTOM in normalized && customFolders.isEmpty()) {
            mutableState.update { it.copy(error = "직접 항목 선택에서 백업할 폴더를 하나 이상 선택해 주세요.") }
            return
        }
        if (state.value.environment.processRunning) {
            mutableState.update { it.copy(error = "일관된 백업을 위해 먼저 SillyTavern 서버를 종료해 주세요.") }
            return
        }
        val request = BackupRequest(UUID.randomUUID().toString(), normalized,
            includeSecrets && (BackupCategory.FULL in normalized || BackupCategory.USER_DATA in normalized),
            if (BackupCategory.CUSTOM in normalized) customFolders else emptySet())
        perform(
            operation = "backup",
            label = "선택 백업 생성 중",
            successMessage = "검증된 ZIP 백업을 휴대폰 Download 폴더에 저장했습니다.",
            retryAction = RetryAction.BACKUP,
            backupRequest = request,
        ) {
            val result = repository.createBackup(request)
            if (result.isSuccess) {
                rememberSuccessfulBackup(request)
                refreshBackupListAfterOperation()
            }
            result
        }
    }

    fun restoreBackup(fileName: String, backupBefore: Boolean = false) {
        if (state.value.environment.processRunning) {
            mutableState.update { it.copy(error = "안전한 복원을 위해 먼저 SillyTavern 서버를 종료해 주세요.") }
            return
        }
        perform(
            operation = "restore",
            label = "백업 복원 중",
            successMessage = if (backupBefore) "복원을 완료했습니다. 복원 전 전체 ZIP 백업은 Download에 보관했습니다."
                else "백업 복원을 완료했습니다. 기존 상태의 임시 보호 사본은 정리했습니다.",
        ) {
            val result = protectBeforeReplacement(backupBefore, { backupBeforeReplacement("restore") }) {
                repository.restoreBackup(fileName)
            }
            if (result.isSuccess) refreshBackupListAfterOperation()
            result
        }
    }

    fun deleteBackup(fileName: String) = perform(
        operation = "delete-backup",
        label = "백업 삭제 중",
        successMessage = "선택한 ZIP 백업을 Download 폴더에서 삭제했습니다.",
    ) {
        val result = repository.deleteBackup(fileName)
        if (result.isSuccess) refreshBackupListAfterOperation()
        result
    }

    fun deleteBackups(fileNames: List<String>) {
        if (fileNames.isEmpty()) return
        perform(
            operation = "delete-backup",
            label = "백업 일괄 삭제 중",
            successMessage = "선택한 ZIP 백업 ${fileNames.size}개를 Download 폴더에서 삭제했습니다.",
        ) {
            val result = repository.deleteBackups(fileNames)
            if (result.isSuccess) refreshBackupListAfterOperation()
            result
        }
    }

    private suspend fun refreshBackupListAfterOperation() {
        try {
            // This read follows a committed backup/restore. Do not relabel that
            // completed operation as cancelled while merely refreshing its list.
            val backups = withoutOperationCancellation { repository.listBackups() }
            val reminder = backupReminderStore.observeArchives(backups)
            mutableState.update { it.copy(backups = backups, backupsLoaded = true,
                backupReminder = reminder, backupReminderNowMillis = System.currentTimeMillis()) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The preceding backup operation is already complete. A list refresh failure must
            // not relabel a successful import, restore, creation, or deletion as failed.
            mutableState.update { it.copy(backupsLoaded = false) }
        }
    }

    fun repair() = perform(
        operation = "repair",
        label = "설치 점검·복구 중",
        successMessage = "사용자 데이터는 유지하고 실행 패키지를 복구했습니다.",
        retryAction = RetryAction.REPAIR,
    ) { repository.repair() }

    fun loadLogs() = loadDiagnosticText(DiagnosticPanel.SERVER_LOG, reportErrors = true)

    fun refreshLogsSilently() = loadDiagnosticText(DiagnosticPanel.SERVER_LOG, reportErrors = false)

    fun loadPreviousServerLogs() = loadDiagnosticText(DiagnosticPanel.PREVIOUS_SERVER_LOG, reportErrors = true)

    fun loadOperationHistory() = loadDiagnosticText(DiagnosticPanel.WORK_HISTORY, reportErrors = true)

    private fun loadDiagnosticText(panel: DiagnosticPanel, reportErrors: Boolean) {
        if (!appInForeground || state.value.isWorking || refreshJob?.isActive == true || !state.value.termuxSetup.verified) return
        if (!reportErrors && !state.value.environment.managerConnected) return
        if (panel == DiagnosticPanel.OVERVIEW) return
        if (logReadJobs[panel]?.isActive == true) {
            // A manual request joins the existing automatic read instead of racing it.
            if (reportErrors) logReadErrorRequests.add(panel)
            return
        }
        if (reportErrors) logReadErrorRequests.add(panel) else logReadErrorRequests.remove(panel)
        val request = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = when (panel) {
                    DiagnosticPanel.SERVER_LOG -> repository.logs()
                    DiagnosticPanel.PREVIOUS_SERVER_LOG -> repository.previousServerLogs()
                    DiagnosticPanel.WORK_HISTORY -> repository.operationHistory()
                    DiagnosticPanel.OVERVIEW -> return@launch
                }
                if (!result.isSuccess) throw IllegalStateException(result.readableError())
                // Read-only refreshes own only their text field, never the global operation state.
                // A late response must not clear a real operation started while this read was pending.
                mutableState.update {
                    when (panel) {
                        DiagnosticPanel.SERVER_LOG -> it.copy(logs = result.stdout.ifBlank { "아직 기록된 서버 로그가 없습니다." })
                        DiagnosticPanel.PREVIOUS_SERVER_LOG -> it.copy(previousServerLogs = result.stdout.ifBlank { "아직 보관된 이전 서버 로그가 없습니다." })
                        DiagnosticPanel.WORK_HISTORY -> it.copy(operationHistory = result.stdout.ifBlank { "아직 기록된 런처 작업이 없습니다." })
                        DiagnosticPanel.OVERVIEW -> it
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (appInForeground && panel in logReadErrorRequests) {
                    mutableState.update {
                        if (it.isWorking) it else it.copy(error = "${panel.label} 조회에 실패했습니다.\n${error.userMessage()}")
                    }
                }
                // Background reads keep the existing text and retry on the next refresh.
            } finally {
                if (logReadJobs[panel] === coroutineContext[Job]) {
                    logReadJobs.remove(panel)
                    logReadErrorRequests.remove(panel)
                }
            }
        }
        // Register before starting so even immediate completion cannot leave an untracked read.
        logReadJobs[panel] = request
        request.start()
    }

    fun runDiagnostics() {
        if (commandEntryBlocked()) return
        viewModelScope.launch {
            val operationStartedAt = System.currentTimeMillis()
            progressLogHistory.clear()
            mutableState.update {
                it.copy(
                    section = MainSection.LOGS,
                    diagnosticPanel = DiagnosticPanel.OVERVIEW,
                    isWorking = true,
                    workingLabel = "전체 환경 진단 중",
                    workingStartedAtMillis = operationStartedAt,
                    workProgress = null,
                )
            }
            LocalProgressJournal("diagnose", ::publishProgress).phase(
                "진단 준비", "Android 설정과 Termux 환경을 차례로 검사합니다.")
            val progressJob = pollOperationProgress("diagnose", operationStartedAt)
            try {
                val report = cancellableOperation("diagnose", operationStartedAt) { repository.diagnose() }
                val finalProgress = try {
                    repository.readProgress()?.takeIf { it.belongsToStartedOperation("diagnose", operationStartedAt) }
                        ?.let(progressLogHistory::merge)
                        ?: state.value.workProgress
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    state.value.workProgress
                }
                progressJob.cancel()
                mutableState.update {
                    it.copy(
                        diagnosticReport = report,
                        isWorking = true,
                        workingLabel = "진단 완료",
                        workProgress = completionProgress(finalProgress, "전체 환경 검사를 완료했습니다."),
                    )
                }
                delay(COMPLETION_ANIMATION_MILLIS)
                val summary = operationSummary(
                    operation = "diagnose",
                    label = "전체 환경 진단 중",
                    detail = "전체 환경 검사를 완료했습니다.",
                    succeeded = true,
                    startedAtMillis = operationStartedAt,
                )
                mutableState.update {
                    it.copy(
                        isWorking = false,
                        workingLabel = "",
                        workProgress = null,
                        message = "환경 진단을 완료했습니다.",
                        lastOperationResult = summary,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val diagnostic = error.userMessage()
                val errorCode = errorCodeFrom(diagnostic)
                if (reconnectTimedOutOperation(errorCode, "diagnose", operationStartedAt)) return@launch
                val summary = operationSummary(
                    operation = "diagnose",
                    label = "전체 환경 진단 중",
                    detail = diagnostic,
                    succeeded = false,
                    startedAtMillis = operationStartedAt,
                    errorCode = errorCode,
                    retryAction = retryActionAfterFailure(errorCode, RetryAction.DIAGNOSE),
                )
                mutableState.update {
                    it.copy(
                        isWorking = false,
                        workingLabel = "",
                        workProgress = null,
                        error = diagnostic,
                        lastOperationResult = summary,
                    )
                }
            } finally {
                progressJob.cancel()
            }
        }
    }

    fun openTermux() {
        if (!repository.openTermux()) mutableState.update { it.copy(error = "Termux를 열 수 없습니다.") }
    }

    fun openTermuxDownload() = repository.openTermuxDownload()
    fun openPermissionSettings() = repository.openLauncherPermissionSettings()
    fun browserOptions() = repository.browserOptions(state.value.environment.port)

    fun preferredBrowser() = repository.preferredBrowser(state.value.environment.port)

    fun openBrowser(packageName: String? = null, rememberChoice: Boolean = false) {
        if (!repository.openBrowser(state.value.environment.port, packageName, rememberChoice)) {
            mutableState.update { it.copy(error = "선택한 브라우저를 열 수 없습니다. 다른 브라우저를 선택해 주세요.") }
        }
    }

    fun clearPreferredBrowser() {
        repository.clearPreferredBrowser()
        mutableState.update { it.copy(message = "저장된 브라우저 선택을 초기화했습니다.") }
    }
    fun openServerLogTerminal() {
        if (commandEntryBlocked(allowWhileWorking = true)) return
        try {
            repository.openServerLogTerminal()
        } catch (_: Exception) {
            verifyTermuxSetup()
        }
    }
    fun openBatteryOptimizationSettings() = repository.openBatteryOptimizationSettings()
    fun openDeviceBatterySettings() = repository.openDeviceBatterySettings()
    fun openTermuxAppSettings() = repository.openTermuxAppSettings()
    fun openLauncherAppSettings() = repository.openLauncherAppSettings()
    fun openTermuxNotificationSettings() = repository.openTermuxNotificationSettings()
    fun openDownloadsFolder() {
        if (!repository.openDownloadsFolder()) {
            mutableState.update { it.copy(error = "Download 폴더를 열 수 없습니다. 휴대폰의 파일 앱에서 Download를 열어 주세요.") }
        }
    }

    fun openSillyTavernFolder() {
        loadSillyTavernFolder("")
    }

    fun loadSillyTavernFolder(relativePath: String) {
        if (state.value.fileBrowserMutating || commandEntryBlocked(allowWhileWorking = true)) return
        fileBrowserJob?.cancel()
        fileBrowserJob = viewModelScope.launch {
            mutableState.update {
                it.copy(
                    fileBrowserOpen = true,
                    fileBrowserPath = relativePath,
                    fileBrowserLoading = true,
                    fileBrowserError = "",
                    fileBrowserEntries = emptyList(),
                )
            }
            try {
                val entries = repository.listSillyTavernFiles(relativePath)
                mutableState.update {
                    it.copy(fileBrowserEntries = entries, fileBrowserLoading = false)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(fileBrowserEntries = emptyList(), fileBrowserLoading = false, fileBrowserError = error.userMessage())
                }
            }
        }
    }

    fun closeSillyTavernFolder() {
        if (state.value.fileBrowserMutating) return
        fileBrowserJob?.cancel()
        mutableState.update { it.copy(fileBrowserOpen = false, fileBrowserEntries = emptyList(),
            fileBrowserLoading = false, fileBrowserError = "", fileBrowserNotice = "", editingFile = null) }
    }

    fun openSillyTavernDirectory() {
        if (state.value.fileBrowserMutating) return
        if (!repository.openSillyTavernDirectory()) {
            mutableState.update { it.copy(fileBrowserError =
                "Termux 폴더를 열 수 있는 권장 파일 앱을 실행하지 못했습니다. 런처 안의 폴더 관리를 이용해 주세요.") }
        }
    }

    fun readSillyTavernTextFile(relativePath: String) {
        if (state.value.fileBrowserLoading || state.value.fileBrowserMutating || commandEntryBlocked(allowWhileWorking = true)) return
        mutableState.update { it.copy(fileBrowserLoading = true, fileBrowserError = "") }
        fileBrowserJob?.cancel()
        fileBrowserJob = viewModelScope.launch {
            try {
                val file = repository.readSillyTavernTextFile(relativePath)
                mutableState.update { it.copy(editingFile = file, fileBrowserLoading = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.update { it.copy(fileBrowserLoading = false, fileBrowserError = error.userMessage()) }
            }
        }
    }

    fun closeTextEditor() {
        if (!state.value.fileBrowserMutating) mutableState.update { it.copy(editingFile = null) }
    }

    fun saveSillyTavernTextFile(content: String) {
        val file = state.value.editingFile ?: return
        mutateTavernFiles("write-st-file", "파일 저장", "파일을 저장했습니다.") {
            repository.saveSillyTavernTextFile(file, content).also { result ->
                if (result.isSuccess) mutableState.update { it.copy(editingFile = null) }
            }
        }
    }

    fun createSillyTavernFolder(name: String) {
        if (!checkEntryName(name)) return
        val parent = state.value.fileBrowserPath
        mutateTavernFiles("mkdir-st", "폴더 만들기", "폴더를 만들었습니다.") {
            repository.createSillyTavernFolder(parent, name)
        }
    }

    fun createSillyTavernFile(name: String) {
        if (!checkEntryName(name)) return
        val parent = state.value.fileBrowserPath
        mutateTavernFiles("create-st-file", "파일 만들기", "빈 파일을 만들었습니다. 파일을 선택하면 내용을 수정할 수 있습니다.") {
            repository.createSillyTavernFile(parent, name)
        }
    }

    fun renameSillyTavernEntry(relativePath: String, newName: String) {
        if (!checkEntryName(newName)) return
        mutateTavernFiles("rename-st", "이름 변경", "이름을 변경했습니다.") {
            repository.renameSillyTavernEntry(relativePath, newName)
        }
    }

    fun trashSillyTavernEntry(relativePath: String) =
        mutateTavernFiles("delete-st", "휴지통으로 이동", "휴지통으로 이동했습니다. 원본을 복구할 수 있도록 Termux에 보관했습니다.") {
            repository.trashSillyTavernEntry(relativePath).also { result ->
                if (result.isSuccess) {
                    val id = result.stdout.lineSequence().firstOrNull { it.startsWith("trash_id=") }?.substringAfter('=').orEmpty()
                    mutableState.update { it.copy(lastTrashedEntryId = id) }
                }
            }
        }

    fun undoLastTrashedEntry() {
        val id = state.value.lastTrashedEntryId.takeIf(String::isNotBlank) ?: return
        mutateTavernFiles("restore-st-trash", "휴지통 항목 복구", "휴지통의 항목을 원래 위치로 복구했습니다.") {
            repository.restoreSillyTavernEntry(id).also { result ->
                if (result.isSuccess) mutableState.update { it.copy(lastTrashedEntryId = "") }
            }
        }
    }

    fun importSillyTavernFile(uri: Uri) {
        val parent = state.value.fileBrowserPath
        mutateTavernFiles("import-st-file", "파일 가져오기", "파일을 가져왔습니다. 같은 이름의 기존 파일은 덮어쓰지 않습니다.") {
            repository.importSillyTavernFile(parent, uri)
        }
    }

    fun importSillyTavernFolder(uri: Uri) {
        val parent = state.value.fileBrowserPath
        mutateTavernFiles("import-st-folder", "폴더 가져오기", "하위 파일과 함께 폴더를 가져왔습니다. 원본 폴더는 그대로 유지됩니다.") {
            repository.importSillyTavernFolder(parent, uri)
        }
    }

    private fun checkEntryName(name: String): Boolean {
        if (validTavernEntryName(name)) return true
        mutableState.update { it.copy(fileBrowserError = "빈 이름, . 또는 .., 경로 구분자와 제어 문자는 사용할 수 없습니다.") }
        return false
    }

    private fun mutateTavernFiles(operation: String, label: String, success: String, block: suspend () -> TermuxCommandResult) {
        if (commandEntryBlocked()) return
        if (!state.value.canModifyTavernFiles()) {
            mutableState.update { it.copy(fileBrowserError = "파일을 변경하려면 서버와 다른 작업을 먼저 종료해 주세요.") }
            return
        }
        val started = System.currentTimeMillis()
        progressLogHistory.clear()
        mutableState.update { it.copy(fileBrowserMutating = true, isWorking = true, workingLabel = label,
            workingStartedAtMillis = started, fileBrowserError = "", fileBrowserNotice = "", workProgress = null) }
        val fileBrowserPath = state.value.fileBrowserPath
        viewModelScope.launch {
            val journal = LocalProgressJournal(operation, ::publishProgress)
            journal.phase("파일 작업 준비", "$label 요청을 Termux에 전달합니다.")
            val progressJob = pollOperationProgress(operation, started)
            var reconnecting = false
            try {
                cancellableOperation(operation, started) { block().requireSuccess() }
                progressJob.cancel()
                journal.completedItem(label)
                mutableState.update { it.copy(fileBrowserNotice = success,
                    lastOperationResult = operationSummary(operation, label, success, true, started)) }
                try {
                    journal.phase("폴더 목록 새로고침", "변경이 끝난 실제 SillyTavern 폴더 목록을 다시 읽고 있습니다.")
                    val entries = repository.listSillyTavernFiles(state.value.fileBrowserPath)
                    mutableState.update { it.copy(fileBrowserEntries = entries) }
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Exception) {
                    mutableState.update { it.copy(fileBrowserNotice = "$success 목록을 새로고침해 주세요.") }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                val detail = error.userMessage()
                if (errorCodeFrom(detail) == "TERMUX_TIMEOUT") {
                    try {
                        val environment = repository.inspect()
                        reconnecting = environment.operationActive
                        mutableState.update { it.copy(environment = environment) }
                    } catch (cancelled: CancellationException) { throw cancelled
                    } catch (_: Exception) { /* The backend also guards against concurrent writes. */ }
                }
                if (reconnecting) {
                    // A callback timeout is not a failed copy while Termux still owns the operation.
                    mutableState.update { it.copy(fileBrowserError = "",
                        fileBrowserNotice = "Termux 응답 대기 시간이 지나 진행 중인 파일 작업에 다시 연결하고 있습니다.") }
                } else {
                    mutableState.update { it.copy(fileBrowserError = detail,
                        lastOperationResult = operationSummary(operation, label, detail, false, started,
                            errorCode = errorCodeFrom(detail))) }
                }
            } finally {
                progressJob.cancel()
                mutableState.update { it.copy(fileBrowserMutating = reconnecting, isWorking = reconnecting,
                    workingLabel = if (reconnecting) "진행 중인 파일 작업에 다시 연결 중" else "",
                    workProgress = if (reconnecting) it.workProgress else null) }
                if (reconnecting) resumeDetachedOperation(fileBrowserPath, started)
            }
        }
    }

    fun openSillyTavernFile(relativePath: String) {
        if (!repository.openSillyTavernFile(relativePath)) {
            val message = "이 파일을 열 수 있는 앱을 찾지 못했습니다."
            mutableState.update { if (it.fileBrowserOpen) it.copy(fileBrowserError = message) else it.copy(error = message) }
        }
    }

    fun showMessage(message: String) {
        mutableState.update { it.copy(message = message) }
    }

    fun retryLastOperation() {
        if (state.value.isWorking) return
        when (state.value.lastOperationResult?.retryAction ?: RetryAction.NONE) {
            RetryAction.NONE -> mutableState.update { it.copy(error = "이 작업은 입력값이나 확인이 필요해 자동으로 다시 실행할 수 없습니다.") }
            RetryAction.REFRESH -> refresh()
            RetryAction.CONNECT_MANAGER -> connectManager()
            RetryAction.INSTALL_RELEASE -> {
                selectInstallBranch(SillyBranch.RELEASE)
                install()
            }
            RetryAction.INSTALL_STAGING -> {
                selectInstallBranch(SillyBranch.STAGING)
                install()
            }
            RetryAction.START -> start()
            RetryAction.STOP -> stop()
            RetryAction.RESTART -> restart()
            RetryAction.CHECK_UPDATE -> checkForUpdates()
            RetryAction.BACKUP -> {
                val request = state.value.lastOperationResult?.backupRequest
                if (request == null) {
                    mutableState.update { it.copy(section = MainSection.SETUP, managementPanel = ManagementPanel.BACKUP,
                        error = null, message = "이전 백업의 선택 정보가 없습니다. 백업할 항목을 다시 선택해 주세요.") }
                    loadBackups()
                } else createBackup(request.categories, request.includeSecrets, request.customFolders)
            }
            RetryAction.REPAIR -> repair()
            RetryAction.DIAGNOSE -> runDiagnostics()
        }
    }

    fun onCommandPermissionResult(granted: Boolean) {
        if (!state.value.hasInstallationHistory) {
            refreshAfterResume()
            return
        }
        if (!granted) mutableState.update { it.finishTermuxSetupCheck(
            it.environment.copy(commandPermissionGranted = false, managerConnected = false),
            TermuxSetupState(TermuxSetupStatus.PERMISSION_REQUIRED,
                "Android 설정에서 런처의 ‘Termux 환경에서 명령 실행’ 권한을 허용해 주세요."),
        ) }
        viewModelScope.launch {
            refreshJob?.join()
            refresh()
        }
    }

    private fun commandEntryBlocked(requireManager: Boolean = true, allowWhileWorking: Boolean = false): Boolean {
        if (state.value.backupTransfer != null) return true
        if ((state.value.isWorking && !allowWhileWorking) || refreshJob?.isActive == true) return true
        val current = state.value
        if (!current.termuxSetup.verified || !current.environment.termuxInstalled ||
            !current.environment.commandPermissionGranted || (requireManager && !current.environment.managerConnected)) {
            mutableState.update { it.copy(section = MainSection.SETUP) }
            verifyTermuxSetup()
            return true
        }
        return false
    }

    fun consumeNotice() {
        mutableState.update { it.copy(message = null, error = null) }
    }

    private fun publishProgress(progress: WorkProgress) {
        val merged = progressLogHistory.merge(progress)
        mutableState.update { it.copy(workProgress = merged) }
    }

    private suspend fun reconnectTimedOutOperation(errorCode: String, operation: String, startedAtMillis: Long): Boolean {
        if (errorCode != "TERMUX_TIMEOUT") return false
        val environment = try {
            repository.inspect()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return false
        }
        if (!environment.operationActive) {
            mutableState.update { it.copy(environment = environment) }
            return false
        }
        mutableState.update { it.reconnectingAfterTimeout(environment, operation) }
        resumeDetachedOperation(startedAtMillis = startedAtMillis, requestedOperation = operation)
        return true
    }

    private fun CoroutineScope.pollOperationProgress(operation: String, startedAtMillis: Long): Job = launch {
        while (isActive) {
            if (appInForeground) {
                try {
                    val progress = repository.readProgress()
                    if (appInForeground && isActive && progress?.belongsToStartedOperation(operation, startedAtMillis) == true) {
                        publishProgress(progress)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Keep the last measured snapshot and its original timestamps on missed polls.
                    // The main command remains authoritative; a read failure does not mean it stopped.
                }
            }
            delay(1_000)
        }
    }

    private fun perform(
        operation: String,
        label: String,
        successMessage: String,
        successMessageForResult: (TermuxCommandResult) -> String = { successMessage },
        retryAction: RetryAction = RetryAction.NONE,
        backupRequest: BackupRequest? = null,
        block: suspend () -> TermuxCommandResult,
    ) {
        if (commandEntryBlocked(requireManager = operation != "connect-manager")) return
        operationCancellationRequested = false
        viewModelScope.launch {
            val operationStartedAt = System.currentTimeMillis()
            progressLogHistory.clear()
            mutableState.update {
                it.copy(isWorking = true, workingLabel = label,
                    workingStartedAtMillis = operationStartedAt, workProgress = null)
            }
            LocalProgressJournal(operation, ::publishProgress).phase(
                "작업 요청 준비", "$label 요청을 Termux에 전달하고 있습니다.")
            val progressJob = pollOperationProgress(operation, operationStartedAt)
            var committedResult: OperationResultSummary? = null
            try {
                val result = cancellableOperation(operation, operationStartedAt, backupRequest) { block() }
                if (!result.isSuccess && operationCancellationRequested && result.exitCode == 130) {
                    progressJob.cancel()
                    val environment = runCatching { repository.inspect() }.getOrElse { state.value.environment }
                    mutableState.update {
                        it.copy(
                            environment = environment,
                            isWorking = false,
                            workingLabel = "",
                            workProgress = null,
                            message = "작업을 안전하게 중단했습니다.",
                            lastOperationResult = operationSummary(
                                operation = operation,
                                label = label,
                                detail = "사용자가 작업을 안전하게 중단했습니다.",
                                succeeded = false,
                                startedAtMillis = operationStartedAt,
                                errorCode = "OPERATION_CANCELLED",
                                backupRequest = backupRequest,
                            ),
                        )
                    }
                    return@launch
                }
                if (!result.isSuccess) throw IllegalStateException(result.readableError())
                // Persist the terminal command result before any optional reads or animation.
                // Process death/cancellation during refresh must not erase that success.
                var resolvedSuccessMessage = try { successMessageForResult(result) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { successMessage }
                val completed = operationSummary(
                    operation, label, resolvedSuccessMessage, true, operationStartedAt,
                    backupRequest = backupRequest,
                )
                committedResult = completed
                mutableState.update { it.copy(lastOperationResult = completed) }
                if (!appInForeground) {
                    mutableState.update {
                        it.copy(
                            isWorking = false,
                            workingLabel = "",
                            workProgress = null,
                            message = "$resolvedSuccessMessage 앱으로 돌아오면 설치 상태를 다시 확인합니다.",
                            lastOperationResult = completed,
                        )
                    }
                    return@launch
                }
                progressJob.cancel()
                val check = checkCompletedOperation(state.value.environment to state.value.backgroundStatus) {
                    val refreshed = repository.inspect(::publishProgress, operation)
                    refreshed to repository.backgroundStatus(refreshed.processRunning)
                }
                if (check.refreshFailed) resolvedSuccessMessage += " 작업은 완료했지만 상태를 갱신하지 못했습니다. 새로고침해 주세요."
                val (environment, backgroundStatus) = check.value
                val refreshedResult = completed.copy(detail = resolvedSuccessMessage).also(operationResultStore::save)
                committedResult = refreshedResult
                mutableState.update {
                    it.copy(
                        environment = environment,
                        backgroundStatus = backgroundStatus,
                        section = if (environment.sillyTavernInstalled) MainSection.HOME else it.section,
                        isWorking = true,
                        workingLabel = "작업 완료",
                        workProgress = completionProgress(it.workProgress, resolvedSuccessMessage),
                    )
                }
                delay(COMPLETION_ANIMATION_MILLIS)
                mutableState.update {
                    it.copy(
                        isWorking = false,
                        workingLabel = "",
                        workProgress = null,
                        message = resolvedSuccessMessage,
                        lastOperationResult = refreshedResult,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val completed = committedResult
                if (completed != null) {
                    // A later UI-only failure cannot turn an acknowledged write into a retry.
                    mutableState.update { it.copy(
                        isWorking = false, workingLabel = "", workProgress = null,
                        message = "${completed.detail} 작업은 완료했습니다. 상태를 다시 확인해 주세요.",
                        lastOperationResult = completed,
                    ) }
                    return@launch
                }
                val backupProgress = if (operation == "backup" && backupRequest != null) {
                    val current = try { repository.readProgress() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                    sequenceOf(current, state.value.workProgress).firstOrNull {
                        BackupRequestCodec.matching(backupRequest, it) != null
                    }
                } else null
                val diagnostic = if (operation == "backup") backupFailureDetail(error.userMessage(), backupProgress?.logText.orEmpty())
                    else error.userMessage()
                val errorCode = errorCodeFrom(diagnostic)
                if (reconnectTimedOutOperation(errorCode, operation, operationStartedAt)) return@launch
                // Rollback can succeed even when a subsequent repair step fails.
                // Conversely a failed restore may now have a retained journal.
                // Refresh read-only state without replacing the actual error.
                val recoveryEnvironment = if (state.value.environment.recoveryPending ||
                    errorCode == "RESTORE_ROLLBACK_REQUIRED" ||
                    operation in setOf("restore", "import-backup", "import-install", "repair")) {
                    progressJob.cancel()
                    try { repository.inspect() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                } else null
                mutableState.update {
                    it.copy(
                        environment = recoveryEnvironment ?: it.environment,
                        backgroundStatus = recoveryEnvironment?.let { current ->
                            repository.backgroundStatus(current.processRunning)
                        } ?: it.backgroundStatus,
                        isWorking = false,
                        workingLabel = "",
                        workProgress = null,
                        error = diagnostic,
                        lastOperationResult = operationSummary(
                            operation = operation,
                            label = label,
                            detail = diagnostic.substringAfter(']', diagnostic).trim(),
                            succeeded = false,
                            startedAtMillis = operationStartedAt,
                            errorCode = errorCode,
                            retryAction = retryActionAfterFailure(errorCode, retryAction),
                            backupRequest = backupRequest,
                        ),
                        logs = if (operation == "backup") backupFailureLog(diagnostic, backupProgress?.logText.orEmpty())
                            else "최근 작업 실패\n\n$diagnostic",
                    )
                }
            } finally {
                if (!state.value.isWorking) {
                    operationCancellationRequested = false
                    cancellationRequestJob?.cancel()
                    mutableState.update { it.copy(cancellationRequested = false) }
                }
                progressJob.cancel()
            }
        }
    }

    private fun operationSummary(
        operation: String,
        label: String,
        detail: String,
        succeeded: Boolean,
        startedAtMillis: Long,
        errorCode: String = "",
        retryAction: RetryAction = RetryAction.NONE,
        backupRequest: BackupRequest? = null,
    ): OperationResultSummary {
        val completedAtMillis = System.currentTimeMillis()
        return OperationResultSummary(
            operation = operation,
            title = label.removeSuffix(" 중"),
            detail = detail,
            succeeded = succeeded,
            errorCode = errorCode,
            completedAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA).format(Date(completedAtMillis)),
            completedAtMillis = completedAtMillis,
            durationSeconds = ((completedAtMillis - startedAtMillis) / 1_000).coerceAtLeast(0),
            retryAction = retryAction,
            backupRequest = backupRequest,
        ).also(operationResultStore::save)
    }

    private fun operationLabel(operation: String): String = when (operation) {
        "connect-manager" -> "런처 연결"
        "install" -> "SillyTavern 설치"
        "start" -> "SillyTavern 시작"
        "stop" -> "SillyTavern 종료"
        "restart" -> "SillyTavern 재시작"
        "update", "update-preflight" -> "SillyTavern 업데이트"
        "switch-branch" -> "브랜치 변경"
        "backup" -> "백업 생성"
        "import-backup" -> "ZIP에서 바로 복원"
        "import-install" -> "기존 설치 가져오기"
        "write-st-file" -> "파일 저장"
        "mkdir-st" -> "폴더 만들기"
        "create-st-file" -> "파일 만들기"
        "rename-st" -> "이름 변경"
        "delete-st" -> "휴지통으로 이동"
        "import-st-file" -> "파일 가져오기"
        "import-st-folder" -> "폴더 가져오기"
        "restore-st-trash" -> "휴지통 항목 복구"
        "restore" -> "백업 복원"
        "delete-backup" -> "백업 삭제"
        "repair" -> "설치 점검·복구"
        "reset-installation" -> "SillyTavern 초기화"
        "diagnose" -> "전체 환경 진단"
        else -> "런처 작업"
    }

    private fun retryActionFor(operation: String): RetryAction = when (operation) {
        "connect-manager" -> RetryAction.CONNECT_MANAGER
        "start" -> RetryAction.START
        "stop" -> RetryAction.STOP
        "restart" -> RetryAction.RESTART
        "update", "update-preflight" -> RetryAction.CHECK_UPDATE
        "backup" -> RetryAction.BACKUP
        "repair" -> RetryAction.REPAIR
        "diagnose" -> RetryAction.DIAGNOSE
        else -> RetryAction.NONE
    }

    private fun errorCodeFrom(message: String): String =
        Regex("""^\[([A-Z0-9_]+)]""").find(message)?.groupValues?.get(1).orEmpty()

    private fun TermuxCommandResult.requireSuccess() {
        if (!isSuccess) throw IllegalStateException(readableError())
    }

    private fun completionProgress(previous: WorkProgress?, message: String) = WorkProgress(
        percent = 100,
        phase = "완료",
        detail = message,
        status = "success",
        progressMode = "complete",
        operation = previous?.operation ?: "complete",
        logText = previous?.logText.orEmpty(),
    )

    private companion object {
        const val COMPLETION_ANIMATION_MILLIS = 1_000L
    }

    private fun Throwable.userMessage(): String = operationErrorSummary(message)
}
