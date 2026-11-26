package app.tavernbridge.launcher.model

enum class AppTheme(val label: String) {
    SYSTEM("시스템 설정"),
    LIGHT("라이트"),
    DARK("다크"),
}

enum class SillyBranch(val value: String, val label: String, val description: String) {
    RELEASE("release", "Release", "안정적인 정식 버전"),
    STAGING("staging", "Staging", "새 기능을 먼저 시험하는 버전"),
    ;

    companion object {
        fun from(value: String?): SillyBranch? = entries.firstOrNull { it.value == value }
    }
}

enum class MainSection(val label: String) {
    HOME("홈"),
    BACKUPS("백업"),
    LOGS("도구"),
    SETTINGS("설정"),
    // Not a top-level tab: entered by the installation guide or Tools > installation.
    SETUP("설치"),
}

val launcherTabs = listOf(MainSection.HOME, MainSection.BACKUPS, MainSection.LOGS, MainSection.SETTINGS)

enum class SettingsPanel(val label: String) {
    GENERAL("앱 정보"),
    SERVER("서버 연결"),
    BATTERY("배터리"),
}

data class BrowserOption(
    val packageName: String,
    val label: String,
)

enum class ServerReadiness {
    OFFLINE,
    STARTING,
    READY,
    UNRESPONSIVE,
    PORT_CONFLICT,
}

data class EnvironmentStatus(
    val termuxInstalled: Boolean = false,
    val commandPermissionGranted: Boolean = false,
    val termuxBatteryUnrestricted: Boolean = false,
    val managerConnected: Boolean = false,
    val managerVersion: String = "",
    val gitInstalled: Boolean = false,
    val gitVersion: String = "",
    val nodeInstalled: Boolean = false,
    val nodeVersion: String = "",
    val sillyTavernInstalled: Boolean = false,
    val branch: SillyBranch? = null,
    val commit: String = "",
    val sillyTavernVersion: String = "",
    val workingTreeClean: Boolean = true,
    val modifiedFiles: List<String> = emptyList(),
    val processRunning: Boolean = false,
    val operationActive: Boolean = false,
    val recoveryPending: Boolean = false,
    val recoveryMessage: String = "",
    val serverReachable: Boolean = false,
    val portListening: Boolean = false,
    val port: Int = 8000,
    // File value can differ from the launcher's explicit command-line port.
    val configPort: Int? = null,
    val externalAccessEnabled: Boolean = false,
    val basicAuthEnabled: Boolean = false,
    val perUserBasicAuth: Boolean = false,
    val extensionRecovery: String = "none",
    val whitelist: List<String> = listOf("::1", "127.0.0.1"),
    val sessionStartedEpoch: Long = 0,
    val sessionStartedAt: String = "",
    val lastServerAction: String = "",
    val lastServerActionAt: String = "",
) {
    val serverReadiness: ServerReadiness
        get() = when {
            processRunning && serverReachable -> ServerReadiness.READY
            processRunning && lastServerAction == "start_failed" -> ServerReadiness.UNRESPONSIVE
            processRunning -> ServerReadiness.STARTING
            portListening -> ServerReadiness.PORT_CONFLICT
            else -> ServerReadiness.OFFLINE
        }
}

data class BackgroundStatus(
    val termuxBatteryUnrestricted: Boolean? = null,
    val wakeLockEnabled: Boolean = false,
    val wakeLockActive: Boolean = false,
    val manufacturer: String = "",
) {
    val isSamsung: Boolean
        get() = manufacturer.equals("samsung", ignoreCase = true)
}

