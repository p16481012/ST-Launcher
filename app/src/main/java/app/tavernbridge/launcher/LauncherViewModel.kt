package app.tavernbridge.launcher
import app.tavernbridge.launcher.model.canReadLiveServerLog
import app.tavernbridge.launcher.model.ChatScanPage
import app.tavernbridge.launcher.model.ChatDamage
import app.tavernbridge.launcher.model.ChatUndo
import app.tavernbridge.launcher.model.readyForStopContinuation
import kotlinx.coroutines.CompletableDeferred
import app.tavernbridge.launcher.model.ManagedExtension
import app.tavernbridge.launcher.model.ExtensionAction
import app.tavernbridge.launcher.model.ExtensionInventory
import app.tavernbridge.launcher.model.ExtensionUpdateMetadata
import app.tavernbridge.launcher.model.ExtensionCheckIssue
import app.tavernbridge.launcher.model.extensionUpdateReceipt
import app.tavernbridge.launcher.model.sameCheckTarget
import app.tavernbridge.launcher.model.mayRefreshVisibleExtensions
import app.tavernbridge.launcher.data.ExtensionVersionClient

import app.tavernbridge.launcher.model.reselectRoot

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.tavernbridge.launcher.data.LauncherRepository
import app.tavernbridge.launcher.data.LauncherReleaseClient
import app.tavernbridge.launcher.data.OperationResultStore
import app.tavernbridge.launcher.data.ThemePreferences
import app.tavernbridge.launcher.data.BackupReminderStore
import app.tavernbridge.launcher.data.BackupTransferController
import app.tavernbridge.launcher.model.BackupArchive
import app.tavernbridge.launcher.model.SafetyInventoryItem
import app.tavernbridge.launcher.model.TavernFileEntry
import app.tavernbridge.launcher.model.EncryptedBackupMode
import app.tavernbridge.launcher.model.EncryptedBackupPhase
import app.tavernbridge.launcher.model.EncryptedBackupState
import app.tavernbridge.launcher.model.withPausedServer
import app.tavernbridge.launcher.model.ServerResumeStatus
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
import app.tavernbridge.launcher.model.DiagnosticReport
import app.tavernbridge.launcher.model.withDiagnosticReport
import app.tavernbridge.launcher.model.ToolPage
import app.tavernbridge.launcher.model.visit
import app.tavernbridge.launcher.model.previousScreen
import app.tavernbridge.launcher.model.LauncherUiState
import app.tavernbridge.launcher.model.EnvironmentRefreshGate
import app.tavernbridge.launcher.model.WarmReturnWindow
import app.tavernbridge.launcher.model.allowsWarmProbe
import app.tavernbridge.launcher.model.probeWarmServer
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
import app.tavernbridge.launcher.data.PcZipStagingOwner
import app.tavernbridge.launcher.data.pcZipStagingOwner
import app.tavernbridge.launcher.model.OperationResultSummary
import app.tavernbridge.launcher.model.RetryAction
import app.tavernbridge.launcher.model.SettingsPanel
import app.tavernbridge.launcher.model.SillyBranch
import app.tavernbridge.launcher.model.WorkProgress
import app.tavernbridge.launcher.model.LocalProgressJournal
import app.tavernbridge.launcher.model.ProgressLogHistory
import app.tavernbridge.launcher.model.belongsToStartedOperation
import app.tavernbridge.launcher.model.closeOperationDialogsForResult
import app.tavernbridge.launcher.model.RecoveredOperationStatus
import app.tavernbridge.launcher.model.recoveredOperationStatus
import app.tavernbridge.launcher.model.recoveredSubstepNotice
import app.tavernbridge.launcher.model.reconnectingAfterTimeout
import app.tavernbridge.launcher.model.resolveWorkingStartedAtMillis
import app.tavernbridge.launcher.model.shouldRestoreUpdateServer
import app.tavernbridge.launcher.model.retryActionAfterFailure
import app.tavernbridge.launcher.model.readyForTermuxDiagnostic
import app.tavernbridge.launcher.model.canOfferRuntimeSync
import app.tavernbridge.launcher.model.canInspectResetImpact
import app.tavernbridge.launcher.model.SafetyDeleteReceipt
import app.tavernbridge.launcher.model.isUnfinishedDeleteStep
import app.tavernbridge.launcher.model.ExistingInstallation
import app.tavernbridge.launcher.model.InstallationImportMode
import app.tavernbridge.launcher.model.protectBeforeReplacement
import app.tavernbridge.launcher.model.canModifyTavernFiles
import app.tavernbridge.launcher.model.validTavernEntryName
import app.tavernbridge.launcher.termux.TermuxCommandResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
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
    private val pcZipScreen = MutableStateFlow<app.tavernbridge.launcher.data.PcZipPreview?>(null)
    internal val pcZipPreview = pcZipScreen.asStateFlow()
    private var pcZipFile: app.tavernbridge.launcher.data.SharedImportStaging.Transfer? = null
    private var pcZipGeneration = 0L
    internal val dataLibrary get() = repository.dataLibrary
    internal fun runLibrary(action: app.tavernbridge.launcher.data.LibraryWrite) {
        perform(operation = action.operation, label = action.label, successMessage = action.label,
            successMessageForResult = { maintenanceMessage(action.message(it), it) }) {
            if (action.pause) maintainServer(action.execute) else action.execute()
        }
    }
    private val operationResultStore = OperationResultStore(application)
    private val guidePreferences = application.getSharedPreferences("setup_navigation", android.content.Context.MODE_PRIVATE)
    private val diagnosticPreferences = application.getSharedPreferences("diagnostic_attention", android.content.Context.MODE_PRIVATE)
    private val safetyDeleteStore = app.tavernbridge.launcher.data.SafetyDeleteReceiptStore(application)
    private var interruptedSafetyBatch = safetyDeleteStore.read()
    private var liveSafetyBatch: SafetyDeleteReceipt? = null
    private val savedOperationResult = interruptedSafetyBatch?.let { receipt ->
        val now = System.currentTimeMillis()
        receipt.summary(now, SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA).format(Date(now)))
    } ?: operationResultStore.load()
    private val themePreferences = ThemePreferences(application)
    private val backupReminderStore = BackupReminderStore(application)
    private val backupMetadataStore = app.tavernbridge.launcher.data.BackupMetadataStore(application, "termux:~/storage/downloads")
    private val backupTransferController = BackupTransferController(application)
    private var backupTransferJob: Job? = null
    private var backupTransferSource: BackupTransferController.Source? = null
    private var backupShareIntent: Intent? = null
    private var partialFileActionNotice = ""
    private val encryptedBackupFiles by lazy { app.tavernbridge.launcher.data.EncryptedBackupFiles(application) }
    private var encryptedBackupJob: Job? = null
    private var encryptedBackupSource: Uri? = null
    private var encryptedBackupPassword: CharArray? = null
    private var decryptedBackupFile: java.io.File? = null
    private var selectionListGeneration = 0L
    private var versionListGeneration = 0L
    private var extensionListGeneration = 0L
    private val extensionQuota = app.tavernbridge.launcher.data.GitHubReadQuota()
    private val mutableState = MutableStateFlow(
        LauncherUiState(
            workingStartedAtMillis = System.currentTimeMillis(),
            setupDeferred = guidePreferences.getBoolean("deferred", false),
            acknowledgedDiagnosticKeys = diagnosticPreferences.getStringSet("seen", emptySet()).orEmpty().toSet(),
            theme = themePreferences.loadTheme(),
            displayPreferences = themePreferences.loadDisplay(),
            configuredPort = repository.configuredPort(),
            previousInstallationKnown = (repository.hasKnownInstallation() || savedOperationResult.indicatesExistingInstallation())
                .also { if (it) repository.rememberKnownInstallation() },
            autoBackupBeforeUpdate = repository.autoBackupBeforeUpdate(),
            autoOpenBrowser = repository.autoOpenBrowser(),
            backupReminder = backupReminderStore.load(),
            backupReminderNowMillis = System.currentTimeMillis(),
            backgroundStatus = repository.backgroundStatus(),
            lastOperationResult = savedOperationResult,
            message = interruptedSafetyBatch?.notice,
            recentResults = operationResultStore.recent(),
            lastTrashedEntryId = repository.lastTrashedEntryId(),
        ),
    )
    val state = mutableState.asStateFlow()
    private var detachedOperationMonitor: Job? = null
    private val logReadJobs = mutableMapOf<DiagnosticPanel, Job>()
    private val logReadErrorRequests = mutableSetOf<DiagnosticPanel>()
    private var refreshJob: Job? = null
    private val deferredReads = VisibleReadQueue(viewModelScope)
    private val environmentRefreshGate = EnvironmentRefreshGate()
    private val warmReturn = WarmReturnWindow()
    private var warmProbeJob: Job? = null
    private val progressLogHistory = ProgressLogHistory()
    private var fileBrowserJob: Job? = null
    private var filePreviewJob: Job? = null
    private var previewGeneration = 0L
    @Volatile private var operationCancellationRequested = false
    private var activeOperationControl: OperationCancellationControl? = null
    private var cancellationRequestJob: Job? = null
    private var pendingInitialCancellation = false
    @Volatile private var appInForeground = false

    val setupCommand: String by lazy { repository.setupCommand() }
    val storageSetupCommand: String get() = repository.storageSetupCommand()

    init {
        // Never replay deletes. Publish the lower bound before consuming the private count journal.
        if (interruptedSafetyBatch != null && savedOperationResult != null && operationResultStore.saveConfirmed(savedOperationResult))
            safetyDeleteStore.clear()
        // Clean only orphaned plaintext staging owned by this feature after process death.
        // No Termux command, shared-storage scan, or network request is performed here.
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { encryptedBackupFiles } }
    }

    fun onAppForegrounded() {
        appInForeground = true
        mutableState.update {
it.copy(backgroundStatus = repository.backgroundStatus(it.environment.processRunning), backupReminder = backupReminderStore.load(),
                backupReminderNowMillis = System.currentTimeMillis())
        }
    }

    fun onAppBackgrounded() {
        appInForeground = false
        warmReturn.stopped(android.os.SystemClock.elapsedRealtime())
        warmProbeJob?.cancel()
    }

    fun deferSetup() {
        guidePreferences.edit().putBoolean("deferred", true).apply()
        mutableState.update { it.visit(MainSection.HOME, reset = true).copy(setupDeferred = true) }
    }

    fun resumeSetup() {
        guidePreferences.edit().remove("deferred").apply()
        mutableState.update { it.visit(MainSection.SETUP).copy(setupDeferred = false) }
    }

    // An in-flight setup check may discover an existing installation. Replace this route
    // in place: Back must return to the caller, not reopen the obsolete setup visit.
    fun settleInstalledSetup() = mutableState.update {
        if (it.section == MainSection.SETUP && it.environmentChecked && it.environment.sillyTavernInstalled &&
            it.termuxSetup.verified && it.environment.managerConnected && !it.environment.recoveryPending)
            it.copy(section = MainSection.LOGS, toolPage = ToolPage.INSTALLATION)
        else it
    }

    fun finishSetup() = mutableState.update { it.visit(MainSection.HOME, reset = true) }

    fun navigateBack() {
        val before = state.value
        val target = before.navigation.previous.lastOrNull()
        if (target == null) {
            // Explicit recovery/entry intents may have supplied a detail with no user history.
            if (before.section == MainSection.LOGS && before.toolPage != ToolPage.INDEX) {
                if (before.toolPage == ToolPage.FILES) leaveFileTools()
                if (before.toolPage == ToolPage.EXTENSIONS) closeExtensions()
                mutableState.update { it.visit(MainSection.LOGS, reset = true) }
            } else if (before.section == MainSection.SETUP) {
                mutableState.update { it.visit(MainSection.HOME, reset = true) }
            }
            return
        }
        if (before.section == MainSection.LOGS && before.toolPage == ToolPage.FILES) leaveFileTools()
        if (before.section == MainSection.LOGS && before.toolPage == ToolPage.EXTENSIONS) closeExtensions()
        mutableState.update { it.previousScreen() }
        val current = state.value
        if (current.section != MainSection.LOGS) return
        if (current.toolPage == ToolPage.FILES && current.fileBrowserMutating) {
            mutableState.update { it.copy(fileBrowserOpen = true) }
            return
        }
        if (!current.environment.managerConnected || current.isWorking) return
        when (current.toolPage) {
            ToolPage.FILES -> loadSillyTavernFolder(target.folderPath)
            ToolPage.EXTENSIONS -> loadExtensions()
            else -> Unit
        }
    }

    fun selectSection(section: MainSection) {
        if (state.value.section == MainSection.LOGS && state.value.toolPage == ToolPage.FILES) {
            leaveFileTools()
        }
        if (state.value.toolPage == ToolPage.EXTENSIONS) closeExtensions()
        if (section == MainSection.SETUP && state.value.setupDeferred) return resumeSetup()
        mutableState.update { it.visit(section) }
    }

    fun selectTool(page: ToolPage, replaceToolsPane: Boolean = false) {
        if (page == ToolPage.LOGS) { openDiagnostics(DiagnosticPanel.SERVER_LOG, replaceToolsPane); return }
        if (state.value.toolPage == ToolPage.EXTENSIONS && page != ToolPage.EXTENSIONS) closeExtensions()
        if (state.value.toolPage == ToolPage.FILES && page != ToolPage.FILES) {
            leaveFileTools()
        }
        mutableState.update { it.visit(MainSection.LOGS, page, replaceToolsPane = replaceToolsPane) }
        if (page == ToolPage.FILES && state.value.fileBrowserMutating) mutableState.update { it.copy(fileBrowserOpen = true) }
        if (!state.value.environment.managerConnected || state.value.isWorking) return
        when (page) {
            ToolPage.EXTENSIONS -> loadExtensions()
            ToolPage.FILES -> openSillyTavernFolder()
            else -> Unit
        }
    }

    fun openServerSettingsFromHome() = mutableState.update { ui ->
        if (ui.section != MainSection.HOME) ui else ui.visit(MainSection.SETTINGS).let {
            it.copy(serverSettingsFromHomeEntry = it.navigation.entryId)
        }
    }

    fun openPortEditor() {
        selectSection(MainSection.SETTINGS)
        mutableState.update { it.copy(portEditorRequested = true) }
    }

    fun consumePortEditorRequest() { mutableState.update { it.copy(portEditorRequested = false) } }

    fun openLanAddressFromNotice() {
        if (!state.value.environment.externalAccessEnabled) return
        if (state.value.section != MainSection.SETTINGS) selectSection(MainSection.SETTINGS)
        mutableState.update { it.copy(lanAddressRequested = true) }
    }

    fun consumeLanAddressRequest() { mutableState.update { it.copy(lanAddressRequested = false) } }

    suspend fun inspectResetImpact(): app.tavernbridge.launcher.model.ResetImpact? {
        val ui = state.value
        if (!ui.canInspectResetImpact()) return null
        return try { repository.inspectResetImpact() }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { null }
    }

    fun selectSettingsPanel(panel: SettingsPanel) {
        mutableState.update { it.copy(settingsPanel = panel) }
    }

    fun openInstallationFromAbout(verifiedInstallationShown: Boolean) {
        if (verifiedInstallationShown)
            selectTool(ToolPage.INSTALLATION)
        else selectSection(MainSection.SETUP)
    }

    fun openDiagnostics(panel: DiagnosticPanel, replaceToolsPane: Boolean = false) {
        if (state.value.toolPage == ToolPage.FILES) {
            leaveFileTools()
        }
        if (state.value.toolPage == ToolPage.EXTENSIONS) closeExtensions()
        mutableState.update { it.visit(MainSection.LOGS,
            if (panel == DiagnosticPanel.OVERVIEW) ToolPage.DIAGNOSTICS else ToolPage.LOGS, panel, replaceToolsPane = replaceToolsPane) }
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

    fun setDisplayPreferences(value: app.tavernbridge.launcher.model.DisplayPreferences) {
        themePreferences.saveDisplay(value)
        mutableState.update { it.copy(displayPreferences = value) }
    }

    fun setBackupReminderInterval(value: app.tavernbridge.launcher.model.BackupInterval) {
        val reminder = backupReminderStore.setInterval(value)
        mutableState.update { it.copy(backupReminder = reminder, backupReminderNowMillis = System.currentTimeMillis()) }
    }

    fun refreshBackupReminderClock(clockReset: Boolean) {
        val now = System.currentTimeMillis()
        mutableState.update {
            if (clockReset || it.backupReminder.displayChangedSince(it.backupReminderNowMillis, now,
                    it.environment.sillyTavernInstalled)) it.copy(backupReminderNowMillis = now) else it
        }
    }

    fun setBackupReminderEnabled(enabled: Boolean) {
        val reminder = backupReminderStore.setEnabled(enabled)
        app.tavernbridge.launcher.data.BackupJobs.reconcile(getApplication())
        mutableState.update { it.copy(backupReminder = reminder, backupReminderNowMillis = System.currentTimeMillis()) }
    }

    fun snoozeBackupReminder() {
        val reminder = backupReminderStore.snooze()
        mutableState.update { it.copy(backupReminder = reminder, backupReminderNowMillis = System.currentTimeMillis()) }
    }

    internal suspend fun checkAutoBackupStorage(): Boolean = state.value.environment.managerConnected &&
        state.value.termuxSetup.verified && repository.backupStorageReady()

    fun openBackups() {
        selectSection(MainSection.BACKUPS)
    }

    fun selectBottomTab(tab: MainSection) {
        if (state.value.toolPage == ToolPage.FILES) leaveFileTools()
        if (state.value.toolPage == ToolPage.EXTENSIONS) closeExtensions()
        mutableState.update { it.reselectRoot(tab) }
    }

    fun beginBackupTransfer(archive: BackupArchive, action: BackupTransferAction) {
        if (state.value.isWorking || state.value.backupTransfer != null || state.value.encryptedBackup != null) return
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

    fun saveServerPort(port: Int) {
        if (commandEntryBlocked()) return
        if (port !in 1024..65535) {
            mutableState.update { it.copy(error = "포트는 1024부터 65535 사이로 입력해 주세요.") }
            return
        }
        perform("save-server-connection", "서버 연결 설정 저장 중", "서버 연결 설정을 저장했습니다.",
            successMessageForResult = { maintenanceMessage("서버 연결 설정을 저장했습니다.", it) }) {
            maintainServer {
                repository.saveConfiguredPort(port)
                mutableState.update { it.copy(configuredPort = port) }
                TermuxCommandResult("local-port", "", "", 0, android.app.Activity.RESULT_OK, "")
            }
        }
    }

    fun refresh() = refreshEnvironment(firstConnectionButton = false)

    private val setupTrip = app.tavernbridge.launcher.model.SetupReturnGate()
    fun onSetupWindowPaused() = setupTrip.paused()
    fun pendingSetupReturn() = setupTrip.waitingForReturn
    fun restoreSetupReturn(wasAway: Boolean) = setupTrip.restoreReturn(wasAway)

    /** Only the onboarding launch is armed. Other shortcuts into Termux do not trigger a check. */
    fun openTermuxForSetup() {
        if (!state.value.awaitingFirstConnection || state.value.isWorking) return
        if (runCatching { repository.openTermux() }.getOrDefault(false)) setupTrip.launched()
        else mutableState.update { it.firstConnectionFailed(it.environment, TermuxSetupState(
            TermuxSetupStatus.UNVERIFIED, "Termux를 열지 못했어요. 설치 상태를 확인해 주세요.")) }
    }

    fun confirmFirstConnection() {
        if (!state.value.awaitingFirstConnection || state.value.isWorking) return
        setupTrip.disarm()
        refreshEnvironment(firstConnectionButton = true)
    }

    private fun refreshEnvironment(firstConnectionButton: Boolean) {
        warmProbeJob?.cancel()
        if (state.value.backupTransfer != null || state.value.encryptedBackup != null) return
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
            val requestedVisit = state.value.navigation.entryId
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
                if (environment.managerConnected && environment.sillyTavernInstalled) {
                    try {
                        app.tavernbridge.launcher.data.BackupJobs.recoverPublished(getApplication(),repository)
                    } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) {
                        // Optional receipt refresh must never invalidate a successful environment inspection.
                    }
                }
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
                    !isInterruptedSafetyChild(progress?.operation, progress?.operationStartedAtMillis ?: 0L) &&
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
                val recoveredProgress = progress?.let(progressLogHistory::merge)
                mutableState.update {
                    it.copy(
                        environment = environment,
                        environmentChecked = true,
                        previousInstallationKnown = environment.sillyTavernInstalled,
                        termuxWakeBlocked = false,
                        section = sectionAfterEnvironmentCheck(environment, firstCheck && it.navigation.entryId == requestedVisit,
                            requestedSection, it.section, it.setupDeferred),
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
                    section = sectionAfterEnvironmentCheck(environment, true, it.section, it.section, it.setupDeferred),
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
        warmProbeJob?.cancel()
        if (state.value.backupTransfer != null || state.value.encryptedBackup != null) return
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
                        section = if (!environment.sillyTavernInstalled && !it.setupDeferred) MainSection.SETUP else it.section,
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
        val shortReturn = warmReturn.consume(android.os.SystemClock.elapsedRealtime(), repository.inspectedAtElapsed)
        if (setupTrip.consume() && state.value.awaitingFirstConnection && !state.value.setupDeferred) {
            viewModelScope.launch {
                refreshJob?.join()
                // The first refresh after process recreation owns its initial busy placeholder;
                // refreshEnvironment's gate distinguishes that from actual Termux work.
                if (appInForeground && state.value.awaitingFirstConnection && !state.value.setupDeferred)
                    refreshEnvironment(firstConnectionButton = true)
            }
            return
        }
        if (state.value.backupTransfer != null || state.value.encryptedBackup != null) return
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
            val before = state.value
            // Fresh Android prerequisites cost no Termux command. Never infer ownership from an HTTP response alone.
            if (shortReturn && appInForeground && before.allowsWarmProbe(repository.baseStatus())) {
                if (warmProbeJob?.isActive == true) return
                warmProbeJob = viewModelScope.launch {
                    probeWarmServer(
                        ping = { repository.pingServer(before.environment.port) },
                        stillCurrent = {
                            val latest = state.value
                            appInForeground && refreshJob?.isActive != true && latest.dispatchedAtMillis == before.dispatchedAtMillis &&
                                latest.environment == before.environment && latest.allowsWarmProbe(repository.baseStatus())
                        },
                        inspect = { refreshSilently() },
                    )
                }
            } else refreshSilently()
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
                val interruptedBatch = isInterruptedSafetyChild(progress?.operation, progress?.operationStartedAtMillis ?: 0L)
                val failed = interruptedBatch || recoveredStatus != RecoveredOperationStatus.SUCCESS
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
                val substepNotice = recoveredSubstepNotice(
                    requestedOperation ?: BackupRequestCodec.parentOperation(repository.savedBackupRequest(), progress),
                    operation, progress?.status,
                )
                val resultDetail = when {
                    interruptedBatch -> interruptedSafetyBatch?.notice ?: state.value.lastOperationResult?.detail.orEmpty()
                    cancelled -> progress?.detail?.ifBlank { "작업을 안전하게 중단했습니다." } ?: "작업을 안전하게 중단했습니다."
                    interrupted -> "작업이 중단되어 완료 여부를 확인할 수 없습니다. 설치 상태와 로그를 확인해 주세요."
                    failed && operation == "backup" -> backupFailureDetail(progress?.detail.orEmpty(), latestLogs)
                    failed -> operationFailureSummary(progress?.errorCode.orEmpty(), progress?.detail.orEmpty(), progress?.logText.orEmpty())
                    substepNotice != null -> substepNotice
                    operation == "update-preflight" -> "작업은 완료됐지만 업데이트 검사 결과를 받지 못했습니다. 업데이트 확인을 다시 실행해 주세요."
                    operation == "diagnose" -> "작업은 완료됐지만 진단 보고서를 받지 못했습니다. 전체 환경 진단을 다시 실행해 주세요."
                    else -> "진행 중이던 작업을 완료했습니다."
                }
                val recoveredSummary = if (interruptedBatch) state.value.lastOperationResult else operationSummary(
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
                            isWorking = false,
                            workingLabel = "",
                            workProgress = null,
                            message = resultDetail,
                            error = null,
                            logs = latestLogs,
                            lastOperationResult = recoveredSummary,
                        )
                    }
                    if (fileBrowserPath == null) recovered else recovered.copy(
                        section = it.section,
                        error = null,
                        fileBrowserMutating = false,
                        fileBrowserLoading = false,
                        fileBrowserEntries = refreshedFileEntries ?: it.fileBrowserEntries,
                        fileBrowserError = if (failed && !cancelled) resultDetail else "",
                        fileBrowserNotice = if (cancelled) resultDetail else if (failed) "" else if (refreshedFileEntries != null) resultDetail
                            else "$resultDetail 목록을 새로고침해 주세요.",
                    )
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

    fun setAutoOpenBrowser(enabled: Boolean) {
        repository.saveAutoOpenBrowser(enabled)
        mutableState.update { it.copy(autoOpenBrowser = enabled) }
    }

    fun checkUpdateInformation() {
        if (state.value.isWorking || state.value.updateInfoLoading) return
        mutableState.update { it.copy(updateInfoLoading = true, updateInfoError = "") }
        viewModelScope.launch {
            val issues = mutableListOf<String>()
            try {
                try {
                    val info = LauncherReleaseClient().check()
                    mutableState.update { it.copy(launcherUpdateInfo = info) }
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (error: Exception) { issues += error.userMessage() }
                if (state.value.environment.managerConnected && !state.value.isWorking) {
                    try {
                        val info = repository.nodePackageInfo()
                        mutableState.update { it.copy(nodePackageInfo = info) }
                    } catch (cancelled: CancellationException) { throw cancelled
                    } catch (error: Exception) { issues += error.userMessage() }
                }
            } finally {
                mutableState.update { it.copy(updateInfoLoading = false, updateInfoError = issues.joinToString("\n")) }
            }
        }
    }

    private suspend fun maintainServer(work: suspend () -> TermuxCommandResult): TermuxCommandResult {
        val control = currentCoroutineContext()[OperationCancellationControl]
        val outcome = withPausedServer(
            wasRunning = state.value.environment.processRunning,
            stop = {
                repository.stop().requireSuccess()
                if (repository.wakeLockEnabled()) repository.setTermuxWakeLock(false).requireSuccess()
            },
            canResume = {
                val current = repository.inspect()
                current.sillyTavernInstalled && !current.operationActive && !current.recoveryPending && current.extensionRecovery == "none" && !current.processRunning
            },
            start = {
                try { startServerWithWakeLock().requireSuccess() }
                catch (cancelled: UserOperationCancelled) {
                    withoutOperationCancellation {
                        if (control != null) repository.cancelOperation("", control.requestId).requireSuccess()
                        repository.stop().requireSuccess()
                    }
                    throw cancelled
                }
            },
            cancellationRequested = { control?.cancellationRequested == true },
            work = { work().also { it.requireSuccess() } },
        )
        return outcome.value.copy(stdout = outcome.value.stdout + "\nserver_resume=${outcome.resumeStatus.name}\n")
    }

    private fun maintenanceMessage(base: String, result: TermuxCommandResult): String {
        val resume = result.stdout.lineSequence().firstOrNull { it.startsWith("server_resume=") }?.substringAfter('=')
        return base + when (resume) {
            ServerResumeStatus.STARTED.name -> " 실행 중이던 서버를 다시 시작했습니다."
            ServerResumeStatus.LEFT_STOPPED.name -> " 중단 요청 또는 실행 상태 확인이 필요해 서버를 다시 시작하지 않았습니다."
            ServerResumeStatus.FAILED.name -> " 작업은 완료했지만 서버 재시작 상태를 확인하지 못했습니다. 홈에서 상태를 확인해 주세요."
            else -> ""
        }
    }

    fun start() = perform(
        operation = "start",
        label = "SillyTavern 시작 중",
        successMessage = "SillyTavern을 시작했습니다.",
        retryAction = RetryAction.START,
    ) {
        startServerWithWakeLock().also { result ->
            if (result.shouldAutoOpenAfterStart(repository.autoOpenBrowser(), appInForeground, operationCancellationRequested)) {
                if (runCatching { repository.openBrowser(repository.configuredPort()) }.getOrDefault(false))
                    mutableState.update { it.copy(autoOpenedStartAtMillis = it.dispatchedAtMillis) }
            }
        }
    }

    fun stop() = dispatchStop()

    /** Result is local to this particular stop, not the latest stored operation/OS callback.
     * The view model holds no picker, dialog, Activity or destructive continuation. */
    internal suspend fun stopForContinuation(entryId: Long): Boolean {
        val before = state.value
        if (!appInForeground || before.navigation.entryId != entryId || !before.readyForStopContinuation(true)) {
            showMessage("서버 상태가 바뀌었어요. 원래 화면에서 다시 확인해 주세요.")
            return false
        }
        val verified = CompletableDeferred<Boolean>()
        val handoff = app.tavernbridge.launcher.model.AbandonedStopNotice()
        fun abandonedNotice(show: Boolean) {
            if (show) showMessage("서버를 껐어요. 이어가려던 작업은 시작하지 않았어요. 원래 작업을 다시 선택해 주세요.")
        }
        dispatchStop(onChecked = { fresh ->
            val now = state.value
            val sameScreen = appInForeground && now.navigation.entryId == entryId
            val canContinue = fresh && sameScreen && now.readyForStopContinuation(false)
            if (!sameScreen) abandonedNotice(handoff.invalidate())
            if (fresh && now.readyForStopContinuation(false)) abandonedNotice(handoff.verified())
            if (sameScreen && !canContinue) showMessage(when {
                !fresh || !now.environment.managerConnected ->
                    "종료 명령은 끝났지만 서버 상태를 확인하지 못했어요. 새로고침한 뒤 다시 선택해 주세요."
                now.environment.processRunning || now.environment.serverReachable ->
                    "서버가 아직 실행 중이거나 응답하고 있어요. 원래 작업은 시작하지 않았어요."
                else -> "다른 작업 또는 복구 상태를 확인해 주세요. 원래 작업은 시작하지 않았어요."
            })
            verified.complete(canContinue)
        }, onFinished = { verified.complete(false) })
        // Bg/rotation may cancel this waiter, but should not cancel an already consented server stop.
        return try { verified.await() } catch (cancelled: CancellationException) {
            abandonedNotice(handoff.invalidate())
            throw cancelled
        }
    }

    private fun dispatchStop(onChecked: ((Boolean) -> Unit)? = null, onFinished: () -> Unit = {}) = perform(
        operation = "stop",
        label = "SillyTavern 종료 중",
        successMessage = "SillyTavern을 종료했습니다.",
        successMessageForResult = {
            if (onChecked != null && !appInForeground)
                "서버 종료 명령을 마쳤어요. 이어가려던 작업은 다시 선택해 주세요."
            else "SillyTavern을 종료했습니다."
        },
        retryAction = RetryAction.STOP,
        onRefreshChecked = onChecked,
        onFinished = onFinished,
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
        partialNotice: () -> String = { "" },
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
            val notice = if (operation == "delete-st") partialFileActionNotice else partialNotice()
            val detail = "요청에 따라 작업을 중단했습니다. 이미 완료된 변경은 유지됩니다." +
                if (notice.isNotEmpty()) "\n$notice" else ""
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
                    if (result.stdout.lineSequence().any { it == "followup_stopped=0" })
                        "업데이트 적용은 완료했어요. 재시작 중단을 요청했지만 서버가 꺼졌는지는 확인하지 못했어요. 홈에서 확인해 주세요."
                    else "업데이트 적용은 완료했습니다. 중단 요청에 따라 서버 재시작을 멈췄습니다."
                } else if (result.stdout.lineSequence().any { it == "updated=0" }) {
                    maintenanceMessage("이미 최신 커밋입니다. 패키지를 다시 설치하지 않았습니다.", result)
                } else {
                    maintenanceMessage("업데이트·패키지 설치·서버 응답 검사를 완료했습니다.", result)
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

    fun resetInstallation(backupBefore: Boolean = true) = perform(
        operation = "reset-installation",
        label = "SillyTavern 초기화 중",
        successMessage = "SillyTavern을 초기 상태로 다시 설치했습니다. Download의 백업 파일은 유지됩니다.",
    ) {
        val result = protectBeforeReplacement(backupBefore, { backupBeforeReplacement("reset-installation") }) {
            repository.resetInstallation()
        }
        if (result.isSuccess) {
            mutableState.update { it.copy(updatePreflight = null) }
        }
        result
    }

    fun backup() = createBackup(setOf(BackupCategory.FULL), false, emptySet())

    fun openSafetyInventory() = openSafetyInventory(configFirst = false)
    fun openConfigSnapshots() = openSafetyInventory(configFirst = true)

    private fun openSafetyInventory(configFirst: Boolean) {
        if (commandEntryBlocked()) return
        mutableState.update { it.visit(MainSection.BACKUPS, safetyOpen = true).let { next ->
            next.copy(safetyInventory = next.safetyInventory.copy(configFirst = configFirst))
        } }
        loadSafetyInventory()
    }

    fun closeSafetyInventory() {
        if (state.value.safetyInventory.open) navigateBack()
    }

    fun loadSafetyInventory(more: Boolean = false) {
        if (commandEntryBlocked() || state.value.safetyInventory.loading) return
        val cursor = if (more) state.value.safetyInventory.nextCursor ?: return else 0
        if (more && state.value.safetyInventory.items.size >= 1000) {
            mutableState.update { it.copy(safetyInventory = it.safetyInventory.copy(error = "한 번에 1,000개까지 표시합니다. 일부 항목을 정리한 뒤 새로고침해 주세요.")) }
            return
        }
        mutableState.update { it.copy(safetyInventory = it.safetyInventory.copy(loading = true, error = "")) }
        viewModelScope.launch {
            try {
                val page = repository.listSafetyInventory(cursor)
                check(page.nextCursor == null || page.nextCursor > cursor) { "보관 목록의 다음 위치를 확인하지 못했습니다." }
                mutableState.update { it.copy(safetyInventory = it.safetyInventory.accept(page, more)) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                mutableState.update { it.copy(safetyInventory = it.safetyInventory.copy(error = error.userMessage())) }
            } finally {
                mutableState.update { it.copy(safetyInventory = it.safetyInventory.copy(loading = false)) }
            }
        }
    }

    /** Selection contains precisely the (id, revision) pairs the user confirmed.
     * Each removal is revalidated under the manager lock; never retry a partially deleted batch. */
    fun deleteSafetySelection(request: List<SafetyInventoryItem>) {
        val snapshot = state.value
        val picks = request.toList()
        val visible = snapshot.safetyInventory.items.associateBy { it.id }
        if (!snapshot.safetyInventory.open || snapshot.safetyInventory.loading || snapshot.safetyInventory.mutationBlocked ||
            snapshot.environment.recoveryPending || picks.size !in 1..1000 || picks.distinctBy { it.id }.size != picks.size ||
            picks.any { visible[it.id] != it }) {
            showMessage("목록이 바뀌었어요. 새로고침한 뒤 다시 선택해 주세요."); return
        }
        if (picks.size == 1) { changeSafetyItem("delete", picks.single()); return }
        perform(operation = "safety-delete", label = "선택한 보관 항목 삭제",
            successMessage = "${picks.size}개를 영구 삭제했어요.",
            onFinished = { if (state.value.safetyInventory.open) loadSafetyInventory() }) {
            var last: TermuxCommandResult? = null
            var receipt = SafetyDeleteReceipt(picks.size)
            withContext(Dispatchers.IO) { safetyDeleteStore.save(receipt) }
            liveSafetyBatch = receipt
            interruptedSafetyBatch = null
            for (pick in picks) {
                currentCoroutineContext()[OperationCancellationControl]?.checkpoint()
                val result = repository.changeSafetyItem("delete", pick)
                result.requireSuccess()
                last = result
                receipt = receipt.acknowledge()
                liveSafetyBatch = receipt
                // Once acknowledged by Termux, journal before dispatching any further delete.
                withContext(Dispatchers.IO) { safetyDeleteStore.save(receipt) }
                mutableState.update { ui -> ui.copy(safetyInventory = ui.safetyInventory.copy(
                    items = ui.safetyInventory.items.filterNot { it.id == pick.id && it.revision == pick.revision }),
                    safetyCopyCount = null, fileTrashCount = null) }
            }
            checkNotNull(last)
        }
    }

    fun changeSafetyItem(action: String, item: SafetyInventoryItem) {
        val inventory = state.value.safetyInventory
        if (!inventory.open || action !in setOf("export", "delete", "restore") || inventory.loading) return
        if (inventory.items.none { it == item } || (action == "export" && !item.canExport) || (action == "restore" && !item.restorable)) {
            showMessage("항목이 바뀌었거나 지원하지 않는 작업이에요. 목록을 다시 확인해 주세요."); return
        }
        if (state.value.safetyInventory.mutationBlocked || state.value.environment.recoveryPending) {
            mutableState.update { it.copy(safetyInventory = it.safetyInventory.copy(error = "미완료 작업의 점검·복구를 먼저 진행해 주세요. 보호 자료는 변경하지 않았습니다.")) }
            return
        }
        if (action == "restore" && state.value.environment.processRunning) {
            mutableState.update { it.copy(safetyInventory = it.safetyInventory.copy(error = "복원하려면 서버를 먼저 종료해 주세요.")) }
            return
        }
        val config = item.kind == app.tavernbridge.launcher.model.SafetyItemKind.CONFIG
        val label = when (action) { "export" -> "안전 사본 내보내기"; "delete" -> "보관 항목 삭제"; else -> if (config) "설정 사본 복원" else "휴지통 항목 복원" }
        perform(operation = "safety-$action", label = label, successMessage = when (action) {
            "export" -> "Download에 사본을 내보냈습니다. 내부 원본은 유지됩니다."
            "delete" -> "영구 삭제했어요."
            else -> if (config) "설정 사본을 복원했어요. 이전 설정도 안전 사본에 보관했어요."
                else "휴지통 항목을 원래 위치로 복원했습니다. 같은 이름의 기존 항목은 덮어쓰지 않습니다."
        }) {
            if (action == "delete") interruptedSafetyBatch = null
            val result = repository.changeSafetyItem(action, item)
            if (result.isSuccess) {
                try {
                    // The mutation is already committed. Cancelling this optional list
                    // refresh must not relabel a completed export/delete as cancelled.
                    val page = withoutOperationCancellation { repository.listSafetyInventory() }
                    mutableState.update { it.copy(safetyInventory = it.safetyInventory.accept(page, false),
                        lastTrashedEntryId = repository.lastTrashedEntryId(), safetyCopyCount = null, fileTrashCount = null) }
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Exception) {
                    mutableState.update { it.copy(safetyInventory = it.safetyInventory.copy(items = emptyList(),
                        error = "작업은 완료했습니다. 목록을 새로고침해 주세요.")) }
                }
            }
            result
        }
    }

    fun loadBackups() {
        if (deferRead(VisibleReadQueue.ListKind.BACKUPS, {
                it.section == MainSection.BACKUPS || it.section == MainSection.SETUP || it.section == MainSection.HOME
            }, ::loadBackups)) return
        if (!state.value.environment.managerConnected || !state.value.termuxSetup.verified) return
        if (commandEntryBlocked(allowWhileWorking = true, notifyBusy = false)) return
        viewModelScope.launch {
            if (state.value.backupListLoading) return@launch
            mutableState.update { it.copy(backupListLoading = true, backupListError = "", backupListErrorCode = "") }
            try {
                val storageReady = repository.backupStorageReady()
                if (!storageReady) {
                    mutableState.update { it.copy(backupStorageReady = false, backupsLoaded = false, safetyCopyCount = null, fileTrashCount = null,
                        backupListLoading = false, backups = emptyList(), backupFolders = emptyList()) }
                    return@launch
                }
                val (backups, safetyCount, trashCount) = repository.readBackupCatalog(true)
                val reminder = backupReminderStore.observeArchives(backups)
                val folders = repository.listUserBackupFolders()
                val updateRecord = repository.lastUpdateRecord()
                mutableState.update {
                    it.copy(
                        backups = backups,
                        backupAnnotations = backupMetadataStore.loadAll(backups.map { it.fileName }),
                        backupReminder = reminder,
                        backupReminderNowMillis = System.currentTimeMillis(),
                        backupFolders = folders,
                        backupsLoaded = true,
                        backupListLoading = false,
                        backupStorageReady = storageReady,
                        message = if (it.backupStorageReady == false) "백업 저장공간을 연결했어요." else it.message,
                        safetyCopyCount = safetyCount, fileTrashCount = trashCount,
                        lastUpdateRecord = updateRecord,
                    )
                }
            } catch (cancelled: CancellationException) {
                mutableState.update { it.copy(backupListLoading = false) }
                throw cancelled
            } catch (error: Exception) {
                val detail = error.userMessage()
                mutableState.update {
                    it.copy(backupListLoading = false, backupListError = detail,
                        backupListErrorCode = errorCodeFrom(detail).ifBlank { "BACKUP_LIST_FAILED" })
                }
            }
        }
    }

    internal fun closePcZip() {
        pcZipGeneration++
        pcZipScreen.value = null
        val file = pcZipFile ?: return
        pcZipFile = null
        viewModelScope.launch(Dispatchers.IO) { repository.discardPcZip(file) }
    }

    internal fun beginPcZip(uri: Uri) {
        if (commandEntryBlocked() || state.value.environment.operationActive) return
        closePcZip()
        val generation = pcZipGeneration
        perform("import-preview", "ZIP 미리보기", "ZIP 내용을 확인했어요. 아직 휴대폰 파일은 바꾸지 않았어요.") {
            val name = repository.pcZipName(uri)
            val file = repository.stagePcZip(uri)
            var retained = false
            try {
                val (preview, result) = repository.previewPcZip(file, null)
                if (generation == pcZipGeneration) {
                    pcZipFile = file
                    pcZipScreen.value = preview.copy(name = name)
                    retained = true
                }
                result
            } finally {
                if (!retained) withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { repository.discardPcZip(file) }
            }
        }
    }

    internal fun mapPcZip(source: String, target: String) {
        val file = pcZipFile ?: return
        val current = pcZipScreen.value ?: return
        if (source !in current.profiles || target.isNotEmpty() && target !in current.targets || commandEntryBlocked()) return
        if (target.isNotEmpty() && current.profiles.any { it.key != source && it.value == target }) {
            showMessage("이 휴대폰 사용자는 다른 원본 사용자와 이미 연결되어 있어요."); return
        }
        recheckPcZip(file, current, current.profiles + (source to target), impact = false)
    }

    internal fun checkPcZipReplacement() {
        val file = pcZipFile ?: return
        val current = pcZipScreen.value ?: return
        if (current.checking || commandEntryBlocked()) return
        recheckPcZip(file, current, current.profiles, impact = true)
    }

    internal fun togglePcZipAvatars() {
        val file = pcZipFile ?: return
        val current = pcZipScreen.value ?: return
        if (current.checking || current.candidateAvatars == 0 || commandEntryBlocked()) return
        recheckPcZip(file, current, current.profiles, impact = false, includeAvatars = !current.avatarsIncluded)
    }

    private fun recheckPcZip(file: app.tavernbridge.launcher.data.SharedImportStaging.Transfer,
        current: app.tavernbridge.launcher.data.PcZipPreview, mapping: Map<String,String>, impact: Boolean,
        includeAvatars: Boolean = current.avatarsIncluded) {
        if (current.checking) return
        val generation = pcZipGeneration
        pcZipScreen.value = current.copy(checking = true)
        perform("import-preview", if (impact) "교체 범위 확인" else "넣을 곳 확인", "ZIP 미리보기를 갱신했어요.",
            onFinished = { if (generation == pcZipGeneration) pcZipScreen.value = pcZipScreen.value?.copy(checking = false) }) {
            try {
                val (preview, result) = repository.previewPcZip(file, mapping, impact, current.archive, includeAvatars)
                if (generation == pcZipGeneration && file == pcZipFile) {
                    pcZipScreen.value = current.refreshFrom(preview)
                        ?: error("[IMPORT_ARCHIVE_CHANGED] 확인하던 ZIP이 바뀌었어요. 원본 ZIP을 다시 선택해 주세요.")
                }
                result
            } catch (e: Exception) {
                if (errorCodeFrom(e.userMessage()) == "IMPORT_ARCHIVE_CHANGED" &&
                    generation == pcZipGeneration && file == pcZipFile) closePcZip()
                throw e
            }
        }
    }

    internal fun applyPcZip(replace: Boolean, backupBefore: Boolean) {
        val preview = pcZipScreen.value ?: return
        val file = pcZipFile ?: return
        if (preview.checking || commandEntryBlocked() || state.value.environment.operationActive || state.value.environment.recoveryPending) return
        if (!replace && !preview.canAdd || preview.layout in setOf("legacy_public", "unknown")) return
        val generation = ++pcZipGeneration
        pcZipFile = null
        pcZipScreen.value = null
        val op = if (replace) "import-backup" else "import-merge"
        var handedOff = false
        var settled = false
        fun settleStaging(active: Boolean) {
            if (settled) return
            settled = true
            when (pcZipStagingOwner(handedOff, generation == pcZipGeneration, active && !operationCancellationRequested,
                state.value.section in setOf(MainSection.SETUP, MainSection.BACKUPS))) {
                PcZipStagingOwner.TERMUX -> Unit
                PcZipStagingOwner.PREVIEW -> {
                    pcZipFile = file
                    pcZipScreen.value = preview.copy(retainedAfterPreflight = true, impact = null)
                }
                PcZipStagingOwner.DISCARD -> viewModelScope.launch(Dispatchers.IO) { repository.discardPcZip(file) }
            }
        }
        perform(op, if (replace) "ZIP으로 교체" else "PC ZIP 안전하게 합치기", "ZIP 처리를 마쳤어요.",
            onFinished = { settleStaging(true) },
            successMessageForResult = { result ->
                val counts = result.stdout.lineSequence().associate { it.substringBefore('=') to it.substringAfter('=', "") }
                val text = if (replace) "확인한 ZIP으로 교체했어요."
                    else "파일 ${counts["added"]?.toIntOrNull() ?: preview.added}개를 추가했어요. " + if (counts["cleanup_pending"] == "1")
                        "합치기는 완료됐고 임시 파일은 안전을 확인한 뒤 정리해요. ZIP을 다시 가져올 필요는 없어요."
                    else "기존 파일은 덮어쓰지 않았어요."
                maintenanceMessage(text, result)
            }) {
            try {
                val result = maintainServer {
                    protectBeforeReplacement(backupBefore, { backupBeforeReplacement(op) }) {
                        handedOff = true
                        repository.applyPcZip(file, preview, replace)
                    }
                }
                if (result.isSuccess && backupBefore) refreshBackupListAfterOperation()
                result
            } finally {
                settleStaging(currentCoroutineContext().isActive)
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
        val request = BackupRequest(UUID.randomUUID().toString(), normalized,
            includeSecrets && (BackupCategory.FULL in normalized || BackupCategory.USER_DATA in normalized),
            if (BackupCategory.CUSTOM in normalized) customFolders else emptySet())
        perform(
            operation = "backup",
            label = "선택 백업 생성 중",
            successMessage = "검증된 ZIP 백업을 휴대폰 Download 폴더에 저장했습니다.",
            successMessageForResult = { maintenanceMessage("검증된 ZIP 백업을 휴대폰 Download 폴더에 저장했습니다.", it) },
            retryAction = RetryAction.BACKUP,
            backupRequest = request,
        ) {
            maintainServer {
                repository.createBackup(request).also { result ->
                    result.requireSuccess()
                    if (!result.stdoutTruncated) {
                        val created = backupMetadataStore.rememberCreated(result.stdout)
                        mutableState.update { it.copy(lastCreatedBackupName = created) }
                    }
                    rememberSuccessfulBackup(request)
                    refreshBackupListAfterOperation(suggestCleanup = true)
                }
            }
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

    fun deleteBackups(selectionKeys: List<String>) {
        if (selectionKeys.isEmpty()) return
        val snapshot = app.tavernbridge.launcher.model.BackupDeleteSelection.resolve(selectionKeys, state.value.backups)
        if (snapshot == null) {
            showMessage("백업 목록이 변경되었거나 파일을 확인할 수 없습니다. 목록에서 다시 선택해 주세요.")
            return
        }
        perform(
            operation = "delete-backup",
            label = "백업 일괄 삭제 중",
            successMessage = "선택한 ZIP 백업 ${snapshot.size}개를 Download 폴더에서 삭제했습니다.",
        ) {
            check(snapshot.all { backupMetadataStore.load(it.fileName).let { note -> !note.pinned && note.trusted } }) {
                "고정된 백업은 먼저 고정을 해제해 주세요. 보호 상태가 불명확하면 삭제하지 않습니다."
            }
            val result = repository.deleteBackupsChecked(snapshot)
            if (result.isSuccess) refreshBackupListAfterOperation()
            result
        }
    }

    private suspend fun refreshBackupListAfterOperation(suggestCleanup: Boolean = false) {
        try {
            // This read follows a committed backup/restore. Do not relabel that
            // completed operation as cancelled while merely refreshing its list.
            val (backups, safetyCount, trashCount) = withoutOperationCancellation { repository.readBackupCatalog(true) }
            val reminder = backupReminderStore.observeArchives(backups)
            val annotations = backupMetadataStore.loadAll(backups.map { archive -> archive.fileName })
            val proposal = if (suggestCleanup) app.tavernbridge.launcher.model.BackupRetentionPolicy.afterCreation(
                backups, annotations, state.value.displayPreferences.keepBackups) else null
            mutableState.update { it.copy(backups = backups, backupsLoaded = true, safetyCopyCount = safetyCount, fileTrashCount = trashCount,
                backupListError = "", backupListErrorCode = "",
                backupAnnotations = annotations, backupCleanupPreview = proposal ?: it.backupCleanupPreview,
                backupReminder = reminder, backupReminderNowMillis = System.currentTimeMillis()) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // The preceding backup operation is already complete. A list refresh failure must
            // not relabel a successful import, restore, creation, or deletion as failed.
            val detail = error.userMessage()
            mutableState.update { it.copy(backupListError = detail,
                backupListErrorCode = errorCodeFrom(detail).ifBlank { "BACKUP_LIST_FAILED" }) }
        }
    }

    fun inspectStorageUsage() {
        val current = mutableState.value
        if (!current.environment.managerConnected || current.storageUsageLoading || current.isWorking || current.environment.operationActive) return
        mutableState.update { it.copy(storageUsageLoading = true, storageUsageError = "") }
        viewModelScope.launch {
            try {
                val (_, report) = repository.inspectStorageUsage()
                mutableState.update { it.copy(storageUsage = report) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                mutableState.update { it.copy(storageUsageError = failure.userMessage()) }
            } finally {
                mutableState.update { it.copy(storageUsageLoading = false) }
            }
        }
    }

    fun openExtensions() {
        if (commandEntryBlocked()) return
        mutableState.update { it.copy(extensionManagerOpen = true) }
        loadExtensions()
    }

    fun openSelectiveRestore(archive: BackupArchive) {
        if (commandEntryBlocked()) return
        if (!state.value.environment.sillyTavernInstalled) { showMessage("개별 복원은 기존 설치가 필요합니다. 먼저 SillyTavern을 설치하거나 전체 백업을 복원해 주세요."); return }
        if (state.value.environment.processRunning) { showMessage("캐릭터·채팅 복원 전에 서버를 종료해 주세요."); return }
        mutableState.update { it.copy(selectiveRestore = app.tavernbridge.launcher.model.SelectiveRestoreUiState(archive.fileName)) }
        loadSelectiveRestore()
    }

    fun closeSelectiveRestore() {
        if (!state.value.isWorking) { selectionListGeneration++; mutableState.update { it.copy(selectiveRestore = null) } }
    }

    fun openPreviousVersions() {
        if (commandEntryBlocked()) return
        mutableState.update { it.copy(previousVersions = app.tavernbridge.launcher.model.PreviousVersionUiState()) }
        loadPreviousVersions()
    }

    fun closePreviousVersions() {
        if (!state.value.isWorking) { versionListGeneration++; mutableState.update { it.copy(previousVersions = null) } }
    }

    fun loadPreviousVersions() {
        if (commandEntryBlocked() || state.value.previousVersions?.loading != false) return
        val generation = ++versionListGeneration
        mutableState.update { it.copy(previousVersions = app.tavernbridge.launcher.model.PreviousVersionUiState(loading = true)) }
        viewModelScope.launch {
            try {
                val versions = repository.previousVersions()
                val snapshots = repository.versionSnapshots()
                mutableState.update { if (generation == versionListGeneration && it.previousVersions != null) it.copy(previousVersions = app.tavernbridge.launcher.model.PreviousVersionUiState(versions = versions, snapshots = snapshots)) else it }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                mutableState.update { if (generation == versionListGeneration && it.previousVersions != null) it.copy(previousVersions = app.tavernbridge.launcher.model.PreviousVersionUiState(error = error.userMessage())) else it }
            }
        }
    }

    fun changeVersion(target: app.tavernbridge.launcher.model.PreviousVersion, snapshot: app.tavernbridge.launcher.model.VersionSnapshot?) {
        val current = state.value.previousVersions ?: return
        val page = current.versions ?: return
        if (state.value.environment.processRunning) { showMessage("버전을 변경하기 전에 서버를 종료해 주세요."); return }
        perform("change-version", "이전 버전으로 변경", "선택한 버전으로 변경하고 실행 검사를 마쳤습니다. 변경 전 전체 ZIP은 Download에 보관했습니다.") {
            check(target in page.versions && (snapshot == null || snapshot in current.snapshots?.snapshots.orEmpty())) { "표시된 버전과 백업에서 다시 선택해 주세요." }
            repository.changeVersion(target, page, snapshot).also { result ->
                if (result.isSuccess) { mutableState.update { it.copy(previousVersions = null) }; refreshBackupListAfterOperation() }
            }
        }
    }

    fun loadSelectiveRestore(more: Boolean = false) {
        val current = state.value.selectiveRestore ?: return
        if (commandEntryBlocked() || current.loading) return
        val cursor = if (more) current.page?.nextCursor ?: return else 0
        if (more && current.page?.items.orEmpty().size >= 1000) { showMessage("한 번에 1,000개까지만 표시합니다. 선택 복원을 나누어 진행해 주세요."); return }
        val generation = ++selectionListGeneration
        mutableState.update { it.copy(selectiveRestore = current.copy(loading = true, error = "")) }
        viewModelScope.launch {
            try {
                val page = repository.selectiveRestoreItems(current.fileName, cursor)
                check(page.nextCursor == null || page.nextCursor > cursor)
                if (more) check(current.page?.archiveRevision == page.archiveRevision && current.page.installationRevision == page.installationRevision) {
                    "백업이나 설치 상태가 변경되었습니다. 창을 닫고 다시 확인해 주세요."
                }
                val combined = page.copy(items = ((if (more) current.page?.items.orEmpty() else emptyList()) + page.items).distinctBy { it.relativePath }.take(1000))
                mutableState.update { if (generation == selectionListGeneration && it.selectiveRestore?.fileName == current.fileName) it.copy(selectiveRestore = current.copy(page = combined)) else it }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) { mutableState.update { if (generation == selectionListGeneration && it.selectiveRestore?.fileName == current.fileName) it.copy(selectiveRestore = current.copy(error = error.userMessage())) else it } }
        }
    }

    fun restoreSelection(choices: List<app.tavernbridge.launcher.model.SelectiveRestoreChoice>, backupBefore: Boolean) {
        val current = state.value.selectiveRestore ?: return
        val page = current.page ?: return
        if (state.value.environment.processRunning) { showMessage("복원 전에 서버를 종료해 주세요."); return }
        perform("restore-selected", "선택한 캐릭터·채팅 복원", "선택한 항목의 복원을 완료했습니다. 같은 이름을 건너뛰도록 선택한 항목은 유지했습니다.") {
            check(choices.isNotEmpty() && choices.size <= 100 && choices.all { it.item in page.items }) { "표시된 목록에서 100개 이하를 선택해 주세요." }
            val result = protectBeforeReplacement(backupBefore, { backupBeforeReplacement("restore-selected") }) {
                repository.restoreSelected(current.fileName, page, choices)
            }
            if (result.isSuccess) mutableState.update { it.copy(selectiveRestore = null) }
            result
        }
    }

    fun closeExtensions() {
        if (!state.value.isWorking) { extensionListGeneration++; mutableState.update { it.copy(extensionManagerOpen = false, extensionLoading = false) } }
    }

    fun loadExtensions(more: Boolean = false) {
        if (deferRead(VisibleReadQueue.ListKind.EXTENSIONS, {
                it.extensionManagerOpen || (it.section == MainSection.LOGS && it.toolPage == ToolPage.EXTENSIONS)
            }) { loadExtensions(more) }) return
        if (commandEntryBlocked() || state.value.extensionLoading) return
        val previous = state.value.extensionInventory
        val cursor = if (more) previous?.nextCursor ?: return else 0
        if (more && previous?.items.orEmpty().size >= 1000) { showMessage("목록은 1,000개까지만 표시합니다."); return }
        val generation = ++extensionListGeneration
        mutableState.update { it.copy(extensionLoading = true, extensionError = "") }
        viewModelScope.launch {
            try {
                val page = repository.listExtensions(cursor)
                check(page.nextCursor == null || page.nextCursor > cursor) { "확장 목록의 다음 위치가 올바르지 않습니다." }
                val items = ((if (more) previous?.items.orEmpty() else emptyList()) + page.items).distinctBy { it.id }.take(1000)
                mutableState.update { if (generation == extensionListGeneration) it.copy(extensionInventory = page.copy(items = items, skipped = page.skipped + if (more) previous?.skipped ?: 0 else 0)) else it }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) { mutableState.update { if (generation == extensionListGeneration) it.copy(extensionError = error.userMessage()) else it } }
            finally { mutableState.update { if (generation == extensionListGeneration) it.copy(extensionLoading = false) else it } }
        }
    }

    fun checkExtensionVersions() {
        if (commandEntryBlocked() || state.value.extensionLoading) return
        val snapshot = state.value.extensionInventory ?: return
        val generation = ++extensionListGeneration
        mutableState.update { it.copy(extensionLoading = true, extensionError = "") }
        viewModelScope.launch {
            try {
                // SillyTavern itself can update/remove an extension since this screen was opened.
                // Re-read the installed pages; never compare a new remote against a stale local HEAD.
                var cursor = 0
                val fresh = linkedMapOf<String, ManagedExtension>()
                var skipped = 0
                lateinit var page: ExtensionInventory
                do {
                    if (generation != extensionListGeneration) return@launch
                    page = repository.listExtensions(cursor)
                    skipped += page.skipped
                    page.items.forEach { fresh[it.id] = it }
                    val next = page.nextCursor ?: break
                    check(next > cursor) { "확장 목록 위치가 올바르지 않습니다." }
                    cursor = next
                } while (fresh.size < snapshot.items.size && fresh.size < 1000)
                val previous = snapshot.items.associateBy { it.id }
                val all = fresh.values.map { item ->
                    val old = previous[item.id]
                    if (old != null && item.sameCheckTarget(old)) item.copy(remoteAhead = old.remoteAhead,
                        checkedRemoteCommit = old.checkedRemoteCommit, remoteVersion = old.remoteVersion, checkIssue = old.checkIssue) else item
                }
                val supported = all.filter { ExtensionVersionClient.refUrl(it) != null }
                val start = snapshot.nextCheckOffset.takeIf { it in supported.indices } ?: 0
                val batch = supported.drop(start).take(20)
                val results = mutableMapOf<String, ExtensionUpdateMetadata>()
                var progressed = 0
                for (item in batch) {
                    if (generation != extensionListGeneration) return@launch
                    val checked = ExtensionVersionClient.inspect(item, extensionQuota)
                    results[item.id] = checked
                    // Leave the cursor on the first incomplete item; retry starts there even on small inventories.
                    if (checked.issue == ExtensionCheckIssue.RATE_LIMIT) break
                    progressed++
                }
                val inventory = page.copy(skipped = skipped, items = all.map { item ->
                    val result = results[item.id]
                    when {
                        result != null -> item.copy(remoteAhead = result.ahead, checkedRemoteCommit = result.remoteCommit,
                            remoteVersion = item.remoteVersion.takeIf { result.remoteCommit != null && result.remoteCommit == item.checkedRemoteCommit },
                            checkIssue = result.issue)
                        batch.any { it.id == item.id } -> item.copy(remoteAhead = null, remoteVersion = null, checkedRemoteCommit = null, checkIssue = null)
                        else -> item
                    }
                }, nextCheckOffset = (start + progressed).takeIf { it < supported.size } ?: 0)
                mutableState.update { ui -> if (generation != extensionListGeneration) ui else ui.copy(
                    extensionInventory = inventory, extensionError = when {
                        supported.isEmpty() -> "원격 브랜치를 확인할 수 있는 공개 GitHub 확장이 없습니다."
                        results.values.any { it.issue == ExtensionCheckIssue.RATE_LIMIT } -> "GitHub 요청 제한으로 검사를 멈췄어요. 한도가 회복된 뒤 다시 누르면 남은 항목부터 확인해요."
                        results.values.any { it.issue != null } -> "확인하지 못한 항목은 최신으로 표시하지 않아요. 항목을 열면 이유를 볼 수 있어요."
                        else -> ""
                    }) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (e: Exception) {
                mutableState.update { ui -> if (generation != extensionListGeneration) ui else ui.copy(
                    extensionError = "확장 검사 실패: ${e.userMessage()}",
                    extensionInventory = ui.extensionInventory?.let { inv -> inv.copy(items = inv.items.map {
                        it.copy(remoteAhead = null, remoteVersion = null, checkedRemoteCommit = null, checkIssue = null)
                    }) }) }
            } finally { mutableState.update { if (generation == extensionListGeneration) it.copy(extensionLoading = false) else it } }
        }
    }

    suspend fun loadExtensionDetailVersion(item: ManagedExtension) {
        val commit = item.checkedRemoteCommit ?: return
        if (item.remoteVersion != null || (item.remoteAhead ?: 0) <= 0) return
        val version = ExtensionVersionClient.detailVersion(item, extensionQuota) ?: return
        mutableState.update { ui ->
            val inventory = ui.extensionInventory ?: return@update ui
            ui.copy(extensionInventory = inventory.copy(items = inventory.items.map {
                if (it.sameCheckTarget(item) && it.checkedRemoteCommit == commit) it.copy(remoteVersion = version) else it
            }))
        }
    }

    fun updateCheckedExtensions(items: List<app.tavernbridge.launcher.model.ManagedExtension>) {
        val current = state.value
        val inventory = current.extensionInventory ?: return
        if (current.environment.processRunning || current.environment.recoveryPending || inventory.recoveryStatus != "none" ||
            items.isEmpty() || items.size > 1000 || items.distinctBy { it.id }.size != items.size ||
            items.any { !it.canUpdate || (it.remoteAhead ?: 0) <= 0 || it !in inventory.items }) return
        var completed = 0
        var changed = 0
        var identical = 0
        perform("extension-update", "확장 ${items.size}개 업데이트", "확장 작업을 완료했어요.",
            partialNotice = { "확장 ${completed}/${items.size}개 완료" },
            successMessageForResult = {
                listOfNotNull("업데이트 ${changed}개".takeIf { changed > 0 }, "변경 없이 동일 ${identical}개".takeIf { identical > 0 },
                    "변경 여부 미확인 ${completed - changed - identical}개".takeIf { completed > changed + identical }).joinToString(" · ")
            },
            onFinished = { if (state.value.mayRefreshVisibleExtensions()) loadExtensions() }) {
            var last: TermuxCommandResult? = null
            for (item in items) {
                operationCheckpoint()
                last = repository.changeExtension(item, app.tavernbridge.launcher.model.ExtensionAction.UPDATE)
                if (!last.isSuccess) {
                    if (last.exitCode == 130) operationCheckpoint()
                    return@perform last.inFailedExtensionBatch(completed, items.size, item.name)
                }
                when (extensionUpdateReceipt(last.stdout)) { "updated" -> changed++; "already-current" -> identical++ }
                completed++
            }
            requireNotNull(last)
        }
    }

    fun changeExtension(item: app.tavernbridge.launcher.model.ManagedExtension, action: app.tavernbridge.launcher.model.ExtensionAction) {
        if (state.value.environment.processRunning) { showMessage("확장을 변경하려면 서버를 먼저 종료해 주세요."); return }
        val defaultMessage = "확장 ${action.label} 작업을 완료했습니다."
        var dispatched = false
        var refreshed = false
        perform("extension-${action.argument}", "확장 ${action.label}", defaultMessage, onFinished = {
            if (dispatched && !refreshed && state.value.mayRefreshVisibleExtensions()) loadExtensions()
        }, successMessageForResult = { result ->
            if (action != ExtensionAction.UPDATE) defaultMessage else when (extensionUpdateReceipt(result.stdout)) {
                "updated" -> "${item.name}: 새 커밋으로 업데이트했어요."
                "already-current" -> "${item.name}: 이미 원격 브랜치와 같아 변경하지 않았어요."
                else -> "${item.name}: 작업은 완료됐지만 변경 여부를 확인하지 못했어요."
            }
        }) {
            dispatched = true
            repository.changeExtension(item, action).also { result ->
                if (result.isSuccess) runCatching { withoutOperationCancellation { repository.listExtensions() } }.onSuccess { inventory ->
                    val checkedCommit = result.stdout.lineSequence().filter { it.startsWith("extension_commit=") }
                        .singleOrNull()?.substringAfter('=')
                    mutableState.update { it.copy(extensionInventory = inventory.copy(items = inventory.items.map { fresh ->
                        if (action == ExtensionAction.UPDATE && extensionUpdateReceipt(result.stdout) != null && checkedCommit == fresh.commit &&
                            fresh.id == item.id && fresh.gitHubRepository == item.gitHubRepository && fresh.gitBranch == item.gitBranch)
                            fresh.copy(remoteAhead = 0) else fresh
                    })) }
                    refreshed = true
                }
            }
        }
    }

    fun recoverExtension() {
        if (state.value.environment.processRunning) { showMessage("서버를 종료한 뒤 확장을 복구해 주세요."); return }
        var dispatched = false
        perform("extension-recover", "중단된 확장 교체 복구", "확장 교체 상태를 확인하고 안전하게 복구했습니다.", onFinished = {
            if (dispatched && state.value.mayRefreshVisibleExtensions()) loadExtensions()
        }) {
            dispatched = true
            repository.recoverExtension().also { if (it.isSuccess) mutableState.update { state -> state.copy(extensionInventory = null) } }
        }
    }

    fun refreshLanAddresses() {
        if (state.value.lanLoading) return
        mutableState.update { it.copy(lanLoading = true) }
        viewModelScope.launch {
            try {
                val endpoints = withContext(kotlinx.coroutines.Dispatchers.IO) { app.tavernbridge.launcher.data.LanAddressInspector().inspect(state.value.configuredPort) }
                mutableState.update { it.copy(lanEndpoints = endpoints) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { showMessage("LAN 주소를 확인하지 못했습니다. Wi-Fi 연결을 확인해 주세요.") }
            finally { mutableState.update { it.copy(lanLoading = false) } }
        }
    }

    fun loadServerConfig() {
        val current = state.value
        if (current.serverConfigLoading || !current.environment.managerConnected || !current.environment.sillyTavernInstalled) return
        mutableState.update { it.copy(serverConfigLoading = true, serverConfigError = false) }
        viewModelScope.launch {
            try {
                val config = repository.readServerConfig()
                mutableState.update { it.copy(serverConfig = config) }
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (_: Exception) { mutableState.update { it.copy(serverConfig = null, serverConfigError = true) } }
            finally { mutableState.update { it.copy(serverConfigLoading = false) } }
        }
    }

    suspend fun serverConfigUsage(): app.tavernbridge.launcher.model.ChatCopyUsage? = try { repository.serverConfigUsage() }
        catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (_: Exception) { null }

    fun saveServerConfig(revision: String, changes: Map<app.tavernbridge.launcher.model.ServerSetting,String>) {
        val config = state.value.serverConfig ?: return
        if (commandEntryBlocked() || changes.isEmpty() || changes.any { !it.key.accepts(it.value) || it.key !in config.values }) return
        if (config.revision != revision) { showMessage("그 사이 설정 파일이 바뀌었어요. 다시 불러와 주세요."); loadServerConfig(); return }
        val message = "서버 설정을 저장했어요." + if (app.tavernbridge.launcher.model.ServerSetting.THUMB in changes)
            " 실리태번 화면을 새로고침해 주세요." else ""
        perform("server-config-write", "서버 설정 저장", message, onFinished = ::loadServerConfig,
            successMessageForResult = { maintenanceMessage(message, it) }) {
            maintainServer { repository.writeServerConfig(revision, changes) }
        }
    }

    fun saveLanSecurity(change: app.tavernbridge.launcher.model.LanSecurityChange): Boolean {
        if (commandEntryBlocked()) { change.close(); return false }
        perform("save-lan-security", "외부 접속 설정 저장", "외부 접속 설정을 저장했습니다.",
            onFinished = change::close,
            successMessageForResult = { maintenanceMessage(app.tavernbridge.launcher.model.lanSettingsSavedMessage(it.stdout), it) }) {
            try { maintainServer { repository.saveLanSecurity(change) } } finally { change.close() }
        }
        return true
    }

    fun saveBackupAnnotation(fileName: String, pinned: Boolean, memo: String) {
        if (state.value.isWorking) return
        val gate = app.tavernbridge.launcher.data.BackupJobState.annotationGate
        if (!gate.tryLock()) { showMessage("자동 백업 정리를 마친 뒤 다시 시도해 주세요."); return }
        try { runCatching { backupMetadataStore.save(fileName, pinned, memo) }
            .onSuccess { annotation -> mutableState.update { it.copy(backupAnnotations = it.backupAnnotations + (fileName to annotation), backupCleanupPreview = null) } }
            .onFailure { showMessage(it.userMessage()) }
        } finally { gate.unlock() }
    }

    fun previewBackupCleanup(keepLatest: Int, olderThanDays: Int) {
        if (commandEntryBlocked() || state.value.backupCleanupLoading) return
        mutableState.update { it.copy(backupCleanupLoading = true) }
        viewModelScope.launch {
            try {
                val backups = repository.listBackups()
                val annotations = backupMetadataStore.loadAll(backups.map { it.fileName })
                val preview = app.tavernbridge.launcher.model.BackupRetentionPolicy.preview(backups, annotations,
                    app.tavernbridge.launcher.model.BackupRetentionOptions(keepLatest, olderThanDays))
                mutableState.update { it.copy(backups = backups, backupAnnotations = annotations, backupCleanupPreview = preview) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) { showMessage(error.userMessage()) }
            finally { mutableState.update { it.copy(backupCleanupLoading = false) } }
        }
    }

    fun closeBackupCleanup() { mutableState.update { it.copy(backupCleanupPreview = null) } }

    fun deleteCleanupSelection(names: Set<String>) {
        val preview = state.value.backupCleanupPreview ?: return
        if (names.isEmpty()) return
        perform("delete-backup", "선택한 오래된 백업 정리", "선택한 백업 ${names.size}개를 삭제했습니다.") {
            check(names.size <= 200) { "한 번에 200개 이하로 선택해 주세요." }
            val backups = repository.listBackups()
            val annotations = backupMetadataStore.loadAll(backups.map { it.fileName })
            val selected = app.tavernbridge.launcher.model.BackupRetentionPolicy.selectedForDeletion(preview, names, backups, annotations)
            val result = repository.deleteBackupsChecked(selected)
            if (result.isSuccess) {
                mutableState.update { it.copy(backupCleanupPreview = null) }
                refreshBackupListAfterOperation()
            }
            result
        }
    }

    fun repair() = perform(
        operation = "repair",
        label = "설치 점검·복구 중",
        successMessage = "사용자 데이터는 유지하고 실행 패키지를 복구했습니다.",
        retryAction = RetryAction.REPAIR,
        allowRuntimeSyncOffer = true,
    ) { repository.repair() }

    fun confirmRuntimeLibrarySync(failureAt: Long) {
        val current = state.value
        if (!current.canOfferRuntimeSync(failureAt)) return
        if (commandEntryBlocked()) return
        // Consume the exact offer synchronously, before a second tap can launch a job.
        if (!mutableState.compareAndSet(current, current.copy(runtimeSyncFailureAt = null))) return
        perform("repair", "설치 점검·복구 중", "실행 환경을 다시 확인하고 실행 패키지를 복구했습니다.",
            retryAction = RetryAction.REPAIR) { repository.repairAfterRuntimeSyncConsent() }
    }

    fun loadLogs() = loadDiagnosticText(DiagnosticPanel.SERVER_LOG, reportErrors = true)

    fun refreshLogsSilently() = loadDiagnosticText(DiagnosticPanel.SERVER_LOG, reportErrors = false)

    fun loadPreviousServerLogs() = loadDiagnosticText(DiagnosticPanel.PREVIOUS_SERVER_LOG, reportErrors = true)

    fun loadOperationHistory() = loadDiagnosticText(DiagnosticPanel.WORK_HISTORY, reportErrors = true)

    fun refreshHistorySilently() {
        val recent = operationResultStore.recent()
        mutableState.update { it.copy(recentResults = recent) }
        if (recent.isEmpty() && state.value.environment.managerConnected && !state.value.isWorking)
            loadDiagnosticText(DiagnosticPanel.WORK_HISTORY, reportErrors = false)
    }

    private fun loadDiagnosticText(panel: DiagnosticPanel, reportErrors: Boolean) {
        if (reportErrors && deferRead(VisibleReadQueue.ListKind.LOG, {
                it.section == MainSection.LOGS && it.toolPage == ToolPage.LOGS && it.diagnosticPanel == panel
            }) { loadDiagnosticText(panel, true) }) return
        val s = state.value
        // server-logs is a bounded, read-only tail. Never bootstrap or read across an installation swap.
        if (!appInForeground || refreshJob?.isActive == true || !s.termuxSetup.verified ||
            (s.isWorking && (panel != DiagnosticPanel.SERVER_LOG || !s.canReadLiveServerLog()))) return
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

    private fun updateDiagnosticState(change: (LauncherUiState) -> LauncherUiState) {
        mutableState.update(change)
        val seen = state.value.acknowledgedDiagnosticKeys
        if (seen != diagnosticPreferences.getStringSet("seen", emptySet()).orEmpty())
            diagnosticPreferences.edit().putStringSet("seen", seen).apply()
    }

    internal fun acknowledgeDiagnostics(shown: DiagnosticReport) = updateDiagnosticState { ui ->
        if (ui.diagnosticReport !== shown) ui
        else ui.copy(acknowledgedDiagnosticKeys = ui.acknowledgedDiagnosticKeys + shown.coveredKeys)
    }

    fun scanChats(more: Boolean) {
        val previous = state.value.diagnosticReport?.chatScan
        val cursor = if (more) previous?.next?.takeIf { it.isNotEmpty() } ?: return else ""
        perform("chat-scan", "채팅 파일 점검", "채팅 파일을 확인했어요.") {
            repository.chatScan(cursor).also { result ->
                if (result.isSuccess) {
                    check(!result.stdoutTruncated) { "검사 결과가 잘렸어요. 다시 검사해 주세요." }
                    val page = ChatScanPage.parse(result.stdout)
                    updateDiagnosticState { ui ->
                        val all = if (more && previous != null) previous.append(page) else page
                        ui.withDiagnosticReport(ui.diagnosticReport?.withChat(all)).copy(
                            chatUndo = if (more) ui.chatUndo else page.receipts, chatCompletion = "")
                    }
                }
            }
        }
    }

    fun repairChats(rows: List<ChatDamage>, mode: String) {
        val known = state.value.diagnosticReport?.chatScan?.issues?.associateBy { it.path } ?: return
        if (rows.isEmpty() || rows.any { known[it.path] != it } ||
            rows.distinctBy { it.path }.size != rows.size || (rows.size > 1 && (mode != "restore-backup" || rows.any { !it.restorable }))) return
        val copy = mode.endsWith("-copy")
        val success = if (copy) "원본을 그대로 두고 새 파일에 저장했어요." else "채팅을 되찾았어요."
        perform("chat-repair", "채팅 파일 복구", success) {
            var last: TermuxCommandResult? = null
            for ((i,row) in rows.withIndex()) {
                operationCheckpoint()
                val result = repository.repairChat(row, mode)
                if (!result.isSuccess) {
                    mutableState.update { it.copy(chatCompletion = "${rows.size}개 중 ${i}개 완료 · 나머지는 변경하지 않았어요.") }
                    return@perform result
                }
                val id = result.stdout.lineSequence().single { it.startsWith("trash_id=") }.substringAfter('=')
                check(!result.stdoutTruncated && ChatUndo.validId(id))
                updateDiagnosticState { ui ->
                    val page = ui.diagnosticReport?.chatScan
                    val refreshed = if (copy || page == null) ui.diagnosticReport else
                        ui.diagnosticReport.withChat(page.copy(ok = page.ok + 1, rows = page.rows.filterNot { it.path == row.path }))
                    ui.withDiagnosticReport(refreshed).copy(chatUndo = listOf(ChatUndo(id,row.label)) + ui.chatUndo,
                        chatCompletion = if (rows.size == 1) "${row.label} · $success" else "${rows.size}개 중 ${i+1}개 완료")
                }
                last = result
            }
            checkNotNull(last)
        }
    }

    fun undoChat(item: ChatUndo) {
        if (item !in state.value.chatUndo) return
        perform("chat-undo", "채팅 복구 실행 취소", "복구 전으로 되돌렸어요.") {
            repository.undoChat(item.id).also { result ->
                if (result.isSuccess) updateDiagnosticState { ui -> ui.withDiagnosticReport(
                    ui.diagnosticReport?.let { r -> r.copy(chatScan = null,
                        items = r.items.map { if (it.id == "chat_integrity") it.copy(value = "다시 검사해 주세요", status = app.tavernbridge.launcher.model.DiagnosticStatus.INFO) else it }) }
                ).copy(chatUndo = ui.chatUndo - item, chatCompletion = "복구 전으로 되돌렸어요. 채팅 파일을 다시 검사해 주세요.") }
            }
        }
    }

    fun runDiagnostics() = runDiagnostics(fromSettings = false)

    fun makeSettingsReport() = runDiagnostics(fromSettings = true)

    private var androidOnlyReportJob: Job? = null

    private fun runDiagnostics(fromSettings: Boolean) {
        if (androidOnlyReportJob?.isActive == true) return
        // A settings report can be useful *because* permissions or setup are missing. Only
        // the normal Tools workflow may route the user to setup; never initiate a first probe here.
        if (commandEntryBlocked(checkTermuxPrerequisites = !fromSettings)) return
        if (fromSettings && !state.value.readyForTermuxDiagnostic()) {
            androidOnlyReportJob = viewModelScope.launch {
                try {
                    val report = withContext(Dispatchers.IO) { repository.diagnose(allowTermuxChecks = false) }
                    updateDiagnosticState { it.withDiagnosticReport(report).copy(message = "Android 항목만 확인했어요. Termux 내부 검사는 실행하지 않았어요.") }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    mutableState.update { it.copy(error = error.userMessage()) }
                }
            }
            return
        }
        if (!fromSettings) openDiagnostics(DiagnosticPanel.OVERVIEW)
        viewModelScope.launch {
            val operationStartedAt = System.currentTimeMillis()
            progressLogHistory.clear()
            mutableState.update {
                it.copy(
                    isWorking = true,
                    workingLabel = "전체 환경 진단 중",
                    workingStartedAtMillis = operationStartedAt,
                    settingsDiagnosticStartedAtMillis = operationStartedAt.takeIf { fromSettings },
                    workProgress = null,
                )
            }
            LocalProgressJournal("diagnose", ::publishProgress).phase(
                "진단 준비", "Android 설정과 Termux 환경을 차례로 검사합니다.")
            val progressJob = pollOperationProgress("diagnose", operationStartedAt)
            try {
                val report = cancellableOperation("diagnose", operationStartedAt) { repository.diagnose() }
                progressJob.cancel()
                val summary = operationSummary(
                    operation = "diagnose",
                    label = "전체 환경 진단 중",
                    detail = "전체 환경 검사를 완료했습니다.",
                    succeeded = true,
                    startedAtMillis = operationStartedAt,
                )
                updateDiagnosticState {
                    it.withDiagnosticReport(report).copy(
                        isWorking = false,
                        workingLabel = "",
                        workProgress = null,
                        chatUndo = report.chatScan?.receipts.orEmpty(),
                        chatCompletion = "",
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
                    retryAction = retryActionAfterFailure(errorCode,
                        if (fromSettings) RetryAction.SETTINGS_DIAGNOSE else RetryAction.DIAGNOSE),
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

    fun openTermux() = openDeviceScreen("Termux") {
        if (!repository.openTermux()) mutableState.update { it.copy(error = "Termux를 열 수 없습니다.") }
    }

    fun openTermuxDownload() = openDeviceScreen("Termux 다운로드 페이지", repository::openTermuxDownload)
    fun openPermissionSettings() = openDeviceScreen("런처 권한 설정", repository::openLauncherPermissionSettings)
    fun browserOptions() = repository.browserOptions(state.value.environment.port)

    fun preferredBrowser() = repository.preferredBrowser(state.value.environment.port)

    fun savePreferredBrowser(packageName: String) {
        val saved = repository.savePreferredBrowser(state.value.environment.port, packageName)
        showMessage(if (saved) "열 앱을 저장했어요." else "앱을 찾을 수 없어요. 다시 선택해 주세요.")
    }

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
        } catch (error: Exception) {
            showMessage("Termux 실시간 로그를 열지 못했어요. ${error.userMessage()}")
        }
    }
    private fun openDeviceScreen(label: String, open: () -> Unit) {
        try { open() }
        catch (_: android.content.ActivityNotFoundException) { showMessage("이 기기에서 ‘${label}’ 화면을 열 앱이 없어요.") }
        catch (_: SecurityException) { showMessage("기기 설정에서 ‘${label}’ 화면 열기를 허용하지 않았어요.") }
    }
    fun openBatteryOptimizationSettings() = openDeviceScreen("배터리 최적화 설정", repository::openBatteryOptimizationSettings)
    fun openDeviceBatterySettings() = openDeviceScreen("배터리 설정", repository::openDeviceBatterySettings)
    fun openDeviceStorageSettings() = openDeviceScreen("휴대폰 저장 공간", repository::openDeviceStorageSettings)
    fun openTermuxAppSettings() = openDeviceScreen("Termux 앱 정보", repository::openTermuxAppSettings)
    fun openLauncherAppSettings() = openDeviceScreen("런처 앱 정보", repository::openLauncherAppSettings)
    fun openTermuxNotificationSettings() = openDeviceScreen("Termux 알림 설정", repository::openTermuxNotificationSettings)
    fun openDownloadsFolder() {
        if (!repository.openDownloadsFolder()) {
            mutableState.update { it.copy(error = "Download 폴더를 열 수 없습니다. 휴대폰의 파일 앱에서 Download를 열어 주세요.") }
        }
    }

    fun openSillyTavernFolder() {
        if (state.value.fileBrowserMutating) return
        // Settings and Tools share the same destination. A second full-screen dialog
        // would obscure the app's bottom notice and the task mini bar.
        mutableState.update { it.visit(MainSection.LOGS, ToolPage.FILES) }
        loadSillyTavernFolder("")
    }

    fun loadSillyTavernFolder(relativePath: String) {
        if (deferRead(VisibleReadQueue.ListKind.FILES, {
                it.section == MainSection.LOGS && it.toolPage == ToolPage.FILES && !it.fileBrowserMutating
            }) { loadSillyTavernFolder(relativePath) }) return
        if (state.value.fileBrowserMutating || commandEntryBlocked(allowWhileWorking = true)) return
        closeFilePreview()
        fileBrowserJob?.cancel()
        fileBrowserJob = viewModelScope.launch {
            mutableState.update {
                it.copy(
                    fileBrowserOpen = true,
                    fileBrowserPath = relativePath,
                    fileBrowserLoading = true,
                    fileListReadFailed = false,
                    fileBrowserError = "",
                    fileBrowserEntries = if (it.fileBrowserPath == relativePath) it.fileBrowserEntries else emptyList(),
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
                    it.copy(fileBrowserEntries = emptyList(), fileBrowserLoading = false, fileListReadFailed = true, fileBrowserError = error.userMessage())
                }
            }
        }
    }

    fun closeSillyTavernFolder() {
        if (state.value.fileBrowserMutating) return
        closeFilePreview()
        fileBrowserJob?.cancel()
        mutableState.update { it.copy(fileBrowserOpen = false, fileBrowserEntries = emptyList(),
            fileBrowserLoading = false, fileBrowserError = "", fileBrowserNotice = "", editingFile = null, filePreview = null) }
    }

    private fun leaveFileTools() {
        if (state.value.fileBrowserMutating) {
            // Hide the UI only. Preserve the active request and its path for the global progress sheet.
            mutableState.update { it.copy(fileBrowserOpen = false) }
        } else closeSillyTavernFolder()
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

    suspend fun readSillyTavernTrash(cursor: String) = repository.readTrashPage(cursor)

    fun restoreTrashedFile(id: String) {
        if (!app.tavernbridge.launcher.model.tavernTrashId.matches(id)) return
        mutateTavernFiles("restore-st-trash", "휴지통 항목 복구", "원래 위치로 복구했어요.") {
            repository.restoreSillyTavernEntry(id).also { result ->
                if (result.isSuccess) mutableState.update { it.copy(lastTrashedEntryId = repository.lastTrashedEntryId(),
                    restoredFilePaths = setOfNotNull(app.tavernbridge.launcher.data.FileManagementProtocol.restoredPath(result.stdout))) }
            }
        }
    }

    fun undoLastTrashedEntry() {
        val ids = repository.trashReceipts().asReversed().ifEmpty { return }
        mutateTavernFiles("restore-st-trash", "휴지통 항목 복구", "되돌렸어요") {
            var last: TermuxCommandResult? = null
            val restored = mutableSetOf<String>()
            for (id in ids) {
                operationCheckpoint()
                last = repository.restoreSillyTavernEntry(id)
                if (!last.isSuccess) return@mutateTavernFiles last
                app.tavernbridge.launcher.data.FileManagementProtocol.restoredPath(last.stdout)?.let(restored::add)
                mutableState.update { it.copy(lastTrashedEntryId = repository.lastTrashedEntryId(), restoredFilePaths = restored.toSet()) }
            }
            requireNotNull(last)
        }
    }

    fun trashSillyTavernEntries(entries: List<TavernFileEntry>) {
        val selected = entries.distinctBy { it.relativePath }
        val parent = state.value.fileBrowserPath
        if (selected.isEmpty() || selected.size > app.tavernbridge.launcher.model.MAX_FILE_SELECTION || selected.any {
                it.relativePath.substringBeforeLast('/', "") != parent || !it.revision.matches(Regex("[a-f0-9]{64}"))
            }) {
            mutableState.update { it.copy(fileBrowserError = "현재 폴더의 항목을 ${app.tavernbridge.launcher.model.MAX_FILE_SELECTION}개 이하로 다시 선택해 주세요.") }
            return
        }
        mutateTavernFiles("delete-st", "선택 항목을 휴지통으로 이동", "선택한 ${selected.size}개 항목을 휴지통으로 이동했습니다.") {
            var completed = 0
            var last: TermuxCommandResult? = null
            for (entry in selected) {
                operationCheckpoint()
                try {
                    last = repository.trashSillyTavernEntry(entry.relativePath, entry.revision, appendUndo = completed > 0).also { it.requireSuccess() }
                    completed++
                    partialFileActionNotice = "휴지통 이동이 확인된 항목: $completed/${selected.size}개. 안전 사본·휴지통 관리에서 복원할 수 있습니다."
                    mutableState.update { it.copy(lastTrashedEntryId = repository.lastTrashedEntryId(), fileBrowserNotice = partialFileActionNotice) }
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (error: Exception) {
                    throw IllegalStateException("${error.userMessage()}\n휴지통 이동이 확인된 항목: $completed/${selected.size}개. 나머지 상태는 목록을 새로고침해 확인해 주세요.", error)
                }
            }
            requireNotNull(last)
        }
    }

    fun previewSillyTavernFile(path: String) {
        if (commandEntryBlocked(allowWhileWorking = true) || state.value.fileBrowserLoading) return
        filePreviewJob?.cancel()
        val request = ++previewGeneration
        mutableState.update { it.copy(pendingFilePreviewPath = path, fileBrowserError = "", filePreview = null) }
        val parent = state.value.fileBrowserPath
        filePreviewJob = viewModelScope.launch {
            try {
                val preview = repository.previewSillyTavernFile(path)
                mutableState.update { if (request == previewGeneration && it.fileBrowserOpen && it.fileBrowserPath == parent &&
                    it.pendingFilePreviewPath == path) it.copy(filePreview = preview) else it }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) { if (request == previewGeneration) mutableState.update { it.copy(fileBrowserError = error.userMessage()) } }
            finally { if (request == previewGeneration) {
                filePreviewJob = null
                mutableState.update { it.copy(pendingFilePreviewPath = null) }
            } }
        }
    }

    fun closeFilePreview() {
        ++previewGeneration
        filePreviewJob?.cancel(); filePreviewJob = null
        mutableState.update { it.copy(filePreview = null, pendingFilePreviewPath = null) }
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
        partialFileActionNotice = ""
        progressLogHistory.clear()
        mutableState.update { it.copy(fileBrowserMutating = true, restoredFilePaths = emptySet(), isWorking = true, workingLabel = label,
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

    fun beginEncryptedBackup(mode: EncryptedBackupMode) {
        if (state.value.isWorking || state.value.backupTransfer != null || state.value.encryptedBackup != null) return
        mutableState.update { it.copy(encryptedBackup = EncryptedBackupState(UUID.randomUUID().toString(), mode)) }
    }

    fun encryptedPickerOpened(id: String, source: Boolean): Boolean {
        val transfer = state.value.encryptedBackup?.takeIf { it.id == id } ?: return false
        if (transfer.phase != if (source) EncryptedBackupPhase.PICK_SOURCE else EncryptedBackupPhase.PICK_DESTINATION) return false
        mutableState.update { it.copy(encryptedBackup = transfer.copy(phase = if (source) EncryptedBackupPhase.SOURCE_OPEN else EncryptedBackupPhase.DESTINATION_OPEN)) }
        return true
    }

    fun selectEncryptedBackupSource(id: String?, uri: Uri?) {
        val transfer = state.value.encryptedBackup?.takeIf { it.id == id && it.phase == EncryptedBackupPhase.SOURCE_OPEN } ?: return
        if (uri == null) { cancelEncryptedBackup(); return }
        encryptedBackupSource = uri
        mutableState.update { it.copy(encryptedBackup = transfer.copy(phase = EncryptedBackupPhase.PASSWORD)) }
    }

    fun submitEncryptedBackupPassword(password: CharArray) {
        val transfer = state.value.encryptedBackup?.takeIf { it.phase == EncryptedBackupPhase.PASSWORD }
        if (transfer == null) { password.fill('\u0000'); return }
        encryptedBackupPassword?.fill('\u0000')
        encryptedBackupPassword = password
        if (transfer.mode == EncryptedBackupMode.EXPORT) mutableState.update { it.copy(encryptedBackup = transfer.copy(phase = EncryptedBackupPhase.PICK_DESTINATION)) }
        else runEncryptedBackup(null)
    }

    fun selectEncryptedBackupDestination(id: String?, uri: Uri?) {
        if (state.value.encryptedBackup?.let { it.id == id && it.phase == EncryptedBackupPhase.DESTINATION_OPEN } != true) return
        if (uri == null) { cancelEncryptedBackup(); return }
        runEncryptedBackup(uri)
    }

    private fun runEncryptedBackup(destination: Uri?) {
        val transfer = state.value.encryptedBackup ?: return
        val source = encryptedBackupSource ?: return
        val password = encryptedBackupPassword ?: return
        encryptedBackupPassword = null
        val started = System.currentTimeMillis()
        val operation = if (transfer.mode == EncryptedBackupMode.EXPORT) "encrypt-backup" else "decrypt-backup"
        mutableState.update { it.copy(encryptedBackup = transfer.copy(phase = EncryptedBackupPhase.RUNNING),
            lastOperationResult = operationSummary(operation, "암호화 백업 처리", "완료 여부를 확인하지 못했습니다. 설치 데이터와 원본은 변경하지 않았습니다. 저장 위치의 미완성 파일을 확인해 주세요.", false, started, "OPERATION_INTERRUPTED")) }
        encryptedBackupJob = viewModelScope.launch {
            var lastProgress = 0L
            val progress: (Long, Long) -> Unit = { bytes, total ->
                val now = android.os.SystemClock.elapsedRealtime()
                if (bytes == 0L || bytes == total || now - lastProgress >= 250) {
                    lastProgress = now
                    mutableState.update { current -> if (current.encryptedBackup?.id == transfer.id) current.copy(encryptedBackup = current.encryptedBackup.copy(bytes = bytes, total = total)) else current }
                }
            }
            try {
                if (transfer.mode == EncryptedBackupMode.EXPORT) {
                    encryptedBackupFiles.encrypt(source, requireNotNull(destination), password, progress)
                    mutableState.update { it.copy(encryptedBackup = null, message = "암호화 백업을 저장했습니다. 원본 ZIP은 그대로 남아 있습니다.",
                        lastOperationResult = operationSummary(operation, "암호화 백업 저장", "암호화 백업을 저장했습니다. 원본 ZIP은 유지했습니다.", true, started)) }
                    encryptedBackupSource = null
                } else {
                    decryptedBackupFile = encryptedBackupFiles.decrypt(source, password, progress)
                    mutableState.update { it.copy(encryptedBackup = transfer.copy(phase = EncryptedBackupPhase.RESTORE_CONFIRM),
                        lastOperationResult = operationSummary(operation, "암호화 백업 인증", "암호화 백업 인증을 마쳤습니다. 아직 설치 데이터는 복원하지 않았습니다.", true, started)) }
                }
            } catch (cancelled: CancellationException) {
                mutableState.update { it.copy(encryptedBackup = null, message = "암호화 백업 처리를 중단했습니다. 원본은 유지됩니다. 저장 위치의 미완성 파일을 확인해 주세요.",
                    lastOperationResult = operationSummary(operation, "암호화 백업 처리", "작업을 중단했습니다. 원본과 설치 데이터는 유지했습니다.", false, started, "OPERATION_CANCELLED")) }
                encryptedBackupSource = null
                throw cancelled
            } catch (error: Exception) {
                val detail = error.message ?: "암호화 백업 처리에 실패했습니다. 원본과 설치 데이터는 유지했습니다."
                mutableState.update { it.copy(encryptedBackup = transfer.copy(phase = EncryptedBackupPhase.ERROR, error = detail),
                    lastOperationResult = operationSummary(operation, "암호화 백업 처리", detail, false, started, "ENCRYPTED_BACKUP_FAILED")) }
            } finally { password.fill('\u0000') }
        }
        encryptedBackupJob?.invokeOnCompletion { password.fill('\u0000') }
    }

    fun cancelEncryptedBackup() {
        if (state.value.encryptedBackup?.phase == EncryptedBackupPhase.RUNNING) { encryptedBackupJob?.cancel(); return }
        encryptedBackupPassword?.fill('\u0000'); encryptedBackupPassword = null
        encryptedBackupSource = null
        encryptedBackupFiles.remove(decryptedBackupFile); decryptedBackupFile = null
        mutableState.update { it.copy(encryptedBackup = null) }
    }

    fun restoreDecryptedBackup(backupBefore: Boolean) {
        val file = decryptedBackupFile ?: return
        if (state.value.encryptedBackup?.phase != EncryptedBackupPhase.RESTORE_CONFIRM || state.value.isWorking) return
        if (state.value.environment.processRunning) { showMessage("복원하기 전에 서버를 종료해 주세요."); return }
        mutableState.update { it.copy(encryptedBackup = null) }
        if (commandEntryBlocked()) { encryptedBackupFiles.remove(file); decryptedBackupFile = null; return }
        decryptedBackupFile = null; encryptedBackupSource = null
        perform("import-backup", "암호화 백업 복원", "암호화 백업의 복원을 완료했습니다. 원본 암호화 파일은 유지했습니다.",
            onFinished = { encryptedBackupFiles.remove(file) }) {
            try {
                val result = protectBeforeReplacement(backupBefore, { backupBeforeReplacement("import-backup") }) {
                    repository.importBackup(encryptedBackupFiles.uri(file))
                }
                if (result.isSuccess) refreshBackupListAfterOperation()
                result
            } finally { encryptedBackupFiles.remove(file) }
        }
    }

    override fun onCleared() {
        encryptedBackupPassword?.fill('\u0000'); encryptedBackupPassword = null
        encryptedBackupFiles.remove(decryptedBackupFile); decryptedBackupFile = null
        super.onCleared()
    }

    private val consumedEntryIds = ArrayDeque<String>()
    fun requestEntryAction(request: app.tavernbridge.launcher.entry.LauncherEntryRequest) {
        if (request.id in consumedEntryIds) return
        consumedEntryIds.addLast(request.id)
        while (consumedEntryIds.size > 32) consumedEntryIds.removeFirst()
        if (request.action == app.tavernbridge.launcher.entry.LauncherEntryAction.OPEN) {
            mutableState.update { it.visit(MainSection.HOME, reset = true) }
        } else mutableState.update { it.copy(pendingEntry = request) }
    }

    fun entryActionGate(): app.tavernbridge.launcher.entry.LauncherEntryGate? {
        val current = state.value
        val request = current.pendingEntry ?: return null
        return app.tavernbridge.launcher.entry.LauncherEntryPolicy.evaluate(request.action, appInForeground,
            current.termuxSetup.verified && current.environment.commandPermissionGranted && current.environment.managerConnected && current.environment.sillyTavernInstalled,
            current.isWorking || current.environment.operationActive || current.backupTransfer != null || current.encryptedBackup != null,
            current.environment.recoveryPending || current.environment.extensionRecovery != "none", current.environment.processRunning)
    }

    fun dismissEntryAction() { mutableState.update { it.copy(pendingEntry = null) } }

    fun confirmEntryAction() {
        val gate = entryActionGate() ?: return
        dismissEntryAction()
        when (gate) {
            app.tavernbridge.launcher.entry.LauncherEntryGate.CONFIRM_START -> start()
            app.tavernbridge.launcher.entry.LauncherEntryGate.CONFIRM_STOP -> stop()
            app.tavernbridge.launcher.entry.LauncherEntryGate.CONFIRM_OPEN -> Unit
            else -> showMessage("현재 상태에서는 실행할 수 없습니다. 런처 화면에서 상태를 확인해 주세요.")
        }
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
                    mutableState.update { it.visit(MainSection.BACKUPS).copy(
                        error = null, message = "이전 백업의 선택 정보가 없습니다. 백업할 항목을 다시 선택해 주세요.") }
                    loadBackups()
                } else createBackup(request.categories, request.includeSecrets, request.customFolders)
            }
            RetryAction.REPAIR -> repair()
            RetryAction.DIAGNOSE -> runDiagnostics()
            RetryAction.SETTINGS_DIAGNOSE -> makeSettingsReport()
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

    private fun deferRead(kind: VisibleReadQueue.ListKind, visible: (LauncherUiState) -> Boolean, read: () -> Unit): Boolean =
        deferredReads.defer(kind, refreshJob, {
            val s = state.value
            appInForeground && s.environment.managerConnected && s.termuxSetup.verified && visible(s)
        }, read)

    private fun commandEntryBlocked(requireManager: Boolean = true, allowWhileWorking: Boolean = false,
        notifyBusy: Boolean = true, checkTermuxPrerequisites: Boolean = true): Boolean {
        val current = state.value
        val busyReason = when {
            current.backupTransfer != null || current.encryptedBackup != null -> "백업 파일 작업을 마친 뒤 다시 눌러 주세요."
            refreshJob?.isActive == true -> "Termux 연결 상태를 확인하고 있어요. 확인이 끝나면 다시 눌러 주세요."
            current.isWorking && !allowWhileWorking -> "진행 중인 작업을 마친 뒤 다시 눌러 주세요."
            else -> null
        }
        if (busyReason != null) {
            if (notifyBusy) showMessage(busyReason)
            return true
        }
        if (!checkTermuxPrerequisites) return false
        if (!current.termuxSetup.verified || !current.environment.termuxInstalled ||
            !current.environment.commandPermissionGranted || (requireManager && !current.environment.managerConnected)) {
            mutableState.update { it.visit(MainSection.SETUP) }
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
        onFinished: () -> Unit = {},
        allowRuntimeSyncOffer: Boolean = false,
        onRefreshChecked: ((Boolean) -> Unit)? = null,
        partialNotice: () -> String = { "" },
        block: suspend () -> TermuxCommandResult,
    ) {
        if (commandEntryBlocked(requireManager = operation != "connect-manager")) { onFinished(); return }
        operationCancellationRequested = false
        viewModelScope.launch {
            val operationStartedAt = System.currentTimeMillis()
            progressLogHistory.clear()
            selectionListGeneration++
            versionListGeneration++
            extensionListGeneration++
            mutableState.update {
                it.closeOperationDialogsForResult().copy(isWorking = true, extensionLoading = false, workingLabel = label,
                    workingStartedAtMillis = operationStartedAt, dispatchedAtMillis = operationStartedAt,
                    dispatchedOperation = operation, lastCreatedBackupName = if (operation == "backup") null else it.lastCreatedBackupName,
                    runtimeSyncFailureAt = null, workProgress = null)
            }
            LocalProgressJournal(operation, ::publishProgress).phase(
                "작업 요청 준비", "$label 요청을 Termux에 전달하고 있습니다.")
            val progressJob = pollOperationProgress(operation, operationStartedAt)
            var committedResult: OperationResultSummary? = null
            var verifiedLinkerFailure = false
            try {
                val result = cancellableOperation(operation, operationStartedAt, backupRequest, partialNotice) { block() }
                verifiedLinkerFailure = allowRuntimeSyncOffer && operation == "repair" && result.isRepairLinkerFailure
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
                        isWorking = false,
                        workingLabel = "",
                        workProgress = null,
                        message = resolvedSuccessMessage,
                        lastOperationResult = refreshedResult,
                    )
                }
                onRefreshChecked?.invoke(!check.refreshFailed)
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
                if (!(operation == "safety-delete" && liveSafetyBatch != null) &&
                    reconnectTimedOutOperation(errorCode, operation, operationStartedAt)) return@launch
                // Rollback can succeed even when a subsequent repair step fails.
                // Conversely a failed restore may now have a retained journal.
                // Refresh read-only state without replacing the actual error.
                val recoveryEnvironment = if (state.value.environment.recoveryPending ||
                    errorCode == "RESTORE_ROLLBACK_REQUIRED" ||
                    operation in setOf("restore", "restore-selected", "change-version", "import-backup", "import-merge", "import-install", "repair", "backup", "save-server-connection", "save-lan-security", "safety-restore", "server-config-write")) {
                    progressJob.cancel()
                    try { repository.inspect() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                } else null
                val failure = operationSummary(
                    operation = operation, label = label, detail = diagnostic.substringAfter(']', diagnostic).trim(),
                    succeeded = false, startedAtMillis = operationStartedAt, errorCode = errorCode,
                    retryAction = retryActionAfterFailure(errorCode, retryAction), backupRequest = backupRequest,
                )
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
                        lastOperationResult = failure,
                        runtimeSyncFailureAt = failure.completedAtMillis.takeIf { verifiedLinkerFailure },
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
        }.invokeOnCompletion { onFinished() }
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
        val batch = liveSafetyBatch?.takeIf { operation == "safety-delete" }
        val outcome = OperationResultSummary(
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
        )
        val summary = batch?.finish(outcome) ?: outcome
        if (batch == null) operationResultStore.save(summary)
        else {
            if (operationResultStore.saveConfirmed(summary)) safetyDeleteStore.clear()
            liveSafetyBatch = null
        }
        return summary
    }

    private fun isInterruptedSafetyChild(operation: String?, startedAt: Long): Boolean =
        (operation == "safety-delete" && interruptedSafetyBatch?.complete == false) ||
            state.value.lastOperationResult.isUnfinishedDeleteStep(operation, startedAt)

    private fun operationLabel(operation: String): String = when (operation) {
        "import-preview" -> "ZIP 내용 확인"
        "import-merge" -> "PC ZIP 안전하게 합치기"
        "library-trash" -> "자료를 휴지통으로 옮기기"
        "library-asset-change" -> "자료 파일 변경"
        "library-card-change" -> "캐릭터 카드 이름 변경·복사"
        "library-undo" -> "휴지통에서 자료 되돌리기"
        "library-import" -> "선택한 자료 가져오기"
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
        "chat-scan" -> "채팅 파일 점검"
        "chat-repair" -> "채팅 파일 복구"
        "chat-undo" -> "채팅 복구 실행 취소"
        "restore" -> "백업 복원"
        "restore-selected" -> "선택한 캐릭터·채팅 복원"
        "change-version" -> "이전 버전으로 변경"
        "save-lan-security" -> "외부 접속 설정 저장"
        "server-config-write" -> "서버 설정 저장"
        "extension-update" -> "확장 업데이트"
        "extension-enable", "extension-disable" -> "확장 사용 설정 변경"
        "extension-trash" -> "확장 휴지통 이동"
        "extension-recover" -> "확장 교체 복구"
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

    private fun Throwable.userMessage(): String = operationErrorSummary(message)
}
