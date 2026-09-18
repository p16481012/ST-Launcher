package app.tavernbridge.launcher.model

enum class AppTheme(val label: String) {
    SYSTEM("시스템 설정"),
    LIGHT("라이트"),
    DARK("다크"),
    PASTEL("자두"),
    AVOCADO("아보카도"),
    BANANA("바나나"),
    BLUEBERRY("블루베리"),
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
    SETUP("관리"),
    LOGS("진단"),
    SETTINGS("설정"),
}

enum class ManagementPanel(val label: String) {
    INSTALL("설치"),
    UPDATE("업데이트"),
    BACKUP("백업·복원"),
}

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
    val serverReachable: Boolean = false,
    val portListening: Boolean = false,
    val port: Int = 8000,
    val externalAccessEnabled: Boolean = false,
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
    val termuxBatteryUnrestricted: Boolean = false,
    val wakeLockEnabled: Boolean = false,
    val wakeLockActive: Boolean = false,
    val manufacturer: String = "",
) {
    val isSamsung: Boolean
        get() = manufacturer.equals("samsung", ignoreCase = true)
}

data class LauncherUiState(
    val section: MainSection = MainSection.HOME,
    val managementPanel: ManagementPanel = ManagementPanel.INSTALL,
    val settingsPanel: SettingsPanel = SettingsPanel.GENERAL,
    val theme: AppTheme = AppTheme.SYSTEM,
    val selectedInstallBranch: SillyBranch = SillyBranch.RELEASE,
    val configuredPort: Int = 8000,
    val environment: EnvironmentStatus = EnvironmentStatus(),
    val environmentChecked: Boolean = false,
    val termuxWakeBlocked: Boolean = false,
    val isWorking: Boolean = true,
    val workingLabel: String = "환경 확인 중",
    val workingStartedAtMillis: Long = 0L,
    val workProgress: WorkProgress? = null,
    val message: String? = null,
    val error: String? = null,
    val lastOperationResult: OperationResultSummary? = null,
    val logs: String = "아직 불러온 로그가 없습니다.",
    val previousServerLogs: String = "아직 보관된 이전 서버 로그가 없습니다.",
    val operationHistory: String = "아직 불러온 작업 기록이 없습니다.",
    val diagnosticPanel: DiagnosticPanel = DiagnosticPanel.OVERVIEW,
    val diagnosticReport: DiagnosticReport? = null,
    val latestVersion: String = "",
    val updateAvailable: Boolean? = null,
    val backups: List<BackupArchive> = emptyList(),
    val backupsLoaded: Boolean = false,
    val backupFolders: List<CustomBackupFolder> = emptyList(),
    val backupStorageReady: Boolean? = null,
    val autoBackupBeforeUpdate: Boolean = true,
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
    val lastTrashedEntryId: String = "",
    val editingFile: TavernTextFile? = null,
    val pendingInstallation: ExistingInstallation? = null,
)