data class LauncherUiState(
    val serverConfig: ServerConfig? = null,
    val serverConfigError: Boolean = false,
    val serverConfigLoading: Boolean = false,
    val section: MainSection = MainSection.HOME,
    val navigation: LauncherNavigation = LauncherNavigation(),
    val setupDeferred: Boolean = false,
    val settingsPanel: SettingsPanel = SettingsPanel.GENERAL,
    val theme: AppTheme = AppTheme.SYSTEM,
    val displayPreferences: DisplayPreferences = DisplayPreferences(),
    val selectedInstallBranch: SillyBranch = SillyBranch.RELEASE,
    val configuredPort: Int = 8000,
    val environment: EnvironmentStatus = EnvironmentStatus(),
    val termuxSetup: TermuxSetupState = TermuxSetupState(),
    // Navigation hint only; command access still requires a live doctor response.
    val previousInstallationKnown: Boolean = false,
    val environmentChecked: Boolean = false,
    val termuxWakeBlocked: Boolean = false,
    val isWorking: Boolean = true,
    val cancellationRequested: Boolean = false,
    val workingLabel: String = "환경 확인 중",
    val workingStartedAtMillis: Long = 0L,
    val settingsDiagnosticStartedAtMillis: Long? = null,
    // Match the dispatch timestamp: nested commands (e.g. stop during backup) are not Home controls.
    val dispatchedOperation: String = "",
    val dispatchedAtMillis: Long = -1L,
    val autoOpenedStartAtMillis: Long = -2L,
    val lastCreatedBackupName: String? = null,
    val portEditorRequested: Boolean = false,
    /** A specific settings visit opened from Home; later visits to the Settings tab still start at its index. */
    val serverSettingsFromHomeEntry: Long? = null,
    val lanAddressRequested: Boolean = false,
    val workProgress: WorkProgress? = null,
    val message: String? = null,
    val error: String? = null,
    val pendingEntry: app.tavernbridge.launcher.entry.LauncherEntryRequest? = null,
    val encryptedBackup: EncryptedBackupState? = null,
    val lastOperationResult: OperationResultSummary? = null,
    // Single-use offer for a live, verified repair result. Never persist or restore consent.
    val runtimeSyncFailureAt: Long? = null,
    val recentResults: List<OperationResultSummary> = emptyList(),
    val logs: String = "아직 불러온 로그가 없습니다.",
    val previousServerLogs: String = "아직 보관된 이전 서버 로그가 없습니다.",
    val operationHistory: String = "아직 불러온 작업 기록이 없습니다.",
    val diagnosticPanel: DiagnosticPanel = DiagnosticPanel.OVERVIEW,
    val toolPage: ToolPage = ToolPage.INDEX,
    val diagnosticReport: DiagnosticReport? = null,
    val acknowledgedDiagnosticKeys: Set<String> = emptySet(),
    val chatUndo: List<ChatUndo> = emptyList(),
    val chatCompletion: String = "",
    val latestVersion: String = "",
    val updateAvailable: Boolean? = null,
    val backups: List<BackupArchive> = emptyList(),
    val backupsLoaded: Boolean = false,
    val backupListLoading: Boolean = false,
    val backupListError: String = "",
    val backupListErrorCode: String = "",
    val backupReminder: BackupReminderState = BackupReminderState(),
    val backupReminderNowMillis: Long = 0L,
    val backupTransfer: BackupTransferState? = null,
    val backupFolders: List<CustomBackupFolder> = emptyList(),
    val backupStorageReady: Boolean? = null,
    val safetyCopyCount: Int? = null,
    val fileTrashCount: Int? = null,
    val safetyInventory: SafetyInventoryUiState = SafetyInventoryUiState(),
    val extensionManagerOpen: Boolean = false,
    val extensionInventory: ExtensionInventory? = null,
    val extensionLoading: Boolean = false,
    val extensionError: String = "",
    val lanEndpoints: List<LanEndpoint> = emptyList(),
    val lanLoading: Boolean = false,
    val storageUsage: StorageUsageReport? = null,
    val storageUsageLoading: Boolean = false,
    val storageUsageError: String = "",
    val backupAnnotations: Map<String, BackupAnnotation> = emptyMap(),
    val backupCleanupPreview: BackupRetentionPreview? = null,
    val backupCleanupLoading: Boolean = false,
    val selectiveRestore: SelectiveRestoreUiState? = null,
    val previousVersions: PreviousVersionUiState? = null,
    val autoBackupBeforeUpdate: Boolean = true,
    val autoOpenBrowser: Boolean = false,
    val launcherUpdateInfo: LauncherUpdateInfo? = null,
    val nodePackageInfo: NodePackageInfo? = null,
    val updateInfoLoading: Boolean = false,
    val updateInfoError: String = "",
    val updatePreflight: UpdatePreflight? = null,
    val lastUpdateRecord: UpdateRecord? = null,
    val backgroundStatus: BackgroundStatus = BackgroundStatus(),
    val fileBrowserOpen: Boolean = false,
    val fileBrowserPath: String = "",
    val fileBrowserEntries: List<TavernFileEntry> = emptyList(),
    val fileBrowserLoading: Boolean = false,
    val fileBrowserError: String = "",
    val fileBrowserMutating: Boolean = false,
    val fileBrowserNotice: String = "",
    val fileListReadFailed: Boolean = false,
    val lastTrashedEntryId: String = "",
    val restoredFilePaths: Set<String> = emptySet(),
    val editingFile: TavernTextFile? = null,
    val filePreview: TavernFilePreview? = null,
    val pendingFilePreviewPath: String? = null,
    val pendingInstallation: ExistingInstallation? = null,
)
