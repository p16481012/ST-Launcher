package app.tavernbridge.launcher

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.tavernbridge.launcher.data.LauncherRepository
import app.tavernbridge.launcher.data.OperationResultStore
import app.tavernbridge.launcher.data.ThemePreferences
import app.tavernbridge.launcher.model.AppTheme
import app.tavernbridge.launcher.model.BackupCategory
import app.tavernbridge.launcher.model.DiagnosticPanel
import app.tavernbridge.launcher.model.LauncherUiState
import app.tavernbridge.launcher.model.MainSection
import app.tavernbridge.launcher.model.OperationResultSummary
import app.tavernbridge.launcher.model.RetryAction
import app.tavernbridge.launcher.model.ManagementPanel
import app.tavernbridge.launcher.model.SettingsPanel
import app.tavernbridge.launcher.model.SillyBranch
import app.tavernbridge.launcher.model.WorkProgress
import app.tavernbridge.launcher.model.RecoveredOperationStatus
import app.tavernbridge.launcher.model.recoveredOperationStatus
import app.tavernbridge.launcher.model.shouldRestoreUpdateServer
import app.tavernbridge.launcher.model.retryActionAfterFailure
import app.tavernbridge.launcher.termux.TermuxCommandResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LauncherViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = LauncherRepository(application)
    private val operationResultStore = OperationResultStore(application)
    private val themePreferences = ThemePreferences(application)
    private val mutableState = MutableStateFlow(
        LauncherUiState(
            theme = themePreferences.loadTheme(),
            configuredPort = repository.configuredPort(),
            autoBackupBeforeUpdate = repository.autoBackupBeforeUpdate(),
            backgroundStatus = repository.backgroundStatus(),
            lastOperationResult = operationResultStore.load(),
        ),
    )
    val state = mutableState.asStateFlow()
    private var detachedOperationMonitor: Job? = null
    private var logRefreshJob: Job? = null
    private var refreshJob: Job? = null
    @Volatile private var operationCancellationRequested = false
    @Volatile private var appInForeground = false

    val setupCommand: String get() = repository.setupCommand()
    val storageSetupCommand: String get() = repository.storageSetupCommand()

    fun onAppForegrounded() {
        appInForeground = true
        mutableState.update {
            it.copy(backgroundStatus = repository.backgroundStatus(it.environment.processRunning))
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

    fun setWakeLockEnabled(enabled: Boolean) {
        if (state.value.isWorking) return
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
            mutableState.update { it.copy(isWorking = true, workingLabel = "서버 연결 설정 저장 중") }
            try {
                val result = repository.saveServerConnectionSettings(port, externalAccessEnabled, whitelist)
                if (!result.isSuccess) throw IllegalStateException(result.readableError())
                val environment = repository.inspect()
                mutableState.update {
                    it.copy(
                        configuredPort = port,
                        environment = environment,
                        isWorking = false,
                        workingLabel = "",
                        message = "서버 연결 설정을 저장했습니다. 다음 서버 시작부터 적용됩니다.",
                    )
                }
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(isWorking = false, workingLabel = "", error = error.userMessage())
                }
            }
        }
    }

    fun refresh() {
        if (!appInForeground) return
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            mutableState.update { it.copy(isWorking = true, workingLabel = "환경 확인 중") }
            try {
                val environment = repository.inspect()
                if (environment.processRunning && repository.wakeLockEnabled()) {
                    repository.setTermuxWakeLock(true).requireSuccess()
                }
                val backupStorageReady = if (environment.managerConnected && environment.sillyTavernInstalled) {
                    runCatching { repository.backupStorageReady() }.getOrNull()
                } else {
                    null
                }
                val progress = if (environment.commandPermissionGranted) {
                    repository.readProgress()
                } else {
                    null
                }
                val recoveredTerminal = !environment.operationActive &&
                    progress?.status in setOf("success", "error", "cancelled") &&
                    (progress?.finishedAtMillis ?: 0L) > (state.value.lastOperationResult?.completedAtMillis ?: 0L)
                val recoveredFailure = recoveredTerminal && progress?.status != "success"
                val recoveredLogs = if (recoveredFailure) {
                    try {
                        repository.logs().stdout.ifBlank { state.value.logs }
                    } catch (_: Exception) {
                        state.value.logs
                    }
                } else {
                    state.value.logs
                }
                val recoveredSummary = if (recoveredTerminal && progress != null) {
                    operationSummary(
                        operation = progress.operation,
                        label = operationLabel(progress.operation),
                        detail = progress.detail.ifBlank {
                            if (recoveredFailure) "작업이 실패했습니다. 로그를 확인해 주세요." else "작업을 완료했습니다."
                        },
                        succeeded = !recoveredFailure,
                        startedAtMillis = progress.finishedAtMillis,
                        errorCode = progress.errorCode,
                        retryAction = if (recoveredFailure) retryActionFor(progress.operation) else RetryAction.NONE,
                    )
                } else {
                    state.value.lastOperationResult
                }
                val initialSection = if (!environment.sillyTavernInstalled) MainSection.SETUP else state.value.section
                mutableState.update {
                    it.copy(
                        environment = environment,
                        environmentChecked = true,
                        termuxWakeBlocked = false,
                        section = initialSection,
                        isWorking = environment.operationActive,
                        workingLabel = progress?.phase.orEmpty(),
                        workProgress = if (environment.operationActive) progress else null,
                        error = if (recoveredFailure) progress?.detail else it.error,
                        message = if (recoveredTerminal && !recoveredFailure) "앱이 종료된 동안 완료된 작업 결과를 복원했습니다." else it.message,
                        lastOperationResult = recoveredSummary,
                        logs = recoveredLogs,
                        backupStorageReady = backupStorageReady ?: it.backupStorageReady,
                        backgroundStatus = repository.backgroundStatus(environment.processRunning),
                    )
                }
                if (environment.operationActive) resumeDetachedOperation()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
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
                mutableState.update {
                    it.copy(
                        environment = if (wakeBlocked) repository.baseStatus() else it.environment,
                        isWorking = false,
                        workingLabel = "",
                        termuxWakeBlocked = wakeBlocked,
                        error = "환경 확인에 실패했습니다. 기존 설치 상태는 변경하지 않았습니다.\n$diagnostic",
                        lastOperationResult = summary,
                        logs = "최근 작업 실패\n\n$diagnostic",
                    )
                }
            }
        }
    }

    fun refreshSilently() {
        if (!appInForeground || state.value.isWorking) return
        viewModelScope.launch {
            try {
                val environment = repository.inspect()
                mutableState.update {
                    it.copy(
                        environment = environment,
                        environmentChecked = true,
                        termuxWakeBlocked = false,
                        section = if (!environment.sillyTavernInstalled) MainSection.SETUP else it.section,
                        backgroundStatus = repository.backgroundStatus(environment.processRunning),
                    )
                }
            } catch (_: Exception) {
                // A later background check will retry without interrupting the user.
            }
        }
    }

    fun refreshAfterResume() {
        if (state.value.environmentChecked) {
            refreshSilently()
        } else {
            refresh()
        }
    }

    private fun resumeDetachedOperation() {
        if (detachedOperationMonitor?.isActive == true) return
        detachedOperationMonitor = viewModelScope.launch {
            while (true) {
                delay(1_500)
                if (!appInForeground) continue
                val progress = try {
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
                    mutableState.update {
                        it.copy(
                            environment = environment,
                            isWorking = true,
                            workingLabel = progress?.phase ?: it.workingLabel,
                            workProgress = progress ?: it.workProgress,
                        )
                    }
                    continue
                }

                val recoveredStatus = recoveredOperationStatus(progress?.status)
                val failed = recoveredStatus != RecoveredOperationStatus.SUCCESS
                val interrupted = recoveredStatus == RecoveredOperationStatus.INTERRUPTED
                val latestLogs = if (failed) {
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
                val resultDetail = when {
                    interrupted -> "작업이 중단되어 완료 여부를 확인할 수 없습니다. 설치 상태와 로그를 확인해 주세요."
                    failed -> progress?.detail?.ifBlank { "작업이 실패했습니다. 로그를 확인해 주세요." }
                        ?: "작업이 실패했습니다. 로그를 확인해 주세요."
                    else -> "진행 중이던 작업을 완료했습니다."
                }
                val recoveredSummary = operationSummary(
                    operation = operation,
                    label = operationLabel(operation),
                    detail = resultDetail,
                    succeeded = !failed,
                    startedAtMillis = System.currentTimeMillis(),
                    errorCode = if (interrupted) "OPERATION_INTERRUPTED" else progress?.errorCode.orEmpty(),
                    retryAction = if (failed && !interrupted) retryActionFor(operation) else RetryAction.NONE,
                )
                mutableState.update {
                    if (failed) {
                        it.copy(
                            environment = environment,
                            isWorking = false,
                            workingLabel = "",
                            workProgress = null,
                            error = resultDetail,
                            logs = latestLogs,
                            lastOperationResult = recoveredSummary,
                        )
                    } else {
                        it.copy(
                            environment = environment,
                            section = if (environment.sillyTavernInstalled) MainSection.HOME else it.section,
                            isWorking = true,
                            workingLabel = "작업 완료",
                            workProgress = completionProgress(progress, "진행 중이던 작업을 완료했습니다."),
                            error = null,
                            logs = latestLogs,
                            lastOperationResult = recoveredSummary,
                        )
                    }
                }
                if (!failed) {
                    delay(COMPLETION_ANIMATION_MILLIS)
                    mutableState.update {
                        it.copy(
                            isWorking = false,
                            workingLabel = "",
                            workProgress = null,
                            message = "진행 중이던 작업을 완료했습니다.",
                        )
                    }
                }
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
        val operation = state.value.workProgress?.operation
        if (operation !in setOf("install", "start")) {
            mutableState.update { it.copy(error = "현재 단계는 데이터 보호를 위해 중간에 중단할 수 없습니다.") }
            return
        }
        if (operationCancellationRequested) return
        operationCancellationRequested = true
        mutableState.update {
            it.copy(
                workProgress = it.workProgress?.copy(
                    phase = "중단 요청 중",
                    detail = "현재 단계를 정리한 뒤 안전하게 중단합니다.",
                ),
            )
        }
        viewModelScope.launch {
            try {
                val result = repository.cancelOperation()
                if (!result.isSuccess) throw IllegalStateException(result.readableError())
            } catch (error: Exception) {
                operationCancellationRequested = false
                mutableState.update { it.copy(error = "작업 중단을 요청하지 못했습니다.\n${error.userMessage()}") }
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
                if (result.stdout.lineSequence().any { it == "updated=0" }) {
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
                    ).requireSuccess()
                    refreshBackupListAfterOperation()
                }
                updateInvoked = true
                val result = repository.update(
                    keepRunning = wasRunning,
                    allowModifiedFiles = state.value.updatePreflight?.modifiedFiles?.isNotEmpty() == true,
                )
                val updateRecord = readUpdateRecordSafely()
                mutableState.update { it.copy(lastUpdateRecord = updateRecord ?: it.lastUpdateRecord) }
                result.requireSuccess()
                mutableState.update { it.copy(updatePreflight = null) }
                result
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
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
        if (state.value.isWorking) return
        viewModelScope.launch {
            mutableState.update { it.copy(isWorking = true, workingLabel = "업데이트 안전 검사 중", workProgress = null) }
            val progressJob = launch {
                while (isActive) {
                    if (appInForeground) {
                        runCatching { repository.readProgress() }.getOrNull()?.let { progress ->
                            if (progress.operation == "update-preflight") {
                                mutableState.update { it.copy(workProgress = progress) }
                            }
                        }
                    }
                    delay(700)
                }
            }
            try {
                val report = repository.checkUpdate()
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
                val summary = operationSummary(
                    operation = "update-preflight",
                    label = "업데이트 안전 검사 중",
                    detail = diagnostic,
                    succeeded = false,
                    startedAtMillis = System.currentTimeMillis(),
                    errorCode = errorCodeFrom(diagnostic),
                    retryAction = retryActionAfterFailure(errorCodeFrom(diagnostic), RetryAction.CHECK_UPDATE),
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
                startServerWithWakeLock().requireSuccess()
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

    fun backup() = perform(
        operation = "backup",
        label = "백업 생성 중",
        successMessage = "다운로드 폴더에 백업을 생성했습니다.",
        retryAction = RetryAction.BACKUP,
    ) { repository.backup() }

    fun loadBackups() {
        if (!state.value.environment.managerConnected) return
        viewModelScope.launch {
            mutableState.update { it.copy(backupsLoaded = false) }
            try {
                val storageReady = repository.backupStorageReady()
                val backups = repository.listBackups()
                val folders = repository.listUserBackupFolders()
                val updateRecord = repository.lastUpdateRecord()
                mutableState.update {
                    it.copy(
                        backups = backups,
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

    fun importBackup(uri: Uri) {
        if (state.value.environment.processRunning) {
            mutableState.update { it.copy(error = "백업 파일을 가져오려면 먼저 SillyTavern 서버를 종료해 주세요.") }
            return
        }
        perform(
            operation = "import-backup",
            label = "백업 파일 가져오는 중",
            successMessage = "선택한 ZIP을 검사해 복원 가능한 백업으로 등록했습니다.",
        ) {
            val result = repository.importBackup(uri)
            if (result.isSuccess) refreshBackupListAfterOperation()
            result
        }
    }

    fun createBackup(
        categories: Set<BackupCategory>,
        includeSecrets: Boolean,
        customFolders: Set<String>,
    ) {
        val normalized = if (BackupCategory.FULL in categories) setOf(BackupCategory.FULL) else categories
        if (normalized.isEmpty() || (normalized == setOf(BackupCategory.CUSTOM) && customFolders.isEmpty())) {
            mutableState.update { it.copy(error = "백업할 항목을 하나 이상 선택해 주세요.") }
            return
        }
        if (state.value.environment.processRunning) {
            mutableState.update { it.copy(error = "일관된 백업을 위해 먼저 SillyTavern 서버를 종료해 주세요.") }
            return
        }
        perform(
            operation = "backup",
            label = "선택 백업 생성 중",
            successMessage = "검증된 ZIP 백업을 휴대폰 Download 폴더에 저장했습니다.",
        ) {
            val result = repository.createBackup(normalized, includeSecrets, customFolders)
            if (result.isSuccess) refreshBackupListAfterOperation()
            result
        }
    }

    fun restoreBackup(fileName: String) {
        if (state.value.environment.processRunning) {
            mutableState.update { it.copy(error = "안전한 복원을 위해 먼저 SillyTavern 서버를 종료해 주세요.") }
            return
        }
        perform(
            operation = "restore",
            label = "백업 복원 중",
            successMessage = "백업 복원을 완료했습니다. 기존 상태는 임시 보호 후 교체했습니다.",
        ) {
            val result = repository.restoreBackup(fileName)
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
            val backups = repository.listBackups()
            mutableState.update { it.copy(backups = backups, backupsLoaded = true) }
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

    fun loadLogs() {
        if (state.value.isWorking) return
        viewModelScope.launch {
            mutableState.update { it.copy(isWorking = true, workingLabel = "로그 불러오는 중") }
            try {
                val result = repository.logs()
                if (!result.isSuccess) throw IllegalStateException(result.readableError())
                mutableState.update {
                    it.copy(
                        logs = result.stdout.ifBlank { "표시할 로그가 없습니다." },
                        isWorking = false,
                        workingLabel = "",
                    )
                }
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(isWorking = false, workingLabel = "", error = error.userMessage())
                }
            }
        }
    }

    fun refreshLogsSilently() {
        if (!appInForeground || state.value.isWorking || !state.value.environment.managerConnected || logRefreshJob?.isActive == true) return
        logRefreshJob = viewModelScope.launch {
            try {
                val result = repository.logs()
                if (result.isSuccess) {
                    mutableState.update {
                        it.copy(logs = result.stdout.ifBlank { "아직 기록된 서버 로그가 없습니다." })
                    }
                }
            } catch (_: Exception) {
                // Keep the last visible server log and retry on the next poll.
            }
        }
    }

    fun loadPreviousServerLogs() {
        if (state.value.isWorking) return
        viewModelScope.launch {
            mutableState.update { it.copy(isWorking = true, workingLabel = "이전 서버 로그 불러오는 중") }
            try {
                val result = repository.previousServerLogs()
                if (!result.isSuccess) throw IllegalStateException(result.readableError())
                mutableState.update {
                    it.copy(
                        previousServerLogs = result.stdout.ifBlank { "아직 보관된 이전 서버 로그가 없습니다." },
                        isWorking = false,
                        workingLabel = "",
                    )
                }
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(isWorking = false, workingLabel = "", error = error.userMessage())
                }
            }
        }
    }

    fun loadOperationHistory() {
        if (state.value.isWorking) return
        viewModelScope.launch {
            try {
                val result = repository.operationHistory()
                if (!result.isSuccess) throw IllegalStateException(result.readableError())
                mutableState.update {
                    it.copy(operationHistory = result.stdout.ifBlank { "아직 기록된 런처 작업이 없습니다." })
                }
            } catch (error: Exception) {
                mutableState.update { it.copy(error = error.userMessage()) }
            }
        }
    }

    fun runDiagnostics() {
        if (state.value.isWorking) return
        viewModelScope.launch {
            val operationStartedAt = System.currentTimeMillis()
            mutableState.update {
                it.copy(
                    section = MainSection.LOGS,
                    diagnosticPanel = DiagnosticPanel.OVERVIEW,
                    isWorking = true,
                    workingLabel = "전체 환경 진단 중",
                    workProgress = null,
                )
            }
            val progressJob = launch {
                while (isActive) {
                    if (!appInForeground) {
                        delay(700)
                        continue
                    }
                    try {
                        repository.readProgress()?.let { progress ->
                            if (progress.operation == "diagnose") {
                                mutableState.update { it.copy(workProgress = progress) }
                            }
                        }
                    } catch (_: Exception) {
                        // A missed progress update does not invalidate the final report.
                    }
                    delay(700)
                }
            }
            try {
                val report = repository.diagnose()
                val finalProgress = try {
                    repository.readProgress()
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
                val summary = operationSummary(
                    operation = "diagnose",
                    label = "전체 환경 진단 중",
                    detail = diagnostic,
                    succeeded = false,
                    startedAtMillis = operationStartedAt,
                    errorCode = errorCodeFrom(diagnostic),
                    retryAction = RetryAction.DIAGNOSE,
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
    fun openServerLogTerminal() = repository.openServerLogTerminal()
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
        viewModelScope.launch {
            mutableState.update {
                it.copy(
                    fileBrowserOpen = true,
                    fileBrowserPath = relativePath,
                    fileBrowserLoading = true,
                    fileBrowserError = "",
                )
            }
            try {
                val entries = repository.listSillyTavernFiles(relativePath)
                mutableState.update {
                    it.copy(fileBrowserEntries = entries, fileBrowserLoading = false)
                }
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(fileBrowserEntries = emptyList(), fileBrowserLoading = false, fileBrowserError = error.userMessage())
                }
            }
        }
    }

    fun closeSillyTavernFolder() {
        mutableState.update { it.copy(fileBrowserOpen = false, fileBrowserEntries = emptyList(), fileBrowserError = "") }
    }

    fun openSillyTavernFile(relativePath: String) {
        if (!repository.openSillyTavernFile(relativePath)) {
            mutableState.update { it.copy(error = "이 파일을 열 수 있는 앱을 찾지 못했습니다.") }
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
            RetryAction.BACKUP -> backup()
            RetryAction.REPAIR -> repair()
            RetryAction.DIAGNOSE -> runDiagnostics()
        }
    }

    fun onCommandPermissionResult(granted: Boolean) {
        if (!granted) {
            mutableState.update {
                it.copy(
                    error = "권한이 허용되지 않았습니다. ‘설정에서 열기’를 눌러 권한 → 추가 권한에서 Termux 명령 실행을 허용해 주세요.",
                )
            }
        }
        refresh()
    }

    fun consumeNotice() {
        mutableState.update { it.copy(message = null, error = null) }
    }

    private fun perform(
        operation: String,
        label: String,
        successMessage: String,
        successMessageForResult: (TermuxCommandResult) -> String = { successMessage },
        retryAction: RetryAction = RetryAction.NONE,
        block: suspend () -> TermuxCommandResult,
    ) {
        if (state.value.isWorking) return
        operationCancellationRequested = false
        viewModelScope.launch {
            val operationStartedAt = System.currentTimeMillis()
            mutableState.update {
                it.copy(isWorking = true, workingLabel = label, workProgress = null)
            }
            val progressJob = launch {
                while (isActive) {
                    if (!appInForeground) {
                        delay(1_000)
                        continue
                    }
                    try {
                        repository.readProgress()?.let { progress ->
                            mutableState.update { it.copy(workProgress = progress) }
                        }
                    } catch (_: Exception) {
                        // The main command remains authoritative if a progress poll is missed.
                    }
                    delay(1_000)
                }
            }
            try {
                val result = block()
                val resolvedSuccessMessage = successMessageForResult(result)
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
                            ),
                        )
                    }
                    return@launch
                }
                if (!result.isSuccess) throw IllegalStateException(result.readableError())
                if (!appInForeground) {
                    mutableState.update {
                        it.copy(
                            isWorking = false,
                            workingLabel = "",
                            workProgress = null,
                            message = "$resolvedSuccessMessage 앱으로 돌아오면 설치 상태를 다시 확인합니다.",
                            lastOperationResult = operationSummary(
                                operation, label, resolvedSuccessMessage, true, operationStartedAt,
                            ),
                        )
                    }
                    return@launch
                }
                val environment = repository.inspect()
                val finalProgress = try {
                    repository.readProgress()
                } catch (_: Exception) {
                    state.value.workProgress
                }
                progressJob.cancel()
                mutableState.update {
                    it.copy(
                        environment = environment,
                        backgroundStatus = repository.backgroundStatus(environment.processRunning),
                        section = if (environment.sillyTavernInstalled) MainSection.HOME else it.section,
                        isWorking = true,
                        workingLabel = "작업 완료",
                        workProgress = completionProgress(finalProgress, resolvedSuccessMessage),
                    )
                }
                delay(COMPLETION_ANIMATION_MILLIS)
                mutableState.update {
                    it.copy(
                        isWorking = false,
                        workingLabel = "",
                        workProgress = null,
                        message = resolvedSuccessMessage,
                        lastOperationResult = operationSummary(
                            operation, label, resolvedSuccessMessage, true, operationStartedAt,
                        ),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val diagnostic = error.userMessage()
                val errorCode = errorCodeFrom(diagnostic)
                val timedOut = errorCode == "TERMUX_TIMEOUT"
                val checkedEnvironment = if (timedOut) {
                    // The RUN_COMMAND process may outlive our callback wait. Reconnect to
                    // its existing operation instead of enabling an immediate duplicate.
                    try {
                        repository.inspect()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                } else {
                    null
                }
                val stillRunning = checkedEnvironment?.operationActive == true
                mutableState.update {
                    it.copy(
                        environment = checkedEnvironment ?: it.environment,
                        isWorking = stillRunning,
                        workingLabel = if (stillRunning) "진행 중인 작업에 다시 연결 중" else "",
                        workProgress = if (stillRunning) it.workProgress else null,
                        error = diagnostic,
                        lastOperationResult = operationSummary(
                            operation = operation,
                            label = label,
                            detail = diagnostic.substringAfter(']', diagnostic).trim(),
                            succeeded = false,
                            startedAtMillis = operationStartedAt,
                            errorCode = errorCode,
                            retryAction = retryActionAfterFailure(errorCode, retryAction),
                        ),
                        logs = "최근 작업 실패\n\n$diagnostic",
                    )
                }
                if (stillRunning) resumeDetachedOperation()
            } finally {
                operationCancellationRequested = false
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
        "import-backup" -> "백업 가져오기"
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
        operation = previous?.operation ?: "complete",
        logText = previous?.logText.orEmpty(),
    )

    private companion object {
        const val COMPLETION_ANIMATION_MILLIS = 1_000L
    }

    private fun Throwable.userMessage(): String = message
        ?.lineSequence()
        ?.filter { it.isNotBlank() }
        ?.take(4)
        ?.joinToString("\n")
        ?.take(500)
        ?: "알 수 없는 오류가 발생했습니다."
}
