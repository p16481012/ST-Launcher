package app.tavernbridge.launcher.data

import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.ClipData
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.provider.DocumentsContract
import android.os.PowerManager
import android.util.Base64
import android.webkit.MimeTypeMap
import app.tavernbridge.launcher.BuildConfig
import app.tavernbridge.launcher.model.BackgroundStatus
import app.tavernbridge.launcher.model.BrowserOption
import app.tavernbridge.launcher.model.EnvironmentStatus
import app.tavernbridge.launcher.model.BackupArchive
import app.tavernbridge.launcher.model.BackupCategory
import app.tavernbridge.launcher.model.BackupRequest
import app.tavernbridge.launcher.model.BackupRequestCodec
import app.tavernbridge.launcher.model.CustomBackupFolder
import app.tavernbridge.launcher.model.backupFolderLabel
import app.tavernbridge.launcher.model.DiagnosticItem
import app.tavernbridge.launcher.model.DiagnosticReport
import app.tavernbridge.launcher.model.DiagnosticStatus
import app.tavernbridge.launcher.model.SillyBranch
import app.tavernbridge.launcher.model.WorkProgress
import app.tavernbridge.launcher.model.LocalProgressJournal
import app.tavernbridge.launcher.model.UpdatePreflight
import app.tavernbridge.launcher.model.UpdateRecord
import app.tavernbridge.launcher.model.TavernFileEntry
import app.tavernbridge.launcher.model.ExistingInstallation
import app.tavernbridge.launcher.model.TavernTextFile
import app.tavernbridge.launcher.termux.TermuxCommandExecutor
import app.tavernbridge.launcher.termux.TermuxCommandResult
import app.tavernbridge.launcher.termux.TermuxContract
import app.tavernbridge.launcher.termux.TermuxResultBus
import app.tavernbridge.launcher.termux.awaitTermuxCommandResult
import app.tavernbridge.launcher.security.redactSensitiveText
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LauncherRepository(private val context: Context) {
    private val executor = TermuxCommandExecutor(context)
    private val preferences = context.getSharedPreferences("launcher_preferences", Context.MODE_PRIVATE)
    private val importStaging = SharedImportStaging(context)
    private val backupRequestStore = BackupRequestStore(context)
    @Volatile private var localOperationProgress: WorkProgress? = null

    companion object {
        private const val SYSTEM_URL_HANDLER = "@android-system"
    }

    fun configuredPort(): Int = preferences.getInt("server_port", 8000).coerceIn(1024, 65535)

    fun saveConfiguredPort(port: Int) {
        preferences.edit().putInt("server_port", port.coerceIn(1024, 65535)).apply()
    }

    fun autoBackupBeforeUpdate(): Boolean = preferences.getBoolean("auto_backup_before_update", true)

    fun saveAutoBackupBeforeUpdate(enabled: Boolean) {
        preferences.edit().putBoolean("auto_backup_before_update", enabled).apply()
    }

    fun wakeLockEnabled(): Boolean = preferences.getBoolean("termux_wake_lock_enabled", false)

    fun saveWakeLockEnabled(enabled: Boolean) {
        preferences.edit().putBoolean("termux_wake_lock_enabled", enabled).apply()
    }

    fun backgroundStatus(serverRunning: Boolean = false): BackgroundStatus {
        val powerManager = context.getSystemService(PowerManager::class.java)
        return BackgroundStatus(
            termuxBatteryUnrestricted = powerManager?.isIgnoringBatteryOptimizations(TermuxContract.PACKAGE) == true,
            wakeLockEnabled = wakeLockEnabled(),
            wakeLockActive = wakeLockEnabled() && serverRunning,
            manufacturer = android.os.Build.MANUFACTURER.orEmpty(),
        )
    }

    suspend fun setTermuxWakeLock(enabled: Boolean): TermuxCommandResult =
        runManager("wake-lock-${if (enabled) "on" else "off"}", timeoutMillis = 30_000)

    fun baseStatus(): EnvironmentStatus {
        val powerManager = context.getSystemService(PowerManager::class.java)
        return EnvironmentStatus(
            termuxInstalled = executor.isTermuxInstalled(),
            commandPermissionGranted = executor.hasRunCommandPermission(),
            termuxBatteryUnrestricted = powerManager?.isIgnoringBatteryOptimizations(TermuxContract.PACKAGE) == true,
            port = configuredPort(),
        )
    }

    suspend fun inspect(onProgress: ((WorkProgress) -> Unit)? = null, progressOperation: String = "refresh"): EnvironmentStatus {
        val progress = onProgress?.let { LocalProgressJournal(progressOperation, it) }
        progress?.phase("환경 확인", "Android 권한, 서버 응답, Termux 설치 상태를 차례로 확인합니다.", 3)
        val base = baseStatus()
        progress?.completedItem("Android 권한·설정 확인")
        progress?.item("서버 응답 확인", "설정된 로컬 서버의 HTTP 응답을 기다립니다.")
        val serverReachable = pingServer(base.port)
        progress?.completedItem("서버 응답 확인")
        if (!base.termuxInstalled || !base.commandPermissionGranted) {
            progress?.completedItem("Termux 미설치 또는 명령 권한 없음 확인 · 명령 검사 생략")
            return base.copy(serverReachable = serverReachable)
        }

        progress?.item("Termux 환경 검사", "명령 응답을 기다립니다. 설치 폴더·실행 상태·도구를 확인합니다.")
        val result = runManager("doctor", timeoutMillis = 20_000)
        if (!result.isSuccess) {
            throw IllegalStateException(result.readableError())
        }
        val status = DoctorOutputParser.parse(result.stdout).copy(
            serverReachable = serverReachable,
            termuxBatteryUnrestricted = base.termuxBatteryUnrestricted,
        )
        progress?.completedItem("Termux 설치·프로세스·도구 검사")
        if (!status.operationActive && !status.recoveryPending) importStaging.cleanAbandoned()
        return status
    }

    suspend fun connectManager(progressOperation: String = "connect-manager"): TermuxCommandResult {
        val progress = LocalProgressJournal(progressOperation, { localOperationProgress = it })
        progress.phase("런처 연결 준비", "관리 스크립트 묶음을 준비하고 있습니다.")
        try {
            val command = buildString {
                append(managerBootstrapCommand())
                append(" && ST_PORT=${configuredPort()} ST_LAUNCHER_VERSION=${shellQuote(BuildConfig.VERSION_NAME)} ")
                append(shellQuote(TermuxContract.MANAGER_PATH))
                append(" doctor")
            }
            progress.completedItem("관리 스크립트 묶음 준비")
            progress.phase("Termux 연결 확인", "관리 스크립트를 전달하고 Termux 환경 검사 응답을 기다립니다.")
            return runBash(
                command = command,
                label = "실리태번 런처 연결",
                description = "관리 스크립트를 설치하고 환경을 확인합니다.",
                timeoutMillis = 30_000,
            ).also { if (it.isSuccess) progress.completedItem("관리 스크립트 전달·연결 확인") }
        } finally { localOperationProgress = null }
    }

    suspend fun install(branch: SillyBranch): TermuxCommandResult =
        runManager("install ${shellQuote(branch.value)}", timeoutMillis = 30 * 60_000L)

    suspend fun start(): TermuxCommandResult {
        val prepared = runManager("prepare-persistent-start", timeoutMillis = 15 * 60_000L)
        if (!prepared.isSuccess || prepared.stdout.lineSequence().any { it == "already_running=1" }) {
            return prepared
        }
        val command = "ST_PORT=${configuredPort()} ST_LAUNCHER_VERSION=${shellQuote(BuildConfig.VERSION_NAME)} ${shellQuote(TermuxContract.MANAGER_PATH)} server-task"
        executor.executeLongRunningBash(
            command = command,
            label = "SillyTavern 서버",
            description = "서버가 실행되는 동안 Termux 작업을 유지합니다.",
        )
        return runManager("finish-persistent-start", timeoutMillis = 15 * 60_000L)
    }

    suspend fun stop(): TermuxCommandResult =
        runManager("stop", timeoutMillis = 30_000)

    suspend fun restart(): TermuxCommandResult {
        val stopped = stop()
        return if (stopped.isSuccess) start() else stopped
    }

    suspend fun cancelOperation(): TermuxCommandResult =
        runManager("cancel", timeoutMillis = 30_000L)

    suspend fun update(keepRunning: Boolean, allowModifiedFiles: Boolean): TermuxCommandResult {
        val result = runManager(
            "update ${if (keepRunning) 1 else 0} ${if (allowModifiedFiles) 1 else 0}",
            timeoutMillis = 45 * 60_000L,
        )
        if (!result.isSuccess || !keepRunning) return result

        val stopped = stop()
        if (!stopped.isSuccess) return stopped
        if (wakeLockEnabled()) setTermuxWakeLock(true).let { if (!it.isSuccess) return it }
        return start()
    }

    suspend fun checkUpdate(): UpdatePreflight {
        val result = runManager("update-preflight", timeoutMillis = 3 * 60_000L)
        if (!result.isSuccess) throw IllegalStateException(result.readableError())
        return UpdatePreflightParser.parse(result.stdout)
    }

    suspend fun lastUpdateRecord(): UpdateRecord? {
        val result = runManager("last-update-record", timeoutMillis = 30_000)
        if (!result.isSuccess) throw IllegalStateException(result.readableError())
        return UpdateRecordParser.parse(result.stdout)
    }

    suspend fun switchBranch(branch: SillyBranch, allowModifiedFiles: Boolean = false): TermuxCommandResult =
        runManager(
            "switch-branch ${shellQuote(branch.value)} ${if (allowModifiedFiles) 1 else 0}",
            timeoutMillis = 15 * 60_000L,
        )

    suspend fun saveServerConnectionSettings(
        port: Int,
        externalAccessEnabled: Boolean,
        whitelist: List<String>,
    ): TermuxCommandResult {
        val encodedWhitelist = Base64.encodeToString(
            whitelist.joinToString("\n").toByteArray(),
            Base64.NO_WRAP,
        )
        val result = runManager(
            "save-server-connection ${if (externalAccessEnabled) 1 else 0} ${shellQuote(encodedWhitelist)}",
            timeoutMillis = 30_000,
        )
        if (result.isSuccess) saveConfiguredPort(port)
        return result
    }

    suspend fun resetInstallation(): TermuxCommandResult =
        runManager("reset-installation", timeoutMillis = 30 * 60_000L)

    fun savedBackupRequest(): BackupRequest? = backupRequestStore.load()

    suspend fun createBackup(
        categories: Set<BackupCategory>,
        includeSecrets: Boolean,
        customFolders: Set<String>,
    ): TermuxCommandResult = createBackup(BackupRequest(UUID.randomUUID().toString(), categories,
        includeSecrets, customFolders))

    suspend fun createBackup(request: BackupRequest): TermuxCommandResult {
        require(BackupRequestCodec.decode(BackupRequestCodec.encode(request)) == request) {
            "백업 선택 정보가 올바르지 않습니다. 백업 항목을 다시 선택해 주세요."
        }
        // Commit before dispatch so process death cannot lose the selected scope.
        backupRequestStore.save(request)
        val kinds = request.categories.joinToString(",") { it.key }
        val folders = request.customFolders.joinToString(",")
        return runManager(
            "backup-select ${shellQuote(kinds)} ${if (request.includeSecrets) 1 else 0} ${shellQuote(folders)}",
            timeoutMillis = 30 * 60_000L,
            backupRequestId = request.id,
        )
    }

    suspend fun listBackups(): List<BackupArchive> {
        val result = runManager("list-backups", timeoutMillis = 2 * 60_000L)
        if (!result.isSuccess) throw IllegalStateException(result.readableError())
        return BackupOutputParser.parse(result.stdout)
    }

    suspend fun listUserBackupFolders(): List<CustomBackupFolder> {
        val result = runManager("list-user-folders", timeoutMillis = 30_000)
        if (!result.isSuccess) throw IllegalStateException(result.readableError())
        return result.stdout.lineSequence().mapNotNull { line ->
            val fields = line.split('\t')
            if (fields.size != 2 || fields[0] != "folder") return@mapNotNull null
            runCatching {
                val key = String(Base64.decode(fields[1], Base64.DEFAULT))
                CustomBackupFolder(key, backupFolderLabel(key))
            }.getOrNull()
        }.sortedBy { it.label }.toList()
    }

    suspend fun listSillyTavernFiles(relativePath: String): List<TavernFileEntry> {
        val encoded = Base64.encodeToString(relativePath.toByteArray(), Base64.NO_WRAP)
        val entries = mutableListOf<TavernFileEntry>()
        var cursor = 0
        var revision: String? = null
        do {
            val arguments = "list-st-files ${shellQuote(encoded)} $cursor"
            val result = if (cursor == 0) runManager(arguments, 30_000) else runExistingManager(arguments, 30_000)
            if (!result.isSuccess) throw IllegalStateException(result.readableError())
            check(!result.stdoutTruncated) { "폴더 목록이 통신 중 잘렸습니다. 다시 열어 주세요." }
            val lines = result.stdout.lines()
            val pageRevision = lines.firstOrNull { it.startsWith("listing_revision=") }?.substringAfter('=')
            check(pageRevision != null && pageRevision.matches(Regex("[0-9a-f]{64}"))) { "폴더 목록 검증 정보를 읽지 못했습니다." }
            check(revision == null || revision == pageRevision) { "목록을 읽는 동안 폴더가 변경되었습니다. 새로고침해 주세요." }
            revision = pageRevision
            for (line in lines.filter { it.startsWith("entry\t") }) {
                val fields = line.split('\t')
                check(fields.size == 7) { "폴더 목록 응답이 올바르지 않습니다." }
                entries += TavernFileEntry(
                    relativePath = String(Base64.decode(fields[2], Base64.DEFAULT), Charsets.UTF_8),
                    name = String(Base64.decode(fields[3], Base64.DEFAULT), Charsets.UTF_8),
                    isDirectory = fields[1] == "D",
                    sizeBytes = fields[4].toLong(),
                    modifiedAt = fields[5],
                    sensitive = fields[6] == "1",
                )
            }
            val next = lines.firstOrNull { it.startsWith("next_cursor=") }?.substringAfter('=')
                ?: error("폴더 목록의 다음 페이지 정보를 읽지 못했습니다.")
            if (next.isEmpty()) break
            val nextCursor = next.toIntOrNull() ?: error("폴더 목록 페이지가 올바르지 않습니다.")
            check(nextCursor > cursor && nextCursor <= 500_000) { "폴더 항목이 너무 많습니다. 하위 폴더를 선택해 주세요." }
            cursor = nextCursor
        } while (true)
        return entries.sortedWith(compareByDescending<TavernFileEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    suspend fun inspectExistingInstallation(path: String): ExistingInstallation {
        val trimmed = path.trim()
        val absolute = if (trimmed.startsWith("~/")) TermuxContract.HOME_PATH + trimmed.removePrefix("~") else trimmed
        require(absolute.startsWith('/') && absolute.none { it.isISOControl() }) { "설치 폴더의 전체 경로를 입력해 주세요." }
        val progress = LocalProgressJournal("inspect-install", { localOperationProgress = it })
        progress.phase("기존 설치 검사", "폴더 구조·Git 정보·용량을 확인합니다. 이 단계에서는 원본을 변경하지 않습니다.")
        progress.item(absolute, "Termux 검사 응답을 기다립니다. 파일이 많으면 폴더 용량 계산에 시간이 걸릴 수 있습니다.")
        try {
            val result = runManager("inspect-install ${shellQuote(encodeFileArgument(absolute))}", 5 * 60_000L)
            if (!result.isSuccess) throw IllegalStateException(result.readableError())
            return FileManagementProtocol.installation(result.stdout).also {
                progress.completedItem("설치 구조·버전·폴더 용량 확인")
            }
        } finally { localOperationProgress = null }
    }

    /** Resolve the selected local folder directly: no duplicate SAF staging tree is created. */
    suspend fun stageInstallationFolder(uri: Uri): ExistingInstallation {
        require(uri.scheme == "content" && DocumentsContract.isTreeUri(uri)) { "설치 폴더를 선택해 주세요." }
        return inspectExistingInstallation(localFolderPathForTree(uri.authority, DocumentsContract.getTreeDocumentId(uri)))
    }

    suspend fun importExistingInstallation(path: String): TermuxCommandResult =
        runManager("import-install ${shellQuote(encodeFileArgument(path))}", 45 * 60_000L)

    // Cancelling the selection must never delete the selected original installation.
    @Suppress("UNUSED_PARAMETER")
    suspend fun discardInstallationImport(sourcePath: String) = Unit

    suspend fun readSillyTavernTextFile(relativePath: String): TavernTextFile {
        val result = runManager("read-st-file ${shellQuote(encodeFileArgument(relativePath))}", 30_000)
        if (!result.isSuccess) throw IllegalStateException(result.readableError())
        check(!result.stdoutTruncated) { "파일 내용이 통신 중 잘렸습니다. 다시 열거나 외부 폴더에서 확인해 주세요." }
        return FileManagementProtocol.textFile(relativePath, result.stdout)
    }

    suspend fun saveSillyTavernTextFile(file: TavernTextFile, content: String): TermuxCommandResult {
        require(content.toByteArray(Charsets.UTF_8).size <= TEXT_EDIT_LIMIT) { "앱 안에서는 64 KiB 이하의 텍스트만 수정할 수 있습니다." }
        require(file.revision.matches(Regex("[0-9a-f]{64}"))) { "파일을 다시 열어 주세요." }
        // The script bootstrap plus 64 KiB content could exceed Linux's per-argument limit.
        val connected = connectManager("write-st-file")
        if (!connected.isSuccess) return connected
        return runExistingManager(
            "write-st-file ${shellQuote(encodeFileArgument(file.relativePath))} ${shellQuote(encodeFileArgument(content))} ${shellQuote(file.revision)}",
            30_000,
        )
    }

    suspend fun createSillyTavernFolder(parent: String, name: String): TermuxCommandResult =
        runManager("mkdir-st ${shellQuote(encodeFileArgument(fileChildPath(parent, name)))}", 30_000)

    suspend fun createSillyTavernFile(parent: String, name: String): TermuxCommandResult =
        runManager("create-st-file ${shellQuote(encodeFileArgument(fileChildPath(parent, name)))}", 30_000)

    suspend fun renameSillyTavernEntry(relativePath: String, newName: String): TermuxCommandResult =
        runManager("rename-st ${shellQuote(encodeFileArgument(relativePath))} ${shellQuote(encodeFileArgument(fileChildPath(relativePath.substringBeforeLast('/', ""), newName)))}", 30_000)

    fun lastTrashedEntryId(): String = preferences.getString("last_trashed_entry", "").orEmpty()

    suspend fun trashSillyTavernEntry(relativePath: String): TermuxCommandResult {
        val result = runManager("delete-st ${shellQuote(encodeFileArgument(relativePath))}", 30_000)
        if (result.isSuccess) {
            val id = result.stdout.lineSequence().firstOrNull { it.startsWith("trash_id=") }?.substringAfter('=')
            if (id != null && id.matches(Regex("[0-9]+-[a-f0-9-]{36}"))) {
                preferences.edit().putString("last_trashed_entry", id).commit()
            }
        }
        return result
    }

    suspend fun restoreSillyTavernEntry(trashId: String): TermuxCommandResult {
        val result = runManager("restore-st-trash ${shellQuote(trashId)}", 30_000)
        if (result.isSuccess && lastTrashedEntryId() == trashId) {
            preferences.edit().remove("last_trashed_entry").commit()
        }
        return result
    }

    suspend fun importSillyTavernFile(parent: String, uri: Uri): TermuxCommandResult {
        val destination = withContext(Dispatchers.IO) { fileChildPath(parent, importStaging.displayName(uri)) }
        val transfer = stageTransfer(uri, backup = false)
        // On timeout/process death the manager may still be reading; idle cleanup handles those later.
        val result = runManager("import-st-file ${shellQuote(encodeFileArgument(destination))} ${shellQuote(transfer.name)}", 15 * 60_000L)
        importStaging.remove(transfer)
        return result
    }

    suspend fun importSillyTavernFolder(parent: String, uri: Uri): TermuxCommandResult {
        require(uri.scheme == "content" && DocumentsContract.isTreeUri(uri)) { "가져올 폴더를 선택해 주세요." }
        val source = localFolderPathForTree(uri.authority, DocumentsContract.getTreeDocumentId(uri))
        val destination = fileChildPath(parent, source.substringAfterLast('/'))
        // Copy directly in Termux; no intermediate ZIP or duplicate shared-storage staging tree.
        return runManager(
            "import-st-folder ${shellQuote(encodeFileArgument(destination))} ${shellQuote(encodeFileArgument(source))}",
            45 * 60_000L,
        )
    }

    fun openSillyTavernDirectory(): Boolean = DirectoryAppLauncher(context).openRecommended()

    fun openSillyTavernFile(relativePath: String): Boolean {
        if (relativePath.startsWith('/') || relativePath.split('/').any { it == ".." }) return false
        val absolutePath = "${TermuxContract.HOME_PATH}/SillyTavern/$relativePath"
        val uri = Uri.Builder()
            .scheme("content")
            .authority("${TermuxContract.PACKAGE}.files")
            .path(absolutePath)
            .build()
        val extension = relativePath.substringAfterLast('.', "").lowercase()
        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            clipData = ClipData.newRawUri(relativePath.substringAfterLast('/'), uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(view, "파일을 열 앱 선택").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return runCatching { context.startActivity(chooser); true }.getOrDefault(false)
    }

    suspend fun backupStorageReady(): Boolean {
        val result = runManager("backup-storage-status", timeoutMillis = 30_000)
        if (!result.isSuccess) throw IllegalStateException(result.readableError())
        return result.stdout.lineSequence().any { it == "backup_storage_ready=1" }
    }

    suspend fun deleteBackup(fileName: String): TermuxCommandResult =
        runManager("delete-backup ${shellQuote(fileName)}", timeoutMillis = 60_000)

    suspend fun deleteBackups(fileNames: List<String>): TermuxCommandResult {
        val encoded = Base64.encodeToString(fileNames.joinToString("\n").toByteArray(), Base64.NO_WRAP)
        return runManager("delete-backups ${shellQuote(encoded)}", timeoutMillis = 2 * 60_000L)
    }

    suspend fun restoreBackup(fileName: String): TermuxCommandResult =
        runManager("restore ${shellQuote(fileName)}", timeoutMillis = 45 * 60_000L)

    suspend fun importBackup(uri: Uri): TermuxCommandResult {
        val transfer = stageTransfer(uri, backup = true)
        val result = runManager("import-backup ${shellQuote(transfer.name)}", timeoutMillis = 45 * 60_000L)
        importStaging.remove(transfer)
        return result
    }

    suspend fun repair(): TermuxCommandResult =
        runManager("repair", timeoutMillis = 30 * 60_000L)

    suspend fun logs(): TermuxCommandResult =
        runExistingManager("server-logs 500", timeoutMillis = 20_000)

    suspend fun previousServerLogs(): TermuxCommandResult =
        runExistingManager("previous-server-logs 800", timeoutMillis = 20_000)

    suspend fun operationHistory(): TermuxCommandResult =
        runExistingManager("history 300", timeoutMillis = 20_000)

    suspend fun diagnose(): DiagnosticReport {
        val progress = LocalProgressJournal("diagnose", { localOperationProgress = it })
        progress.phase("Android 설정 검사", "Termux 설치·명령 권한·배터리 설정을 확인합니다.", 3)
        return try { diagnoseWithProgress(progress) } finally { localOperationProgress = null }
    }

    private suspend fun diagnoseWithProgress(progress: LocalProgressJournal): DiagnosticReport {
        val items = mutableListOf<DiagnosticItem>()
        val termuxInstalled = executor.isTermuxInstalled()
        progress.completedItem("Termux 설치 확인")
        val permissionGranted = executor.hasRunCommandPermission()
        progress.completedItem("외부 명령 실행 권한 확인")
        items += DiagnosticItem(
            id = "launcher_version",
            title = "런처 버전",
            value = BuildConfig.VERSION_NAME,
            status = DiagnosticStatus.INFO,
        )
        items += DiagnosticItem(
            id = "termux",
            title = "Termux 설치",
            value = if (termuxInstalled) "설치됨" else "설치되지 않음",
            status = if (termuxInstalled) DiagnosticStatus.OK else DiagnosticStatus.ERROR,
            recommendation = if (termuxInstalled) "" else "F-Droid판 Termux를 설치하고 한 번 실행해 주세요.",
        )
        items += DiagnosticItem(
            id = "command_permission",
            title = "외부 명령 권한",
            value = if (permissionGranted) "허용됨" else "허용 필요",
            detail = if (permissionGranted) "런처가 Termux 관리 명령을 실행할 수 있습니다."
            else "Android 설정에서 Termux 환경 명령 실행 권한을 허용해 주세요.",
            status = if (permissionGranted) DiagnosticStatus.OK else DiagnosticStatus.ERROR,
            recommendation = if (permissionGranted) "" else "런처 앱 정보 → 권한 → 추가 권한에서 Termux 명령 실행을 허용해 주세요.",
        )

        if (termuxInstalled) {
            val powerManager = context.getSystemService(PowerManager::class.java)
            val excluded = powerManager?.isIgnoringBatteryOptimizations(TermuxContract.PACKAGE) == true
            items += DiagnosticItem(
                id = "battery",
                title = "Termux 배터리 최적화",
                value = if (excluded) "제외됨" else "최적화 대상",
                detail = if (excluded) "화면이 꺼진 뒤에도 서버가 유지될 가능성이 높습니다."
                else "장시간 백그라운드 실행이 중단될 수 있습니다.",
                status = if (excluded) DiagnosticStatus.OK else DiagnosticStatus.WARNING,
                recommendation = if (excluded) "" else "Termux 배터리 설정을 ‘제한 없음’으로 바꾸고 제조사 절전 목록에서도 제외해 주세요.",
            )
            progress.completedItem("Termux 배터리 최적화 설정 확인")
        } else {
            progress.completedItem("Termux 미설치 확인 · 배터리 검사 생략")
        }

        if (!termuxInstalled || !permissionGranted) {
            return diagnosticReport(items)
        }

        // Long-running probes publish their own measured state; do not cover it with a local waiting card.
        localOperationProgress = null
        val result = runManager("diagnose", timeoutMillis = 90_000)
        if (!result.isSuccess) {
            items += DiagnosticItem(
                id = "diagnose_command",
                title = "Termux 환경 검사",
                value = "검사 실패",
                detail = result.readableError().take(500),
                status = DiagnosticStatus.ERROR,
            )
            return diagnosticReport(items)
        }

        val values = result.stdout.lineSequence().mapNotNull { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
        }.toMap()
        fun value(key: String) = values[key].orEmpty()
        fun enabled(key: String) = value(key) == "1"
        fun decoded(key: String): String = try {
            String(Base64.decode(value(key), Base64.DEFAULT))
        } catch (_: Exception) {
            ""
        }
        fun toolItem(id: String, title: String, version: String) = DiagnosticItem(
            id = id,
            title = title,
            value = version.ifBlank { "설치되지 않음" },
            status = if (version.isBlank()) DiagnosticStatus.ERROR else DiagnosticStatus.OK,
        )

        val recovery = DoctorOutputParser.parse(result.stdout)
        if (recovery.recoveryPending) {
            items += DiagnosticItem(
                id = "restore_recovery",
                title = "중단된 복원",
                value = "기존 데이터 복구 필요",
                detail = recovery.recoveryMessage,
                status = DiagnosticStatus.ERROR,
                recommendation = "서버를 종료한 뒤 홈 또는 관리 화면의 점검·복구를 실행해 주세요. 자동 복구가 거부되면 보호사본을 지우지 말고 진단 내용을 확인해 주세요.",
            )
        }
        items += storageItem("termux_storage", "Termux 저장 공간", value("termux_free_bytes"))
        items += if (enabled("downloads_ready")) {
            storageItem("download_storage", "휴대폰 Download 백업 폴더", value("downloads_free_bytes"))
        } else {
            DiagnosticItem(
                "download_storage",
                "휴대폰 Download 백업 폴더",
                "아직 사용하지 않음",
                "백업 파일을 ‘내 파일 → Download’에 저장하려면 Termux에서 termux-setup-storage를 한 번 실행하세요. Android 저장공간을 연결하는 Termux 공식 명령이며, 백업 기능을 사용하지 않는다면 지금 설정하지 않아도 됩니다.",
                DiagnosticStatus.INFO,
                recommendation = "백업을 사용하려면 Termux에서 termux-setup-storage를 한 번 실행해 주세요.",
            )
        }
        items += DiagnosticItem(
            "github", "GitHub 연결", if (enabled("github_reachable")) "정상" else "연결 실패",
            status = if (enabled("github_reachable")) DiagnosticStatus.OK else DiagnosticStatus.ERROR,
            recommendation = if (enabled("github_reachable")) "" else "Wi-Fi 또는 모바일 데이터와 DNS/VPN 설정을 확인한 뒤 다시 진단해 주세요.",
        )
        val termuxRepositoryConfigured = enabled("termux_repo_configured") || value("termux_repo").isNotBlank()
        val termuxRepositoryReachable = enabled("termux_repo_reachable")
        items += DiagnosticItem(
            "termux_repo",
            "Termux 저장소",
            value = when {
                termuxRepositoryReachable -> "정상"
                termuxRepositoryConfigured -> "설정됨 · 응답 확인 필요"
                else -> "설정되지 않음"
            },
            detail = value("termux_repo").replace(context.filesDir.parent.orEmpty(), "~"),
            status = when {
                termuxRepositoryReachable -> DiagnosticStatus.OK
                termuxRepositoryConfigured -> DiagnosticStatus.WARNING
                else -> DiagnosticStatus.ERROR
            },
            recommendation = when {
                termuxRepositoryReachable -> ""
                termuxRepositoryConfigured -> "현재 미러는 설정되어 있습니다. Termux에서 pkg update가 정상 동작한다면 설정을 유지하고 네트워크 연결 후 다시 진단해 주세요."
                else -> "Termux에서 termux-change-repo로 사용 가능한 미러를 선택한 뒤 다시 진단해 주세요."
            },
        )
        items += toolItem("git", "Git", value("git_version"))
        items += toolItem("node", "Node.js", value("node_version"))
        items += toolItem("npm", "npm", value("npm_version"))
        items += DiagnosticItem(
            "node_requirement",
            "SillyTavern Node 요구 버전",
            value("node_required").ifBlank { "확인할 수 없음" },
            detail = "현재 설치: ${value("node_version").ifBlank { "없음" }}",
            status = when {
                value("node_required").isBlank() -> DiagnosticStatus.WARNING
                enabled("node_compatible") -> DiagnosticStatus.OK
                else -> DiagnosticStatus.ERROR
            },
        )
        items += DiagnosticItem(
            "st_folder", "SillyTavern 폴더", if (enabled("st_folder_ready")) "정상" else "설치 불완전",
            detail = "~/SillyTavern · ${value("st_branch").ifBlank { "브랜치 알 수 없음" }} · ${value("st_version").ifBlank { "버전 알 수 없음" }}",
            status = if (enabled("st_folder_ready")) DiagnosticStatus.OK else DiagnosticStatus.ERROR,
        )
        items += DiagnosticItem(
            "dependencies", "Node 패키지", if (enabled("dependencies_ready")) "정상" else "점검·복구 필요",
            status = if (enabled("dependencies_ready")) DiagnosticStatus.OK else DiagnosticStatus.WARNING,
            recommendation = if (enabled("dependencies_ready")) "" else "서버를 종료한 뒤 관리 → 설치 → 설치 점검·복구를 실행해 주세요.",
        )
        val dirtyCount = value("dirty_count").toIntOrNull() ?: 0
        val dirtyFiles = decoded("dirty_files_b64").lineSequence().map { it.trim() }.filter { it.isNotBlank() }.joinToString("\n")
        items += DiagnosticItem(
            "git_changes", "수정된 설치 파일", if (dirtyCount == 0) "없음" else "${dirtyCount}개",
            detail = dirtyFiles,
            status = if (dirtyCount == 0) DiagnosticStatus.OK else DiagnosticStatus.WARNING,
            recommendation = if (dirtyCount == 0) "" else "업데이트 전에 변경 내용을 확인하고, 필요하면 별도 백업 후 진행해 주세요.",
        )
        val reachable = enabled("server_reachable")
        val listening = enabled("port_listening")
        items += DiagnosticItem(
            "port", "서버 포트 ${configuredPort()}",
            when {
                reachable -> "SillyTavern 응답 중"
                listening -> "다른 프로세스가 사용 중"
                else -> "사용 가능"
            },
            status = when {
                reachable -> DiagnosticStatus.OK
                listening -> DiagnosticStatus.WARNING
                else -> DiagnosticStatus.INFO
            },
            recommendation = if (listening && !reachable) "설정에서 다른 포트를 선택하거나 해당 포트를 사용하는 프로세스를 종료해 주세요." else "",
        )
        val processCount = value("node_process_count").toIntOrNull() ?: 0
        items += DiagnosticItem(
            "node_process", "실행 중인 Node 서버", if (processCount > 0) "${processCount}개" else "없음",
            status = if (processCount > 0) DiagnosticStatus.OK else DiagnosticStatus.INFO,
        )
        val lastError = redactSecrets(decoded("last_error_b64"))
        val lastOperation = value("last_operation")
        val lastOperationErrorCode = value("last_error_code")
        items += DiagnosticItem(
            "last_operation",
            "최근 런처 작업",
            lastOperation.ifBlank { "기록 없음" },
            detail = if (lastOperationErrorCode.isBlank()) "오류 코드 없음" else "오류 코드 $lastOperationErrorCode",
            status = if (lastOperationErrorCode.isBlank()) DiagnosticStatus.INFO else DiagnosticStatus.WARNING,
            recommendation = if (lastOperationErrorCode.isBlank()) "" else "오류 코드와 작업명을 함께 전달하면 문제 확인이 더 빨라집니다.",
        )
        items += DiagnosticItem(
            "last_error", "최근 서버 오류", if (lastError.isBlank()) "발견되지 않음" else "확인 필요",
            detail = lastError,
            status = if (lastError.isBlank()) DiagnosticStatus.OK else DiagnosticStatus.WARNING,
            recommendation = if (lastError.isBlank()) "" else "서버 로그의 같은 시각을 확인하고, 오류 코드와 함께 진단 내용을 복사해 주세요.",
        )
        progress.phase("진단 보고서 정리", "완료된 검사 결과를 진단 항목으로 정리합니다.", items.size.toLong())
        items.forEach { item ->
            val status = when (item.status) {
                DiagnosticStatus.OK -> "정상"
                DiagnosticStatus.WARNING -> "확인 필요"
                DiagnosticStatus.ERROR -> "오류 확인"
                DiagnosticStatus.INFO -> "정보 확인"
            }
            // Only the known item title/status is logged, never diagnostic values or credentials.
            progress.completedItem("${item.title} · $status")
        }
        return diagnosticReport(items, value("manager_version"))
    }

    fun openServerLogTerminal() {
        val serverLog = "${TermuxContract.HOME_PATH}/.st-launcher/logs/server.log"
        val legacyServerLog = "${TermuxContract.HOME_PATH}/.st-launcher/logs/sillytavern.log"
        val accessLog = "${TermuxContract.HOME_PATH}/SillyTavern/access.log"
        val command = buildString {
            append("mkdir -p ${shellQuote("${TermuxContract.HOME_PATH}/.st-launcher/logs")}")
            append(" && touch ${shellQuote(serverLog)} ${shellQuote(legacyServerLog)} ${shellQuote(accessLog)}")
            append("; printf '\\n실리태번 서버 로그를 실시간으로 표시합니다. 종료: Ctrl+C\\n\\n'")
            append("; tail -n 100 -F ${shellQuote(serverLog)} ${shellQuote(legacyServerLog)} ${shellQuote(accessLog)}")
        }
        executor.executeForegroundBash(
            command = command,
            label = "SillyTavern 서버 로그",
            description = "서버와 모델 API 출력을 실시간으로 표시합니다.",
        )
    }

    suspend fun readProgress(): WorkProgress? {
        localOperationProgress?.let { return it }
        val command = buildString {
            append("test -f ${shellQuote(TermuxContract.PROGRESS_PATH)} && cat ${shellQuote(TermuxContract.PROGRESS_PATH)} || true")
            append("; printf '\\n'; cat ${shellQuote("${TermuxContract.HOME_PATH}/.st-launcher/run/progress-heartbeat.env")} 2>/dev/null || true")
            append("; printf '\\n'; sed 's/^/server_/' ${shellQuote("${TermuxContract.HOME_PATH}/.st-launcher/run/server-log-context.env")} 2>/dev/null || true")
            append("; printf '\\n__ST_LAUNCHER_SERVER_LOG__\\n'")
            append("; tail -c 20000 ${shellQuote("${TermuxContract.HOME_PATH}/.st-launcher/logs/server.log")} 2>/dev/null || true")
            append("; printf '\\n__ST_LAUNCHER_LOG__\\n'")
            append("; test -f ${shellQuote("${TermuxContract.HOME_PATH}/.st-launcher/logs/operation.log")}")
            append(" && tail -c 20000 ${shellQuote("${TermuxContract.HOME_PATH}/.st-launcher/logs/operation.log")} || true")
        }
        val result = runBash(
            command = command,
            label = "진행 상태 확인",
            description = "현재 작업의 진행 단계를 확인합니다.",
            timeoutMillis = 5_000,
        )
        if (!result.isSuccess || result.stdout.isBlank()) return null
        if (result.stdoutTruncated) return null
        return WorkProgressParser.parse(result.stdout)
    }

    fun openTermux(): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(TermuxContract.PACKAGE) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return true
    }

    fun openTermuxDownload() {
        openUrl("https://f-droid.org/packages/com.termux/")
    }

    fun openLauncherPermissionSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun openBatteryOptimizationSettings() {
        startSettingsIntent(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            fallbackPackage = TermuxContract.PACKAGE,
        )
    }

    fun openDeviceBatterySettings() {
        startSettingsIntent(
            Intent("android.settings.VIEW_ADVANCED_POWER_USAGE_DETAIL").apply {
                data = Uri.parse("package:${TermuxContract.PACKAGE}")
            },
            fallbackPackage = TermuxContract.PACKAGE,
        )
    }

    fun openTermuxAppSettings() {
        openAppSettings(TermuxContract.PACKAGE)
    }

    fun openLauncherAppSettings() {
        openAppSettings(context.packageName)
    }

    fun openTermuxNotificationSettings() {
        openNotificationSettings(TermuxContract.PACKAGE)
    }

    fun openDownloadsFolder(): Boolean {
        val downloads = DocumentsContract.buildRootUri(
            "com.android.providers.downloads.documents",
            "downloads",
        )
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, downloads)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return runCatching {
            context.startActivity(picker)
            true
        }.getOrElse {
            picker.removeExtra(DocumentsContract.EXTRA_INITIAL_URI)
            runCatching {
                context.startActivity(picker)
                true
            }.getOrDefault(false)
        }
    }

    fun browserOptions(port: Int): List<BrowserOption> {
        val intent = browserIntent(port)
        val matches = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        }
        val handlers = matches
            .mapNotNull { match ->
                val packageName = match.activityInfo?.packageName.orEmpty()
                if (packageName.isBlank()) null else BrowserOption(
                    packageName = packageName,
                    label = match.loadLabel(context.packageManager).toString().ifBlank { packageName },
                )
            }
            .distinctBy(BrowserOption::packageName)
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
        return listOf(
            BrowserOption(
                packageName = SYSTEM_URL_HANDLER,
                label = "Android 기본 열기",
            ),
        ) + handlers
    }

    fun preferredBrowser(port: Int): BrowserOption? {
        val savedPackage = preferences.getString("preferred_browser_package", null) ?: return null
        return browserOptions(port).firstOrNull { it.packageName == savedPackage }
            ?: run {
                preferences.edit().remove("preferred_browser_package").apply()
                null
            }
    }

    fun clearPreferredBrowser() {
        preferences.edit().remove("preferred_browser_package").apply()
    }

    fun openBrowser(port: Int, packageName: String? = null, rememberChoice: Boolean = false): Boolean {
        val intent = browserIntent(port).apply {
            if (!packageName.isNullOrBlank() && packageName != SYSTEM_URL_HANDLER) {
                setPackage(packageName)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            if (rememberChoice && !packageName.isNullOrBlank()) {
                preferences.edit().putString("preferred_browser_package", packageName).apply()
            }
            true
        }.getOrDefault(false)
    }

    fun setupCommand(): String =
        "mkdir -p ~/.termux && grep -q '^allow-external-apps=true$' ~/.termux/termux.properties 2>/dev/null || echo 'allow-external-apps=true' >> ~/.termux/termux.properties; termux-reload-settings"

    fun storageSetupCommand(): String = "termux-setup-storage"

    private fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun browserIntent(port: Int) = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("http://127.0.0.1:$port"),
    )

    private fun openNotificationSettings(packageName: String) {
        startSettingsIntent(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            },
            fallbackPackage = packageName,
        )
    }

    private fun openAppSettings(packageName: String) {
        startSettingsIntent(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            },
        )
    }

    private fun startSettingsIntent(intent: Intent, fallbackPackage: String? = null) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            if (fallbackPackage != null) openAppSettings(fallbackPackage)
        }
    }

    private suspend fun runManager(arguments: String, timeoutMillis: Long, backupRequestId: String = ""): TermuxCommandResult {
        val command = "${managerBootstrapCommand()} && ST_PORT=${configuredPort()} ST_LAUNCHER_VERSION=${shellQuote(BuildConfig.VERSION_NAME)} ST_BACKUP_REQUEST_ID=${shellQuote(backupRequestId)} ${shellQuote(TermuxContract.MANAGER_PATH)} $arguments"
        return runBash(
            command = command,
            label = "실리태번 관리",
            description = arguments,
            timeoutMillis = timeoutMillis,
        )
    }

    private suspend fun runExistingManager(arguments: String, timeoutMillis: Long): TermuxCommandResult {
        val command = "ST_PORT=${configuredPort()} ST_LAUNCHER_VERSION=${shellQuote(BuildConfig.VERSION_NAME)} ${shellQuote(TermuxContract.MANAGER_PATH)} $arguments"
        return runBash(
            command = command,
            label = "실리태번 로그 확인",
            description = "저장된 로그를 불러옵니다.",
            timeoutMillis = timeoutMillis,
        )
    }

    private fun managerBootstrapCommand(): String {
        return createManagerBundleBootstrapCommand(
            TermuxContract.MANAGER_PATH.substringBeforeLast('/'),
            managerAssetNames.associateWith { name ->
                context.assets.open(name).bufferedReader().use { it.readText() }
            },
        )
    }

    private suspend fun stageTransfer(uri: Uri, backup: Boolean): SharedImportStaging.Transfer {
        val started = System.currentTimeMillis()
        var activity = started
        var previousBytes = -1L
        var lastHistoryAt = 0L
        var lastHistoryBytes = -1L
        val history = ArrayDeque<String>()
        val clock = SimpleDateFormat("HH:mm:ss", Locale.KOREA)
        fun amount(bytes: Long): String = if (bytes < 0) "전체 용량 확인 중" else
            if (bytes < 1_048_576L) "$bytes B" else String.format(Locale.KOREA, "%.1f MiB", bytes / 1_048_576.0)
        try {
            return importStaging.copy(uri, backup) { progress ->
                val now = System.currentTimeMillis()
                if (progress.bytes != previousBytes || progress.finished) activity = now
                previousBytes = progress.bytes
                if ((now - lastHistoryAt >= 2_000L && progress.bytes != lastHistoryBytes) || progress.finished) {
                    val name = redactSensitiveText(progress.name.filterNot {
                        it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt()
                    }, maxLength = 512)
                    history.addLast("[${clock.format(Date(now))}] ${if (progress.finished) "전달 파일 준비 완료" else "파일 복사"} · $name · ${amount(progress.bytes)} / ${amount(progress.total)}")
                    while (history.size > 8) history.removeFirst()
                    lastHistoryAt = now
                    lastHistoryBytes = progress.bytes
                }
                localOperationProgress = WorkProgress(
                    percent = 0,
                    phase = "가져올 파일 복사",
                    detail = "선택한 원본은 그대로 두고 Termux에 전달할 임시 파일을 복사합니다.",
                    operation = if (backup) "import-backup" else "import-st-file",
                    progressMode = if (progress.total > 0) "bytes" else "indeterminate",
                    completedBytes = progress.bytes,
                    totalBytes = progress.total.coerceAtLeast(0),
                    completedFiles = if (progress.finished) 1 else 0,
                    totalFiles = 1,
                    currentItem = progress.name,
                    phaseStartedAtMillis = started,
                    operationStartedAtMillis = started,
                    heartbeatAtMillis = now,
                    activityAtMillis = activity,
                    logText = history.joinToString("\n"),
                )
            }
        } finally {
            localOperationProgress = null
        }
    }

    private suspend fun runBash(
        command: String,
        label: String,
        description: String,
        timeoutMillis: Long,
    ): TermuxCommandResult {
        if (!executor.isTermuxInstalled()) {
            throw IllegalStateException("Termux가 설치되어 있지 않습니다.")
        }
        if (!executor.hasRunCommandPermission()) {
            throw SecurityException(
                "Termux 명령 실행 권한이 없습니다. Android 설정 → 애플리케이션 → 실리태번 런처 → 권한 → 추가 권한에서 ‘Termux 환경에서 명령 실행’을 허용해 주세요.",
            )
        }
        val callbackId = UUID.randomUUID().toString()
        return awaitTermuxCommandResult(callbackId, timeoutMillis, TermuxResultBus.results) {
            executor.executeBash(callbackId, command, label, description)
        }
    }

    private suspend fun pingServer(port: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            val connection = URL("http://127.0.0.1:$port/").openConnection() as HttpURLConnection
            connection.connectTimeout = 1_000
            connection.readTimeout = 1_000
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            connection.connect()
            val code = connection.responseCode
            connection.disconnect()
            code in 200..499
        } catch (_: Exception) {
            false
        }
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private fun storageItem(id: String, title: String, rawBytes: String): DiagnosticItem {
        val bytes = rawBytes.toLongOrNull() ?: 0L
        val status = when {
            bytes >= 2L * 1024 * 1024 * 1024 -> DiagnosticStatus.OK
            bytes >= 512L * 1024 * 1024 -> DiagnosticStatus.WARNING
            else -> DiagnosticStatus.ERROR
        }
        return DiagnosticItem(id, title, formatBytes(bytes), "사용 가능한 여유 공간", status)
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f GB", bytes / 1073741824.0)
        bytes >= 1024L * 1024 -> String.format(Locale.US, "%.0f MB", bytes / 1048576.0)
        else -> "$bytes B"
    }

    private fun nowText(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA).format(Date())

    private fun diagnosticReport(
        items: List<DiagnosticItem>,
        managerVersion: String = "",
    ): DiagnosticReport = DiagnosticReport(
        checkedAt = nowText(),
        items = items,
        copyOnlyDetails = linkedMapOf(
            "빌드 코드" to BuildConfig.VERSION_CODE.toString(),
            "관리 스크립트" to managerVersion.ifBlank { "확인할 수 없음" },
        ),
    )

    private fun redactSecrets(text: String): String = redactSensitiveText(text)
}
